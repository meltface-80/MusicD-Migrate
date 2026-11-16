package com.musicd.migrate

/*
 * Model.kt — the shapes everything else speaks in.
 *
 * These are deliberately NOT any service's shapes. QobuzClient and CsvLibrary
 * each convert into these and nothing downstream — the matcher, the
 * migration, the API — knows where anything came from. That is what lets a
 * library file be imported by the same engine that once moved Spotify
 * libraries, and would let another service be added back without a fork.
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
 * Split from [MusicTarget] so that the migration's SOURCE is asked for
 * nothing but reads — a dry run, and every phase before the writes, can then
 * be seen at a glance to touch nothing.
 *
 * `albumDetail` lives here rather than with the searches because it is asked
 * of the SOURCE: it is how a source album's barcode is fetched BEFORE
 * anything is searched for.
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
     * corroborate a match made without a barcode -- see
     * Match.tracklistCorroborates.
     */
    fun albumTracks(albumId: String): List<Track>

    /**
     * Why this album cannot be matched from what the source knows, or null.
     *
     * A library FILE is the case: a row with no barcode and no track listing
     * leaves title and artist, which are not decisive. Without this the engine
     * would ask for the listing, get nothing, and record a FAILED read — red,
     * uncached, and fifty of them trip the breaker meant for a broken check.
     * It is not broken; the evidence is simply not in the file, which is an
     * ordinary refusal with a reason. Only asked when the listing check is on.
     */
    fun unmatchable(album: Album): String? = null

    /**
     * Whether [albumTracks] can say anything for this album. True for a
     * service (a read that comes back empty there is a FAILED read); false for
     * a library-file row with no track listing, so a title match for an album
     * whose barcode the service lacks is refused with a reason rather than
     * recorded as a broken check.
     */
    fun hasListing(albumId: String): Boolean = true
}

/**
 * A library that can also be SEARCHED and WRITTEN — a migration destination.
 *
 * A target is a source too, and not incidentally: the engine reads the
 * target's existing library once up front so that everything already there is
 * skipped rather than searched for, which is most of why a second run is fast.
 *
 * It is also what a CLEAR-OUT acts on: the removals at the bottom are the
 * mirror image of the saves, batched the same way, and only Clearance calls
 * them.
 */
interface MusicTarget : MusicSource {

    fun searchByIsrc(isrc: String): List<Track>
    fun searchTracks(title: String, artist: String): List<Track>
    fun searchAlbums(title: String, artist: String): List<Album>

    /**
     * Albums by BARCODE — the album's answer to [searchByIsrc].
     *
     * Searched FOR rather than compared afterwards: a service whose album
     * search returns no barcode (Spotify's did not) would otherwise leave the
     * barcode tier unable to fire. QobuzClient's results carry the code.
     */
    fun searchByUpc(upc: String): List<Album>
    fun searchArtists(name: String): List<Artist>

    fun createPlaylist(name: String, description: String, isPublic: Boolean): Playlist
    fun addToPlaylist(playlistId: String, trackIds: List<String>)
    fun saveTracks(trackIds: List<String>)
    fun saveAlbums(albumIds: List<String>)
    fun followArtists(artistIds: List<String>)

    // ------------------------------------------------------------ removals
    //
    // Only ever called by Clearance, after the user has previewed the list and
    // typed the service's name. Batched to the endpoint maximum by the client,
    // exactly like the saves. A batch that fails throws; the caller records
    // every id in it as failed.

    fun unsaveTracks(trackIds: List<String>)
    fun unsaveAlbums(albumIds: List<String>)
    fun unfollowArtists(artistIds: List<String>)

    /**
     * Take one playlist out of the library.
     *
     * @param owned whether the signed-in user made it. An owned playlist is
     *   DELETED; one they merely follow is UNFOLLOWED, and the playlist itself
     *   carries on existing for its owner. On Qobuz they are two different
     *   endpoints, and the wrong one fails.
     */
    fun removePlaylist(playlistId: String, owned: Boolean)
}

/** A sign-in that is gone or was refused. Never treated as "no results". */
class AuthError(message: String) : RuntimeException(message)

/** The service asked this app to slow down and it did not let up. */
class RateLimitError(message: String) : RuntimeException(message)
