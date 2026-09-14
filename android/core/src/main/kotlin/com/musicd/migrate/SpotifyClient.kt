package com.musicd.migrate

import org.json.JSONObject

/*
 * SpotifyClient.kt — the Spotify Web API, as much of it as migrating needs.
 * The Kotlin twin of lib/spotify.js.
 *
 * THE RATE LIMIT IS THE DESIGN CONSTRAINT, exactly as it is on the JavaScript
 * side. Spotify answers 429 with a Retry-After, and a migration is thousands
 * of requests. So every 429 is obeyed with the delay Spotify asked for rather
 * than a guess; every write is batched to the endpoint's maximum (100 playlist
 * tracks, 50 saved tracks, 50 albums, 50 artists); every read pages at the
 * maximum. A caller that hand-rolled any of it would work on a test account
 * and fall over on a real library.
 */

data class SpotifySession(
    val clientId: String = "",
    var accessToken: String = "",
    var refreshToken: String = "",
    var expiresAt: Long = 0,
    var userId: String = "",
    var name: String = ""
)

class SpotifyClient(
    val session: SpotifySession,
    private val http: Http = UrlConnectionHttp(),
    private val onTokens: (SpotifySession) -> Unit = {},
    private val onRateLimit: (Long) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) : MusicTarget {

    override val serviceName = "spotify"
    override val accountId: String get() = session.userId

    companion object {
        const val API = "https://api.spotify.com/v1"

        /** How many 429s in a row to sit through before giving up on one
         *  request. */
        const val MAX_RETRIES = 5

        /** Spotify has been known to ask for minutes. Past this, fail honestly
         *  rather than hold a migration open for an hour pretending to work. */
        const val MAX_RETRY_WAIT_MS = 60_000L

        /**
         * How many results a `upc:` search may return and still be trusted as
         * a barcode lookup. A barcode identifies one release; a crowd means
         * the filter is not filtering. See searchByUpc.
         */
        const val UPC_TRUST_LIMIT = 3

        /**
         * Trade the one-time code for tokens. No client secret — see Pkce.
         */
        fun exchangeCode(
            http: Http, clientId: String, code: String, redirectUri: String, verifier: String
        ): SpotifySession = tokenRequest(http, mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier
        ), clientId)

        /**
         * A fresh access token from the refresh token.
         *
         * Spotify ROTATES refresh tokens: the response may carry a new one,
         * and when it does the old one stops working. Carrying the old one
         * forward when none came back — rather than leaving the caller to
         * notice — is what stops a long-lived install losing its sign-in.
         */
        fun refresh(http: Http, clientId: String, refreshToken: String): SpotifySession {
            val out = tokenRequest(http, mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId
            ), clientId)
            if (out.refreshToken.isEmpty()) out.refreshToken = refreshToken
            return out
        }

        private fun tokenRequest(
            http: Http, form: Map<String, Any?>, clientId: String
        ): SpotifySession {
            val res = try {
                http.request("POST", Pkce.TOKEN_URL,
                    body = query(form).toByteArray(Charsets.UTF_8),
                    contentType = "application/x-www-form-urlencoded", timeoutMs = 20000)
            } catch (e: Exception) {
                throw RuntimeException("Could not reach Spotify to sign in: ${e.message}")
            }
            val data = parseObject(res.body)
            if (!res.ok) {
                // Spotify's token errors are genuinely informative and the
                // user can act on most of them — a wrong client id, an
                // unregistered redirect URI, a code already spent. Passing the
                // description through beats "HTTP 400".
                val desc = data?.strOrNull("error_description")
                    ?: data?.strOrNull("error")
                    ?: res.body.take(200)
                throw RuntimeException("Spotify refused the sign-in: $desc")
            }
            val token = data?.strOrNull("access_token")
                ?: throw RuntimeException("Spotify returned no access token")
            return SpotifySession(
                clientId = clientId,
                accessToken = token,
                refreshToken = data.str("refresh_token"),
                // 60s of slack: a token that expires mid-flight comes back as
                // a 401 that looks exactly like a revoked sign-in.
                expiresAt = System.currentTimeMillis() +
                    ((data.longOrNull("expires_in") ?: 3600L) - 60L) * 1000L
            )
        }
    }

    private fun accessToken(): String {
        if (session.accessToken.isNotEmpty() && session.expiresAt > System.currentTimeMillis()) {
            return session.accessToken
        }
        if (session.refreshToken.isEmpty()) throw AuthError("Not signed in to Spotify.")
        val fresh = refresh(http, session.clientId, session.refreshToken)
        session.accessToken = fresh.accessToken
        session.refreshToken = fresh.refreshToken
        session.expiresAt = fresh.expiresAt
        onTokens(session)
        return session.accessToken
    }

    /**
     * One request, with the whole rate-limit and expiry story handled.
     *
     * The forced refresh on a 401 exists because a token can be revoked
     * between the expiry check and the request landing — the user signed out
     * elsewhere, or changed their password. One refresh distinguishes that
     * from a dead sign-in; doing it twice would loop.
     */
    fun request(method: String, path: String, params: Map<String, Any?> = emptyMap(),
                body: String? = null): JSONObject? {
        var attempt = 0
        var refreshed = false

        while (true) {
            val token = accessToken()
            val qs = query(params)
            val url = API + path + if (qs.isEmpty()) "" else "?$qs"
            val res = try {
                http.request(method, url, mapOf("Authorization" to "Bearer $token"),
                    body?.toByteArray(Charsets.UTF_8), "application/json")
            } catch (e: Exception) {
                if (attempt++ < 2) { sleeper(500L * attempt); continue }
                throw RuntimeException("Could not reach Spotify: ${e.message}")
            }

            if (res.status == 429) {
                if (attempt++ >= MAX_RETRIES) {
                    throw RateLimitError("Spotify is rate limiting this app and did not let up.")
                }
                // Retry-After is in SECONDS and is authoritative. Guessing
                // shorter turns one 429 into a cascade of them.
                val waitS = res.header("retry-after")?.toLongOrNull() ?: 2L
                val waitMs = minOf(maxOf(waitS, 1L) * 1000L + 250L, MAX_RETRY_WAIT_MS)
                onRateLimit(waitMs)
                sleeper(waitMs)
                continue
            }

            if (res.status == 401 && !refreshed && session.refreshToken.isNotEmpty()) {
                refreshed = true
                session.expiresAt = 0 // force the refresh on the next loop
                continue
            }
            if (res.status == 401) throw AuthError("Spotify sign-in has expired — sign in again.")
            if (res.status == 403) {
                throw AuthError("Spotify refused that (403): ${messageFrom(res.body)}. " +
                    "If this is the first run after an update, sign in to Spotify again — " +
                    "a new permission was added.")
            }
            if (!res.ok) {
                throw RuntimeException("Spotify HTTP ${res.status}: ${messageFrom(res.body)}")
            }
            if (res.body.isEmpty()) return null // 204, which every write answers with
            return parseObject(res.body)
                ?: throw RuntimeException("Spotify returned an unexpected (non-JSON) response")
        }
    }

    /** Walk an offset-paged collection to the end. */
    private fun pageAll(
        path: String, params: Map<String, Any?> = emptyMap(), limit: Int = 50
    ): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var offset = 0
        while (true) {
            val page = request("GET", path, params + mapOf("limit" to limit, "offset" to offset))
            val items = page?.arrOrNull("items")?.objects().orEmpty()
            if (items.isEmpty()) break
            out.addAll(items)
            offset += items.size
            val total = page?.intOrNull("total")
            if (total != null && offset >= total) break
            if (items.size < limit) break
            if (offset > 100000) break // a runaway pager is a bug, not a big library
        }
        return out
    }

    // ------------------------------------------------------------------ reads

    override fun me(): Account {
        val j = request("GET", "/me")
        session.userId = j?.str("id").orEmpty()
        session.name = j?.strOrNull("display_name") ?: session.userId
        return Account(session.userId, session.name)
    }

    override fun playlists(): List<Playlist> = pageAll("/me/playlists").map { p ->
        Playlist(
            id = p.str("id"),
            name = p.str("name"),
            description = p.str("description"),
            isPublic = p.optBoolean("public", false),
            ownerId = p.objOrNull("owner")?.str("id").orEmpty(),
            trackCount = p.objOrNull("tracks")?.intOrNull("total") ?: 0
        )
    }

    override fun playlistTracks(playlistId: String): List<Track> {
        // `fields` trims the response hard. A 2,000-track playlist is megabytes
        // of JSON unfiltered, most of it album art URLs in three sizes each.
        val fields = "total,items(is_local,track(id,name,duration_ms,type," +
            "artists(name),album(name),external_ids(isrc)))"
        return pageAll("/playlists/${urlEncode(playlistId)}/tracks",
            mapOf("fields" to fields, "additional_types" to "track"), 100)
            .mapNotNull { fromPlaylistItem(it) }
    }

    override fun savedTracks(): List<Track> =
        pageAll("/me/tracks").mapNotNull { toTrack(it.objOrNull("track")) }

    override fun savedAlbums(): List<Album> =
        pageAll("/me/albums").mapNotNull { toAlbum(it.objOrNull("album")) }

    /**
     * Followed artists page by CURSOR, not offset — the one endpoint here that
     * does, so pageAll cannot be used. Passing an offset to it is silently
     * ignored and returns the first page forever.
     */
    override fun followedArtists(): List<Artist> {
        val out = ArrayList<Artist>()
        var after = ""
        while (true) {
            val params = linkedMapOf<String, Any?>("type" to "artist", "limit" to 50)
            if (after.isNotEmpty()) params["after"] = after
            val page = request("GET", "/me/following", params)
            val artists = page?.objOrNull("artists")
            val items = artists?.arrOrNull("items")?.objects().orEmpty()
            if (items.isEmpty()) break
            out.addAll(items.map { Artist(it.str("id"), it.str("name")) })
            after = artists?.objOrNull("cursors")?.str("after").orEmpty()
            if (after.isEmpty()) break
        }
        return out
    }

    // --------------------------------------------------------------- searches

    /** The definitive lookup: Spotify indexes ISRC and filters on it directly. */
    override fun searchByIsrc(isrc: String): List<Track> =
        request("GET", "/search", mapOf("q" to "isrc:$isrc", "type" to "track", "limit" to 10))
            ?.objOrNull("tracks")?.arrOrNull("items")?.objects()
            ?.mapNotNull { toTrack(it) }.orEmpty()

    override fun searchTracks(title: String, artist: String): List<Track> {
        // Field filters rather than a bag of words: `track:"x" artist:"y"`
        // makes Spotify match the title against titles instead of against
        // lyrics, album names and playlist descriptions, which a plain query
        // does.
        val q = "track:${quoteTerm(title)}" +
            if (artist.isNotEmpty()) " artist:${quoteTerm(artist)}" else ""
        return request("GET", "/search", mapOf("q" to q, "type" to "track", "limit" to 12))
            ?.objOrNull("tracks")?.arrOrNull("items")?.objects()
            ?.mapNotNull { toTrack(it) }.orEmpty()
    }

    /**
     * Albums by BARCODE, and the reason album matching went from
     * mostly-missing to mostly-found.
     *
     * Spotify's album SEARCH returns SimplifiedAlbumObject — no
     * `external_ids`, so no barcode — which meant every candidate from
     * [searchAlbums] carried `upc = ""` and Match's barcode tier compared a
     * real Qobuz code against an empty string for all of them. Everything fell
     * through to the title tiers, where the track-count gate then refused any
     * edition mismatch. Measured on a real library: 114 of 195 favourite
     * albums reported "not found".
     *
     * The `upc:` filter is the fix (documented for /search, albums only). What
     * comes back STILL has no barcode on it, so THE FILTER IS THE EVIDENCE: a
     * hit is Spotify asserting the album carries that code, and the code is
     * stamped onto the result so the tier can see it.
     *
     * Stamped only when the result set is SMALL. A barcode identifies one
     * release; if this returns a crowd the filter is not behaving like a
     * filter, and treating it as decisive would be exactly the confidently
     * wrong match this app refuses to make. Unstamped, they are judged on
     * title, artist and track count like anything else.
     */
    override fun searchByUpc(upc: String): List<Album> {
        val code = upc.trim()
        if (code.isEmpty()) return emptyList()
        val albums = request("GET", "/search",
            mapOf("q" to "upc:$code", "type" to "album", "limit" to 10))
            ?.objOrNull("albums")?.arrOrNull("items")?.objects()
            ?.mapNotNull { toAlbum(it) }.orEmpty()
        if (albums.isEmpty() || albums.size > UPC_TRUST_LIMIT) return albums
        return albums.map { it.copy(upc = code) }
    }

    override fun searchAlbums(title: String, artist: String): List<Album> {
        val q = "album:${quoteTerm(title)}" +
            if (artist.isNotEmpty()) " artist:${quoteTerm(artist)}" else ""
        return request("GET", "/search", mapOf("q" to q, "type" to "album", "limit" to 12))
            ?.objOrNull("albums")?.arrOrNull("items")?.objects()
            ?.mapNotNull { toAlbum(it) }.orEmpty()
    }

    override fun searchArtists(name: String): List<Artist> =
        request("GET", "/search",
            mapOf("q" to "artist:${quoteTerm(name)}", "type" to "artist", "limit" to 10))
            ?.objOrNull("artists")?.arrOrNull("items")?.objects()
            ?.map { Artist(it.str("id"), it.str("name")) }.orEmpty()

    override fun albumDetail(albumId: String): Album? =
        toAlbum(request("GET", "/albums/${urlEncode(albumId)}"))

    /**
     * An album's tracks.
     *
     * A SimplifiedTrackObject, so it carries no ISRC and no album block --
     * fine for what this is for, which is comparing TITLES against the track
     * listing of an album on the other side. Nothing here is migrated as a
     * track.
     */
    override fun albumTracks(albumId: String): List<Track> =
        pageAll("/albums/${urlEncode(albumId)}/tracks").mapNotNull { toTrack(it) }

    // ----------------------------------------------------------------- writes

    override fun createPlaylist(name: String, description: String, isPublic: Boolean): Playlist {
        if (session.userId.isEmpty()) me()
        val body = JSONObject()
            .put("name", name.take(100))
            // Private by default, always. Someone migrating their library has
            // not asked to publish it.
            .put("public", isPublic)
            .put("description", description.take(300))
            .toString()
        val j = request("POST", "/users/${urlEncode(session.userId)}/playlists", body = body)
            ?: throw RuntimeException("Spotify did not return the new playlist")
        val id = j.strOrNull("id")
            ?: throw RuntimeException("Spotify did not return the new playlist")
        return Playlist(id = id, name = j.str("name"))
    }

    override fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        for (batch in chunk(trackIds, 100)) {
            val uris = org.json.JSONArray(batch.map { "spotify:track:$it" })
            request("POST", "/playlists/${urlEncode(playlistId)}/tracks",
                body = JSONObject().put("uris", uris).toString())
        }
    }

    override fun saveTracks(trackIds: List<String>) {
        for (batch in chunk(trackIds, 50)) {
            request("PUT", "/me/tracks",
                body = JSONObject().put("ids", org.json.JSONArray(batch)).toString())
        }
    }

    override fun saveAlbums(albumIds: List<String>) {
        for (batch in chunk(albumIds, 50)) {
            request("PUT", "/me/albums",
                body = JSONObject().put("ids", org.json.JSONArray(batch)).toString())
        }
    }

    override fun followArtists(artistIds: List<String>) {
        for (batch in chunk(artistIds, 50)) {
            // ids go in the QUERY for this one, not the body, unlike every
            // other write here. Sending them in the body succeeds with a 204
            // and follows nobody, which is the worst possible way for an API
            // to disagree.
            request("PUT", "/me/following",
                mapOf("type" to "artist", "ids" to batch.joinToString(",")),
                body = JSONObject().put("ids", org.json.JSONArray(batch)).toString())
        }
    }
}

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
