package com.musicd.migrate.api

import com.musicd.migrate.*
import com.musicd.migrate.http.Request
import com.musicd.migrate.http.Response
import org.json.JSONObject

/**
 * The Qobuz sign-in: the stored session, the client built from it, and the
 * routes that create it. (Spotify's lived here too until 0.5.0 removed it.)
 * Split out of MigrateApi.kt, which
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

    fun qobuzClient(): QobuzClient? =
        qobuzSession()?.let { QobuzClient(it, http, onRateLimit = { ms -> onRateLimit(ms) }) }

    /**
     * Where a service should send the browser back to.
     *
     * Derived from the request rather than configured. On the phone this is
     * always the loopback server's own address.
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
}
