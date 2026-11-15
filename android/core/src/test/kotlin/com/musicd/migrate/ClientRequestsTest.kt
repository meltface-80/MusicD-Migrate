package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** What the Qobuz client actually sends: write and removal batching, paging,
 *  the barcode search and an album's track listing. */
class ClientRequestsTest {

    private fun qobuz(http: Http) =
        QobuzClient(QobuzSession(token = "t", userId = "1"), http, sleeper = {})

    // ------------------------------------------------------ write batching

    @Test fun `Qobuz writes batch at 50 and go in the query string`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"status":"success"}""")))
        QobuzClient(QobuzSession(token = "t", userId = "1"), http, sleeper = {})
            .addToPlaylist("pl", (0 until 130).map { "t$it" })
        assertEquals(3, http.calls.size)
        assertTrue(http.calls[0].url.contains("track_ids="))
        assertTrue(http.calls[0].url.contains("app_id="))
    }

    @Test fun `empty write lists send no requests at all`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        val qz = qobuz(http)
        qz.saveTracks(emptyList())
        qz.saveAlbums(listOf(""))
        assertEquals(0, http.calls.size)
    }

    // ------------------------------------------------------------- removals
    //
    // The clear-out's requests. Each one is the mirror image of a save, and
    // each is asserted on its METHOD as well as its path: a removal sent as a
    // PUT is a save, and would quietly do the opposite of what was asked.

    @Test fun `Qobuz unfavourites through favorite delete with the same id lists as create`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"status":"success"}""")))
        val qz = QobuzClient(QobuzSession(token = "t", userId = "1"), http, sleeper = {})
        qz.unsaveTracks((0 until 60).map { "t$it" })
        qz.unsaveAlbums(listOf("a1"))
        qz.unfollowArtists(listOf("r1"))
        assertEquals("60 tracks in two batches, then one each", 4, http.calls.size)
        for (c in http.calls) {
            assertTrue(c.url, c.url.startsWith(QobuzClient.BASE + "favorite/delete?"))
            assertFalse("never favorite/create: ${c.url}", c.url.contains("favorite/create"))
        }
        assertTrue(http.calls[0].url.contains("track_ids=t0%2C"))
        assertTrue(http.calls[2].url.contains("album_ids=a1"))
        assertTrue(http.calls[3].url.contains("artist_ids=r1"))
    }

    @Test fun `Qobuz deletes a playlist the user made and unsubscribes from one they follow`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"status":"success"}""")))
        val qz = QobuzClient(QobuzSession(token = "t", userId = "1"), http, sleeper = {})
        qz.removePlaylist("11", owned = true)
        qz.removePlaylist("22", owned = false)
        assertTrue(http.calls[0].url, http.calls[0].url.startsWith(QobuzClient.BASE + "playlist/delete?"))
        assertTrue(http.calls[0].url.contains("playlist_id=11"))
        assertTrue(http.calls[1].url,
            http.calls[1].url.startsWith(QobuzClient.BASE + "playlist/unsubscribe?"))
        assertTrue(http.calls[1].url.contains("playlist_id=22"))
    }

    @Test fun `empty removal lists send no requests at all`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "")))
        val qz = qobuz(http)
        qz.unsaveTracks(emptyList())
        qz.unsaveAlbums(listOf(""))
        qz.unfollowArtists(emptyList())
        assertEquals(0, http.calls.size)
    }

    // ---------------------------------------------------------------- paging

    @Test fun `Qobuz paging stops on a short page`() {
        val http = FakeHttp(listOf(FakeHttp.res(200,
            """{"tracks":{"items":[{"id":0,"title":"T0","duration":100},
                                   {"id":1,"title":"T1","duration":100}]}}""")))
        val all = QobuzClient(QobuzSession(token = "t"), http, sleeper = {}).savedTracks()
        assertEquals(2, all.size)
        assertEquals(1, http.calls.size)
    }

    // ------------------------------------------------------- barcode search

    @Test fun `an empty barcode makes no request at all`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        val qz = qobuz(http)
        assertEquals(emptyList<Album>(), qz.searchByUpc(""))
        assertEquals(emptyList<Album>(), qz.searchByUpc("   "))
        assertEquals(0, http.calls.size)
    }

    @Test fun `Qobuz searches the barcode as a plain query and needs no stamp`() {
        // Qobuz's album listings carry the upc, so Match compares the real
        // codes itself rather than trusting the search.
        val http = FakeHttp(listOf(FakeHttp.res(200, """
            {"albums":{"items":[
              {"id":7,"title":"Master Of Puppets","upc":"075992736121",
               "tracks_count":8,"artist":{"name":"Metallica"}}
            ]}}""")))
        val albums = QobuzClient(QobuzSession(token = "t"), http, sleeper = {})
            .searchByUpc("075992736121")
        assertTrue(http.calls[0].url.contains("query=075992736121"))
        assertTrue(http.calls[0].url.contains("type=albums"))
        assertEquals("read from the response, not stamped",
            "075992736121", albums[0].upc)
    }

    // -------------------------------------------- an album's track listing
    //
    // albumTracks was added to the interface and to the clients for the
    // corroboration tier -- the thing that decides a barcode-less album --
    // and NOT ONE of these tests existed. Only the fake was tested, and a
    // fake agrees with whatever you wrote. On a real library the tier then
    // refused every album, because the read it depends on silently returned
    // nothing.

    @Test fun `a Qobuz album's tracks come from album per get, with no extra parameter`() {
        // album/get RETURNS THE TRACKS ON ITS OWN. MusicD-Remote -- a Qobuz
        // client that works -- asks for `{album_id}` and reads `tracks.items`,
        // and passing an `extra` value the API does not define is a request it
        // can refuse outright. A refused read is not a visible error here:
        // safely() turns it into "could not read the track listing" and every
        // album goes unmatched.
        val http = FakeHttp(listOf(FakeHttp.res(200, """
            {"id":"0060254776324","title":"Kind Of Blue","upc":"0060254776324",
             "tracks_count":5,"artist":{"name":"Miles Davis"},
             "tracks":{"items":[
               {"id":1,"title":"So What","duration":545,"isrc":"USSM17700001"},
               {"id":2,"title":"Freddie Freeloader","duration":574}]}}""")))
        val qz = QobuzClient(QobuzSession(token = "t"), http, sleeper = {})
        val tracks = qz.albumTracks("0060254776324")

        assertTrue(http.calls[0].url.contains("album/get"))
        assertTrue(http.calls[0].url.contains("album_id=0060254776324"))
        assertFalse("album/get carries the tracks already; an undefined extra risks a refusal",
            http.calls[0].url.contains("extra="))

        assertEquals(listOf("So What", "Freddie Freeloader"), tracks.map { it.title })
        assertEquals("Qobuz counts SECONDS", 545_000L, tracks[0].durationMs)
        assertEquals("USSM17700001", tracks[0].isrc)
        assertEquals("the album block does not repeat per track, so it is threaded down",
            "Kind Of Blue", tracks[0].album)
    }

    @Test fun `a Qobuz album with no tracks block is an empty listing, not a throw`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"id":"x","title":"Nothing"}""")))
        val qz = QobuzClient(QobuzSession(token = "t"), http, sleeper = {})
        assertEquals(emptyList<Track>(), qz.albumTracks("x"))
    }

    @Test fun `an album id is escaped rather than concatenated`() {
        // Ids arrive from a search response rather than from us, and a path
        // built by concatenation is one odd id away from requesting something
        // else entirely.
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"id":"x","title":"T"}""")))
        qobuz(http).albumTracks("a/b?c&x=1")
        assertTrue(http.calls[0].url, http.calls[0].url.contains("album_id=a%2Fb%3Fc%26x%3D1"))
        assertFalse(http.calls[0].url, http.calls[0].url.contains("&x=1"))
    }
}
