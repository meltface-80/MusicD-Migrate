package com.musicd.migrate.api

import com.musicd.migrate.*
import com.musicd.migrate.http.Request
import com.musicd.migrate.http.Response
import org.json.JSONObject

/**
 * Both services' sign-ins: the stored sessions, the clients built from them,
 * and the OAuth routes that create them. Split out of MigrateApi.kt, which
 * routes to these.
 *
 * @param onRateLimit told when a client is held by its service, so the live
 *   run can say so on the page — see Migration.noteRateLimit. Resolved at call
 *   time, because a client is built before the run that will use it exists.
 */
class SignIns(
    private val store: Store,
    private val http: Http,
    private val onRateLimit: (Long) -> Unit
) {
    fun qobuzSession(): QobuzSession? {
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

    /**
     * The Client ID in force: whatever is saved, else the baked-in one.
     *
     * One function rather than the four `store.setting("spotify.clientId")`
     * reads this replaces — a fallback applied at three sites out of four is a
     * sign-in that starts with one id and redeems the code with another, which
     * fails with an error about the CODE and not about the id.
     *
     * See [Pkce.DEFAULT_CLIENT_ID] for what is baked in and what it costs.
     */
    fun spotifyClientId(): String =
        store.setting("spotify.clientId").orEmpty().ifEmpty { Pkce.DEFAULT_CLIENT_ID }

    fun spotifySession(): SpotifySession? {
        val clientId = spotifyClientId()
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

    fun qobuzClient(): QobuzClient? =
        qobuzSession()?.let { QobuzClient(it, http, onRateLimit = { ms -> onRateLimit(ms) }) }

    fun spotifyClient(): SpotifyClient? =
        spotifySession()?.let {
            // Persisted on every refresh. Spotify rotates refresh tokens, so a
            // refresh that is not written down can leave the install unable to
            // recover.
            SpotifyClient(it, http, onTokens = { s -> saveSpotify(s) },
                onRateLimit = { ms -> onRateLimit(ms) })
        }

    /**
     * Where a service should send the browser back to.
     *
     * Derived from the request rather than configured. On the phone this is always the loopback server's own
     * address, which is exactly the address Spotify accepts for plain http —
     * so the APK is the one place the redirect works with no caveat at all.
     */
    fun callbackUrlFrom(req: Request, path: String): String {
        val host = req.headers["x-forwarded-host"] ?: req.headers["host"] ?: return ""
        val proto = req.headers["x-forwarded-proto"]?.split(",")?.firstOrNull()?.trim()
            ?: "http"
        return "$proto://$host$path"
    }

    // ----------------------------------------------------------- Qobuz auth

    fun qobuzStart(req: Request): Response {
        val redirect = callbackUrlFrom(req, "/api/qobuz/oauth/callback")
        store.putSetting("qobuz.pending", redirect)
        return Response.redirect(QobuzOAuth.buildAuthorizeUrl(redirect))
    }

    fun qobuzCallback(req: Request): Response {
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

    fun qobuzPaste(req: Request): Response {
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

    fun qobuzLogin(req: Request): Response {
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

    fun saveSpotifyClientId(req: Request): Response {
        val id = parseObject(req.bodyText)?.str("clientId").orEmpty().trim()
        if (!Regex("^[0-9a-fA-F]{32}$").matches(id)) {
            return Response.json(400, obj("error" to
                "A Spotify Client ID is 32 letters and numbers. Copy it from your app on " +
                "developer.spotify.com/dashboard."))
        }
        store.putSetting("spotify.clientId", id)
        return ok()
    }

    fun spotifyStart(req: Request): Response {
        val clientId = spotifyClientId()
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

    fun spotifyCallback(req: Request): Response = try {
        finishSpotify(Pkce.parseCallback(req.rawTarget))
        Response.html(200, closingPage("Signed in to Spotify. You can close this tab."))
    } catch (e: Exception) {
        Response.html(200, closingPage("Spotify sign-in failed: ${e.message}"))
    }

    fun spotifyPaste(req: Request): Response = try {
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
            spotifyClientId(), parsed.code,
            pending.str("redirectUri"), verifier)
        store.deleteSetting("spotify.pending")
        saveSpotify(tokens)

        val client = SpotifyClient(tokens, http, onTokens = { s -> saveSpotify(s) })
        runCatching { client.me() }
        saveSpotify(tokens)
    }
}
