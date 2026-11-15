package com.musicd.migrate

/*
 * Jobs.kt — the types a run is described in, shared by a migration
 * (Migration.kt) and a clear-out (Clearance.kt).
 */

class Cancelled : RuntimeException("Cancelled")

/**
 * What MigrateApi holds while a run is in flight — a [Migration] or a
 * [Clearance]. One at a time, whichever it is: two runs at once would double
 * the request rate into two rate-limited services.
 */
interface RunningJob {
    fun cancel()

    /** A service has told the run to wait; the page must be able to say so. */
    fun noteRateLimit(ms: Long)
}

/**
 * The corroboration read is broken, so the run was stopped.
 *
 * Its own type so a caller can tell it from an ordinary failure.
 */
class BrokenRead(message: String) : RuntimeException(message)

data class MigrationOptions(
    /** null = all of the user's own; a list = exactly these; empty = none. */
    val playlistIds: List<String>? = null,
    val doPlaylists: Boolean = true,
    val doAlbums: Boolean = true,
    val doArtists: Boolean = true,
    val doTracks: Boolean = true,
    val dryRun: Boolean = false,
    val strict: Boolean = false,
    val toleranceMs: Long = Match.DEFAULT_TOLERANCE_MS,
    /** "add-missing" | "create-new" | "skip" */
    val onExisting: String = "add-missing",
    val includeOthersPlaylists: Boolean = false,
    val playlistSuffix: String = "",
    val concurrency: Int = 4,
    /**
     * Check a barcode-less album match against the album's own track listing.
     *
     * On by default: without a barcode, title and artist are not decisive
     * evidence, and this is the app that refuses where the evidence is not
     * decisive. Costs up to two extra reads per album.
     */
    val corroborate: Boolean = true
)

data class Progress(
    val phase: String = "starting",
    val step: String = "",
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val counts: Map<String, Int> = emptyMap(),
    val searches: Int = 0,
    val cacheHits: Int = 0,
    /** How many times a service has held the run. The page's spelling, which
     *  is the authority. */
    val rateLimits: Int = 0
) {
    fun toJson(): String = buildString {
        append("{")
        append("\"phase\":").append(jsonQuote(phase)).append(",")
        append("\"step\":").append(jsonQuote(step)).append(",")
        append("\"label\":").append(jsonQuote(label)).append(",")
        append("\"done\":").append(done).append(",")
        append("\"total\":").append(total).append(",")
        append("\"searches\":").append(searches).append(",")
        append("\"cacheHits\":").append(cacheHits).append(",")
        append("\"rateLimits\":").append(rateLimits).append(",")
        append("\"counts\":{")
        append(counts.entries.joinToString(",") { jsonQuote(it.key) + ":" + it.value })
        append("}}")
    }
}
