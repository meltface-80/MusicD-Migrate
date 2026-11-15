package com.musicd.migrate

/*
 * SpotifyTokens.kt — the signed-in session, and trading a code or a refresh
 * token for an access token. No client secret: see Pkce. Split out of
 * SpotifyClient.kt.
 */

data class SpotifySession(
    val clientId: String = "",
    var accessToken: String = "",
    var refreshToken: String = "",
    var expiresAt: Long = 0,
    var userId: String = "",
    var name: String = ""
)

object SpotifyTokens {
    /**
     * Trade the one-time code for tokens. No client secret — see Pkce.
     */
    fun exchangeCode(
        http: Http, clientId: String, code: String, redirectUri: String, verifier: String
    ): SpotifySession = tokenRequest(http, mapOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to redirectUri,
        "client_id" to clientId,
        "code_verifier" to verifier
    ), clientId)

    /**
     * A fresh access token from the refresh token.
     *
     * Spotify ROTATES refresh tokens: the response may carry a new one,
     * and when it does the old one stops working. Carrying the old one
     * forward when none came back — rather than leaving the caller to
     * notice — is what stops a long-lived install losing its sign-in.
     */
    fun refresh(http: Http, clientId: String, refreshToken: String): SpotifySession {
        val out = tokenRequest(http, mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId
        ), clientId)
        if (out.refreshToken.isEmpty()) out.refreshToken = refreshToken
        return out
    }

    private fun tokenRequest(
        http: Http, form: Map<String, Any?>, clientId: String
    ): SpotifySession {
        val res = try {
            http.request("POST", Pkce.TOKEN_URL,
                body = query(form).toByteArray(Charsets.UTF_8),
                contentType = "application/x-www-form-urlencoded", timeoutMs = 20000)
        } catch (e: Exception) {
            throw RuntimeException("Could not reach Spotify to sign in: ${e.message}")
        }
        val data = parseObject(res.body)
        if (!res.ok) {
            // Spotify's token errors are genuinely informative and the
            // user can act on most of them — a wrong client id, an
            // unregistered redirect URI, a code already spent. Passing the
            // description through beats "HTTP 400".
            val desc = data?.strOrNull("error_description")
                ?: data?.strOrNull("error")
                ?: res.body.take(200)
            throw RuntimeException("Spotify refused the sign-in: $desc")
        }
        val token = data?.strOrNull("access_token")
            ?: throw RuntimeException("Spotify returned no access token")
        return SpotifySession(
            clientId = clientId,
            accessToken = token,
            refreshToken = data.str("refresh_token"),
            // 60s of slack: a token that expires mid-flight comes back as
            // a 401 that looks exactly like a revoked sign-in.
            expiresAt = System.currentTimeMillis() +
                ((data.longOrNull("expires_in") ?: 3600L) - 60L) * 1000L
        )
    }
}
