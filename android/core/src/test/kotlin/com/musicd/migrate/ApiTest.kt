package com.musicd.migrate

import com.musicd.migrate.api.MigrateApi
import com.musicd.migrate.http.Assets
import com.musicd.migrate.http.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // /login, not /api/spotify/callback — see Pkce.CALLBACK_PATH.
        assertEquals("http://127.0.0.1:3380/login", j.getString("redirectUri"))
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

    @Test fun `a run that dies of an ERROR is recorded, not left to kill the app`() {
        // catch (e: Exception) does not catch an Error. An OutOfMemoryError
        // sails through it, kills the worker thread, and Android's default
        // handler kills the process: the app vanishes, the job row says
        // "running" forever, and there is nothing to say why.
        val store = MemoryStore()
        store.createJob("boom", "roon-to-spotify", "{}", false)
        api(store).runJob("boom") { throw OutOfMemoryError("Failed to allocate 16MB") }

        val j = get(api(store), "/api/jobs").getJSONArray("jobs").getJSONObject(0)
        assertEquals("failed", j.getString("status"))
        val err = j.getString("error")
        assertTrue(err, err.contains("OutOfMemoryError"))
        assertTrue("the message survives: $err", err.contains("Failed to allocate 16MB"))
        assertTrue("and the user is told what it cost them: $err",
            err.contains("anything already written is still there"))
    }

    @Test fun `a cancelled run and a failed one are told apart`() {
        val store = MemoryStore()
        store.createJob("c", "roon-to-qobuz", "{}", false)
        api(store).runJob("c") { throw Cancelled() }
        assertEquals("cancelled", store.job("c")!!.status)

        store.createJob("f", "roon-to-qobuz", "{}", false)
        api(store).runJob("f") { throw RuntimeException("Spotify sign-in has expired") }
        assertEquals("failed", store.job("f")!!.status)
        assertEquals("Spotify sign-in has expired", store.job("f")!!.error)

        store.createJob("ok", "roon-to-qobuz", "{}", false)
        api(store).runJob("ok") { }
        assertEquals("done", store.job("ok")!!.status)
        assertNull(store.job("ok")!!.error)
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
    // ----------------------------------------------------------------------
    // Regression: Spotify redirects to /login, not /api/spotify/callback. The
    // shared community Client IDs whitelist exactly that one loopback path,
    // and anything else is refused with "redirect_uri: Not matching
    // configuration". The JavaScript twin of these is in
    // test/unit/server.test.js.

    @Test fun `login is routed, not swallowed by the static fallback`() {
        // /login is not under /api/, so without an explicit match it would be
        // looked up as an asset called "login" and 404.
        val res = raw(api(), "GET", "/login?error=access_denied")
        assertEquals(200, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("cancelled or refused"))
    }

    @Test fun `the old callback path is still served`() {
        val res = raw(api(), "GET", "/api/spotify/callback?error=access_denied")
        assertEquals(200, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("cancelled or refused"))
    }

    @Test fun `a sign-in start hands Spotify the login redirect`() {
        val store = MemoryStore()
        store.putSetting("spotify.clientId", "a".repeat(32))
        val res = raw(api(store), "GET", "/api/spotify/oauth/start")
        assertEquals(302, res.status)
        val location = res.headers["Location"] ?: ""
        assertTrue("the authorize URL must carry the /login redirect, url-encoded",
            location.contains(java.net.URLEncoder.encode(
                "http://127.0.0.1:3380/login", "UTF-8")))
        // And the verifier stored for the exchange must name the same URI, or
        // the token request is rejected for a mismatched redirect_uri.
        val pending = parseObject(store.setting("spotify.pending")!!)!!
        assertEquals("http://127.0.0.1:3380/login", pending.str("redirectUri"))
    }

    // ---------------------------------------------------------------- Roon
    //
    // The same assertions test/unit/server.test.js makes on the Node side. No
    // Roon Core is contacted: every one of these is either refused before a
    // packet would leave, or reads rows the test put in the store itself.

    @Test fun `asking for the state never starts looking for a Roon Core`() {
        // A page refresh must not broadcast on somebody's network. The Roon
        // client is created on the first press of "Find my Roon Core".
        val j = get(api(), "/api/state").getJSONObject("roon")
        assertEquals("idle", j.getString("stage"))
        assertEquals(false, j.getBoolean("paired"))
        assertEquals(0, j.getInt("albums"))
        assertTrue(j.isNull("scan"))
    }

    @Test fun `a scan is refused until there is a Core to scan`() {
        val res = post(api(), "/api/roon/scan")
        assertEquals(400, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("Pair with a Roon Core"))
    }

    @Test fun `migrating from Roon is refused with the step that is missing`() {
        val res = post(api(), "/api/migrate",
            """{"direction":"roon-to-spotify","albums":true,"dryRun":true}""")
        assertEquals(400, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("Pair with a Roon Core"))
    }

    @Test fun `Roon is never offered as a destination`() {
        // There is no way to put an album into somebody's local library. An
        // unknown direction falls back to the default rather than inventing
        // one, and the refusal that follows names Qobuz, not Roon.
        val res = post(api(), "/api/migrate",
            """{"direction":"spotify-to-roon","albums":true,"dryRun":true}""")
        assertEquals(400, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("Qobuz"))
    }

    @Test fun `asking Roon for playlists answers with the reason, not an empty list`() {
        val res = raw(api(), "GET", "/api/playlists?service=roon")
        assertEquals(400, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("no length to match it on"))
    }

    @Test fun `the library CSV says there is no scan rather than sending an empty file`() {
        val res = raw(api(), "GET", "/api/roon/library.csv")
        assertEquals(404, res.status)
        assertTrue(res.body.toString(Charsets.UTF_8).contains("has been scanned yet"))
    }

    @Test fun `the library CSV carries the albums and what each was matched to`() {
        val store: Store = MemoryStore()
        store.saveRoonAlbums("roon", listOf(
            RoonAlbumRow("ra_one", "Kind Of Blue", "Miles Davis", 0),
            RoonAlbumRow("ra_two", "-Minus", "Someone", 1)))
        store.setRoonAlbumTrackCount("roon", "ra_one", 5)
        store.cacheMatch("roon", "ra_one", "spotify", "album", "spAlbum1", "exact+tracklist")
        store.cacheMatch("roon", "ra_two", "spotify", "album", null,
            "an album of that name is there but its track listing does not agree")

        val res = raw(api(store), "GET", "/api/roon/library.csv")
        assertEquals(200, res.status)
        assertTrue(res.contentType.contains("text/csv"))
        val lines = res.body.toString(Charsets.UTF_8).split("\r\n")
        assertEquals("artist,album,tracks,spotify,spotifyNote,qobuz,qobuzNote,roonKey", lines[0])
        assertEquals("Miles Davis,Kind Of Blue,5,spAlbum1,,,,ra_one", lines[1])
        // A title beginning with "-" is executed as a formula by Excel.
        assertTrue("CSV injection guard: ${lines[2]}", lines[2].startsWith("Someone,'-Minus,"))
        assertTrue(lines[2].contains("track listing does not agree"))
    }
}
