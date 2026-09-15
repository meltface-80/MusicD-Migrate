package com.musicd.migrate.roon

import com.musicd.migrate.MemoryStore
import com.musicd.migrate.Store
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Roon library walk, against a scripted browse tree.
 *
 * The twin of test/unit/roon.test.js, and it has to stay one: the same page
 * drives both halves, and a walk that behaves differently here is a library
 * that migrates differently on a phone. No socket and no Core — RoonClient
 * takes browse/load/withSession as a seam.
 */
class RoonClientTest {

    private class ScriptedCore(
        val albums: List<Alb> = emptyList(),
        val artists: List<String> = emptyList(),
        override val coreId: String? = "core-1",
        override val coreName: String? = "Study Mac"
    ) : BrowseApi {
        class Alb(val title: String, val artist: String, val tracks: List<Row> = emptyList()) {
            val itemKey = "k:$title"
        }
        class Row(val title: String, val itemKey: String? = "t:$title",
                  val hint: String? = "action_list", val subtitle: String = "")

        var browseCalls = 0
        var loadCalls = 0
        var sessions = 0
        /** Overridable so a test can model a Core that over-reports a count. */
        var browseAnswer: ((JSONObject) -> JSONObject)? = null
        var wrapLoad: ((JSONObject, JSONObject) -> JSONObject)? = null

        private val at = HashMap<String, Any>()

        override fun browse(opts: JSONObject): JSONObject {
            browseCalls++
            browseAnswer?.let { return it(opts) }
            val key = opts.getString("multi_session_key")
            if (opts.optBoolean("pop_all", false)) {
                at[key] = if (opts.getString("hierarchy") == "artists") "artists" else "albums"
                return JSONObject().put("action", "list")
                    .put("list", JSONObject().put("count", albums.size))
            }
            val itemKey = opts.optString("item_key", "")
            if (itemKey.isNotEmpty()) {
                val found = albums.find { it.itemKey == itemKey }
                    ?: return JSONObject().put("action", "message").put("message", "that item is gone")
                at[key] = found
                return JSONObject().put("action", "list")
                    .put("list", JSONObject().put("count", found.tracks.size))
            }
            return JSONObject().put("action", "list").put("list", JSONObject().put("count", 0))
        }

        override fun load(opts: JSONObject): JSONObject {
            loadCalls++
            val where = at[opts.getString("multi_session_key")] ?: "albums"
            val rows: List<JSONObject> = when {
                where == "artists" -> artists.map { JSONObject().put("title", it) }
                where is Alb -> where.tracks.map {
                    JSONObject().put("title", it.title).put("subtitle", it.subtitle)
                        .apply {
                            if (it.itemKey != null) put("item_key", it.itemKey)
                            if (it.hint != null) put("hint", it.hint)
                        }
                }
                else -> albums.map {
                    JSONObject().put("title", it.title).put("subtitle", it.artist)
                        .put("item_key", it.itemKey).put("hint", "action_list")
                }
            }
            val offset = opts.getInt("offset")
            val count = opts.getInt("count")
            val page = rows.drop(offset).take(count)
            val out = JSONObject()
                .put("items", JSONArray(page))
                .put("list", JSONObject().put("count", rows.size))
            return wrapLoad?.invoke(opts, out) ?: out
        }

        override fun <T> withSession(fn: (String) -> T): T {
            sessions++
            return fn("s1")
        }
    }

    private fun alb(title: String, artist: String, tracks: List<ScriptedCore.Row> = emptyList()) =
        ScriptedCore.Alb(title, artist, tracks)

    private fun row(title: String) = ScriptedCore.Row(title)

    // --------------------------------------------------------------- the scan

    @Test fun `a scan walks every page and stores what it found`() {
        val albums = (0 until 250).map { alb("Album $it", "Artist ${it % 20}") }
        val core = ScriptedCore(albums)
        val store: Store = MemoryStore()
        val progress = ArrayList<Int>()

        val summary = RoonClient(core, store).scan(onProgress = { done, _, _, _ ->
            progress.add(done)
        })

        assertEquals(250, summary.stored)
        assertEquals(250, summary.total)
        assertTrue(summary.done)
        assertEquals(250, store.roonAlbumCount("core-1"))
        assertEquals(listOf(100, 200, 250), progress)
        assertEquals("one load per page and not one per album", 3, core.loadCalls)
        assertEquals("one pooled browse session for the whole scan", 1, core.sessions)
    }

    @Test fun `two copies of the same record are one album, and the count says so`() {
        val core = ScriptedCore(listOf(
            alb("Kind Of Blue", "Miles Davis"),
            alb("kind of blue", "Miles Davis"),
            alb("Blue Train", "John Coltrane")))
        val summary = RoonClient(core, MemoryStore()).scan()
        assertEquals(2, summary.stored)
        assertEquals("reported, not hidden", 1, summary.duplicates)
    }

    @Test fun `a scan that is interrupted resumes where it stopped`() {
        val core = ScriptedCore((0 until 250).map { alb("Album $it", "A") })
        val store: Store = MemoryStore()
        var seen = 0
        RoonClient(core, store).scan(cancelled = { seen++ >= 1 })
        assertEquals(100, store.roonAlbumCount("core-1"))
        assertEquals(100, store.roonScan()!!.offset)
        assertFalse(store.roonScan()!!.done)

        val before = core.loadCalls
        val summary = RoonClient(core, store).scan(resume = true)
        assertEquals(250, summary.offset)
        assertEquals(250, store.roonAlbumCount("core-1"))
        assertEquals("it resumed at page two rather than paying for page one again",
            2, core.loadCalls - before)
    }

    @Test fun `a resumed scan starts over when the library changed while it was stopped`() {
        // An offset only means the same album as long as the list is the same
        // length. Roon sorts alphabetically, so a record bought since shifts
        // everything after it -- and resuming at the old offset would SKIP an
        // album for every one added. Being quietly one album short of a ten
        // thousand album inventory is not something anyone would notice.
        val store: Store = MemoryStore()
        val first = (0 until 250).map { alb("Album " + it.toString().padStart(3, '0'), "A") }
        var stopAfter = 0
        RoonClient(ScriptedCore(first), store).scan(cancelled = { stopAfter++ >= 1 })
        assertEquals(100, store.roonAlbumCount("core-1"))

        // Two records bought while the scan was stopped, both sorting first.
        val grown = listOf(alb("AAA one", "A"), alb("AAA two", "A")) + first
        val summary = RoonClient(ScriptedCore(grown), store).scan(resume = true)

        assertEquals("every album, not the 150 a resume would have added", 252, summary.stored)
        assertEquals(252, store.roonAlbumCount("core-1"))
        assertTrue(store.roonAlbums("core-1").any { it.title == "AAA one" })
        assertTrue(store.roonAlbums("core-1").any { it.title == "Album 249" })
    }

    @Test fun `a resumed scan of an unchanged library really does resume`() {
        // The restart must not fire on every resume, or the resume is
        // decoration and a stopped scan always pays from the beginning.
        val store: Store = MemoryStore()
        val core = ScriptedCore((0 until 250).map { alb("Album $it", "A") })
        var seen = 0
        RoonClient(core, store).scan(cancelled = { seen++ >= 1 })
        val before = core.loadCalls
        RoonClient(core, store).scan(resume = true)
        assertEquals("pages two and three, not all three", 2, core.loadCalls - before)
    }

    @Test fun `a rescan replaces the inventory rather than merging into it`() {
        val store: Store = MemoryStore()
        RoonClient(ScriptedCore(listOf(alb("Gone", "A"), alb("Kept", "B"))), store).scan()
        assertEquals(2, store.roonAlbumCount("core-1"))

        // "Gone" has been deleted from Roon. It must not live on in an export.
        RoonClient(ScriptedCore(listOf(alb("Kept", "B"))), store).scan()
        assertEquals(1, store.roonAlbumCount("core-1"))
        assertEquals(listOf("Kept"), store.roonAlbums("core-1").map { it.title })
    }

    @Test fun `a Core that reports a count it does not serve does not loop forever`() {
        // A count of 5,000 with two albums actually there, repeated on every
        // page. Reaching the end by offset alone never happens, so a short
        // page has to be what ends the walk.
        val core = ScriptedCore(listOf(alb("One", "A"), alb("Two", "B")))
        var pages = 0
        core.browseAnswer = { JSONObject().put("action", "list")
            .put("list", JSONObject().put("count", 5000)) }
        core.wrapLoad = { _, out ->
            pages++
            if (pages > 20) fail("the scan is looping: it never decided the list had ended")
            out.put("list", JSONObject().put("count", 5000))
        }
        val summary = RoonClient(core, MemoryStore()).scan()
        assertEquals(2, summary.stored)
        assertEquals("one short page is the whole list", 1, pages)
    }

    @Test fun `a Core that declines says so in its own words`() {
        val core = ScriptedCore()
        core.browseAnswer = { JSONObject().put("action", "message")
            .put("message", "Library is still importing") }
        try {
            RoonClient(core, MemoryStore()).scan()
            fail("expected the Core's own reason")
        } catch (e: RoonException) {
            assertTrue(e.message!!.contains("Library is still importing"))
        }
    }

    @Test fun `a browse that answers with no list at all is an error`() {
        val core = ScriptedCore()
        core.browseAnswer = { JSONObject().put("action", "none") }
        try {
            RoonClient(core, MemoryStore()).scan()
            fail("expected a refusal")
        } catch (e: RoonException) {
            assertTrue(e.message!!.contains("gave no list"))
        }
    }

    // -------------------------------------------------------- the albums

    @Test fun `the scanned albums come back with no barcode and an honest null count`() {
        val core = ScriptedCore(listOf(alb("Kind Of Blue", "Miles Davis")))
        val store: Store = MemoryStore()
        val client = RoonClient(core, store)
        client.scan()

        val albums = client.savedAlbums()
        assertEquals(1, albums.size)
        assertEquals("Roon has no barcodes: missing data, not evidence", "", albums[0].upc)
        assertEquals(null, albums[0].trackCount)
        assertEquals(listOf("Miles Davis"), albums[0].artists)
        assertEquals("the id is stable across scans, so the match cache survives one",
            RoonClient.albumKey("Kind Of Blue", "Miles Davis"), albums[0].id)
    }

    /**
     * The two halves must file the same record under the SAME id.
     *
     * The match cache is keyed on it. If the APK and the container disagreed,
     * a library scanned on one and migrated on the other would pay for every
     * lookup twice and report nothing from the first run.
     */
    @Test fun `an album key matches the one lib roon js produces`() {
        assertEquals("ra_bc1119343e184856",
            RoonClient.albumKey("Kind Of Blue", "Miles Davis"))
        assertEquals("the key is normalised, so two spellings are one album",
            RoonClient.albumKey("Kind Of Blue", "Miles Davis"),
            RoonClient.albumKey("kind of  blue", "Miles Davis"))
    }

    @Test fun `reading albums before a scan is an error, not an empty library`() {
        try {
            RoonClient(ScriptedCore(), MemoryStore()).savedAlbums()
            fail("expected a refusal")
        } catch (e: RoonException) {
            assertTrue(e.message!!.contains("has been scanned yet"))
        }
    }

    @Test fun `an album id survives a rescan that moved everything`() {
        val store: Store = MemoryStore()
        RoonClient(ScriptedCore(listOf(alb("Ziggy Stardust", "David Bowie"))), store).scan()
        val before = RoonClient(ScriptedCore(listOf(alb("Ziggy Stardust", "David Bowie"))), store)
            .savedAlbums()[0].id

        // Ten records bought, all sorting above it.
        val after = ScriptedCore((0 until 10).map { alb("A$it", "Someone") } +
            listOf(alb("Ziggy Stardust", "David Bowie")))
        val client = RoonClient(after, store)
        client.scan()
        val moved = client.savedAlbums().first { it.title == "Ziggy Stardust" }
        assertEquals("keying on the offset would have invalidated every cached match",
            before, moved.id)
    }

    // -------------------------------------------------- drilling into one

    @Test fun `an album's track listing is drilled on demand and the count remembered`() {
        val one = alb("Blue Train", "John Coltrane",
            listOf(row("Blue Train"), row("Moment's Notice"), row("Locomotion")))
        val store: Store = MemoryStore()
        val client = RoonClient(ScriptedCore(listOf(one)), store)
        client.scan()

        val id = RoonClient.albumKey("Blue Train", "John Coltrane")
        val tracks = client.albumTracks(id)
        assertEquals(listOf("Blue Train", "Moment's Notice", "Locomotion"),
            tracks.map { it.title })
        assertEquals("a Roon browse row carries no length, and pretending it is zero is worse",
            null, tracks[0].durationMs)
        assertEquals("", tracks[0].isrc)
        assertEquals("learned for free while we were in there",
            3, store.roonAlbum("core-1", id)!!.trackCount)
    }

    @Test fun `a header row inside an album is not a track`() {
        val one = alb("Box Set", "Someone", listOf(
            ScriptedCore.Row("Disc 1", itemKey = null, hint = "header"),
            row("First"),
            ScriptedCore.Row("No key at all", itemKey = null, hint = null),
            row("Second")))
        val client = RoonClient(ScriptedCore(listOf(one)), MemoryStore())
        client.scan()
        assertEquals("a disc banner counted as a track would make every count wrong",
            listOf("First", "Second"),
            client.albumTracks(RoonClient.albumKey("Box Set", "Someone")).map { it.title })
    }

    @Test fun `the remembered offset is used, and confirmed before it is trusted`() {
        val albums = (0 until 150).map {
            alb("Album " + it.toString().padStart(3, '0'), "Artist", listOf(row("t$it")))
        }
        val core = ScriptedCore(albums)
        val store: Store = MemoryStore()
        val client = RoonClient(core, store)
        client.scan()

        val before = core.loadCalls
        client.albumTracks(RoonClient.albumKey("Album 140", "Artist"))
        assertEquals("one load to confirm the hint, one to read the album's contents",
            2, core.loadCalls - before)
    }

    @Test fun `a stale offset costs a scan, never the wrong album`() {
        // The whole reason the hint is confirmed against the title. Getting it
        // wrong means migrating a record the user does not own, which is the
        // failure this repository exists to avoid.
        val store: Store = MemoryStore()
        RoonClient(ScriptedCore(listOf(
            alb("Aja", "Steely Dan", listOf(row("Black Cow"))),
            alb("Ziggy Stardust", "David Bowie", listOf(row("Five Years"))))), store).scan()

        // Roon's list has been reordered under us: everything shifted by one.
        val after = ScriptedCore(listOf(
            alb("Abbey Road", "The Beatles", listOf(row("Come Together"))),
            alb("Aja", "Steely Dan", listOf(row("Black Cow"))),
            alb("Ziggy Stardust", "David Bowie", listOf(row("Five Years")))))
        val tracks = RoonClient(after, store)
            .albumTracks(RoonClient.albumKey("Aja", "Steely Dan"))
        assertEquals("the album at the remembered offset is a different one now, and the " +
            "title check is what stops it being returned",
            listOf("Black Cow"), tracks.map { it.title })
    }

    @Test fun `an album that has been deleted from Roon is not an error`() {
        val store: Store = MemoryStore()
        RoonClient(ScriptedCore(listOf(alb("Sold", "Someone", listOf(row("x"))))), store).scan()
        assertEquals("one record sold between a scan and a migration must not stop the run",
            emptyList<String>(),
            RoonClient(ScriptedCore(), store)
                .albumTracks(RoonClient.albumKey("Sold", "Someone")).map { it.title })
    }

    // ------------------------------------------------------------ artists

    @Test fun `artists are walked live, paged, and de-duplicated`() {
        val names = (0 until 150).map { "Artist $it" } + listOf("Artist 7")
        val core = ScriptedCore(artists = names)
        val artists = RoonClient(core, MemoryStore()).followedArtists()
        assertEquals(150, artists.size)
        assertEquals("paged at 100", 2, core.loadCalls)
        assertEquals(150, artists.map { it.id }.toSet().size)
    }

    @Test fun `tracks and playlists are refused with a reason, not reported as none`() {
        val client = RoonClient(ScriptedCore(), MemoryStore())
        assertTrue("the reason has to be the real one: a Roon track carries no duration",
            client.unsupported["tracks"]!!.contains("length"))
        assertTrue(client.unsupported["playlists"]!!.contains("list of tracks"))
    }
}
