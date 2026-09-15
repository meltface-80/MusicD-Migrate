package com.musicd.migrate.api

import com.musicd.migrate.*
import com.musicd.migrate.http.Assets
import com.musicd.migrate.roon.BrowseApi
import com.musicd.migrate.roon.RoonClient
import com.musicd.migrate.roon.RoonCore
import com.musicd.migrate.roon.RoonDiscovery
import com.musicd.migrate.roon.RoonExtension
import com.musicd.migrate.roon.RoonScanSummary
import com.musicd.migrate.roon.RoonStage
import com.musicd.migrate.roon.SoodDiscovery
import com.musicd.migrate.roon.StoreRoonMemory
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
    private val version: String = "0.1.0",
    /**
     * How a Roon Core is found.
     *
     * Injectable because on ANDROID it must hold a WifiManager multicast lock
     * while it listens: the platform filters multicast out of userspace
     * without one, so SOOD replies never arrive and discovery reports no Core
     * on a network that has one. The app module supplies a discovery that
     * takes the lock; :core cannot, because it must not depend on the SDK.
     */
    private val roonDiscovery: RoonDiscovery = SoodDiscovery()
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

            p == "/api/roon/connect" && req.method == "POST" -> roonConnect(req)
            p == "/api/roon/forget" && req.method == "POST" -> roonForget()
            p == "/api/roon/scan" && req.method == "POST" -> roonScanStart(req)
            p == "/api/roon/scan/cancel" && req.method == "POST" -> roonScanCancel()
            p == "/api/roon/library.csv" -> roonLibraryCsv()

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
            append("\"roon\":").append(roonStateJson()).append(",")
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

    // ---------------------------------------------------------------- Roon

    /*
     * Created LAZILY and never on startup. Pairing means broadcasting on the
     * user's network and asking them to enable an extension, and a copy of
     * this app used only for Qobuz and Spotify should do neither. The first
     * press of "Find my Roon Core" is what brings it into existence.
     */
    @Volatile private var roon: RoonCore? = null
    @Volatile private var roonScanning = false
    @Volatile private var roonCancelScan = false
    @Volatile private var roonProgress: IntArray? = null

    private fun roonCore(): RoonCore {
        roon?.let { return it }
        synchronized(this) {
            roon?.let { return it }
            val core = RoonCore(
                memory = StoreRoonMemory(store),
                discovery = roonDiscovery,
                extension = RoonExtension(version = version)
            )
            roon = core
            return core
        }
    }

    private fun roonClient(core: BrowseApi): RoonClient = RoonClient(core, store)

    /**
     * What the page shows for Roon. Reads only — asking for the state must
     * never start a connection, or a page refresh would broadcast on the
     * user's network.
     */
    private fun roonStateJson(): String {
        val core = roon
        if (core == null) {
            return "{\"stage\":\"idle\",\"detail\":\"\",\"paired\":false," +
                "\"albums\":0,\"scan\":null,\"scanning\":false,\"progress\":null}"
        }
        val st = core.status
        val coreId = core.coreId ?: "roon"
        val saved = store.roonScan()?.takeIf { it.coreId == coreId }
        val p = roonProgress
        return buildString {
            append("{\"stage\":").append(jsonQuote(st.stage))
            append(",\"detail\":").append(jsonQuote(st.detail))
            append(",\"coreName\":").append(jsonQuote(st.coreName.orEmpty()))
            append(",\"host\":").append(jsonQuote(st.host.orEmpty()))
            append(",\"port\":").append(st.port)
            append(",\"paired\":").append(st.paired)
            append(",\"albums\":").append(store.roonAlbumCount(coreId))
            append(",\"scanning\":").append(roonScanning)
            append(",\"scan\":").append(saved?.let { scanJson(it) } ?: "null")
            append(",\"progress\":")
            if (p == null) append("null") else append("{\"done\":").append(p[0])
                .append(",\"total\":").append(p[1])
                .append(",\"stored\":").append(p[2])
                .append(",\"duplicates\":").append(p[3]).append("}")
            append("}")
        }
    }

    private fun scanJson(s: RoonScanSummary): String =
        "{\"coreId\":" + jsonQuote(s.coreId) + ",\"offset\":" + s.offset +
        ",\"total\":" + s.total + ",\"stored\":" + s.stored +
        ",\"duplicates\":" + s.duplicates + ",\"done\":" + s.done + "}"

    /**
     * Start pairing. Returns at once and the page polls /api/state: on a FIRST
     * pair Roon answers "Registered" only once the user has enabled the
     * extension, and a request held open for that is a request that times out.
     */
    private fun roonConnect(req: Request): Response {
        val b = parseObject(req.bodyText) ?: JSONObject()
        val host = b.str("host").trim()
        val port = b.intOrNull("port") ?: 0
        return try {
            val core = roonCore()
            if (host.isNotEmpty()) core.connectTo(host, if (port > 0) port else 9330)
            else core.start()
            ok()
        } catch (e: Exception) {
            Response.json(500, obj("error" to (e.message ?: "Could not start")))
        }
    }

    private fun roonForget(): Response {
        roon?.rediscover()
        return ok()
    }

    /**
     * Walk the library and store it, in the background — ten thousand albums
     * is a hundred round trips to the Core, and the page shows progress rather
     * than waiting on one request.
     */
    private fun roonScanStart(req: Request): Response {
        if (roonScanning) {
            return Response.json(409, obj("error" to "A Roon scan is already running."))
        }
        val core = roon
        if (core == null || !core.isPaired) {
            return Response.json(400, obj("error" to "Pair with a Roon Core first."))
        }
        val resume = (parseObject(req.bodyText) ?: JSONObject()).optBoolean("resume", false)
        roonScanning = true
        roonCancelScan = false
        roonProgress = intArrayOf(0, 0, 0, 0)
        Thread({
            try {
                roonClient(core).scan(
                    resume = resume,
                    cancelled = { roonCancelScan },
                    onProgress = { done, total, stored, dupes ->
                        roonProgress = intArrayOf(done, total, stored, dupes)
                    })
            } catch (e: Exception) {
                // Kept where the page can read it rather than thrown away: a
                // scan that stopped for a reason must say the reason.
                store.putSetting("roon.scanError", e.message ?: "The scan failed.")
            } finally {
                roonScanning = false
                roonProgress = null
            }
        }, "roon-scan").apply { isDaemon = true }.start()
        return ok()
    }

    private fun roonScanCancel(): Response {
        if (!roonScanning) return Response.json(404, obj("error" to "No Roon scan is running."))
        roonCancelScan = true
        return ok()
    }

    /**
     * The scanned library, as a spreadsheet.
     *
     * The actual deliverable of a Roon scan: not "it worked", but a row per
     * album saying whether it was found on each service and, when it was not,
     * WHY. The reasons come out of the match cache.
     */
    private fun roonLibraryCsv(): Response {
        val coreId = roon?.coreId ?: "roon"
        val rows = store.roonAlbums(coreId)
        if (rows.isEmpty()) {
            return Response.text(404, "No Roon library has been scanned yet.")
        }
        val out = StringBuilder("artist,album,tracks,spotify,spotifyNote,qobuz,qobuzNote,roonKey")
        for (r in rows) {
            val sp = store.cachedMatch("roon", r.albumKey, "spotify", "album")
            val qz = store.cachedMatch("roon", r.albumKey, "qobuz", "album")
            out.append("\r\n").append(listOf(
                r.artist, r.title, r.trackCount?.toString().orEmpty(),
                sp?.toId.orEmpty(), if (sp != null && sp.toId == null) sp.method else "",
                qz?.toId.orEmpty(), if (qz != null && qz.toId == null) qz.method else "",
                r.albumKey
            ).joinToString(",") { csvCell(it) })
        }
        return Response.bytes(200, "text/csv; charset=utf-8",
            out.toString().toByteArray(Charsets.UTF_8),
            mapOf("Content-Disposition" to "attachment; filename=\"roon-library.csv\""))
    }

    // ------------------------------------------------------------- library

    /**
     * Every direction this app will run, and which service is which end.
     *
     * Roon appears only on the left. There is no way to write an album into
     * somebody's local library — the file would have to exist first — and
     * Model.kt enforces it: RoonClient is a MusicSource and is not a
     * MusicTarget, so "spotify-to-roon" would not compile, let alone run. It
     * is simply not offered. Kept in step with DIRECTIONS in index.js.
     */
    private val DIRECTIONS = mapOf(
        "qobuz-to-spotify" to ("qobuz" to "spotify"),
        "spotify-to-qobuz" to ("spotify" to "qobuz"),
        "roon-to-spotify" to ("roon" to "spotify"),
        "roon-to-qobuz" to ("roon" to "qobuz")
    )

    /**
     * A client for one end, or a refusal saying what is missing.
     *
     * Throws rather than returning null so the caller cannot forget to check:
     * the message is what the user is shown, and "Sign in to Spotify first" is
     * more use than a 500.
     */
    private fun sourceFor(name: String): MusicSource {
        if (name != "roon") return targetFor(name)
        val core = roon
        if (core == null || !core.isPaired) throw RuntimeException("Pair with a Roon Core first.")
        if (store.roonAlbumCount(core.coreId ?: "roon") == 0) {
            throw RuntimeException("Scan your Roon library first \u2014 a migration reads the " +
                "scan, not the Core, so it would otherwise look as though you owned nothing.")
        }
        return roonClient(core)
    }

    private fun targetFor(name: String): MusicTarget = when (name) {
        "qobuz" -> qobuzClient() ?: throw RuntimeException("Sign in to Qobuz first.")
        "spotify" -> spotifyClient() ?: throw RuntimeException("Sign in to Spotify first.")
        else -> throw RuntimeException("Unknown service: $name")
    }

    private fun playlists(req: Request): Response {
        val which = req.param("service").orEmpty()
        if (which == "roon") {
            // Answered with the REASON rather than an empty list. An empty
            // list reads as "you have no playlists"; the truth is that this
            // app will not migrate them.
            return Response.json(400, obj("error" to RoonClient.UNSUPPORTED_PLAYLISTS))
        }
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
        val asked = b.str("direction")
        val direction = if (DIRECTIONS.containsKey(asked)) asked else "qobuz-to-spotify"
        val (fromName, toName) = DIRECTIONS.getValue(direction)

        // Only the two services actually involved have to be signed in.
        // Requiring both for a Roon migration would refuse a run that needs
        // neither the other service's tokens nor its catalogue.
        val source: MusicSource
        val target: MusicTarget
        try {
            source = sourceFor(fromName)
            target = targetFor(toName)
        } catch (e: Exception) {
            return Response.json(400, obj("error" to (e.message ?: "Not signed in.")))
        }

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
