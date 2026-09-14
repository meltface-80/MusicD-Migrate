package com.musicd.migrate

import java.util.concurrent.atomic.AtomicInteger

/**
 * A stand-in for a service client.
 *
 * It implements MusicService exactly as SpotifyClient and QobuzClient do,
 * which is the point: Migration cannot tell them apart, and neither can this.
 * The JavaScript suite has the same fake in test/unit/migrate.test.js.
 */
open class FakeService(
    override val serviceName: String,
    var catalogue: MutableList<Track> = ArrayList(),
    var catalogueAlbums: MutableList<Album> = ArrayList(),
    var catalogueArtists: MutableList<Artist> = ArrayList(),
    var libTracks: MutableList<Track> = ArrayList(),
    var libAlbums: MutableList<Album> = ArrayList(),
    var libArtists: MutableList<Artist> = ArrayList(),
    var libPlaylists: LinkedHashMap<String, Pair<String, MutableList<Track>>> = LinkedHashMap()
) : MusicService {

    override var accountId: String = "me"

    val writtenTracks = java.util.Collections.synchronizedList(ArrayList<String>())
    val writtenAlbums = java.util.Collections.synchronizedList(ArrayList<String>())
    val writtenArtists = java.util.Collections.synchronizedList(ArrayList<String>())
    val created = java.util.Collections.synchronizedList(ArrayList<Playlist>())
    val added = java.util.Collections.synchronizedMap(HashMap<String, MutableList<String>>())
    val searchCount = AtomicInteger(0)

    /** Set to make a lookup blow up, for the robustness tests. */
    var isrcSearchFails: (String) -> Boolean = { false }
    var textSearchFails: (String, String) -> Boolean = { _, _ -> false }
    var isrcSearchDelayMs: (String) -> Long = { 0 }
    var saveTracksFails = false
    var playlistsOverride: (() -> List<Playlist>)? = null
    var searchByIsrcAuthFails = false

    override fun me() = Account(accountId, "Fake")

    override fun playlists(): List<Playlist> =
        playlistsOverride?.invoke() ?: libPlaylists.map { (id, p) ->
            Playlist(id = id, name = p.first, ownerId = "me", trackCount = p.second.size)
        }

    override fun playlistTracks(playlistId: String): List<Track> =
        libPlaylists[playlistId]?.second?.toList() ?: emptyList()

    override fun savedTracks(): List<Track> = libTracks.toList()
    override fun savedAlbums(): List<Album> = libAlbums.toList()
    override fun followedArtists(): List<Artist> = libArtists.toList()

    override fun searchByIsrc(isrc: String): List<Track> {
        searchCount.incrementAndGet()
        if (searchByIsrcAuthFails) throw AuthError("Spotify sign-in has expired")
        if (isrcSearchFails(isrc)) throw RuntimeException("service blew up")
        val delay = isrcSearchDelayMs(isrc)
        if (delay > 0) Thread.sleep(delay)
        return catalogue.filter { it.isrc.isNotEmpty() && it.isrc == isrc }
    }

    override fun searchTracks(title: String, artist: String): List<Track> {
        searchCount.incrementAndGet()
        if (textSearchFails(title, artist)) throw RuntimeException("service blew up")
        return catalogue.filter { it.title.contains(title, ignoreCase = true) }
    }

    override fun searchAlbums(title: String, artist: String): List<Album> {
        searchCount.incrementAndGet()
        return catalogueAlbums.filter { it.title.contains(title, ignoreCase = true) }
    }

    override fun searchArtists(name: String): List<Artist> {
        searchCount.incrementAndGet()
        return catalogueArtists.filter { it.name.equals(name, ignoreCase = true) }
    }

    override fun albumDetail(albumId: String): Album? = libAlbums.find { it.id == albumId }

    override fun createPlaylist(name: String, description: String, isPublic: Boolean): Playlist {
        val id = "new${created.size + 1}"
        val pl = Playlist(id = id, name = name)
        created.add(pl)
        libPlaylists[id] = name to ArrayList()
        return pl
    }

    override fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        added.getOrPut(playlistId) { java.util.Collections.synchronizedList(ArrayList()) }
            .addAll(trackIds)
        libPlaylists[playlistId]?.second?.addAll(
            trackIds.map { Track(id = it, title = "t", durationMs = 1) })
    }

    override fun saveTracks(trackIds: List<String>) {
        if (saveTracksFails) throw RuntimeException("Spotify said no")
        writtenTracks.addAll(trackIds)
    }

    override fun saveAlbums(albumIds: List<String>) { writtenAlbums.addAll(albumIds) }
    override fun followArtists(artistIds: List<String>) { writtenArtists.addAll(artistIds) }
}
