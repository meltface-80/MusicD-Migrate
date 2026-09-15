package com.musicd.migrate.roon

import com.musicd.migrate.Album
import com.musicd.migrate.Account
import com.musicd.migrate.Artist
import com.musicd.migrate.Canon
import com.musicd.migrate.MusicSource
import com.musicd.migrate.Playlist
import com.musicd.migrate.RoonAlbumRow
import com.musicd.migrate.Store
import com.musicd.migrate.Track
import com.musicd.migrate.arrOrNull
import com.musicd.migrate.intOrNull
import com.musicd.migrate.objOrNull
import com.musicd.migrate.objects
import com.musicd.migrate.str
import com.musicd.migrate.strOrNull
import org.json.JSONObject
import java.security.MessageDigest

/*
 * RoonClient.kt — a Roon library as a migration SOURCE.
 *
 * The Kotlin twin of lib/roon.js. Everything said there is said here, and the
 * reason for all of it is one fact: a Roon browse row is a title, a subtitle,
 * an image key and a session-scoped item key. No MBID, no ISRC, no barcode, no
 * service id, and NO TRACK LENGTH.
 *
 *   - ALBUMS can be migrated: title and artist, corroborated by the album's
 *     own track listing, is evidence worth offering.
 *   - TRACKS cannot, ever. Title and artist alone are exactly the two facts a
 *     cover, a re-recording and a live take also satisfy.
 *   - PLAYLISTS cannot, because a playlist is a list of tracks.
 *
 * Those two are DECLARED unsupported with the reason, and Migration records
 * the reason. Returning an empty list would finish a run green having migrated
 * nothing, which reads as an empty library rather than as a thing this app
 * will not do.
 */

/** One browse row, in this app's spelling. */
data class BrowseRow(
    val title: String,
    val subtitle: String,
    val itemKey: String?,
    val imageKey: String?,
    val hint: String?
) {
    companion object {
        fun of(o: JSONObject): BrowseRow = BrowseRow(
            title = o.str("title"),
            subtitle = o.str("subtitle"),
            itemKey = o.strOrNull("item_key"),
            imageKey = o.strOrNull("image_key"),
            hint = o.strOrNull("hint")
        )
    }
}

/** What a scan found and where it got to. */
data class RoonScanSummary(
    val coreId: String,
    val offset: Int,
    val total: Int,
    val stored: Int,
    val duplicates: Int,
    val done: Boolean
)

class RoonClient(
    private val core: BrowseApi,
    private val store: Store,
    private val log: (String) -> Unit = {}
) : MusicSource {

    companion object {
        /** Roon's own page size for a browse level. */
        const val PAGE = 100

        /**
         * The most rows read from one album's contents. 500 covers a box set;
         * nothing in a migration needs a second page of an album.
         */
        const val ALBUM_CONTENTS_MAX = 500

        const val ARTISTS_MAX = 20000

        /** Separates the two halves of a key so "ab|c" and "a|bc" differ. */
        private const val KEY_SEP = ""

        /**
         * A stable id for a Roon album.
         *
         * NOT Roon's item_key, which is session-scoped: it means nothing once
         * the browse session is re-navigated, and a stored one would drill
         * into whatever has since taken that key. NOT the offset either, which
         * moves the moment a record is bought. A hash of the normalised title
         * and artist instead, so it is the same id on every scan — which is
         * what lets the match cache survive a rescan.
         *
         * Must agree with albumKey() in lib/roon.js, or the two halves file
         * the same record under different ids and neither can read the
         * other's cache.
         */
        fun albumKey(title: String, artist: String): String =
            "ra_" + sha1(Canon.canon(title) + KEY_SEP + Canon.primaryArtist(artist)).take(16)

        fun artistKey(name: String): String = "rn_" + sha1(Canon.canon(name)).take(16)

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Roon answered, but not with a list.
         *
         * When the action is "message" the Core has said why in its own words,
         * and discarding that is how a user asking "why is my library empty?"
         * gets told about a protocol instead of the reason.
         */
        fun requireList(body: JSONObject?, what: String) {
            val action = body?.str("action") ?: ""
            if (action == "list") return
            if (action == "message") {
                throw RoonException("Roon says: " +
                    (body?.strOrNull("message") ?: "it declined"))
            }
            throw RoonException("Roon gave no list for $what " +
                "(it answered \"${action.ifEmpty { "nothing" }}\")")
        }

        val UNSUPPORTED_TRACKS =
            "Roon's browse API gives a track's title and artist but not its length, and a " +
            "title and artist alone are exactly what a cover, a re-recording and a live " +
            "version also satisfy. Matching on that would quietly put the wrong recording " +
            "in your library, so favourite tracks are not migrated from Roon. Albums are."

        val UNSUPPORTED_PLAYLISTS =
            "A playlist is a list of tracks, and a track from Roon carries no length to " +
            "match it on — see the note about favourite tracks. Roon playlists are " +
            "not migrated."
    }

    override val serviceName: String = "roon"
    override val accountId: String get() = core.coreId ?: "roon"

    override val unsupported: Map<String, String> = mapOf(
        "tracks" to UNSUPPORTED_TRACKS,
        "playlists" to UNSUPPORTED_PLAYLISTS
    )

    override fun me(): Account = Account(accountId, core.coreName ?: "Roon")

    /* Present so the object really is a MusicSource, and empty because
     * `unsupported` is what carries the reason. Migration never reaches these. */
    override fun playlists(): List<Playlist> = emptyList()
    override fun playlistTracks(playlistId: String): List<Track> = emptyList()
    override fun savedTracks(): List<Track> = emptyList()

    // --------------------------------------------------------------- the scan

    /**
     * Walk the whole album list and store it.
     *
     * Paged at 100, one transaction per page, and the offset is remembered so
     * a scan interrupted at album 6,000 resumes there. Roon's album list is
     * alphabetically stable, so an offset means the same thing on the next
     * pass — as long as the library has not changed underneath.
     */
    fun scan(
        resume: Boolean = false,
        cancelled: () -> Boolean = { false },
        onProgress: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }
    ): RoonScanSummary {
        val coreId = accountId
        var offset = 0
        var stored = 0
        var duplicates = 0

        val saved = store.roonScan()
        if (resume && saved != null && saved.coreId == coreId && saved.offset > 0) {
            offset = saved.offset
            stored = saved.stored
            duplicates = saved.duplicates
            log("resuming the Roon scan at album $offset")
        } else {
            // A fresh scan replaces the inventory rather than merging into it,
            // so a record deleted in Roon does not live on in an export.
            store.clearRoonAlbums(coreId)
        }

        return core.withSession { key ->
            val head = core.browse(JSONObject()
                .put("hierarchy", "albums").put("multi_session_key", key).put("pop_all", true))
            requireList(head, "the album list")
            var total = head.objOrNull("list")?.intOrNull("count") ?: 0

            while (true) {
                if (cancelled()) break
                val page = core.load(JSONObject()
                    .put("hierarchy", "albums").put("multi_session_key", key)
                    .put("offset", offset).put("count", PAGE))
                val items = page.arrOrNull("items")?.objects()?.map { BrowseRow.of(it) }
                    ?: emptyList()
                page.objOrNull("list")?.intOrNull("count")?.let { if (it > 0) total = it }
                if (items.isEmpty()) break

                val rows = items.mapIndexed { i, it ->
                    RoonAlbumRow(
                        albumKey = albumKey(it.title, it.subtitle),
                        title = it.title,
                        artist = it.subtitle,
                        position = offset + i,
                        imageKey = it.imageKey
                    )
                }
                val result = store.saveRoonAlbums(coreId, rows)
                stored += result.first
                duplicates += result.second
                offset += items.size

                store.saveRoonScan(RoonScanSummary(coreId, offset, total, stored,
                    duplicates, false))
                onProgress(offset, total, stored, duplicates)

                // A page short of a full one is the end of the list. Trusting
                // `total` alone would loop for ever against a Core that
                // reports a count it does not actually serve.
                if (items.size < PAGE) break
                if (total > 0 && offset >= total) break
            }

            val summary = RoonScanSummary(coreId, offset, total, stored, duplicates, !cancelled())
            store.saveRoonScan(summary)
            summary
        }
    }

    // --------------------------------------------------------- the library

    /**
     * The scanned albums.
     *
     * Reads the inventory rather than walking Roon: a migration must not spend
     * ten minutes re-reading a ten thousand album library on its own.
     *
     * Throws rather than returning nothing when there is no scan. "You have
     * not scanned yet" and "your library is empty" are different, and a
     * migration that reported the first as the second would finish green
     * having done nothing.
     */
    override fun savedAlbums(): List<Album> {
        val rows = store.roonAlbums(accountId)
        if (rows.isEmpty()) {
            throw RoonException("No Roon library has been scanned yet. Scan it first — " +
                "a migration reads the scan, not the Core, so it cannot do it for you " +
                "without appearing to have found nothing.")
        }
        return rows.map {
            Album(
                id = it.albumKey,
                upc = "",                    // Roon has none. Missing data, not evidence.
                title = it.title,
                artists = if (it.artist.isEmpty()) emptyList() else listOf(it.artist),
                trackCount = it.trackCount   // null until something drills in
            )
        }
    }

    /**
     * Nothing more is known about a Roon album than the scan holds.
     *
     * Migration asks this for a barcode before it searches. Answering null is
     * the honest answer and costs nothing.
     */
    override fun albumDetail(albumId: String): Album? = null

    /**
     * An album's track listing, drilled on demand.
     *
     * About four calls, so it is never done for the whole library. It exists
     * for corroborating a candidate album on the other service, where a track
     * listing is the only evidence a Roon album has to offer.
     *
     * Durations are null and that is not an oversight: a Roon browse row does
     * not carry one.
     */
    override fun albumTracks(albumId: String): List<Track> {
        val coreId = accountId
        val row = store.roonAlbum(coreId, albumId) ?: return emptyList()

        return core.withSession { key ->
            core.browse(JSONObject()
                .put("hierarchy", "albums").put("multi_session_key", key).put("pop_all", true))
            val item = findAlbum(key, row)
            if (item?.itemKey == null) {
                // Not an error: a record can be removed from Roon between a
                // scan and a migration, and one missing album must not stop a
                // run.
                log("album \"${row.title}\" is no longer where the scan left it")
                emptyList()
            } else {
                val into = core.browse(JSONObject()
                    .put("hierarchy", "albums").put("multi_session_key", key)
                    .put("item_key", item.itemKey))
                requireList(into, "the album \"${row.title}\"")

                val tracks = loadLevel(key, ALBUM_CONTENTS_MAX)
                    // A header row is the album's own banner, not a track; a
                    // row with no item key cannot be one either.
                    .filter { it.hint != "header" && it.itemKey != null && it.title.isNotEmpty() }
                    .mapIndexed { i, it ->
                        Track(
                            id = "$albumId:$i",
                            title = it.title,
                            artists = listOfNotNull(
                                it.subtitle.ifEmpty { row.artist }.takeIf { s -> s.isNotEmpty() }),
                            album = row.title,
                            durationMs = null
                        )
                    }
                // Learned for free while we were in there.
                store.setRoonAlbumTrackCount(coreId, albumId, tracks.size)
                tracks
            }
        }
    }

    /**
     * Every artist in the library.
     *
     * Walked live rather than stored: it is a few load calls at worst, and it
     * is not the thing the user asked to inventory.
     */
    override fun followedArtists(): List<Artist> = core.withSession { key ->
        val head = core.browse(JSONObject()
            .put("hierarchy", "artists").put("multi_session_key", key).put("pop_all", true))
        requireList(head, "the artist list")
        val out = LinkedHashMap<String, Artist>()
        for (row in loadLevel(key, ARTISTS_MAX, "artists")) {
            if (row.title.isEmpty()) continue
            val id = artistKey(row.title)
            if (!out.containsKey(id)) out[id] = Artist(id, row.title)
        }
        out.values.toList()
    }

    // --------------------------------------------------------------- internals

    /** Page through the level the session is currently on. */
    private fun loadLevel(key: String, max: Int, hierarchy: String = "albums"): List<BrowseRow> {
        val out = ArrayList<BrowseRow>()
        var offset = 0
        while (true) {
            val page = core.load(JSONObject()
                .put("hierarchy", hierarchy).put("multi_session_key", key)
                .put("offset", offset).put("count", PAGE))
            val items = page.arrOrNull("items")?.objects()?.map { BrowseRow.of(it) } ?: emptyList()
            if (items.isEmpty()) break
            out.addAll(items)
            offset += items.size
            if (items.size < PAGE) break
            if (offset >= max) break
            val total = page.objOrNull("list")?.intOrNull("count") ?: 0
            if (total > 0 && offset >= total) break
        }
        return out
    }

    /**
     * Find a scanned album in the live list again.
     *
     * The remembered offset first, CONFIRMED against the title: a hint that
     * has gone stale — because records were added above it — must cost a scan,
     * never yield the wrong album. Only if that fails does it page through
     * looking for the title.
     */
    private fun findAlbum(key: String, row: RoonAlbumRow): BrowseRow? {
        val wantTitle = Canon.canon(row.title)
        val wantArtist = Canon.primaryArtist(row.artist)

        fun matches(it: BrowseRow) = Canon.canon(it.title) == wantTitle &&
            (wantArtist.isEmpty() || Canon.primaryArtist(it.subtitle) == wantArtist)

        if (row.position >= 0) {
            val page = core.load(JSONObject()
                .put("hierarchy", "albums").put("multi_session_key", key)
                .put("offset", row.position).put("count", 1))
            val one = page.arrOrNull("items")?.objects()?.firstOrNull()?.let { BrowseRow.of(it) }
            if (one != null && matches(one)) return one
        }

        var offset = 0
        while (true) {
            val page = core.load(JSONObject()
                .put("hierarchy", "albums").put("multi_session_key", key)
                .put("offset", offset).put("count", PAGE))
            val items = page.arrOrNull("items")?.objects()?.map { BrowseRow.of(it) } ?: emptyList()
            if (items.isEmpty()) return null
            items.firstOrNull { matches(it) }?.let { return it }
            offset += items.size
            if (items.size < PAGE) return null
            val total = page.objOrNull("list")?.intOrNull("count") ?: 0
            if (total > 0 && offset >= total) return null
        }
    }
}
