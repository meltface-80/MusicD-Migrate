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

/** The clear-out route, end to end through the API. */
class ClearApiTest : ApiFixtures() {

    // ------------------------------------------------------------ clearing
    //
    // The guards on the one irreversible route are on the SERVER, not only on
    // the page, so a stale page or a stray request cannot empty a library.

    /** An Http that records every request and answers a Qobuz favourites
     *  read with two tracks, and anything else with success. */
    private class QobuzLibraryHttp : Http {
        val calls = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?,
            contentType: String?, timeoutMs: Int
        ): HttpResponse {
            calls.add("$method $url")
            val text = if (url.contains("favorite/getUserFavorites") && url.contains("offset=0"))
                """{"tracks":{"items":[{"id":11,"title":"One","duration":200},
                   {"id":12,"title":"Two","duration":180}]}}"""
            else """{"status":"success"}"""
            return HttpResponse(200, emptyMap(), text)
        }
        fun removals() = calls.filter { it.contains("favorite/delete") }
    }

    private fun waitFor(store: Store, jobId: String) {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && store.job(jobId)?.status == "running") {
            Thread.sleep(25)
        }
    }

    @Test fun `a clear-out with no dryRun field is a preview`() {
        val store = signedIn()
        val http = QobuzLibraryHttp()
        val res = post(api(store, http), "/api/clear", """{"service":"qobuz","tracks":true}""")
        assertEquals(200, res.status)
        val jobId = JSONObject(res.body.toString(Charsets.UTF_8)).getString("jobId")
        waitFor(store, jobId)
        assertEquals("done", store.job(jobId)!!.status)
        assertEquals(true, store.job(jobId)!!.dryRun)
        assertEquals("clear-qobuz", store.job(jobId)!!.direction)
        assertEquals("nothing was removed: ${http.calls}", 0, http.removals().size)
        assertEquals(2, store.itemCounts(jobId)["listed"])
    }

    @Test fun `a real clear-out is refused unless the service's name is typed`() {
        val store = signedIn()
        val http = QobuzLibraryHttp()
        val a = api(store, http)
        for (confirm in listOf(null, "", "yes", "spotify")) {
            val body = JSONObject().put("service", "qobuz").put("tracks", true)
                .put("dryRun", false)
            if (confirm != null) body.put("confirm", confirm)
            val res = post(a, "/api/clear", body.toString())
            assertEquals("confirm=$confirm", 400, res.status)
            val error = JSONObject(res.body.toString(Charsets.UTF_8)).getString("error")
            assertTrue(error, error.contains("Type \"Qobuz\" to confirm"))
        }
        assertEquals("not one request went to Qobuz: ${http.calls}", 0, http.calls.size)
        assertEquals("and no job was even created", 0, store.jobs().size)
    }

    @Test fun `typed, it removes, and the report lists every id`() {
        val store = signedIn()
        val http = QobuzLibraryHttp()
        val res = post(api(store, http), "/api/clear",
            """{"service":"qobuz","tracks":true,"dryRun":false,"confirm":" Qobuz "}""")
        assertEquals(200, res.status)
        val jobId = JSONObject(res.body.toString(Charsets.UTF_8)).getString("jobId")
        waitFor(store, jobId)
        assertEquals("done", store.job(jobId)!!.status)
        assertEquals(1, http.removals().size)
        assertTrue(http.removals()[0], http.removals()[0].contains("track_ids=11%2C12"))
        assertEquals(2, store.itemCounts(jobId)["removed"])

        val csv = raw(api(store, http), "GET", "/api/job/$jobId/report.csv")
            .body.toString(Charsets.UTF_8).split("\r\n")
        assertTrue(csv.drop(1).joinToString("\n"), csv.drop(1).all {
            it.endsWith(",11") || it.endsWith(",12") })
    }

    @Test fun `a clear-out needs a service, a kind and a sign-in`() {
        assertEquals(400, post(api(signedIn()), "/api/clear", """{"tracks":true}""").status)
        assertEquals(400, post(api(signedIn()), "/api/clear",
            """{"service":"roon","tracks":true}""").status)
        val none = post(api(signedIn()), "/api/clear", """{"service":"qobuz"}""")
        assertEquals(400, none.status)
        assertTrue(none.body.toString(Charsets.UTF_8).contains("Pick at least one"))
        val gone = post(api(signedIn()), "/api/clear", """{"service":"spotify","albums":true}""")
        assertEquals("Spotify is not a library this version can clear", 400, gone.status)
        val out = post(api(), "/api/clear", """{"service":"qobuz","albums":true}""")
        assertEquals(400, out.status)
        assertTrue(out.body.toString(Charsets.UTF_8).contains("Sign in to Qobuz"))
    }
}
