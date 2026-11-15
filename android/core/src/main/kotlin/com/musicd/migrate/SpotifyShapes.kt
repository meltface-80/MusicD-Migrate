package com.musicd.migrate

import org.json.JSONObject

/*
 * SpotifyShapes.kt — Spotify's JSON in this app's shapes, and the small
 * helpers the client builds requests with. Split out of SpotifyClient.kt.
 */

// ---------------------------------------------------------- shape conversion

/**
 * A Spotify track object in this app's shape, or null if it is not a track we
 * can migrate.
 *
 * The nulls matter. A playlist can contain a PODCAST EPISODE (no ISRC, no
 * artist, an id that is not a track id) and a LOCAL FILE (a track object whose
 * id is null, existing only on that person's machine). Both arrive inside
 * items[].track looking close enough to a track to crash a naive mapper, and
 * neither can be migrated to anything.
 */
internal fun toTrack(t: JSONObject?): Track? {
    if (t == null) return null
    val type = t.str("type")
    if (type.isNotEmpty() && type != "track") return null
    val id = t.strOrNull("id") ?: return null
    return Track(
        id = id,
        isrc = t.objOrNull("external_ids")?.str("isrc").orEmpty(),
        title = t.str("name"),
        artists = t.arrOrNull("artists")?.objects()?.mapNotNull { it.strOrNull("name") }
            .orEmpty(),
        album = t.objOrNull("album")?.str("name").orEmpty(),
        durationMs = t.longOrNull("duration_ms")?.takeIf { it > 0 }
    )
}

/** As toTrack, but reporting WHY an item was skipped so the job can say so. */
internal fun fromPlaylistItem(item: JSONObject?): Track? {
    if (item == null) return null
    if (item.optBoolean("is_local", false)) {
        return Track(id = "local", title = item.objOrNull("track")?.str("name")
            ?.takeIf { it.isNotEmpty() } ?: "a local file", skip = "local file")
    }
    val raw = item.objOrNull("track")
    val t = toTrack(raw)
    if (t != null) return t
    if (raw?.str("type") == "episode") {
        return Track(id = "episode",
            title = raw.strOrNull("name") ?: "an episode", skip = "podcast episode")
    }
    return null
}

internal fun toAlbum(a: JSONObject?): Album? {
    if (a == null) return null
    val id = a.strOrNull("id") ?: return null
    return Album(
        id = id,
        upc = a.objOrNull("external_ids")?.str("upc").orEmpty(),
        title = a.str("name"),
        artists = a.arrOrNull("artists")?.objects()?.mapNotNull { it.strOrNull("name") }
            .orEmpty(),
        trackCount = a.intOrNull("total_tracks"),
        year = a.str("release_date").take(4).toIntOrNull()
    )
}

/**
 * A search term, quoted.
 *
 * A double quote inside the term ends the quoted phrase and turns the rest
 * into loose words — so a track called 'The "Real" Thing' searched raw finds
 * nothing and looks like the track is missing. Spotify has no escape for it
 * inside a quoted phrase, so the quote is dropped rather than escaped.
 */
internal fun quoteTerm(s: String): String = "\"" + s.replace("\"", " ").trim() + "\""

internal fun chunk(list: List<String>, n: Int): List<List<String>> =
    list.filter { it.isNotEmpty() }.chunked(n)

internal fun messageFrom(text: String): String =
    parseObject(text)?.let { j ->
        j.objOrNull("error")?.strOrNull("message") ?: j.strOrNull("error")
    } ?: text.take(160)
