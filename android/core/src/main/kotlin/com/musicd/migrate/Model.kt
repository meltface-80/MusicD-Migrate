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
 * A library that can be READ.
 *
 * Spotify and Qobuz are both of these; so is Roon, and Roon is the reason the
 * interface is split in two. A Roon library is somebody's own files on their
 * own disk, reached through the browse API. It can be listed and it cannot be
 * added to — there is no "save this album to Roon", because the album would
 * have to exist as a file first. So a Roon client implements this and stops.
 *
 * `albumDetail` lives here rather than with the searches because it is asked
 * of the SOURCE: it is how a source album's barcode is fetched BEFORE
 * anything is searched for. A source with no barcodes to give still has to
 * answer; it answers null.
 *
 * Kept in step with SOURCE_METHODS in lib/service.js — ContractTest fails if
 * the two drift, the same way it does for the edition words.
 */
interface MusicSource {
    val serviceName: String
    val accountId: String

    fun me(): Account

    fun playlists(): List<Playlist>
    fun playlistTracks(playlistId: String): List<Track>
    fun savedTracks(): List<Track>
    fun savedAlbums(): List<Album>
    fun followedArtists(): List<Artist>
    fun albumDetail(albumId: String): Album?

    /**
     * An album's track listing.
     *
     * In the READ half because it is asked of BOTH sides: of the source to
     * learn what the user owns, and of a candidate on the target to
     * corroborate a match made without a barcode. For a Roon album that
     * listing is the only independent evidence there is -- see
     * Match.tracklistCorroborates.
     */
    fun albumTracks(albumId: String): List<Track>
}

/**
 * A library that can also be SEARCHED and WRITTEN — a migration destination.
 *
 * A target is a source too, and not incidentally: the engine reads the
 * target's existing library once up front so that everything already there is
 * skipped rather than searched for, which is most of why a second run is fast.
 *
 * Kept in step with TARGET_METHODS in lib/service.js.
 */
interface MusicTarget : MusicSource {

    fun searchByIsrc(isrc: String): List<Track>
    fun searchTracks(title: String, artist: String): List<Track>
    fun searchAlbums(title: String, artist: String): List<Album>

    /**
     * Albums by BARCODE — the album's answer to [searchByIsrc].
     *
     * Spotify's album search returns SimplifiedAlbumObject, which carries no
     * external_ids and therefore no barcode, so the matcher's barcode tier
     * could never fire against a search result. The `upc:` filter is the fix;
     * see SpotifyClient.searchByUpc for why the returned albums are stamped
     * with the code that was searched for, and when they are not.
     */
    fun searchByUpc(upc: String): List<Album>
    fun searchArtists(name: String): List<Artist>

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
