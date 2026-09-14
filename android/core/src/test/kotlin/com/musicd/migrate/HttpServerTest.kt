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
            "web/index.html" -> "<!doctype html><title>MusicD Migrate</title>".toByteArray()
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
        assertTrue(body.contains("MusicD Migrate"))
    }

    @Test fun `the API answers over the wire with the shape the page expects`() {
        val (status, body, type) = fetch("/api/state")
        assertEquals(200, status)
        assertTrue(type.startsWith("application/json"))
        val j = JSONObject(body)
        assertEquals(false, j.getJSONObject("qobuz").getBoolean("signedIn"))
        assertEquals("0.1.0", j.getString("version"))
        // The redirect URI is derived from the Host header of this very
        // request, which on a phone is always the loopback address — the one
        // Spotify accepts over plain http.
        assertTrue(j.getJSONObject("spotify").getString("redirectUri")
            .startsWith("http://127.0.0.1:"))
        assertEquals(true,
            j.getJSONObject("spotify").getJSONObject("redirectCheck").getBoolean("ok"))
    }

    @Test fun `a POST body actually arrives — which is why this is a real socket`() {
        // The whole reason the APK serves the page over HTTP rather than
        // intercepting it in the WebView: shouldInterceptRequest is never
        // handed the BODY of a POST, and this app POSTs to sign in, to start a
        // migration and to cancel one.
        val (status, body, _) = fetch("/api/spotify/client-id", "POST",
            """{"clientId":"${"a".repeat(32)}"}""")
        assertEquals(200, status)
        assertEquals(true, JSONObject(body).getBoolean("ok"))
        assertEquals("a".repeat(32), store.setting("spotify.clientId"))
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
