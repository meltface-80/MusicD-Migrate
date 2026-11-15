package com.musicd.migrate.api

import com.musicd.migrate.*
import com.musicd.migrate.http.Assets
import com.musicd.migrate.http.Request
import com.musicd.migrate.http.Response
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/*
 * MigrateApi.kt — the route table the bundled page (public/app.js) talks to.
 *
 * THE PAGE IS THE AUTHORITY ON EVERY FIELD NAME, not this file. MusicD Remote
 * Lite learned this the expensive way — eleven wire-contract bugs came from
 * porting a server's names rather than reading what the page actually asks
 * for — so every shape built here was taken from app.js, and ContractTest
 * checks that every route and option the page sends is served and read here.
 *
 * ONE JOB AT A TIME, an import or a clear-out: two at once would race on the
 * match cache, double the request rate into a rate-limited service, and
 * produce two progress bars for one library.
 *
 * QOBUZ ONLY since 0.5.0. Spotify was removed at the owner's request; the
 * engine underneath is still service-neutral (MusicSource / MusicTarget), so
 * bringing a service back is a client and a sign-in, not a rewrite.
 */
class MigrateApi(
    private val store: Store,
    private val assets: Assets,
    private val http: Http = UrlConnectionHttp(),
    private val version: String = "0.1.0",
    /**
     * Told whenever there starts or stops being long work to protect.
     *
     * The app module holds a foreground service only while this is true, and
     * that is not a nicety: Android 14 caps a `dataSync` foreground service at
     * SIX HOURS in any 24, calls `Service.onTimeout` when the cap is reached,
     * and kills the process if the service does not stop. v0.2.5 held one for
     * the whole life of the app and was killed on a real phone with
     *
     *     ForegroundServiceDidNotStopInTimeException: A foreground service of
     *     type dataSync did not stop within its timeout
     *
     * A migration or a clear-out takes minutes. Holding the service for
     * those, and not for the hours in between, keeps the app inside a
     * budget it cannot exhaust — and the notification then appears while
     * something is actually being written, which is what it is for.
     *
     * `:core` cannot touch a Service, so it reports the fact and the app
     * module decides what to do with it.
     */
    private val onBusyChanged: (Boolean) -> Unit = {}
) {
    /** The live migration or clear-out, or null. */
    @Volatile private var current: RunningJob? = null
    @Volatile private var currentJobId: String? = null
    private val jobs = Executors.newSingleThreadExecutor { r ->
        Thread(r, "migration").apply { isDaemon = true }
    }

    init {
        // Any job still marked running when the app starts again was killed —
        // the service was stopped, the phone ran out of memory. Nothing
        // resumes it, so leaving it "running" would show a spinner that never
        // ends.
        store.markOrphansInterrupted()
    }

    private val signIns = SignIns(store, http) { ms -> current?.noteRateLimit(ms) }

    // --------------------------------------------------------------- routing

    fun handle(req: Request): Response = try {
        route(req)
    } catch (e: AuthError) {
        Response.json(401, obj("error" to e.message.orEmpty()))
    } catch (e: Exception) {
        Response.json(500, obj("error" to (e.message ?: "Something went wrong.")))
    }

    private fun route(req: Request): Response {
        val p = req.path

        if (!p.startsWith("/api/")) return static(req)

        return when {
            p == "/api/state" && req.method == "GET" -> state(req)

            p == "/api/qobuz/oauth/start" -> signIns.qobuzStart(req)
            p == "/api/qobuz/oauth/callback" -> signIns.qobuzCallback(req)
            p == "/api/qobuz/oauth/paste" -> signIns.qobuzPaste(req)
            p == "/api/qobuz/login" -> signIns.qobuzLogin(req)
            p == "/api/qobuz/signout" -> { store.deleteSetting("qobuz.session"); ok() }

            p == "/api/import/file" && req.method == "POST" -> importFile(req)
            p == "/api/import/forget" && req.method == "POST" -> { imported = null; ok() }
            p == "/api/migrate" && req.method == "POST" -> migrate(req)
            p == "/api/clear" && req.method == "POST" -> clear(req)
            p == "/api/jobs" -> jobsList()
            p == "/api/cache/clear" && req.method == "POST" -> {
                store.clearMatchCache()
                Response.json(200, """{"ok":true,"cacheSize":${store.matchCacheSize()}}""")
            }

            p.startsWith("/api/job/") -> jobRoute(req, p.removePrefix("/api/job/"))

            else -> Response.notFound()
        }
    }

    private fun jobRoute(req: Request, rest: String): Response {
        val slash = rest.indexOf('/')
        val id = if (slash < 0) rest else rest.substring(0, slash)
        val tail = if (slash < 0) "" else rest.substring(slash + 1)
        val job = store.job(id) ?: return Response.json(404, obj("error" to "No such job."))

        return when {
            tail == "" && req.method == "DELETE" -> {
                if (currentJobId == id) {
                    Response.json(409, obj("error" to "That job is still running."))
                } else {
                    store.deleteJob(id); ok()
                }
            }
            tail == "" -> Response.json(200, jobJson(store, job, currentJobId == job.id))
            tail == "items" -> {
                val status = req.param("status")
                val all = store.items(id, status)
                val limit = (req.param("limit")?.toIntOrNull() ?: 500).coerceAtMost(5000)
                Response.json(200, """{"total":${all.size},"items":""" +
                    all.take(limit).joinToString(",", "[", "]") { itemJson(it) } + "}")
            }
            tail == "report.csv" -> csvReport(store, req.param("status"), job)
            tail == "cancel" && req.method == "POST" -> {
                val m = current
                if (m == null || currentJobId != id) {
                    Response.json(404, obj("error" to "That job is not running."))
                } else {
                    m.cancel(); ok()
                }
            }
            else -> Response.notFound()
        }
    }

    // ----------------------------------------------------------------- state

    private fun state(req: Request): Response {
        val q = signIns.qobuzSession()
        return Response.json(200, buildString {
            append("{")
            append("\"qobuz\":{\"signedIn\":").append(q != null)
            append(",\"name\":").append(jsonQuote(q?.name.orEmpty())).append("},")
            // The library file waiting to be imported, or null. Held in
            // memory only: if Android reclaims the process, choose it again.
            append("\"import\":").append(imported?.summary?.toJson() ?: "null").append(",")
            append("\"cacheSize\":").append(store.matchCacheSize()).append(",")
            append("\"job\":")
            val jid = currentJobId
            if (jid == null) append("null")
            else append("{\"id\":").append(jsonQuote(jid)).append(",\"running\":true}")
            append(",\"version\":").append(jsonQuote(version))
            append("}")
        })
    }

    // ------------------------------------------------------------ busy flag

    /**
     * Whether a run is in flight, as last reported.
     *
     * Kept so the callback fires on CHANGES only: a service that calls
     * startForeground on every poll would re-post the notification each time.
     */
    @Volatile private var busy = false

    /** Re-read the flag and tell the app module if that changed. */
    private fun refreshBusy() {
        val now = currentJobId != null
        if (now == busy) return
        busy = now
        // A listener that throws must not take the run down with it.
        runCatching { onBusyChanged(now) }
    }

    // ------------------------------------------------------------- library

    /**
     * A client for one service, or a refusal saying what is missing.
     *
     * Throws rather than returning null so the caller cannot forget to check:
     * the message is what the user is shown, and "Sign in to Qobuz first" is
     * more use than a 500.
     */
    private fun targetFor(name: String): MusicTarget = when (name) {
        "qobuz" -> signIns.qobuzClient() ?: throw RuntimeException("Sign in to Qobuz first.")
        else -> throw RuntimeException("Unknown service: $name")
    }

    /** The library file uploaded and parsed, waiting to be imported. */
    @Volatile private var imported: CsvLibrary? = null

    /**
     * Take a library file (the request body, as the page read it from disk)
     * and parse it, answering with what was found — before anything is
     * searched for, so the user sees "812 albums, 610 with a barcode" and any
     * warning while they can still pick a different file.
     *
     * Streamed into the parser from the request bytes: a track-per-row export
     * of a big library is tens of megabytes, and holding it as a String too
     * would double that on a 256MB heap.
     */
    private fun importFile(req: Request): Response {
        if (current != null) {
            return Response.json(409, obj("error" to "Wait for the current run to finish."))
        }
        val name = req.param("name").orEmpty().ifEmpty { "library.csv" }.take(200)
        return try {
            val lib = CsvLibrary.parse(java.io.ByteArrayInputStream(req.body), name)
            imported = lib
            Response.json(200, lib.summary.toJson())
        } catch (e: CsvError) {
            Response.json(400, obj("error" to (e.message ?: "That file could not be read.")))
        }
    }

    // ------------------------------------------------------------------ jobs

    /**
     * Import the uploaded library file's albums into Qobuz favourites.
     *
     * The migration engine does the work, with the file as its SOURCE: every
     * album goes through the same lookups and the same Match rules a
     * streaming service's would. Albums only, at the owner's decision.
     *
     * A preview is what an absent `dryRun` means here, as for a clear-out: an
     * import writes to somebody's library, and the safe default is to show
     * what it would do.
     */
    private fun migrate(req: Request): Response {
        if (current != null) {
            return Response.json(409, obj("error" to "Something is already running."))
        }
        val b = parseObject(req.bodyText) ?: JSONObject()
        val direction = b.str("direction").ifEmpty { IMPORT_DIRECTION }
        if (direction != IMPORT_DIRECTION) {
            return Response.json(400, obj("error" to "This version imports a library file " +
                "into Qobuz and nothing else; \"$direction\" is not offered any more."))
        }
        val source = imported
            ?: return Response.json(400, obj("error" to "Choose a library file first."))
        val target = try {
            targetFor("qobuz")
        } catch (e: Exception) {
            return Response.json(400, obj("error" to (e.message ?: "Not signed in.")))
        }
        val options = MigrationOptions(
            doPlaylists = false,
            doAlbums = b.optBoolean("albums", true),
            doArtists = false,
            doTracks = false,
            dryRun = b.optBoolean("dryRun", true),
            strict = b.optBoolean("strict", false),
            // Default ON, so an absent field means corroborate: an older page
            // gets the safer behaviour rather than the title-only match.
            corroborate = b.optBoolean("corroborate", true),
            concurrency = b.intOrNull("concurrency") ?: 4
        )
        if (!options.doAlbums) {
            return Response.json(400, obj("error" to "Nothing to import was chosen."))
        }

        val jobId = UUID.randomUUID().toString()
        store.createJob(jobId, direction, req.bodyText, options.dryRun)
        val migration = Migration(source, target, store, jobId, options)
        return startJob(jobId, migration) { migration.run() }
    }

    /**
     * Empty one service's library — favourite tracks, favourite albums,
     * followed artists, playlists, in any combination.
     *
     * THE GUARDS ARE HERE, ON THE SERVER, and not only on the page. A preview
     * is what an absent `dryRun` means, and a real run is refused unless
     * `confirm` carries the service's name — the same word the page makes the
     * user type. So a stale page, a double tap or any other request that did
     * not come from somebody who read the preview and typed "Qobuz" cannot
     * remove anything.
     */
    private fun clear(req: Request): Response {
        if (current != null) {
            return Response.json(409, obj("error" to "Something is already running."))
        }
        val b = parseObject(req.bodyText) ?: JSONObject()
        val service = b.str("service")
        if (service != "qobuz") {
            return Response.json(400, obj("error" to "Only a Qobuz library can be cleared."))
        }
        val options = ClearOptions(
            doTracks = b.optBoolean("tracks", false),
            doAlbums = b.optBoolean("albums", false),
            doArtists = b.optBoolean("artists", false),
            doPlaylists = b.optBoolean("playlists", false),
            includeOthersPlaylists = b.optBoolean("includeOthersPlaylists", false),
            dryRun = b.optBoolean("dryRun", true)
        )
        if (!options.doTracks && !options.doAlbums && !options.doArtists &&
            !options.doPlaylists) {
            return Response.json(400, obj("error" to "Pick at least one thing to clear."))
        }
        val label = service.replaceFirstChar { it.uppercase() }
        if (!options.dryRun && !b.str("confirm").trim().equals(service, ignoreCase = true)) {
            return Response.json(400, obj("error" to
                "Type \"$label\" to confirm. Removing cannot be undone, so nothing was " +
                "touched."))
        }
        val target = try {
            targetFor(service)
        } catch (e: Exception) {
            return Response.json(400, obj("error" to (e.message ?: "Not signed in.")))
        }

        val jobId = UUID.randomUUID().toString()
        store.createJob(jobId, "clear-$service", req.bodyText, options.dryRun)
        val clearance = Clearance(target, store, jobId, options)
        return startJob(jobId, clearance) { clearance.run() }
    }

    /**
     * Run a job on the job thread and answer with its id at once.
     *
     * Deliberately not run inline: a run takes minutes and the WebView is not
     * going to hold a request open for it. The page polls /api/job/:id.
     */
    private fun startJob(jobId: String, job: RunningJob, body: () -> Unit): Response {
        current = job
        currentJobId = jobId
        refreshBusy()
        jobs.execute {
            try {
                runJob(jobId, body)
            } finally {
                // Only clear if it is still this job: a cancel followed
                // immediately by a new run would otherwise have the old one
                // clear the new.
                if (current === job) { current = null; currentJobId = null }
                refreshBusy()
            }
        }
        return Response.json(200, """{"jobId":${jsonQuote(jobId)}}""")
    }

    /**
     * Run a job and record how it ended — including when it ends in an ERROR.
     *
     * `catch (e: Exception)` does not catch an Error. An OutOfMemoryError, a
     * StackOverflowError or a missing class would sail through it, kill this
     * worker thread, and Android's default handler would then kill the whole
     * PROCESS: the app disappears, the job row stays "running" forever, and
     * there is nothing at all to say what happened. A crash with no evidence
     * costs a release to find; a red row naming the Error costs one reading.
     *
     * Split out from the executor lambda only so it can be tested, which the
     * lambda could not be.
     */
    internal fun runJob(jobId: String, body: () -> Unit) {
        try {
            body()
            store.finishJob(jobId, "done", null)
        } catch (e: Cancelled) {
            store.finishJob(jobId, "cancelled", null)
        } catch (e: Exception) {
            store.finishJob(jobId, "failed", e.message)
        } catch (t: Throwable) {
            // Deliberately NOT rethrown. Rethrowing would put the process back
            // where it was: dead, with the reason only in a logcat nobody on a
            // phone can read.
            store.finishJob(jobId, "failed",
                "the app hit a " + t.javaClass.simpleName +
                (t.message?.let { ": $it" } ?: "") +
                ". The run stopped; anything already written is still there.")
        }
    }

    private fun jobsList(): Response =
        Response.json(200, "{\"jobs\":" +
            store.jobs(25).joinToString(",", "[", "]") { jobJson(store, it, currentJobId == it.id) } + "}")

    // --------------------------------------------------------------- static

    private fun static(req: Request): Response {
        val rel = if (req.path == "/" || req.path.isEmpty()) "index.html"
                  else req.path.trimStart('/')
        // Nothing outside the bundle, ever. A path is only ever read from the
        // app's own assets, but a ".." that escaped would still be a bug worth
        // not having.
        if (rel.contains("..")) return Response.notFound()
        val bytes = assets.read("web/$rel") ?: return Response.notFound()
        return Response.bytes(200, mimeFor(rel), bytes,
            mapOf("Cache-Control" to "no-cache"))
    }

    /**
     * Stop whatever is running, because the platform is about to stop us.
     *
     * Called from the app module when Android says the foreground service has
     * had its six hours ([android.app.Service.onTimeout]). The work cannot
     * carry on — the process is killed moments later if the service does not
     * step down, and once it has stepped down a long run would be killed
     * under memory pressure anyway.
     *
     * So the run is cancelled through the same `cancel()` that
     * `/api/job/:id/cancel` uses, and `runJob` records the job as
     * **cancelled** rather than leaving the row at "running" for ever. A run that
     * stopped for a reason the user can read is the whole point; a row that
     * says "running" about a process that no longer exists is not.
     *
     * Safe to call with nothing running: it does nothing.
     */
    fun cancelCurrentJob() {
        current?.cancel()
    }

    fun shutdown() {
        current?.cancel()
        jobs.shutdownNow()
    }

    companion object {
        /** The one direction there is: a library file into Qobuz. */
        const val IMPORT_DIRECTION = "csv-to-qobuz"
    }
}
