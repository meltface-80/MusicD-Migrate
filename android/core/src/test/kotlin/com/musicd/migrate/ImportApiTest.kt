package com.musicd.migrate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library-file routes, as the page uses them. */
class ImportApiTest : ApiFixtures() {

    @Test fun `an upload answers with what was found, and the state carries it`() {
        val a = api()
        val res = upload(a, SMALL_LIBRARY, "mine.csv")
        assertEquals(200, res.status)
        val j = JSONObject(res.body.toString(Charsets.UTF_8))
        // Every field the page reads off the summary.
        assertEquals("mine.csv", j.getString("fileName"))
        assertEquals("tracks", j.getString("shape"))
        assertEquals(2, j.getInt("albums"))
        assertEquals(4, j.getInt("tracks"))
        assertEquals(1, j.getInt("withBarcode"))
        assertEquals(2, j.getInt("withListing"))
        assertEquals(0, j.getInt("unmatchable"))
        assertTrue(j.has("rows") && j.has("skippedRows") && j.has("warnings") &&
            j.has("columns") && j.has("ignoredColumns"))

        val state = get(a, "/api/state").getJSONObject("import")
        assertEquals("mine.csv", state.getString("fileName"))
    }

    @Test fun `a file that is not a library is refused with the reason`() {
        val res = upload(api(), "name,email\nBob,bob@example.com\n")
        assertEquals(400, res.status)
        assertTrue(JSONObject(res.body.toString(Charsets.UTF_8)).getString("error")
            .contains("No column names the album"))
    }

    @Test fun `forgetting the file clears it`() {
        val a = api()
        upload(a, SMALL_LIBRARY)
        assertEquals(200, post(a, "/api/import/forget").status)
        assertTrue(get(a, "/api/state").isNull("import"))
    }

    @Test fun `an import with no dryRun field is a preview`() {
        val store = signedIn()
        val a = api(store, FakeHttp(listOf(FakeHttp.res(200, "{}"))))
        upload(a, SMALL_LIBRARY)
        val res = post(a, "/api/migrate", """{"direction":"csv-to-qobuz","albums":true}""")
        assertEquals(200, res.status)
        val jobId = JSONObject(res.body.toString(Charsets.UTF_8)).getString("jobId")
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && store.job(jobId)?.status == "running") {
            Thread.sleep(25)
        }
        assertEquals("done", store.job(jobId)!!.status)
        assertEquals("an absent dryRun means preview", true, store.job(jobId)!!.dryRun)
        assertEquals("csv-to-qobuz", store.job(jobId)!!.direction)
    }
}
