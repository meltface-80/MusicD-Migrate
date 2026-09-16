package com.musicd.migrate

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * Pkce.kt — signing in to Spotify without a client secret. The Kotlin twin of
 * lib/spotify-pkce.js, tested by the same cases including RFC 7636's own
 * published test vector.
 *
 * WHY PKCE. The ordinary authorization-code flow needs a client secret, and
 * there is nowhere to put one in an APK: anything shipped inside it is
 * published to everybody who installs it. PKCE exists for exactly this — the
 * app mints a random verifier per sign-in, sends only its hash, and proves
 * possession by presenting the original when it redeems the code.
 *
 * WHY THE USER SUPPLIES THE CLIENT ID. Spotify will not issue tokens to an
 * unregistered application, and an application is registered against a fixed
 * list of redirect URIs — which for this app is whatever address the phone is
 * reachable on. On top of that a Spotify app in development mode serves at
 * most 25 named users, so a shared id would work for the first 25 people and
 * fail for everyone after.
 */
object Pkce {

    const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
    const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    /**
     * What this app asks to be allowed to do.
     *
     * Read AND write on each of the four things it migrates, because the same
     * install is the source one day and the destination the next. Nothing here
     * grants playback control or listening history: a scope not asked for is a
     * scope that cannot be misused.
     */
    val SCOPES = listOf(
        "playlist-read-private",
        "playlist-read-collaborative",
        "playlist-modify-private",
        "playlist-modify-public",
        "user-library-read",
        "user-library-modify",
        "user-follow-read",
        "user-follow-modify"
    )

    /**
     * The path Spotify redirects back to: `/login`, NOT this app's own
     * `/api/spotify/callback`.
     *
     * Not guessable, and it cost a release. Spotify has frozen new app
     * registrations, so for most people the only available Client ID is one of
     * the well-known ones the open-source Spotify ecosystem shares (librespot,
     * ncspot, Spotty, SpotOn). Those registrations whitelist exactly ONE
     * loopback path — `/login` — on any 127.0.0.1 port (RFC 8252). Anything
     * else is refused before the user can sign in:
     *
     *     redirect_uri: Not matching configuration
     *
     * `/login` is what SpotOn registers too, and where this came from.
     */
    const val CALLBACK_PATH = "/login"

    /** Still served, never advertised: for a redirect URI registered against
     *  somebody's own app before /login became the default. */
    const val LEGACY_CALLBACK_PATH = "/api/spotify/callback"

    /**
     * The Client ID used when nobody has saved one.
     *
     * Spotify's registrations are frozen, so for the person this was built
     * for there is no such thing as "get your own" — asking for one that
     * cannot be obtained just makes the app unusable. So it is baked in, at
     * the owner's explicit request, with all of this stated in the open:
     *
     * - **It is not registered to MusicD Migrate.** It is one of the
     *   well-known ids the open-source Spotify world shares, and it works
     *   here for exactly one reason: `/login` is whitelisted on it (see
     *   [CALLBACK_PATH]).
     * - **The consent screen names that application, not this one**, and
     *   requests made with it count against its quota. If it is ever
     *   rate-limited or withdrawn, the Spotify half stops working and nothing
     *   here can fix that.
     * - **It is a fallback, never an override.** A saved id always wins.
     * - **It is not secret.** PKCE means there is no client secret, and a
     *   client id travels in the authorise URL in plain sight.
     *
     * Kept in step with DEFAULT_CLIENT_ID in lib/spotify-pkce.js by hand;
     * ContractTest fails if the two ever disagree, because a mismatch would
     * mean the phone and the container sign in as two different applications.
     */
    const val DEFAULT_CLIENT_ID = "d420a117a32841c2b3474932e49fb54b"

    private const val VERIFIER_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private val random = SecureRandom()

    fun createVerifier(length: Int = 64): String {
        val n = length.coerceIn(43, 128) // the spec's range
        val sb = StringBuilder(n)
        repeat(n) { sb.append(VERIFIER_ALPHABET[random.nextInt(VERIFIER_ALPHABET.length)]) }
        return sb.toString()
    }

    /** base64url(SHA-256(verifier)) — what Spotify is shown instead. */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun createState(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class RedirectCheck(val ok: Boolean, val reason: String)

    /**
     * Is this an address Spotify will accept as a redirect URI?
     *
     * Spotify tightened this in 2025: https, OR http on a LOOPBACK IP LITERAL.
     * "http://localhost:3380/…" is specifically refused — the hostname
     * resolves differently on different machines — while "http://127.0.0.1"
     * is fine. Neither is obvious, and Spotify's error for a bad one
     * ("INVALID_CLIENT: Invalid redirect URI") names nothing that would lead a
     * person to the cause. So it is checked here, and the UI says which
     * situation the user is in before a sign-in is spent finding out.
     */
    fun checkRedirectUri(url: String?): RedirectCheck {
        val u = try {
            java.net.URI(url ?: "")
        } catch (e: Exception) {
            return RedirectCheck(false, "That is not a URL Spotify could redirect to.")
        }
        val port = if (u.port > 0) u.port.toString() else "3380"
        return when {
            u.scheme == "https" -> RedirectCheck(true, "")
            u.scheme != "http" ->
                RedirectCheck(false, "Spotify only accepts http and https redirect URIs.")
            u.host == "127.0.0.1" || u.host == "[::1]" || u.host == "::1" ->
                RedirectCheck(true, "")
            u.host == "localhost" -> RedirectCheck(false,
                "Spotify rejects http://localhost — it wants the loopback address as a " +
                    "number. Open this app on http://127.0.0.1:$port instead, or use the " +
                    "paste-back option below.")
            else -> RedirectCheck(false,
                "Spotify only accepts an http redirect on 127.0.0.1, so it will not " +
                    "redirect to ${u.host}. Register " +
                    "http://127.0.0.1:$port/api/spotify/callback in your Spotify app and " +
                    "use the paste-back option below.")
        }
    }

    fun buildAuthorizeUrl(
        clientId: String, redirectUri: String, challenge: String, state: String,
        forceDialog: Boolean = true, scopes: List<String> = SCOPES
    ): String {
        require(clientId.isNotEmpty()) { "A Spotify Client ID is required to sign in" }
        require(redirectUri.isNotEmpty()) { "A redirect URI is required to sign in" }
        require(challenge.isNotEmpty()) { "A code challenge is required to sign in" }
        val params = linkedMapOf<String, Any?>(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to redirectUri,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "scope" to scopes.joinToString(" "),
            "state" to state
        )
        // Forces the consent screen. Without it, re-authorising after a scope
        // change silently reuses the old grant and the new scope is never
        // granted — which surfaces later as a 403 on one endpoint.
        if (forceDialog) params["show_dialog"] = "true"
        return AUTHORIZE_URL + "?" + query(params)
    }

    data class Callback(val code: String, val state: String, val error: String)

    /**
     * Pull the code (or Spotify's refusal) out of whatever came back.
     *
     * Accepts the whole redirect URL, a bare query string, or the code alone.
     * The redirect normally lands here by itself, but when the sign-in was
     * started from a device that cannot reach this phone's loopback, the only
     * way back is copying the address bar — and people copy all three shapes.
     */
    fun parseCallback(urlOrCode: String?): Callback {
        val v = (urlOrCode ?: "").trim()
        if (v.isEmpty()) return Callback("", "", "")
        if (v.startsWith("http") || v.contains("?") || v.contains("&") || v.contains("=")) {
            val qs = if (v.contains("?")) v.substringAfter("?") else v.removePrefix("?")
            val p = parseQuery(qs)
            // Spotify reports a refused or cancelled sign-in as
            // ?error=access_denied rather than as a status. Reading it is what
            // lets the UI say "you cancelled" instead of "no code came back".
            return Callback(p["code"].orEmpty().trim(), p["state"].orEmpty().trim(),
                p["error"].orEmpty().trim())
        }
        return Callback(v, "", "")
    }

    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in raw.split("&")) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            out[decode(key)] = decode(value)
        }
        return out
    }

    private fun decode(s: String): String =
        try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }
}

/*
 * Qobuz's sign-in, which needs no dashboard visit at all.
 *
 * Ported from lib/qobuz-oauth.js, which is itself ported from
 * meltface-80/MusicD-Remote. Unlike Spotify, Qobuz accepts ANY redirect_url
 * without pre-registration — which is why this side asks the user for nothing.
 *
 * The application credentials are the ones the Qobuz desktop app is registered
 * with, published in the open by the author of an existing open-source Qobuz
 * client. They identify the APPLICATION, never a user, and without a token
 * minted through the sign-in they grant nothing at all.
 */
object QobuzOAuth {
    const val APP_ID = "304027809"
    const val PRIVATE_KEY = "6lz8C03UDIC7"
    const val SIGNIN_URL = "https://www.qobuz.com/signin/oauth"
    const val API_BASE = "https://www.qobuz.com/api.json/0.2"
    const val CODE_PARAM = "code_autorisation"

    fun buildAuthorizeUrl(redirectUrl: String, appId: String = APP_ID): String {
        require(redirectUrl.isNotEmpty()) {
            "redirect_url is required to start the Qobuz sign-in"
        }
        return SIGNIN_URL + "?" + query(mapOf("ext_app_id" to appId,
            "redirect_url" to redirectUrl))
    }

    /**
     * Accepts the full redirect URL, a bare query string, or the code alone.
     * Returns "" when there is none to find — a URL that carried no code is an
     * error page or a cancelled sign-in, and reporting "" lets the caller say
     * so rather than failing later as an opaque rejection from Qobuz.
     */
    fun extractCode(urlOrCode: String?): String {
        val v = (urlOrCode ?: "").trim()
        if (v.isEmpty()) return ""
        if (v.startsWith("http") || v.contains("?") || v.contains("&") || v.contains("=")) {
            val qs = if (v.contains("?")) v.substringAfter("?") else v.removePrefix("?")
            return Pkce.parseQuery(qs)[CODE_PARAM].orEmpty().trim()
        }
        return v
    }
}
