package com.musicd.migrate

/*
 * Model.kt — the shapes everything else speaks in.
 *
 * These are deliberately NOT either service's shapes. SpotifyClient and
 * QobuzClient each convert into these and nothing downstream — the matcher,
 * the migration, the API — knows which service anything came from. That is
 * what makes "Qobuz to Spotify" and "Spotify to Qobuz" one code path instead
 * of two that drift.
 *
 * Durations are MILLISECONDS everywhere, including on the Qobuz side where the
 * service counts in seconds. QobuzClient converts; nothing else needs to know.
 */

data class Track(
    val id: String,
    val isrc: String = "",
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val durationMs: Long? = null,
    /** Set when the item cannot be migrated at all — a local file, a podcast
     *  episode — so the report can say which, rather than "not found". */
    val skip: String? = null
)

data class Album(
    val id: String,
    val upc: String = "",
    val title: String = "",
    val artists: List<String> = emptyList(),
    val trackCount: Int? = null,
    val year: Int? = null
)

data class Artist(val id: String, val name: String = "")

data class Playlist(
    val id: String,
    val name: String = "",
    val description: String = "",
    val isPublic: Boolean = false,
    val ownerId: String = "",
    val trackCount: Int = 0
)

data class Account(val id: String, val name: String)

/**
 * A service, as the migration engine sees one.
 *
 * Both clients implement this identically, which is the whole design: the
 * engine holds a source and a target and never asks which is which.
 */
interface MusicService {
    val serviceName: String
    val accountId: String

    fun me(): Account

    fun playlists(): List<Playlist>
    fun playlistTracks(playlistId: String): List<Track>
    fun savedTracks(): List<Track>
    fun savedAlbums(): List<Album>
    fun followedArtists(): List<Artist>

    fun searchByIsrc(isrc: String): List<Track>
    fun searchTracks(title: String, artist: String): List<Track>
    fun searchAlbums(title: String, artist: String): List<Album>
    fun searchArtists(name: String): List<Artist>
    fun albumDetail(albumId: String): Album?

    fun createPlaylist(name: String, description: String, isPublic: Boolean): Playlist
    fun addToPlaylist(playlistId: String, trackIds: List<String>)
    fun saveTracks(trackIds: List<String>)
    fun saveAlbums(albumIds: List<String>)
    fun followArtists(artistIds: List<String>)
}

/** A sign-in that is gone or was refused. Never treated as "no results". */
class AuthError(message: String) : RuntimeException(message)

/** The service asked this app to slow down and it did not let up. */
class RateLimitError(message: String) : RuntimeException(message)
