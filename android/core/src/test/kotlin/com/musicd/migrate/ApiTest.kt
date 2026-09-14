package com.musicd.migrate

import com.musicd.migrate.api.MigrateApi
import com.musicd.migrate.http.Assets
import com.musicd.migrate.http.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the APK's route table the way the bundled page drives it.
 *
 * The point is not that these routes exist — it is that they answer the SAME
 * SHAPES index.js answers, because public/app.js is written once and shipped
 * to both. test/unit/server.test.js asserts the same things on the Node side.
 */
class ApiTest {

    private class FakeAssets(val files: Map<String, String>) : Assets {
        override fun read(path: String): ByteArray? = files[path]?.toByteArray()
    }

    private fun api(store: Store = MemoryStore(), http: Http = FakeHttp(emptyList())) =
        MigrateApi(store, FakeAssets(mapOf(
            "web/index.html" to "<!doctype html><title>MusicD Migrate</title>",
            "web/app.js" to "console.log(1)")), http, "0.1.0")

    private fun get(api: MigrateApi, path: String): JSONObject {
        val q = if (path.contains("?")) path.substringAfter("?") else ""
        val res = api.handle(Request("GET", path.substringBefore("?"),
            com.musicd.migrate.http.HttpServer.parseQuery(q),
            mapOf("host" to "127.0.0.1:3380"), ByteArray(0), false, "127.0.0.1", path))
        return JSONObject(res.body.toString(Charsets.UTF_8))
    }

    private fun post(api: MigrateApi, path: String, body: String = "{}"):
        com.musicd.migrate.http.Response =
        api.handle(Request("POST", path, emptyMap(), mapOf("host" to "127.0.0.1:3380"),
            body.toByteArray(), false, "127.0.0.1", path))

    private fun raw(api: MigrateApi, method: String, path: String) =
        api.handle(Request(method, path.substringBefore("?"),
            com.musicd.migrate.http.HttpServer.parseQuery(
                if (path.contains("?")) path.substringAfter("?") else ""),
            mapOf("host" to "127.0.0.1:3380"), ByteArray(0), false, "127.0.0.1", path))

    // ------------------------------------------------------------------------

    @Test fun `state carries every field the bundled page reads`() {
        val j = get(api(), "/api/state")
        // Each of these is read by name in public/app.js. A rename on one side
        // breaks the APK and nothing in the Docker build would notice.
        assertTrue(j.has("pinRequired"))
        assertTrue(j.has("cacheSize"))
        assertTrue(j.has("version"))
        assertTrue(j.has("job"))
        val q = j.getJSONObject("qobuz")
        assertEquals(false, q.getBoolean("signedIn"))
        assertTrue(q.has("name"))
        val sp = j.getJSONObject("spotify")
        assertEquals(false, sp.getBoolean("signedIn"))
        assertTrue(sp.has("name"))
        assertTrue(sp.has("clientId"))
        assertTrue(sp.has("redirectUri"))
        assertTrue(sp.getJSONObject("redirectCheck").has("ok"))
        assertTrue(sp.getJSONObject("redirectCheck").has("reason"))
    }

    @Test fun `the redirect URI is derived from the request, and Spotify accepts it`() {
        val j = get(api(), "/api/state").getJSONObject("spotify")
        assertEquals("http://127.0.0.1:3380/api/spotify/callback", j.getString("redirectUri"))
        // On the phone the server is ALWAYS on loopback, which is the one
        // address Spotify accepts over plain http — so the APK never hits the
        // caveat the Docker build has to explain.
        assertEquals(true, j.getJSONObject("redirectCheck").getBoolean("ok"))
    }

    @Test fun `a migration is refused while not signed in`() {
        val res = post(api(), "/api/migrate", """{"direction":"qobuz-to-spotify","tracks":true}""")
        assertEquals(400, res.status)
        assertTrue(JSONObject(res.body.toString(Charsets.UTF_8)).getString("error")
            .contains("Sign in to Qobuz"))
    }

    @Test fun `a malformed Spotify client id is refused with an actionable message`() {
        val res = post(api(), "/api/spotify/client-id", """{"clientId":"not-a-client-id"}""")
        assertEquals(400, res.status)
        assertTrue(JSONObject(res.body.toString(Charsets.UTF_8)).getString("error")
            .contains("32 letters and numbers"))
    }

    @Test fun `a well-formed client id is accepted and reported back`() {
        val store = MemoryStore()
        val a = api(store)
        assertEquals(200, post(a, "/api/spotify/client-id",
            """{"clientId":"${"a".repeat(32)}"}""").status)
        assertEquals("a".repeat(32),
            get(a, "/api/state").getJSONObject("spotify").getString("clientId"))
    }

    @Test fun `an unknown job is a 404, not a crash`() {
        assertEquals(404, raw(api(), "GET", "/api/job/nope").status)
        assertEquals(404, raw(api(), "GET", "/api/job/nope/report.csv").status)
    }

    @Test fun `the CSV report escapes quotes, commas and formula-leading cells`() {
        val store = MemoryStore()
        store.createJob("csvjob", "qobuz-to-spotify", "{}", false)
        store.addItems("csvjob", listOf(
            JobItem("track", "1", "Song, with a comma", "unmatched",
                container = "My \"Best\" Mix",
                note = "nothing called \"Song, with a comma\""),
            JobItem("track", "2", "-Dash Leading Title", "unmatched", note = "not found")))
        store.finishJob("csvjob", "done", null)

        val res = raw(api(store), "GET", "/api/job/csvjob/report.csv")
        assertEquals(200, res.status)
        assertTrue(res.contentType.startsWith("text/csv"))
        val lines = res.body.toString(Charsets.UTF_8).split("\r\n")
        assertEquals("kind,playlist,item,status,method,note,targetId", lines[0])
        assertTrue("a quote is doubled and the cell quoted",
            lines[1].contains("\"My \"\"Best\"\" Mix\""))
        assertTrue("a comma forces quoting", lines[1].contains("\"Song, with a comma\""))
        assertTrue("a leading dash is defused so a spreadsheet does not run it as a formula",
            lines[2].contains("'-Dash Leading Title"))
    }

    @Test fun `job items can be filtered to the unmatched ones`() {
        val store = MemoryStore()
        store.createJob("mixed", "qobuz-to-spotify", "{}", false)
        store.addItems("mixed", listOf(
            JobItem("track", "1", "A", "matched", targetId = "x"),
            JobItem("track", "2", "B", "unmatched", note = "gone")))
        val a = api(store)
        assertEquals(2, get(a, "/api/job/mixed/items").getInt("total"))
        val un = get(a, "/api/job/mixed/items?status=unmatched")
        assertEquals(1, un.getInt("total"))
        val item = un.getJSONArray("items").getJSONObject(0)
        assertEquals("B", item.getString("sourceLabel"))
        assertTrue("items come back in the app's shape", item.isNull("targetId"))
        // Every field public/app.js reads off an item.
        for (k in listOf("kind", "container", "sourceId", "sourceLabel", "targetId",
                         "status", "method", "note")) {
            assertTrue("the page reads item.$k", item.has(k))
        }
    }

    @Test fun `a job listing carries its counts and the fields the page renders`() {
        val store = MemoryStore()
        store.createJob("mixed", "qobuz-to-spotify", "{}", true)
        store.addItems("mixed", listOf(
            JobItem("track", "1", "A", "matched", targetId = "x"),
            JobItem("track", "2", "B", "unmatched", note = "gone")))
        val j = get(api(store), "/api/jobs").getJSONArray("jobs").getJSONObject(0)
        for (k in listOf("id", "created", "direction", "status", "dryRun", "counts",
                         "progress", "error", "running")) {
            assertTrue("the page reads job.$k", j.has(k))
        }
        assertEquals(1, j.getJSONObject("counts").getInt("matched"))
        assertEquals(1, j.getJSONObject("counts").getInt("unmatched"))
    }

    @Test fun `cancelling a job that is not running is a 404`() {
        val store = MemoryStore()
        store.createJob("mixed", "qobuz-to-spotify", "{}", false)
        assertEquals(404, post(api(store), "/api/job/mixed/cancel").status)
    }

    @Test fun `a job can be deleted, and takes its items with it`() {
        val store = MemoryStore()
        store.createJob("mixed", "qobuz-to-spotify", "{}", false)
        store.addItems("mixed", listOf(JobItem("track", "1", "A", "matched")))
        val a = api(store)
        assertEquals(200, raw(a, "DELETE", "/api/job/mixed").status)
        assertEquals(404, raw(a, "GET", "/api/job/mixed").status)
        assertTrue(store.items("mixed").isEmpty())
    }

    @Test fun `clearing the match cache reports the new size`() {
        val store = MemoryStore()
        store.cacheMatch("qobuz", "1", "spotify", "track", "s1", "isrc")
        val j = JSONObject(post(api(store), "/api/cache/clear").body.toString(Charsets.UTF_8))
        assertEquals(true, j.getBoolean("ok"))
        assertEquals(0, j.getInt("cacheSize"))
    }

    @Test fun `a cancelled Spotify sign-in is explained rather than reported as missing`() {
        val res = raw(api(), "GET", "/api/spotify/callback?error=access_denied")
        assertEquals(200, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("cancelled or refused"))
    }

    @Test fun `the bundled page is served from the APK's own assets`() {
        val res = raw(api(), "GET", "/")
        assertEquals(200, res.status)
        assertTrue(res.contentType.startsWith("text/html"))
        assertTrue(res.body.toString(Charsets.UTF_8).contains("MusicD Migrate"))
        assertEquals("application/javascript; charset=utf-8",
            raw(api(), "GET", "/app.js").contentType)
    }

    @Test fun `nothing outside the bundle can be reached`() {
        assertEquals(404, raw(api(), "GET", "/../../etc/passwd").status)
        assertEquals(404, raw(api(), "GET", "/nope.html").status)
    }

    @Test fun `an interrupted job is not left showing a spinner forever`() {
        val store = MemoryStore()
        store.createJob("killed", "qobuz-to-spotify", "{}", false)
        // Constructing the API is what sweeps orphans, exactly as starting the
        // server does on the Node side.
        val j = get(api(store), "/api/jobs").getJSONArray("jobs").getJSONObject(0)
        assertEquals("interrupted", j.getString("status"))
        assertEquals(false, j.getBoolean("running"))
    }

    @Test fun `progress json embedded in a job row stays parseable`() {
        val store = MemoryStore()
        store.createJob("p", "qobuz-to-spotify", "{}", false)
        store.updateProgress("p", Progress(phase = "matching", label = "He said \"hi\"",
            done = 3, total = 10, counts = mapOf("matched" to 3)).toJson())
        val j = get(api(store), "/api/job/p")
        assertNotNull(j.getJSONObject("progress"))
        assertEquals("He said \"hi\"", j.getJSONObject("progress").getString("label"))
        assertEquals(3, j.getJSONObject("progress").getJSONObject("counts").getInt("matched"))
    }
}
