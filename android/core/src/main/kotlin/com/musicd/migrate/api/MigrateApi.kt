package com.musicd.migrate.api

import com.musicd.migrate.*
import com.musicd.migrate.http.Assets
import com.musicd.migrate.http.Request
import com.musicd.migrate.http.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/*
 * MigrateApi.kt — the same route table index.js serves, answering the same
 * page.
 *
 * THE BUNDLED PAGE IS THE AUTHORITY ON EVERY FIELD NAME, not index.js. This
 * file and that one must agree exactly, because public/app.js is shipped to
 * both and only ever written once. MusicD Remote Lite learned this the
 * expensive way — eleven wire-contract bugs came from porting the server's
 * names rather than reading what the page actually asks for — so every shape
 * built here was taken from app.js, and ApiContractTest checks the two route
 * tables still list the same routes.
 *
 * ONE JOB AT A TIME, exactly as on the server: two migrations at once would
 * race on the match cache, double the request rate into two rate-limited
 * services, and produce two progress bars for one library.
 */
class MigrateApi(
    private val store: Store,
    private val assets: Assets,
    private val http: Http = UrlConnectionHttp(),
    private val version: String = "0.1.0"
) {
    /** The live migration, or null. */
    @Volatile private var current: Migration? = null
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

    // ------------------------------------------------------------- sessions

    private fun qobuzSession(): QobuzSession? {
        val raw = store.setting("qobuz.session") ?: return null
        val j = parseObject(raw) ?: return null
        val token = j.strOrNull("token") ?: return null
        return QobuzSession(token, j.str("userId"), j.str("appId"), j.str("name"))
    }

    private fun saveQobuz(s: QobuzSession) {
        store.putSetting("qobuz.session", JSONObject()
            .put("token", s.token).put("userId", s.userId)
            .put("appId", s.appId).put("name", s.name).toString())
    }

    private fun spotifySession(): SpotifySession? {
        val clientId = store.setting("spotify.clientId").orEmpty()
        if (clientId.isEmpty()) return null
        val j = parseObject(store.setting("spotify.session") ?: return null) ?: return null
        val refresh = j.strOrNull("refreshToken") ?: return null
        return SpotifySession(clientId, j.str("accessToken"), refresh,
            j.longOrNull("expiresAt") ?: 0L, j.str("userId"), j.str("name"))
    }

    private fun saveSpotify(s: SpotifySession) {
        store.putSetting("spotify.session", JSONObject()
            .put("accessToken", s.accessToken).put("refreshToken", s.refreshToken)
            .put("expiresAt", s.expiresAt).put("userId", s.userId)
            .put("name", s.name).toString())
    }

    private fun qobuzClient(): QobuzClient? =
        qobuzSession()?.let { QobuzClient(it, http) }

    private fun spotifyClient(): SpotifyClient? =
        spotifySession()?.let {
            // Persisted on every refresh. Spotify rotates refresh tokens, so a
            // refresh that is not written down can leave the install unable to
            // recover.
            SpotifyClient(it, http, onTokens = { s -> saveSpotify(s) })
        }

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

        // Spotify's redirect target, which is NOT under /api/ — so it has to be
        // matched before the static fallback or it would 404 looking for a file
        // called "login". See Pkce.CALLBACK_PATH for why the path is /login.
        if (p == Pkce.CALLBACK_PATH) return spotifyCallback(req)

        if (!p.startsWith("/api/")) return static(req)

        return when {
            p == "/api/state" && req.method == "GET" -> state(req)

            p == "/api/qobuz/oauth/start" -> qobuzStart(req)
            p == "/api/qobuz/oauth/callback" -> qobuzCallback(req)
            p == "/api/qobuz/oauth/paste" -> qobuzPaste(req)
            p == "/api/qobuz/login" -> qobuzLogin(req)
            p == "/api/qobuz/signout" -> { store.deleteSetting("qobuz.session"); ok() }

            p == "/api/spotify/client-id" -> spotifyClientId(req)
            p == "/api/spotify/oauth/start" -> spotifyStart(req)
            p == Pkce.LEGACY_CALLBACK_PATH -> spotifyCallback(req)
            p == "/api/spotify/paste" -> spotifyPaste(req)
            p == "/api/spotify/signout" -> {
                store.deleteSetting("spotify.session")
                store.deleteSetting("spotify.pending")
                ok()
            }

            p == "/api/playlists" -> playlists(req)
            p == "/api/migrate" && req.method == "POST" -> migrate(req)
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
            tail == "" -> Response.json(200, jobJson(job))
            tail == "items" -> {
                val status = req.param("status")
                val all = store.items(id, status)
                val limit = (req.param("limit")?.toIntOrNull() ?: 500).coerceAtMost(5000)
                Response.json(200, """{"total":${all.size},"items":""" +
                    all.take(limit).joinToString(",", "[", "]") { itemJson(it) } + "}")
            }
            tail == "report.csv" -> csv(id, req.param("status"), job.id)
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
        val q = qobuzSession()
        val sp = spotifySession()
        val redirect = callbackUrlFrom(req, Pkce.CALLBACK_PATH)
        val check = Pkce.checkRedirectUri(redirect)
        return Response.json(200, buildString {
            append("{")
            append("\"pinRequired\":false,")
            append("\"qobuz\":{\"signedIn\":").append(q != null)
            append(",\"name\":").append(jsonQuote(q?.name.orEmpty())).append("},")
            append("\"spotify\":{\"signedIn\":").append(sp != null)
            append(",\"name\":").append(jsonQuote(sp?.name.orEmpty()))
            append(",\"clientId\":").append(jsonQuote(store.setting("spotify.clientId").orEmpty()))
            append(",\"redirectUri\":").append(jsonQuote(redirect))
            append(",\"redirectCheck\":{\"ok\":").append(check.ok)
            append(",\"reason\":").append(jsonQuote(check.reason)).append("}},")
            append("\"cacheSize\":").append(store.matchCacheSize()).append(",")
            append("\"job\":")
            val jid = currentJobId
            if (jid == null) append("null")
            else append("{\"id\":").append(jsonQuote(jid)).append(",\"running\":true}")
            append(",\"version\":").append(jsonQuote(version))
            append("}")
        })
    }

    /**
     * Where a service should send the browser back to.
     *
     * Derived from the request rather than configured, the same way index.js
     * derives it. On the phone this is always the loopback server's own
     * address, which is exactly the address Spotify accepts for plain http —
     * so the APK is the one place the redirect works with no caveat at all.
     */
    private fun callbackUrlFrom(req: Request, path: String): String {
        val host = req.headers["x-forwarded-host"] ?: req.headers["host"] ?: return ""
        val proto = req.headers["x-forwarded-proto"]?.split(",")?.firstOrNull()?.trim()
            ?: "http"
        return "$proto://$host$path"
    }

    // ----------------------------------------------------------- Qobuz auth

    private fun qobuzStart(req: Request): Response {
        val redirect = callbackUrlFrom(req, "/api/qobuz/oauth/callback")
        store.putSetting("qobuz.pending", redirect)
        return Response.redirect(QobuzOAuth.buildAuthorizeUrl(redirect))
    }

    private fun qobuzCallback(req: Request): Response {
        val code = QobuzOAuth.extractCode(req.rawTarget)
        if (code.isEmpty()) {
            return Response.html(200, closingPage("Qobuz did not send a sign-in code back. " +
                "The sign-in may have been cancelled — close this and try again."))
        }
        return try {
            finishQobuz(code)
            Response.html(200, closingPage("Signed in to Qobuz. You can close this tab."))
        } catch (e: Exception) {
            Response.html(200, closingPage("Qobuz sign-in failed: ${e.message}"))
        }
    }

    private fun qobuzPaste(req: Request): Response {
        val code = QobuzOAuth.extractCode(parseObject(req.bodyText)?.str("url").orEmpty())
        if (code.isEmpty()) {
            return Response.json(400, obj("error" to "No sign-in code found in that."))
        }
        return try {
            finishQobuz(code); ok()
        } catch (e: Exception) {
            Response.json(400, obj("error" to (e.message ?: "Sign-in failed")))
        }
    }

    private fun finishQobuz(code: String) {
        val session = QobuzClient.exchangeCode(http, code)
        // user/get is a nicety — the token is already proven by the exchange —
        // so a failure here must not throw away a good sign-in.
        runCatching { QobuzClient(session, http).me() }
        saveQobuz(session)
    }

    private fun qobuzLogin(req: Request): Response {
        val b = parseObject(req.bodyText)
        return try {
            val s = QobuzSession()
            QobuzClient(s, http).login(b?.str("username").orEmpty(), b?.str("password").orEmpty())
            saveQobuz(s)
            Response.json(200, """{"ok":true,"name":${jsonQuote(s.name)}}""")
        } catch (e: Exception) {
            Response.json(400, obj("error" to (e.message ?: "Sign-in failed")))
        }
    }

    // --------------------------------------------------------- Spotify auth

    private fun spotifyClientId(req: Request): Response {
        val id = parseObject(req.bodyText)?.str("clientId").orEmpty().trim()
        if (!Regex("^[0-9a-fA-F]{32}$").matches(id)) {
            return Response.json(400, obj("error" to
                "A Spotify Client ID is 32 letters and numbers. Copy it from your app on " +
                "developer.spotify.com/dashboard."))
        }
        store.putSetting("spotify.clientId", id)
        return ok()
    }

    private fun spotifyStart(req: Request): Response {
        val clientId = store.setting("spotify.clientId").orEmpty()
        if (clientId.isEmpty()) {
            return Response.text(400, "Set your Spotify Client ID first.")
        }
        val redirectUri = callbackUrlFrom(req, Pkce.CALLBACK_PATH)
        val verifier = Pkce.createVerifier()
        val state = Pkce.createState()
        // Stored rather than held in memory so the callback still works if the
        // service is restarted mid-sign-in, and so the paste-back path can
        // find the verifier that goes with the code being pasted.
        store.putSetting("spotify.pending", JSONObject()
            .put("verifier", verifier).put("state", state)
            .put("redirectUri", redirectUri).toString())
        return Response.redirect(Pkce.buildAuthorizeUrl(clientId, redirectUri,
            Pkce.challengeFor(verifier), state))
    }

    private fun spotifyCallback(req: Request): Response = try {
        finishSpotify(Pkce.parseCallback(req.rawTarget))
        Response.html(200, closingPage("Signed in to Spotify. You can close this tab."))
    } catch (e: Exception) {
        Response.html(200, closingPage("Spotify sign-in failed: ${e.message}"))
    }

    private fun spotifyPaste(req: Request): Response = try {
        finishSpotify(Pkce.parseCallback(parseObject(req.bodyText)?.str("url").orEmpty()))
        ok()
    } catch (e: Exception) {
        Response.json(400, obj("error" to (e.message ?: "Sign-in failed")))
    }

    private fun finishSpotify(parsed: Pkce.Callback) {
        if (parsed.error.isNotEmpty()) {
            throw RuntimeException(if (parsed.error == "access_denied")
                "The sign-in was cancelled or refused on Spotify's page."
            else "Spotify reported: ${parsed.error}")
        }
        if (parsed.code.isEmpty()) throw RuntimeException("No sign-in code found in that.")

        val pending = parseObject(store.setting("spotify.pending") ?: "")
            ?: throw RuntimeException("No sign-in was in progress. Start again from this page.")
        val verifier = pending.strOrNull("verifier")
            ?: throw RuntimeException("No sign-in was in progress. Start again from this page.")
        // The state ties the callback to the sign-in this process started. It
        // is checked only when one came back: the paste-back path routinely
        // loses it, because people copy the address bar of a page that failed
        // to load.
        val expected = pending.str("state")
        if (parsed.state.isNotEmpty() && expected.isNotEmpty() && parsed.state != expected) {
            throw RuntimeException("That sign-in does not match the one started here. " +
                "Start again.")
        }

        val tokens = SpotifyClient.exchangeCode(http,
            store.setting("spotify.clientId").orEmpty(), parsed.code,
            pending.str("redirectUri"), verifier)
        store.deleteSetting("spotify.pending")
        saveSpotify(tokens)

        val client = SpotifyClient(tokens, http, onTokens = { s -> saveSpotify(s) })
        runCatching { client.me() }
        saveSpotify(tokens)
    }

    // ------------------------------------------------------------- library

    private fun playlists(req: Request): Response {
        val which = req.param("service").orEmpty()
        val client: MusicTarget? = when (which) {
            "qobuz" -> qobuzClient()
            "spotify" -> spotifyClient()
            else -> null
        } ?: return Response.json(400, obj("error" to "Not signed in to $which."))

        return try {
            if (client!!.accountId.isEmpty()) client.me()
            val mine = client.accountId
            Response.json(200, "{\"playlists\":" + client.playlists().joinToString(",", "[", "]") {
                buildString {
                    append("{\"id\":").append(jsonQuote(it.id))
                    append(",\"name\":").append(jsonQuote(it.name))
                    append(",\"description\":").append(jsonQuote(it.description))
                    append(",\"public\":").append(it.isPublic)
                    append(",\"ownerId\":").append(jsonQuote(it.ownerId))
                    append(",\"trackCount\":").append(it.trackCount)
                    append(",\"mine\":").append(it.ownerId.isEmpty() || it.ownerId == mine)
                    append("}")
                }
            } + "}")
        } catch (e: AuthError) {
            Response.json(401, obj("error" to e.message.orEmpty()))
        } catch (e: Exception) {
            Response.json(502, obj("error" to (e.message ?: "The service did not answer.")))
        }
    }

    // ------------------------------------------------------------------ jobs

    private fun migrate(req: Request): Response {
        if (current != null) {
            return Response.json(409, obj("error" to "A migration is already running."))
        }
        val b = parseObject(req.bodyText) ?: JSONObject()
        val direction = if (b.str("direction") == "spotify-to-qobuz") "spotify-to-qobuz"
                        else "qobuz-to-spotify"

        val qz = qobuzClient()
            ?: return Response.json(400, obj("error" to "Sign in to Qobuz first."))
        val sp = spotifyClient()
            ?: return Response.json(400, obj("error" to "Sign in to Spotify first."))

        val source: MusicSource = if (direction == "qobuz-to-spotify") qz else sp
        val target: MusicTarget = if (direction == "qobuz-to-spotify") sp else qz

        // `playlists` is either a boolean or an array of ids — the page sends
        // both shapes, so both are read here.
        val plField = b.opt("playlists")
        val doPlaylists: Boolean
        val playlistIds: List<String>?
        when (plField) {
            is JSONArray -> { doPlaylists = true; playlistIds = plField.strings() }
            is Boolean -> { doPlaylists = plField; playlistIds = null }
            else -> { doPlaylists = false; playlistIds = null }
        }

        val onExisting = b.str("onExisting").let {
            if (it in listOf("add-missing", "create-new", "skip")) it else "add-missing"
        }

        val options = MigrationOptions(
            playlistIds = playlistIds,
            doPlaylists = doPlaylists,
            doAlbums = b.optBoolean("albums", false),
            doArtists = b.optBoolean("artists", false),
            doTracks = b.optBoolean("tracks", false),
            dryRun = b.optBoolean("dryRun", false),
            strict = b.optBoolean("strict", false),
            // Default ON, so an absent field means corroborate: an older page
            // gets the safer behaviour rather than the title-only match.
            corroborate = b.optBoolean("corroborate", true),
            onExisting = onExisting,
            includeOthersPlaylists = b.optBoolean("includeOthersPlaylists", false),
            playlistSuffix = b.str("playlistSuffix").take(40),
            concurrency = b.intOrNull("concurrency") ?: 4
        )

        val jobId = UUID.randomUUID().toString()
        store.createJob(jobId, direction, req.bodyText, options.dryRun)

        val migration = Migration(source, target, store, jobId, options)
        current = migration
        currentJobId = jobId

        // Deliberately not run inline: a migration takes minutes and the
        // WebView is not going to hold a request open for it. The job id comes
        // back now and the page polls /api/job/:id.
        jobs.execute {
            try {
                migration.run()
                store.finishJob(jobId, "done", null)
            } catch (e: Cancelled) {
                store.finishJob(jobId, "cancelled", null)
            } catch (e: Exception) {
                store.finishJob(jobId, "failed", e.message)
            } finally {
                // Only clear if it is still this job: a cancel followed
                // immediately by a new migration would otherwise have the old
                // one clear the new.
                if (current === migration) { current = null; currentJobId = null }
            }
        }

        return Response.json(200, """{"jobId":${jsonQuote(jobId)}}""")
    }

    private fun jobsList(): Response =
        Response.json(200, "{\"jobs\":" +
            store.jobs(25).joinToString(",", "[", "]") { jobJson(it) } + "}")

    private fun jobJson(job: JobRow): String = buildString {
        append("{\"id\":").append(jsonQuote(job.id))
        append(",\"created\":").append(job.created)
        append(",\"finished\":").append(job.finished ?: "null")
        append(",\"direction\":").append(jsonQuote(job.direction))
        append(",\"status\":").append(jsonQuote(job.status))
        append(",\"dryRun\":").append(job.dryRun)
        append(",\"progress\":").append(
            if (job.progressJson.isBlank()) "{}" else job.progressJson)
        append(",\"error\":").append(if (job.error == null) "null" else jsonQuote(job.error))
        append(",\"running\":").append(currentJobId == job.id)
        append(",\"counts\":{")
        append(store.itemCounts(job.id).entries.joinToString(",") {
            jsonQuote(it.key) + ":" + it.value
        })
        append("}}")
    }

    private fun itemJson(it: JobItem): String = buildString {
        append("{\"kind\":").append(jsonQuote(it.kind))
        append(",\"container\":").append(
            if (it.container == null) "null" else jsonQuote(it.container))
        append(",\"sourceId\":").append(jsonQuote(it.sourceId))
        append(",\"sourceLabel\":").append(jsonQuote(it.sourceLabel))
        append(",\"targetId\":").append(
            if (it.targetId == null) "null" else jsonQuote(it.targetId))
        append(",\"status\":").append(jsonQuote(it.status))
        append(",\"method\":").append(if (it.method == null) "null" else jsonQuote(it.method))
        append(",\"note\":").append(if (it.note == null) "null" else jsonQuote(it.note))
        append("}")
    }

    /**
     * The unmatched list as a spreadsheet — the actual deliverable of a
     * migration, in a form someone can work through. A list that can only be
     * scrolled in a WebView is a list nobody finishes.
     */
    private fun csv(jobId: String, status: String?, shortId: String): Response {
        val rows = store.items(jobId, status)
        val sb = StringBuilder("kind,playlist,item,status,method,note,targetId")
        for (r in rows) {
            sb.append("\r\n")
            sb.append(listOf(r.kind, r.container.orEmpty(), r.sourceLabel, r.status,
                r.method.orEmpty(), r.note.orEmpty(), r.targetId.orEmpty())
                .joinToString(",") { csvCell(it) })
        }
        return Response.bytes(200, "text/csv; charset=utf-8",
            sb.toString().toByteArray(Charsets.UTF_8),
            mapOf("Content-Disposition" to
                "attachment; filename=\"musicd-migrate-${shortId.take(8)}.csv\""))
    }

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

    fun shutdown() {
        current?.cancel()
        jobs.shutdownNow()
    }

    companion object {
        fun ok() = Response.json(200, """{"ok":true}""")

        fun obj(vararg pairs: Pair<String, String>): String =
            pairs.joinToString(",", "{", "}") { jsonQuote(it.first) + ":" + jsonQuote(it.second) }

        fun mimeFor(path: String): String = when (path.substringAfterLast('.', "")) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "webmanifest" -> "application/json; charset=utf-8"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }

        /**
         * A CSV cell.
         *
         * A leading =, +, - or @ is EXECUTED AS A FORMULA by Excel and Sheets
         * when the file is opened, and track titles beginning with "-" are not
         * rare. Identical to the rule in index.js.
         */
        fun csvCell(v: String): String {
            val guarded = if (v.isNotEmpty() && v[0] in "=+-@") "'$v" else v
            return if (guarded.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) {
                "\"" + guarded.replace("\"", "\"\"") + "\""
            } else guarded
        }

        fun escapeHtml(s: String): String = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        /** What an OAuth redirect lands on. Identical to the server's. */
        fun closingPage(message: String): String = """<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MusicD Migrate</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 16px/1.5 system-ui, sans-serif; margin: 0; display: grid;
         place-items: center; min-height: 100svh; padding: 24px; text-align: center; }
  p { max-width: 32rem; }
</style>
<p>${escapeHtml(message)}</p>
<script>
  try { if (window.opener) window.opener.postMessage("musicd-migrate-auth", "*"); } catch (e) {}
</script>"""
    }
}
