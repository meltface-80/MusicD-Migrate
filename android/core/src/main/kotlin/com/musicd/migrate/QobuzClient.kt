package com.musicd.migrate

import org.json.JSONObject
import java.security.MessageDigest

/*
 * QobuzClient.kt — the Qobuz API, as much of it as migrating needs. The Kotlin
 * twin of lib/qobuz.js.
 *
 * IMPORTANT: this is the UNOFFICIAL Qobuz API — the same one the Lyrion/LMS
 * "Qobuz" community plugin and every open-source Qobuz client use. It is not a
 * sanctioned integration, it is against Qobuz's terms of service, and it can
 * change without notice. The README says so where someone will read it before
 * installing rather than after.
 *
 * IT IMPLEMENTS THE SAME INTERFACE AS SpotifyClient ON PURPOSE. Migration then
 * has no idea which service is the source and which is the destination, and
 * both directions are one code path rather than two that drift apart.
 *
 * TWO THINGS ABOUT QOBUZ'S DATA THAT BITE, both absorbed here:
 *   - `duration` is in SECONDS where Spotify's duration_ms is milliseconds. A
 *     matcher comparing 213 against 213000 rejects every track in the library
 *     and looks exactly like "nothing is on Qobuz".
 *   - the edition is a SEPARATE FIELD: title "Blue Monday" + version "2016
 *     Remaster" against Spotify's one string. They are recomposed here so
 *     Canon.stripVersion sees the same thing from both sides.
 */

data class QobuzSession(
    var token: String = "",
    var userId: String = "",
    var appId: String = "",
    var name: String = ""
)

class QobuzClient(
    val session: QobuzSession,
    private val http: Http = UrlConnectionHttp(),
    private val onRateLimit: (Long) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) : MusicTarget {

    override val serviceName = "qobuz"
    override val accountId: String get() = session.userId

    companion object {
        const val BASE = "https://www.qobuz.com/api.json/0.2/"

        // The app_id the Lyrion/LMS Qobuz plugin uses. It identifies the
        // APPLICATION, never a user.
        const val DEFAULT_APP_ID = "942852567"
        const val MAX_RETRIES = 4

        fun md5Hex(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Trade the one-time code from the browser sign-in for a login token.
         *
         * Codes are single-use and short-lived, so a failure here is nearly
         * always "start again" rather than anything the user can fix — which
         * is what the 404 message says, because Qobuz's own status code for a
         * spent code is a 404 and that reads like a broken app.
         */
        fun exchangeCode(http: Http, code: String): QobuzSession {
            require(code.isNotEmpty()) { "No sign-in code to exchange" }
            val url = QobuzOAuth.API_BASE + "/oauth/callback?" +
                query(mapOf("code" to code, "private_key" to QobuzOAuth.PRIVATE_KEY))
            val res = try {
                http.request("GET", url, mapOf("X-App-Id" to QobuzOAuth.APP_ID),
                    timeoutMs = 20000)
            } catch (e: Exception) {
                throw RuntimeException(
                    "Could not reach Qobuz to finish signing in: ${e.message}")
            }
            if (res.status == 404) {
                throw RuntimeException("Qobuz rejected the sign-in code. Codes can only be " +
                    "used once and expire quickly — start the sign-in again.")
            }
            if (!res.ok) throw RuntimeException("Qobuz refused the sign-in (HTTP ${res.status})")
            val data = parseObject(res.body)
            val token = data?.strOrNull("token")
            val userId = data?.strOrNull("user_id")
            if (token == null || userId == null) {
                throw RuntimeException("Qobuz returned no token for that sign-in code")
            }
            return QobuzSession(token = token, userId = userId, appId = QobuzOAuth.APP_ID)
        }
    }

    /**
     * Which app this session acts as.
     *
     * A user_auth_token belongs to the app that minted it, and Qobuz refuses a
     * token presented with a different app_id — so the id and the token move
     * together. A token from the browser sign-in belongs to THAT app, not to
     * the default, and presenting the wrong one is a 401 that reads exactly
     * like an expired sign-in.
     */
    private val appId: String get() = session.appId.ifEmpty { DEFAULT_APP_ID }

    fun request(
        endpoint: String, params: Map<String, Any?> = emptyMap(), method: String = "GET"
    ): JSONObject? {
        // BASE already ends in "/", so a leading slash here produces
        // ".../0.2//x" — a URL that is wrong in a way nothing downstream can
        // see, because the failure arrives as an ordinary non-200.
        val path = endpoint.trimStart('/')
        var attempt = 0

        while (true) {
            val qs = query(params + mapOf("app_id" to appId))
            val headers = HashMap<String, String>()
            headers["X-App-Id"] = appId
            if (session.token.isNotEmpty()) headers["X-User-Auth-Token"] = session.token

            val res = try {
                http.request(method, BASE + path + "?" + qs, headers, timeoutMs = 20000)
            } catch (e: Exception) {
                if (attempt++ < 2) { sleeper(500L * attempt); continue }
                throw RuntimeException("Could not reach Qobuz: ${e.message}")
            }

            if (res.status == 429) {
                if (attempt++ >= MAX_RETRIES) {
                    throw RateLimitError("Qobuz is rate limiting this app and did not let up.")
                }
                // Qobuz does not send Retry-After. Backing off exponentially
                // from a second is a guess, but an unbounded hammer is not an
                // alternative.
                val waitMs = minOf(1000L shl attempt, 30000L)
                onRateLimit(waitMs)
                sleeper(waitMs)
                continue
            }

            if (res.status == 401) {
                throw AuthError("Qobuz sign-in has expired or was rejected — sign in again.")
            }
            if (!res.ok) {
                // THE BODY IS THE DIAGNOSIS. Qobuz distinguishes its failures
                // in the response TEXT and nowhere else, and every one of them
                // arrives as an ordinary non-200.
                throw RuntimeException("Qobuz HTTP ${res.status}: ${res.body.take(200)}")
            }
            return parseObject(res.body)
                ?: throw RuntimeException("Qobuz returned an unexpected (non-JSON) response")
        }
    }

    // -------------------------------------------------------------------- auth

    /**
     * Sign in with an email and password.
     *
     * THE FALLBACK, not the default — the browser flow is offered first and
     * this is for when the redirect cannot get back. Qobuz wants the password
     * MD5-hashed, which is what the LMS plugin does too; the plaintext never
     * leaves this function.
     */
    fun login(username: String, password: String, alreadyHashed: Boolean = false): QobuzSession {
        require(username.isNotEmpty() && password.isNotEmpty()) {
            "A Qobuz email and password are required"
        }
        val hash = if (alreadyHashed) password else md5Hex(password)
        val r = request("user/login", mapOf("username" to username, "password" to hash))
        val token = r?.strOrNull("user_auth_token")
        val user = r?.objOrNull("user")
        val id = user?.strOrNull("id")
        if (token == null || id == null) {
            throw RuntimeException("Qobuz login failed — check the email and password")
        }
        session.token = token
        session.userId = id
        session.name = user.strOrNull("display_name") ?: user.strOrNull("login") ?: username
        return session
    }

    override fun me(): Account {
        val r = request("user/get",
            if (session.userId.isEmpty()) emptyMap() else mapOf("user_id" to session.userId))
        val u = r?.objOrNull("user") ?: r
        u?.strOrNull("id")?.let { session.userId = it }
        session.name = u?.strOrNull("display_name") ?: u?.strOrNull("login") ?: session.name
        return Account(session.userId, session.name)
    }

    // ------------------------------------------------------------------ reads

    /** Offset paging, with the section picked out by the caller because Qobuz
     *  names it differently on every endpoint. */
    private fun pageAll(
        endpoint: String, params: Map<String, Any?>, pick: (JSONObject?) -> List<JSONObject>,
        limit: Int = 500
    ): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var offset = 0
        while (true) {
            val r = request(endpoint, params + mapOf("limit" to limit, "offset" to offset))
            val items = pick(r)
            if (items.isEmpty()) break
            out.addAll(items)
            offset += items.size
            if (items.size < limit) break
            if (offset > 100000) break
        }
        return out
    }

    private fun section(r: JSONObject?, key: String): List<JSONObject> =
        r?.objOrNull(key)?.arrOrNull("items")?.objects()
            ?: r?.arrOrNull(key)?.objects()
            ?: emptyList()

    override fun playlists(): List<Playlist> =
        pageAll("playlist/getUserPlaylists", emptyMap(), { section(it, "playlists") }).map { p ->
            // Qobuz lists playlists somebody else made and this user
            // SUBSCRIBED to alongside their own, indistinguishable except by
            // owner. Ownership is reported and the UI lets the user decide.
            Playlist(
                id = p.str("id"),
                name = p.str("name"),
                description = p.str("description"),
                isPublic = p.optBoolean("is_public", false),
                ownerId = p.objOrNull("owner")?.str("id").orEmpty(),
                trackCount = p.intOrNull("tracks_count") ?: 0
            )
        }

    override fun playlistTracks(playlistId: String): List<Track> =
        pageAll("playlist/get", mapOf("playlist_id" to playlistId, "extra" to "tracks"),
            { section(it, "tracks") }).mapNotNull { toQobuzTrack(it, null) }

    override fun savedTracks(): List<Track> =
        pageAll("favorite/getUserFavorites", mapOf("type" to "tracks"),
            { section(it, "tracks") }).mapNotNull { toQobuzTrack(it, null) }

    override fun savedAlbums(): List<Album> =
        pageAll("favorite/getUserFavorites", mapOf("type" to "albums"),
            { section(it, "albums") }).mapNotNull { toQobuzAlbum(it) }

    override fun followedArtists(): List<Artist> =
        pageAll("favorite/getUserFavorites", mapOf("type" to "artists"),
            { section(it, "artists") }).map { Artist(it.str("id"), it.str("name")) }

    // --------------------------------------------------------------- searches

    /**
     * Search by ISRC.
     *
     * Qobuz has no documented ISRC filter — unlike Spotify's `isrc:` — but it
     * does index the code, so a plain query usually returns the right track.
     * "Usually" is not good enough alone, which is why this returns CANDIDATES
     * rather than an answer: Match compares the ISRCs itself and only accepts
     * a real equality. A near-miss costs one wasted request, never a wrong
     * match.
     */
    override fun searchByIsrc(isrc: String): List<Track> =
        section(request("catalog/search",
            mapOf("query" to isrc, "type" to "tracks", "limit" to 10)), "tracks")
            .mapNotNull { toQobuzTrack(it, null) }

    override fun searchTracks(title: String, artist: String): List<Track> =
        section(request("catalog/search", mapOf(
            "query" to listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" "),
            "type" to "tracks", "limit" to 12)), "tracks")
            .mapNotNull { toQobuzTrack(it, null) }

    /**
     * Albums by barcode. Same caveat as [searchByIsrc]: Qobuz has no
     * documented barcode filter but does index the code.
     *
     * No stamping needed on this side — unlike Spotify's, Qobuz's album
     * listings DO carry `upc`, so Match compares the real codes itself and a
     * near-miss costs one wasted request rather than a wrong match.
     */
    override fun searchByUpc(upc: String): List<Album> {
        val code = upc.trim()
        if (code.isEmpty()) return emptyList()
        return section(request("catalog/search",
            mapOf("query" to code, "type" to "albums", "limit" to 10)), "albums")
            .mapNotNull { toQobuzAlbum(it) }
    }

    override fun searchAlbums(title: String, artist: String): List<Album> =
        section(request("catalog/search", mapOf(
            "query" to listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" "),
            "type" to "albums", "limit" to 12)), "albums")
            .mapNotNull { toQobuzAlbum(it) }

    override fun searchArtists(name: String): List<Artist> =
        section(request("catalog/search",
            mapOf("query" to name, "type" to "artists", "limit" to 10)), "artists")
            .map { Artist(it.str("id"), it.str("name")) }

    /** Qobuz's album listing already carries the barcode, so unlike Spotify
     *  this never needs a second request — it exists to keep the interfaces
     *  identical for Migration. */
    override fun albumDetail(albumId: String): Album? =
        toQobuzAlbum(request("album/get", mapOf("album_id" to albumId)))

    // ----------------------------------------------------------------- writes

    override fun createPlaylist(name: String, description: String, isPublic: Boolean): Playlist {
        val r = request("playlist/create", mapOf(
            "name" to name.take(200),
            "description" to description.take(500),
            // Private by default, always — the same rule as the Spotify side.
            // A migration is not a publication.
            "is_public" to if (isPublic) "true" else "false",
            "is_collaborative" to "false"
        ), "POST")
        val id = r?.strOrNull("id")
            ?: throw RuntimeException("Qobuz did not return the new playlist")
        return Playlist(id = id, name = r.str("name"))
    }

    /**
     * Batched at 50 rather than any endpoint limit, because these ids go in
     * the QUERY STRING: a thousand of them is a 9KB URL, and the failure when
     * a server decides that is too long is a 414 that names nothing.
     */
    override fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        for (batch in chunk(trackIds, 50)) {
            request("playlist/addTracks",
                mapOf("playlist_id" to playlistId, "track_ids" to batch.joinToString(",")),
                "POST")
        }
    }

    override fun saveTracks(trackIds: List<String>) {
        for (batch in chunk(trackIds, 50)) {
            request("favorite/create",
                mapOf("type" to "tracks", "track_ids" to batch.joinToString(",")), "POST")
        }
    }

    override fun saveAlbums(albumIds: List<String>) {
        for (batch in chunk(albumIds, 50)) {
            request("favorite/create",
                mapOf("type" to "albums", "album_ids" to batch.joinToString(",")), "POST")
        }
    }

    override fun followArtists(artistIds: List<String>) {
        for (batch in chunk(artistIds, 50)) {
            request("favorite/create",
                mapOf("type" to "artists", "artist_ids" to batch.joinToString(",")), "POST")
        }
    }
}

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
