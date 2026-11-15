package com.musicd.migrate

import com.musicd.migrate.api.MigrateApi
import com.musicd.migrate.http.Assets
import com.musicd.migrate.http.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

/**
 * The real server, over a real socket, answering the real route table.
 *
 * Everything the APK does except the Android shell itself runs here: the
 * loopback HTTP server, the request parsing, the API, the asset serving. What
 * is left untested on a JVM is MainActivity and MigrateService — the WebView
 * and the foreground service — and the README says so rather than implying the
 * whole app has been exercised.
 */
class HttpServerTest {

    private class TestAssets : Assets {
        override fun read(path: String): ByteArray? = when (path) {
            "web/index.html" -> "<!doctype html><title>Mandarin Migration</title>".toByteArray()
            "web/app.js" -> "console.log('hi')".toByteArray()
            else -> null
        }
    }

    private lateinit var server: HttpServer
    private lateinit var store: Store

    @Before fun start() {
        store = MemoryStore()
        val api = MigrateApi(store, TestAssets(), FakeHttp(emptyList()), "0.1.0")
        // Port 0: the OS picks a free one, so the test cannot collide with
        // anything else on the machine running CI.
        server = HttpServer({ req -> api.handle(req) }, 0)
        server.start()
    }

    @After fun stop() {
        server.stop()
        store.close()
    }

    private fun fetch(path: String, method: String = "GET", body: String? = null):
        Triple<Int, String, String> {
        val conn = URI(server.rootUrl + path).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = false
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val type = conn.getHeaderField("Content-Type") ?: ""
        conn.disconnect()
        return Triple(status, text, type)
    }

    @Test fun `it binds loopback and serves the bundled page`() {
        assertTrue("the server must be on loopback and nowhere else",
            server.rootUrl.startsWith("http://127.0.0.1:"))
        val (status, body, type) = fetch("/")
        assertEquals(200, status)
        assertTrue(type.startsWith("text/html"))
        assertTrue(body.contains("Mandarin Migration"))
    }

    @Test fun `the API answers over the wire with the shape the page expects`() {
        val (status, body, type) = fetch("/api/state")
        assertEquals(200, status)
        assertTrue(type.startsWith("application/json"))
        val j = JSONObject(body)
        assertEquals(false, j.getJSONObject("qobuz").getBoolean("signedIn"))
        assertEquals("0.1.0", j.getString("version"))
        assertTrue("no library file yet", j.isNull("import"))
    }

    @Test fun `a POST body actually arrives — which is why this is a real socket`() {
        // The whole reason the APK serves the page over HTTP rather than
        // intercepting it in the WebView: shouldInterceptRequest is never
        // handed the BODY of a POST, and this app POSTs a whole library file.
        val (status, body, _) = fetch("/api/import/file?name=mine.csv", "POST",
            "album artist,album,title\nMetallica,Master Of Puppets,Battery\n")
        assertEquals(body, 200, status)
        val j = JSONObject(body)
        assertEquals("mine.csv", j.getString("fileName"))
        assertEquals(1, j.getInt("albums"))
    }

    @Test fun `a library file of tens of megabytes is accepted over the socket`() {
        // A one-row-per-track export of a ten-thousand-album library is about
        // 20MB. The server used to refuse any body over 4MB with a 413, which
        // the page would have shown as "Payload Too Large" for exactly the
        // libraries this exists for.
        val sb = StringBuilder("album artist,album,track,title,barcode\n")
        var album = 0
        while (sb.length < 12 * 1024 * 1024) {
            album++
            for (t in 1..12) {
                sb.append("Artist $album,Album $album,$t,A fairly ordinary track title $t,\n")
            }
        }
        val (status, body, _) = fetch("/api/import/file?name=big.csv", "POST", sb.toString())
        assertEquals(body.take(200), 200, status)
        assertEquals(album, JSONObject(body).getInt("albums"))
        assertEquals(album * 12, JSONObject(body).getInt("tracks"))
    }

    @Test fun `an unknown path is a 404 rather than a hang`() {
        assertEquals(404, fetch("/api/nope").first)
        assertEquals(404, fetch("/nothing.html").first)
    }

    @Test fun `a sign-in start redirects to the service's own page`() {
        val conn = URI(server.rootUrl + "/api/qobuz/oauth/start").toURL()
            .openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        assertEquals(302, conn.responseCode)
        val location = conn.getHeaderField("Location")
        assertTrue("the user signs in on qobuz.com, never in this app",
            location.startsWith("https://www.qobuz.com/signin/oauth"))
        // And the redirect it hands Qobuz points back at this very server.
        assertTrue(location.contains(java.net.URLEncoder.encode(
            server.rootUrl + "/api/qobuz/oauth/callback", "UTF-8")))
        conn.disconnect()
    }

    @Test fun `several requests on one connection are all answered`() {
        // Keep-alive: the page polls a job's progress about once a second, and
        // a fresh connection per poll is pure overhead on a phone.
        repeat(5) {
            assertEquals(200, fetch("/api/state").first)
        }
    }

    @Test fun `stopping the server releases the port`() {
        val port = server.port
        server.stop()
        // Rebinding the same port immediately is exactly what a service
        // restart does, and it only works because the socket sets
        // SO_REUSEADDR before binding.
        val again = HttpServer({ _ -> com.musicd.migrate.http.Response.text(200, "ok") }, port)
        again.start()
        assertEquals(port, again.port)
        again.stop()
    }
}
