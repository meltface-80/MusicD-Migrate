package com.musicd.migrate

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/*
 * Http.kt — the one place this module talks to the network.
 *
 * An interface rather than a direct call, for one reason: every test in this
 * module runs on a plain JVM with no phone, no account and no internet, and it
 * gets there by handing the clients a fake. SpotifyClientTest and
 * QobuzClientTest drive the real conversion, paging and batching code against
 * scripted responses, which is the same thing test/unit/clients.test.js does
 * with a fake fetch on the JavaScript side.
 *
 * HttpURLConnection rather than a library: :core has no dependencies at all
 * (org.json is compileOnly because Android supplies it), and one fewer thing
 * in the APK is one fewer thing to keep current.
 */

data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    val ok: Boolean get() = status in 200..299
    fun header(name: String): String? = headers[name.lowercase()]
}

interface Http {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMs: Int = 30000
    ): HttpResponse
}

class UrlConnectionHttp : Http {
    override fun request(
        method: String, url: String, headers: Map<String, String>, body: ByteArray?,
        contentType: String?, timeoutMs: Int
    ): HttpResponse {
        // URI(...).toURL() rather than the URL(String) constructor, which is
        // deprecated: URL's own parser is lenient in ways that have been a
        // source of parsing-differential bugs, and URI validates.
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.setRequestProperty("Content-Length", body.size.toString())
                conn.outputStream.use { it.write(body) }
            }

            val status = conn.responseCode
            // The ERROR stream, not the input stream, on any non-2xx — reading
            // the wrong one throws and the response body is lost. Both
            // services put the only useful diagnosis in that body: Qobuz
            // distinguishes a bad token from a bad request in the text and
            // nowhere else.
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                val out = ByteArrayOutputStream()
                s.use { it.copyTo(out) }
                out.toString("UTF-8")
            } ?: ""

            val head = HashMap<String, String>()
            for ((k, v) in conn.headerFields) {
                if (k != null && v.isNotEmpty()) head[k.lowercase()] = v[0]
            }
            return HttpResponse(status, head, text)
        } finally {
            conn.disconnect()
        }
    }
}

/** Percent-encoding for a query VALUE. `encode` turns a space into "+", which
 *  is correct in a query string and wrong in a path segment — nothing here
 *  builds paths from user input, so this is the only form needed. */
fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

fun query(params: Map<String, Any?>): String =
    params.entries
        .filter { it.value != null && it.value.toString().isNotEmpty() }
        .joinToString("&") { urlEncode(it.key) + "=" + urlEncode(it.value.toString()) }
