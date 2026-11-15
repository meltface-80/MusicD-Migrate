package com.musicd.migrate

import com.musicd.migrate.api.MigrateApi
import com.musicd.migrate.http.Assets
import com.musicd.migrate.http.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the APK's route table the way the bundled page drives it.
 *
 * The point is not that these routes exist — it is that they answer the
 * SHAPES public/app.js reads, field for field.
 */
class ApiTest : ApiFixtures() {

    // ------------------------------------------------------------------------

    @Test fun `state carries every field the bundled page reads`() {
        val j = get(api(), "/api/state")
        // Each of these is read by name in public/app.js. A rename here breaks
        // the page, and nothing else would notice.
        assertTrue(j.has("cacheSize"))
        assertTrue(j.has("version"))
        assertTrue(j.has("job"))
        val q = j.getJSONObject("qobuz")
        assertEquals(false, q.getBoolean("signedIn"))
        assertTrue(q.has("name"))
        assertTrue("no file chosen yet", j.isNull("import"))
        assertTrue("Spotify is gone from the state", !j.has("spotify"))
    }

    @Test fun `an import is refused without a file, then without a sign-in`() {
        val a = api()
        val noFile = post(a, "/api/migrate", """{"direction":"csv-to-qobuz","albums":true}""")
        assertEquals(400, noFile.status)
        assertTrue(JSONObject(noFile.body.toString(Charsets.UTF_8)).getString("error")
            .contains("Choose a library file"))

        assertEquals(200, upload(a, SMALL_LIBRARY).status)
        val res = post(a, "/api/migrate", """{"direction":"csv-to-qobuz","albums":true}""")
        assertEquals(400, res.status)
        assertTrue(JSONObject(res.body.toString(Charsets.UTF_8)).getString("error")
            .contains("Sign in to Qobuz"))
    }

    @Test fun `the old Spotify directions are refused rather than run as something else`() {
        val a = api(signedIn())
        upload(a, SMALL_LIBRARY)
        val res = post(a, "/api/migrate", """{"direction":"qobuz-to-spotify","albums":true}""")
        assertEquals(400, res.status)
        assertTrue(JSONObject(res.body.toString(Charsets.UTF_8)).getString("error")
            .contains("not offered"))
    }

    @Test fun `an unknown job is a 404, not a crash`() {
        assertEquals(404, raw(api(), "GET", "/api/job/nope").status)
        assertEquals(404, raw(api(), "GET", "/api/job/nope/report.csv").status)
    }

    @Test fun `the CSV report escapes quotes, commas and formula-leading cells`() {
        val store = MemoryStore()
        store.createJob("csvjob", "csv-to-qobuz", "{}", false)
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
        assertEquals("kind,playlist,item,status,method,note,targetId,id", lines[0])
        assertTrue("each row ends with the item's own id: ${lines[2]}", lines[2].endsWith(",2"))
        assertTrue("named after the run, not the old app",
            res.headers["Content-Disposition"].orEmpty()
                .contains("mandarin-csv-to-qobuz-csvjob.csv"))
        assertTrue("a quote is doubled and the cell quoted",
            lines[1].contains("\"My \"\"Best\"\" Mix\""))
        assertTrue("a comma forces quoting", lines[1].contains("\"Song, with a comma\""))
        assertTrue("a leading dash is defused so a spreadsheet does not run it as a formula",
            lines[2].contains("'-Dash Leading Title"))
    }

    @Test fun `job items can be filtered to the unmatched ones`() {
        val store = MemoryStore()
        store.createJob("mixed", "csv-to-qobuz", "{}", false)
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

    /**
     * A store with both services "signed in", so a migration can actually be
     * started without a network. Every request is answered with `{}` by the
     * FakeHttp, so the run reads empty libraries and finishes immediately —
     * which is all this needs: the point is the START and the END, not the
     * work in between.
     */

    @Test fun `the app is told when there is work to protect, and when there is not`() {
        // Android 14 caps a dataSync foreground service at SIX HOURS in any
        // 24, calls Service.onTimeout when the cap is reached, and kills the
        // process if the service does not stop. v0.2.5 held one for the whole
        // life of the app and died of exactly that on a real phone:
        //
        //   ForegroundServiceDidNotStopInTimeException: A foreground service
        //   of type dataSync did not stop within its timeout
        //
        // So the app holds the service only while a run is in flight — and
        // :core has to say when that is.
        val seen = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        val store = signedIn()
        val a = MigrateApi(store, FakeAssets(emptyMap()),
            FakeHttp(listOf(FakeHttp.res(200, "{}"))), "0.1.0",
            onBusyChanged = { seen.add(it) })

        assertEquals("idle: nothing to protect, nothing reported", 0, seen.size)
        assertEquals(200, upload(a, SMALL_LIBRARY).status)
        assertEquals("parsing a file is not a run", 0, seen.size)

        val res = post(a, "/api/migrate", """{"direction":"csv-to-qobuz","albums":true}""")
        assertEquals(res.body.toString(Charsets.UTF_8), 200, res.status)
        val jobId = JSONObject(res.body.toString(Charsets.UTF_8)).getString("jobId")

        // The run is on its own thread; wait for the row to settle.
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until &&
               store.job(jobId)?.status == "running") Thread.sleep(50)

        assertEquals("told exactly once at the start and once at the end: $seen",
            listOf(true, false), seen.toList())
    }

    @Test fun `a second migration is refused while one is running, and the app is told once`() {
        // The flag follows the RUN, not the request: a refused start must not
        // report busy again, or the service would re-post its notification.
        val store = signedIn()
        val seen = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        // A slow Http keeps the first run alive long enough to race it.
        val slow = object : Http {
            override fun request(
                method: String, url: String, headers: Map<String, String>,
                body: ByteArray?, contentType: String?, timeoutMs: Int
            ): HttpResponse {
                Thread.sleep(300)
                return HttpResponse(200, emptyMap(), "{}")
            }
        }
        val a = MigrateApi(store, FakeAssets(emptyMap()), slow, "0.1.0",
            onBusyChanged = { seen.add(it) })
        upload(a, SMALL_LIBRARY)

        assertEquals(200, post(a, "/api/migrate",
            """{"direction":"csv-to-qobuz","albums":true}""").status)
        val again = post(a, "/api/migrate",
            """{"direction":"csv-to-qobuz","albums":true}""")
        assertEquals("a second run is refused", 409, again.status)

        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && seen.size < 2) Thread.sleep(50)
        assertEquals("one start, one end — not two starts: $seen",
            listOf(true, false), seen.toList())
    }

    @Test fun `the six-hour cap cancels the run rather than abandoning it`() {
        // What the app calls from Service.onTimeout. The process is killed
        // moments later if the service does not step down, so the run cannot
        // continue — but a job row left at "running" about a process that no
        // longer exists says nothing at all. It must end as CANCELLED.
        val store = signedIn()
        val slow = object : Http {
            override fun request(
                method: String, url: String, headers: Map<String, String>,
                body: ByteArray?, contentType: String?, timeoutMs: Int
            ): HttpResponse {
                Thread.sleep(300)
                return HttpResponse(200, emptyMap(), "{}")
            }
        }
        val seen = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        val a = MigrateApi(store, FakeAssets(emptyMap()), slow, "0.1.0",
            onBusyChanged = { seen.add(it) })
        upload(a, SMALL_LIBRARY)

        val res = post(a, "/api/migrate", """{"direction":"csv-to-qobuz","albums":true}""")
        assertEquals(200, res.status)
        val jobId = JSONObject(res.body.toString(Charsets.UTF_8)).getString("jobId")

        a.cancelCurrentJob()

        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until &&
               store.job(jobId)?.status == "running") Thread.sleep(50)
        assertEquals("the row says what happened", "cancelled", store.job(jobId)?.status)
        assertEquals("and the app is told the work is over: $seen",
            listOf(true, false), seen.toList())

        // And with nothing running it is a no-op rather than a throw: the
        // cap can be reached while the app sits idle.
        a.cancelCurrentJob()
    }

    @Test fun `a run that dies of an ERROR is recorded, not left to kill the app`() {
        // catch (e: Exception) does not catch an Error. An OutOfMemoryError
        // sails through it, kills the worker thread, and Android's default
        // handler kills the process: the app vanishes, the job row says
        // "running" forever, and there is nothing to say why.
        val store = MemoryStore()
        store.createJob("boom", "csv-to-qobuz", "{}", false)
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
        store.createJob("c", "csv-to-qobuz", "{}", false)
        api(store).runJob("c") { throw Cancelled() }
        assertEquals("cancelled", store.job("c")!!.status)

        store.createJob("f", "csv-to-qobuz", "{}", false)
        api(store).runJob("f") { throw RuntimeException("Qobuz sign-in has expired") }
        assertEquals("failed", store.job("f")!!.status)
        assertEquals("Qobuz sign-in has expired", store.job("f")!!.error)

        store.createJob("ok", "csv-to-qobuz", "{}", false)
        api(store).runJob("ok") { }
        assertEquals("done", store.job("ok")!!.status)
        assertNull(store.job("ok")!!.error)
    }

    @Test fun `a job listing carries its counts and the fields the page renders`() {
        val store = MemoryStore()
        store.createJob("mixed", "csv-to-qobuz", "{}", true)
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
        store.createJob("mixed", "csv-to-qobuz", "{}", false)
        assertEquals(404, post(api(store), "/api/job/mixed/cancel").status)
    }

    @Test fun `a job can be deleted, and takes its items with it`() {
        val store = MemoryStore()
        store.createJob("mixed", "csv-to-qobuz", "{}", false)
        store.addItems("mixed", listOf(JobItem("track", "1", "A", "matched")))
        val a = api(store)
        assertEquals(200, raw(a, "DELETE", "/api/job/mixed").status)
        assertEquals(404, raw(a, "GET", "/api/job/mixed").status)
        assertTrue(store.items("mixed").isEmpty())
    }

    @Test fun `clearing the match cache reports the new size`() {
        val store = MemoryStore()
        store.cacheMatch("csv", "1", "qobuz", "album", "q1", "upc")
        val j = JSONObject(post(api(store), "/api/cache/clear").body.toString(Charsets.UTF_8))
        assertEquals(true, j.getBoolean("ok"))
        assertEquals(0, j.getInt("cacheSize"))
    }

    @Test fun `the bundled page is served from the APK's own assets`() {
        val res = raw(api(), "GET", "/")
        assertEquals(200, res.status)
        assertTrue(res.contentType.startsWith("text/html"))
        assertTrue(res.body.toString(Charsets.UTF_8).contains("Mandarin Migration"))
        assertEquals("application/javascript; charset=utf-8",
            raw(api(), "GET", "/app.js").contentType)
    }

    @Test fun `nothing outside the bundle can be reached`() {
        assertEquals(404, raw(api(), "GET", "/../../etc/passwd").status)
        assertEquals(404, raw(api(), "GET", "/nope.html").status)
    }

    @Test fun `an interrupted job is not left showing a spinner forever`() {
        val store = MemoryStore()
        store.createJob("killed", "csv-to-qobuz", "{}", false)
        // Constructing the API is what sweeps orphans, exactly as starting the
        // server does on the Node side.
        val j = get(api(store), "/api/jobs").getJSONArray("jobs").getJSONObject(0)
        assertEquals("interrupted", j.getString("status"))
        assertEquals(false, j.getBoolean("running"))
    }

    @Test fun `progress json embedded in a job row stays parseable`() {
        val store = MemoryStore()
        store.createJob("p", "csv-to-qobuz", "{}", false)
        store.updateProgress("p", Progress(phase = "matching", label = "He said \"hi\"",
            done = 3, total = 10, counts = mapOf("matched" to 3)).toJson())
        val j = get(api(store), "/api/job/p")
        assertNotNull(j.getJSONObject("progress"))
        assertEquals("He said \"hi\"", j.getJSONObject("progress").getString("label"))
        assertEquals(3, j.getJSONObject("progress").getJSONObject("counts").getInt("matched"))
    }
}
