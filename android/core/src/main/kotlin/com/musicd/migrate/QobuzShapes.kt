package com.musicd.migrate

import org.json.JSONObject

/*
 * QobuzShapes.kt — Qobuz's JSON in this app's shapes. Split out of
 * QobuzClient.kt to keep it under the 500-line limit.
 */

// ---------------------------------------------------------- shape conversion

/**
 * A Qobuz track in this app's shape.
 *
 * @param albumFallback the album block, when the track came from an album
 *   listing that does not repeat it.
 *
 * THE TWO CONVERSIONS THAT MATTER ARE BOTH HERE: seconds to milliseconds, and
 * title+version to one title. Doing either at the call site would mean doing
 * it right in six places and wrong in the seventh.
 */
internal fun toQobuzTrack(t: JSONObject?, albumFallback: Album?): Track? {
    if (t == null) return null
    val id = t.strOrNull("id") ?: return null
    val albumObj = t.objOrNull("album")
    val version = t.strOrNull("version")
    val title = if (version != null) "${t.str("title")} ($version)" else t.str("title")
    val performer = t.objOrNull("performer")?.strOrNull("name")
        ?: albumObj?.objOrNull("artist")?.strOrNull("name")
        ?: albumFallback?.artists?.firstOrNull()
        ?: ""
    return Track(
        id = id,
        isrc = t.str("isrc"),
        title = title.trim(),
        artists = if (performer.isEmpty()) emptyList() else listOf(performer),
        album = albumObj?.strOrNull("title") ?: albumFallback?.title ?: "",
        // Qobuz counts in SECONDS. Everything downstream counts in
        // milliseconds.
        durationMs = t.longOrNull("duration")?.takeIf { it > 0 }?.times(1000L)
    )
}

internal fun toQobuzAlbum(a: JSONObject?): Album? {
    if (a == null) return null
    val id = a.strOrNull("id") ?: return null
    val version = a.strOrNull("version")
    val title = if (version != null) "${a.str("title")} ($version)" else a.str("title")
    val artist = a.objOrNull("artist")?.strOrNull("name")
        ?: a.arrOrNull("artists")?.objects()?.firstOrNull()?.strOrNull("name")
        ?: ""
    return Album(
        id = id,
        upc = a.str("upc"),
        title = title.trim(),
        artists = if (artist.isEmpty()) emptyList() else listOf(artist),
        trackCount = a.intOrNull("tracks_count"),
        year = qobuzYear(a)
    )
}

private fun qobuzYear(a: JSONObject): Int? {
    for (key in listOf("release_date_original", "release_date_stream", "released_at")) {
        if (a.isNull(key)) continue
        val v = a.opt(key)
        if (v is Number) {
            // `released_at` is unix seconds; the release_date_* fields are
            // "YYYY-MM-DD" strings. Both appear, on the same object.
            val y = java.time.Instant.ofEpochSecond(v.toLong())
                .atZone(java.time.ZoneOffset.UTC).year
            if (y > 1000) return y
        } else {
            val y = v?.toString()?.take(4)?.toIntOrNull()
            if (y != null && y > 1000) return y
        }
    }
    return null
}
