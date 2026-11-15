package com.musicd.migrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.json.JSONObject


/** What the clients actually send: write and removal batching, paging, the
 *  barcode search and an album's track listing. */
class ClientRequestsTest {

    private fun liveSession() = SpotifySession(
        clientId = "cid", accessToken = "tok", refreshToken = "ref",
        expiresAt = System.currentTimeMillis() + 600_000, userId = "me")


    // ------------------------------------------------------ write batching

    @Test fun `Spotify playlist writes batch at 100 and use track URIs`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        SpotifyClient(liveSession(), http, sleeper = {})
            .addToPlaylist("pl", (0 until 250).map { "t$it" })
        assertEquals("250 tracks is three requests, not 250", 3, http.calls.size)
        val first = JSONObject(http.calls[0].body!!)
        assertEquals(100, first.getJSONArray("uris").length())
        assertEquals("spotify:track:t0", first.getJSONArray("uris").getString(0))
    }

    @Test fun `Spotify saved-track writes batch at 50`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        SpotifyClient(liveSession(), http, sleeper = {})
            .saveTracks((0 until 120).map { "t$it" })
        assertEquals(3, http.calls.size)
        assertEquals(50, JSONObject(http.calls[0].body!!).getJSONArray("ids").length())
    }

    @Test fun `following artists puts the ids in the query, where Spotify wants them`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        SpotifyClient(liveSession(), http, sleeper = {}).followArtists(listOf("a1", "a2"))
        assertTrue(http.calls[0].url.contains("ids=a1%2Ca2"))
        assertTrue(http.calls[0].url.contains("type=artist"))
    }

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
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        sp.saveTracks(emptyList())
        sp.saveAlbums(listOf(""))
        assertEquals(0, http.calls.size)
    }

    // ------------------------------------------------------------- removals
    //
    // The clear-out's requests. Each one is the mirror image of a save, and
    // each is asserted on its METHOD as well as its path: a removal sent as a
    // PUT is a save, and would quietly do the opposite of what was asked.

    @Test fun `Spotify removes saved tracks with a DELETE, fifty ids at a time, in the query`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "")))
        SpotifyClient(liveSession(), http, sleeper = {})
            .unsaveTracks((0 until 120).map { "t$it" })
        assertEquals("120 tracks is three requests", 3, http.calls.size)
        for (c in http.calls) {
            assertEquals("DELETE", c.method)
            assertTrue(c.url, c.url.startsWith("${SpotifyClient.API}/me/tracks?"))
            assertNull("ids in the query, not a body a DELETE may not carry", c.body)
        }
        assertTrue(http.calls[0].url.contains("ids=t0%2Ct1%2C"))
        assertEquals(50, java.net.URLDecoder.decode(
            http.calls[0].url.substringAfter("ids="), "UTF-8").split(",").size)
    }

    @Test fun `Spotify removes saved albums twenty at a time, its maximum for this call`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "")))
        SpotifyClient(liveSession(), http, sleeper = {})
            .unsaveAlbums((0 until Clearance.ALBUM_BATCH).map { "a$it" })
        assertEquals("one clear-out batch is exactly one request", 1, http.calls.size)
        assertEquals("DELETE", http.calls[0].method)
        assertTrue(http.calls[0].url, http.calls[0].url.contains("/me/albums?ids="))

        val more = FakeHttp(listOf(FakeHttp.res(200, "")))
        SpotifyClient(liveSession(), more, sleeper = {}).unsaveAlbums((0 until 45).map { "a$it" })
        assertEquals("45 albums is three requests of at most 20", 3, more.calls.size)
    }

    @Test fun `Spotify unfollows artists with type=artist in the query`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "")))
        SpotifyClient(liveSession(), http, sleeper = {}).unfollowArtists(listOf("a1", "a2"))
        assertEquals("DELETE", http.calls[0].method)
        assertTrue(http.calls[0].url.contains("/me/following?"))
        assertTrue(http.calls[0].url.contains("type=artist"))
        assertTrue(http.calls[0].url.contains("ids=a1%2Ca2"))
    }

    @Test fun `Spotify removes a playlist by unfollowing it, owned or not`() {
        for (owned in listOf(true, false)) {
            val http = FakeHttp(listOf(FakeHttp.res(200, "")))
            SpotifyClient(liveSession(), http, sleeper = {}).removePlaylist("pl/1", owned)
            assertEquals("DELETE", http.calls[0].method)
            assertEquals("the id is escaped, not concatenated",
                "${SpotifyClient.API}/playlists/pl%2F1/followers", http.calls[0].url)
        }
    }

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
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        sp.unsaveTracks(emptyList())
        sp.unsaveAlbums(listOf(""))
        QobuzClient(QobuzSession(token = "t", userId = "1"), http, sleeper = {})
            .unfollowArtists(emptyList())
        assertEquals(0, http.calls.size)
    }

    // ---------------------------------------------------------------- paging

    @Test fun `Spotify paging stops at the reported total`() {
        fun page(n: Int, total: Int) = FakeHttp.res(200,
            """{"total":$total,"items":[""" +
                (0 until n).joinToString(",") { """{"id":"p$it"}""" } + "]}")
        val http = FakeHttp(listOf(page(50, 60), page(10, 60), page(0, 60)))
        val all = SpotifyClient(liveSession(), http, sleeper = {}).playlists()
        assertEquals(60, all.size)
        assertEquals("it stopped without a third empty request", 2, http.calls.size)
    }

    @Test fun `Qobuz paging stops on a short page`() {
        val http = FakeHttp(listOf(FakeHttp.res(200,
            """{"tracks":{"items":[{"id":0,"title":"T0","duration":100},
                                   {"id":1,"title":"T1","duration":100}]}}""")))
        val all = QobuzClient(QobuzSession(token = "t"), http, sleeper = {}).savedTracks()
        assertEquals(2, all.size)
        assertEquals(1, http.calls.size)
    }

    @Test fun `a Spotify playlist page skips local files and episodes, naming them`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """
            {"total":3,"items":[
              {"is_local":true,"track":{"name":"bootleg.flac"}},
              {"is_local":false,"track":{"id":"e","type":"episode","name":"Some Show"}},
              {"is_local":false,"track":{"id":"t","type":"track","name":"Song",
                "duration_ms":1000,"artists":[{"name":"A"}],"album":{"name":"R"},
                "external_ids":{}}}
            ]}"""), FakeHttp.res(200, """{"total":3,"items":[]}""")))
        val tracks = SpotifyClient(liveSession(), http, sleeper = {}).playlistTracks("p")
        assertEquals(3, tracks.size)
        assertEquals("local file", tracks[0].skip)
        assertEquals("podcast episode", tracks[1].skip)
        assertNull(tracks[2].skip)
    }
    // ------------------------------------------------------- barcode search
    // Spotify's album search returns SimplifiedAlbumObject with no
    // external_ids, so a candidate never carries a barcode and Match's barcode
    // tier could not fire. The `upc:` filter is the fix, and because the result
    // still has no barcode on it, THE FILTER IS THE EVIDENCE — the code is
    // stamped on so the tier can see it.

    @Test fun `a Spotify barcode search uses the upc filter and stamps the result`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, """
            {"albums":{"items":[
              {"id":"sal","name":"Master of Puppets","total_tracks":8,
               "artists":[{"name":"Metallica"}]}
            ]}}""")))   // note: NO external_ids, exactly as Spotify sends
        val albums = SpotifyClient(liveSession(), http, sleeper = {})
            .searchByUpc("075992736121")
        assertTrue(http.calls[0].url.contains("q=upc%3A075992736121"))
        assertTrue(http.calls[0].url.contains("type=album"))
        assertEquals(1, albums.size)
        assertEquals("the searched-for barcode must be stamped on, or the tier cannot fire",
            "075992736121", albums[0].upc)
    }

    @Test fun `a barcode search that returns a crowd is not trusted as one`() {
        // A barcode identifies one release. If the filter hands back a pile it
        // is not filtering, and stamping would be a confidently wrong match.
        val items = (0 until 6).joinToString(",") {
            """{"id":"a$it","name":"Something Else","total_tracks":9,
                "artists":[{"name":"Nope"}]}"""
        }
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"albums":{"items":[$items]}}""")))
        val albums = SpotifyClient(liveSession(), http, sleeper = {})
            .searchByUpc("075992736121")
        assertEquals(6, albums.size)
        assertTrue("nothing is stamped, so these are judged on title and artist",
            albums.all { it.upc.isEmpty() })
    }

    @Test fun `an empty barcode makes no request at all`() {
        val http = FakeHttp(listOf(FakeHttp.res(200, "{}")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        assertEquals(emptyList<Album>(), sp.searchByUpc(""))
        assertEquals(emptyList<Album>(), sp.searchByUpc("   "))
        assertEquals(0, http.calls.size)
    }

    @Test fun `Qobuz searches the barcode as a plain query and needs no stamp`() {
        // Unlike Spotify's, Qobuz's album listings DO carry upc, so Match
        // compares the real codes itself.
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
    // albumTracks was added to the interface and to both clients for the
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

    @Test fun `a Spotify album's tracks are paged, and carry no ISRC`() {
        // SimplifiedTrackObject: no external_ids and no album block. Fine for
        // comparing TITLES against the other side's listing, but a caller must
        // not expect an ISRC from it.
        val page1 = (0 until 50).joinToString(",") {
            """{"id":"t$it","name":"Track $it","duration_ms":200000,
                "artists":[{"name":"Band"}]}""" }
        val http = FakeHttp(listOf(
            FakeHttp.res(200, """{"items":[$page1],"total":52}"""),
            FakeHttp.res(200, """{"items":[
                {"id":"t50","name":"Track 50","duration_ms":1000,"artists":[{"name":"Band"}]},
                {"id":"t51","name":"Track 51","duration_ms":1000,"artists":[{"name":"Band"}]}],
                "total":52}""")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        val tracks = sp.albumTracks("sal")

        assertTrue(http.calls[0].url.contains("albums/sal/tracks"))
        assertEquals("a box set is more than one page", 52, tracks.size)
        assertEquals("Track 0", tracks[0].title)
        assertEquals("a simplified track has none", "", tracks[0].isrc)
    }

    @Test fun `an album id is escaped rather than concatenated`() {
        // Ids arrive from a search response rather than from us, and a path
        // built by concatenation is one odd id away from requesting something
        // else entirely.
        val http = FakeHttp(listOf(FakeHttp.res(200, """{"items":[],"total":0}""")))
        val sp = SpotifyClient(liveSession(), http, sleeper = {})
        sp.albumTracks("a/b?c")
        assertTrue(http.calls[0].url, http.calls[0].url.contains("albums/a%2Fb%3Fc/tracks"))
    }
}
