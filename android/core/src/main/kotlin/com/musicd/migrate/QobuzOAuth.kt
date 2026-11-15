package com.musicd.migrate

/*
 * Qobuz's sign-in, which needs no dashboard visit at all.
 *
 * Ported (via this repository's former Node server) from
 * meltface-80/MusicD-Remote. Qobuz accepts ANY redirect_url without
 * pre-registration — which is why the app asks the user for nothing.
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
            return parseQuery(qs)[CODE_PARAM].orEmpty().trim()
        }
        return v
    }

    /** A query string as a map, percent-decoded. Lenient on purpose: it reads
     *  addresses people paste, and a bad escape must not lose the code. */
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
