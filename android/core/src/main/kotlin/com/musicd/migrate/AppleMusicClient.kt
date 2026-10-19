package com.musicd.migrate

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/*
 * AppleMusicClient.kt — the Apple Music API, as much of it as migrating needs.
 *
 * The twin of lib/apple-music.js, kept in step with it by hand; read that
 * file's header for the whole argument. In short:
 *
 *   - every request carries a DEVELOPER TOKEN, an ES256 JWT signed here with
 *     the user's own MusicKit key, and a MUSIC USER TOKEN that MusicKit JS
 *     handed back after the user allowed it in Apple's window;
 *   - library ids ("i.…", "l.…", "p.…") are not catalogue ids, every write
 *     takes catalogue ids, so every read reports the catalogue id wherever
 *     the library item has one (`include=catalog`);
 *   - there is no "follow an artist", so artists are declared unsupported in
 *     both directions and the run records that.
 */

/** The user's MusicKit key. Validated by [AppleMusicKey.parse]. */
data class AppleMusicKey(val teamId: String, val keyId: String, val privateKey: String) {
    companion object {
        /**
         * Check what the user pasted, naming the field that is wrong.
         * The same three messages as checkKey in lib/apple-music.js.
         */
        fun parse(teamId: String, keyId: String, pem: String): AppleMusicKey {
            val team = teamId.trim().uppercase()
            val kid = keyId.trim().uppercase()
            if (!Regex("^[A-Z0-9]{10}$").matches(team)) {
                throw IllegalArgumentException("A Team ID is 10 letters and numbers. It is in " +
                    "the top right of developer.apple.com/account, under your name.")
            }
            if (!Regex("^[A-Z0-9]{10}$").matches(kid)) {
                throw IllegalArgumentException("A Key ID is 10 letters and numbers. It is " +
                    "shown next to the key under Certificates, Identifiers & Profiles → Keys.")
            }
            val key = AppleMusicKey(team, kid, pem.trim())
            key.signingKey() // throws with the message the user needs
            return key
        }
    }

    /** The key as something [Signature] can use, or a refusal in words. */
    internal fun signingKey(): PrivateKey {
        val body = privateKey
            .replace(Regex("-----(BEGIN|END) [A-Z ]*PRIVATE KEY-----"), "")
            .replace(Regex("\\s"), "")
        val der = try {
            if (!privateKey.contains("PRIVATE KEY") || body.isEmpty()) throw IllegalArgumentException()
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("That is not a private key. Paste the whole of the " +
                ".p8 file, including the BEGIN PRIVATE KEY and END PRIVATE KEY lines.")
        }
        val key = try {
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (e: Exception) {
            null
        }
        if (key !is ECPrivateKey || key.params.curve.field.fieldSize != 256) {
            throw IllegalArgumentException("That key is not a MusicKit key. A MusicKit key is " +
                "an EC P-256 key in a .p8 file.")
        }
        return key
    }
}

data class AppleMusicSession(
    val key: AppleMusicKey,
    val userToken: String,
    var storefront: String = ""
)

class AppleMusicClient(
    val session: AppleMusicSession,
    private val http: Http = UrlConnectionHttp(),
    private val onRateLimit: (Long) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() }
) : MusicTarget {

    override val serviceName = "apple"

    /** Apple names no account to the API; see [playlists] for how a followed
     *  playlist is told apart. */
    override val accountId: String get() = ""

    override val unsupported: Map<String, String> = mapOf("artists" to UNSUPPORTED_ARTISTS)

    companion object {
        const val API = "https://api.music.apple.com"

        /** See TOKEN_TTL_S in lib/apple-music.js. */
        const val TOKEN_TTL_S = 24 * 3600L
        const val TOKEN_SLACK_S = 3600L
        /** The token handed to MusicKit JS in a browser for the sign-in. */
        const val BROWSER_TOKEN_TTL_S = 3600L

        const val MAX_RETRIES = 5
        const val MAX_RETRY_WAIT_MS = 60_000L
        const val PACE_START_MS = 250L
        const val PACE_MAX_MS = 2_000L
        const val PACE_DECAY_AFTER = 50

        /** Apple documents no maximum for either write; see lib/apple-music.js. */
        const val LIBRARY_BATCH = 50
        const val PLAYLIST_BATCH = 100

        const val UNSUPPORTED_ARTISTS = "Apple Music has no way to follow an artist — its " +
            "API can add albums, songs and playlists to a library and nothing else — so " +
            "followed artists are not moved to or from it."

        /**
         * Sign a developer token.
         *
         * ES256, so the signature is the raw 64-byte r‖s that JOSE wants. The
         * JVM's SHA256withECDSA produces DER, which Apple rejects with a 401
         * that reads exactly like a bad key — hence [derToRaw].
         */
        fun developerToken(key: AppleMusicKey, nowS: Long, ttlS: Long = TOKEN_TTL_S): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val head = enc.encodeToString(JSONObject().put("alg", "ES256")
                .put("kid", key.keyId).put("typ", "JWT").toString().toByteArray())
            val body = enc.encodeToString(JSONObject().put("iss", key.teamId)
                .put("iat", nowS).put("exp", nowS + ttlS).toString().toByteArray())
            val sig = Signature.getInstance("SHA256withECDSA").run {
                initSign(key.signingKey())
                update("$head.$body".toByteArray())
                sign()
            }
            return "$head.$body." + enc.encodeToString(derToRaw(sig))
        }

        /** An ASN.1 SEQUENCE { INTEGER r, INTEGER s } as 32-byte r ‖ 32-byte s. */
        internal fun derToRaw(der: ByteArray): ByteArray {
            var i = 2
            if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
            fun int(): ByteArray {
                require(der[i].toInt() == 0x02) { "not a DER ECDSA signature" }
                val len = der[i + 1].toInt()
                val v = der.copyOfRange(i + 2, i + 2 + len)
                i += 2 + len
                val trimmed = v.dropWhile { it.toInt() == 0 }.toByteArray()
                return ByteArray(32 - trimmed.size) + trimmed
            }
            return int() + int()
        }

        internal fun isLibraryId(id: String): Boolean = Regex("^[ilp]\\.").containsMatchIn(id)
    }

    @Volatile private var devToken = ""
    @Volatile private var devExpiresAt = 0L

    @Volatile private var holdUntil = 0L
    @Volatile private var paceMs = 0L
    private val paceLock = Any()
    private var nextSlot = 0L
    private var sinceThrottle = 0
    internal fun paceMsForTest(): Long = paceMs

    @Synchronized private fun developerToken(): String {
        val now = clock()
        if (devToken.isEmpty() || devExpiresAt - TOKEN_SLACK_S * 1000 <= now) {
            devToken = developerToken(session.key, now / 1000)
            devExpiresAt = (now / 1000 + TOKEN_TTL_S) * 1000
        }
        return devToken
    }

    /** The user's country store, which every catalogue path names. Asked once. */
    fun storefront(): String {
        if (session.storefront.isNotEmpty()) return session.storefront
        val sf = request("GET", "/v1/me/storefront")?.arrOrNull("data")?.objects()
            ?.firstOrNull()?.strOrNull("id")
            ?: throw RuntimeException("Apple Music did not say which country store this " +
                "account uses")
        session.storefront = sf
        return sf
    }

    private fun catalog(rest: String): String = "/v1/catalog/${urlEncode(storefront())}$rest"

    /**
     * One request, with the rate-limit story handled as in SpotifyClient: one
     * worker's 429 holds all of them, Retry-After is obeyed and never
     * shortened, and after the first 429 requests are paced.
     */
    fun request(method: String, path: String, params: Map<String, Any?> = emptyMap(),
                body: String? = null): JSONObject? {
        if (session.userToken.isEmpty()) throw AuthError("Connect Apple Music first.")
        var attempt = 0
        while (true) {
            val hold = holdUntil - clock()
            if (hold > 0) sleeper(hold)
            if (paceMs > 0) {
                val wait = synchronized(paceLock) {
                    val now = clock()
                    val slot = maxOf(now, nextSlot)
                    nextSlot = slot + paceMs
                    slot - now
                }
                if (wait > 0) sleeper(wait)
            }

            val qs = query(params)
            val url = API + path + if (qs.isEmpty()) "" else (if ('?' in path) "&" else "?") + qs
            val res = try {
                http.request(method, url, mapOf(
                    "Authorization" to "Bearer ${developerToken()}",
                    "Music-User-Token" to session.userToken),
                    body?.toByteArray(Charsets.UTF_8), "application/json")
            } catch (e: Exception) {
                if (attempt++ < 2) { sleeper(500L * attempt); continue }
                throw RuntimeException("Could not reach Apple Music: ${e.message}")
            }

            if (res.status == 429) {
                // Apple does not document Retry-After. When it sends one it is
                // obeyed and never shortened; when not, back off exponentially.
                if (attempt++ >= MAX_RETRIES) {
                    throw RateLimitError("Apple Music is rate limiting this app and did not let up.")
                }
                val asked = res.header("retry-after")?.toDoubleOrNull() ?: 0.0
                val waitMs = if (asked > 0) (asked * 1000).toLong() + 250L
                             else minOf(1000L shl attempt, 30_000L)
                if (waitMs > MAX_RETRY_WAIT_MS) {
                    throw RateLimitError("Apple Music asked this app to wait " +
                        "${waitMs / 1000}s before its next request, which is longer than a " +
                        "migration will hold open. Wait and re-run. Nothing already matched " +
                        "has been lost and none of this was cached.")
                }
                synchronized(paceLock) {
                    paceMs = minOf(maxOf(paceMs * 2, PACE_START_MS), PACE_MAX_MS)
                    sinceThrottle = 0
                }
                // The top of the loop sleeps on the hold; sleeping here too
                // would wait twice.
                holdUntil = clock() + waitMs
                onRateLimit(waitMs)
                continue
            }

            // 401 is the DEVELOPER token; 403 is the user's sign-in or their
            // subscription. Fixed in two different places, so said apart.
            if (res.status == 401) {
                throw AuthError("Apple Music refused the developer token (401). Check the " +
                    "Team ID, Key ID and key, and that the key has MusicKit enabled.")
            }
            if (res.status == 403) {
                val detail = messageFromApple(res.body)
                throw AuthError("Apple Music refused this account (403)" +
                    (if (detail.isNotEmpty()) ": $detail" else "") + ". Connect Apple Music " +
                    "again — the sign-in lasts about six months — and check the account has " +
                    "an Apple Music subscription.")
            }

            if (paceMs > 0) {
                synchronized(paceLock) {
                    if (++sinceThrottle >= PACE_DECAY_AFTER) {
                        sinceThrottle = 0
                        paceMs = if (paceMs <= PACE_START_MS) 0L else paceMs * 3 / 4
                    }
                }
            }

            if (!res.ok) {
                val detail = messageFromApple(res.body)
                throw RuntimeException("Apple Music HTTP ${res.status}" +
                    if (detail.isNotEmpty()) ": $detail" else "")
            }
            if (res.body.isEmpty()) return null // 202 and 204, which every write answers
            return parseObject(res.body)
                ?: throw RuntimeException("Apple Music returned an unexpected (non-JSON) response")
        }
    }

    /**
     * Follow `next` to the end, mapping each page as it arrives — see pageAll
     * in SpotifyClient for why that is not optional. Apple's `next` carries
     * the offset and drops the rest of the query, so the rest is put back.
     */
    private fun <T> pageAll(path: String, params: Map<String, Any?>,
                            map: (JSONObject) -> T?): List<T> {
        val out = ArrayList<T>()
        var next = path
        var pages = 0
        while (next.isNotEmpty()) {
            val q = if (next == path) params else keepOnNext(params, next)
            val page = request("GET", next, q)
            for (item in page?.arrOrNull("data")?.objects().orEmpty()) map(item)?.let { out.add(it) }
            next = page?.str("next").orEmpty()
            if (++pages > 2000) break // a runaway pager is a bug, not a big library
        }
        return out
    }

    private fun keepOnNext(params: Map<String, Any?>, next: String): Map<String, Any?> {
        val has = next.substringAfter('?', "").split('&').map { it.substringBefore('=') }.toSet()
        return params.filterKeys { it !in has }
    }

    // ------------------------------------------------------------------ reads

    override fun me(): Account = Account("", "Apple Music (${storefront().uppercase()})")

    override fun playlists(): List<Playlist> =
        pageAll("/v1/me/library/playlists", mapOf("limit" to 100)) { p ->
            val a = p.objOrNull("attributes") ?: JSONObject()
            Playlist(
                id = p.strOrNull("id") ?: return@pageAll null,
                name = a.str("name"),
                description = a.objOrNull("description")?.str("standard").orEmpty(),
                isPublic = a.optBoolean("isPublic", false),
                // One the user cannot edit was added from the catalogue — the
                // equivalent of a followed playlist elsewhere.
                ownerId = if (a.has("canEdit") && !a.optBoolean("canEdit", true)) "apple-music"
                          else "",
                // Unknown without reading it; null rather than a wrong "0".
                trackCount = null
            )
        }

    override fun playlistTracks(playlistId: String): List<Track> =
        pageAll("/v1/me/library/playlists/${urlEncode(playlistId)}/tracks",
            mapOf("include" to "catalog", "limit" to 100)) { appleFromPlaylistItem(it) }

    /** The library's songs — Apple Music's collection of tracks. See savedTracks
     *  in lib/apple-music.js for why that is the right equivalent. */
    override fun savedTracks(): List<Track> =
        pageAll("/v1/me/library/songs", mapOf("include" to "catalog", "limit" to 100)) {
            appleTrack(it)
        }

    override fun savedAlbums(): List<Album> =
        pageAll("/v1/me/library/albums", mapOf("include" to "catalog", "limit" to 100)) {
            appleAlbum(it)
        }

    /** Never offered — see [unsupported]. */
    override fun followedArtists(): List<Artist> = emptyList()

    override fun albumDetail(albumId: String): Album? {
        val j = if (isLibraryId(albumId)) {
            request("GET", "/v1/me/library/albums/${urlEncode(albumId)}",
                mapOf("include" to "catalog"))
        } else {
            request("GET", catalog("/albums/${urlEncode(albumId)}"))
        }
        return appleAlbum(j?.arrOrNull("data")?.objects()?.firstOrNull())
    }

    override fun albumTracks(albumId: String): List<Track> =
        if (isLibraryId(albumId)) {
            pageAll("/v1/me/library/albums/${urlEncode(albumId)}/tracks",
                mapOf("limit" to 100)) { appleTrack(it) }
        } else {
            pageAll(catalog("/albums/${urlEncode(albumId)}/tracks"), emptyMap()) { appleTrack(it) }
        }

    // --------------------------------------------------------------- searches

    override fun searchByIsrc(isrc: String): List<Track> =
        request("GET", catalog("/songs"), mapOf("filter[isrc]" to isrc.uppercase()))
            ?.arrOrNull("data")?.objects()?.mapNotNull { appleTrack(it) }.orEmpty()

    /** Plain text, like Qobuz: no field filters to AND together. */
    override fun searchTracks(title: String, artist: String): List<Track> =
        request("GET", catalog("/search"),
            mapOf("term" to term(title, artist), "types" to "songs", "limit" to 25))
            ?.objOrNull("results")?.objOrNull("songs")?.arrOrNull("data")?.objects()
            ?.mapNotNull { appleTrack(it) }.orEmpty()

    /** Albums by barcode. Apple's answer carries the barcode itself, so
     *  nothing is stamped. */
    override fun searchByUpc(upc: String): List<Album> {
        val code = upc.trim()
        if (code.isEmpty()) return emptyList()
        return request("GET", catalog("/albums"), mapOf("filter[upc]" to code))
            ?.arrOrNull("data")?.objects()?.mapNotNull { appleAlbum(it) }.orEmpty()
    }

    override fun searchAlbums(title: String, artist: String): List<Album> =
        request("GET", catalog("/search"),
            mapOf("term" to term(title, artist), "types" to "albums", "limit" to 25))
            ?.objOrNull("results")?.objOrNull("albums")?.arrOrNull("data")?.objects()
            ?.mapNotNull { appleAlbum(it) }.orEmpty()

    override fun searchArtists(name: String): List<Artist> =
        request("GET", catalog("/search"),
            mapOf("term" to name, "types" to "artists", "limit" to 10))
            ?.objOrNull("results")?.objOrNull("artists")?.arrOrNull("data")?.objects()
            ?.map { Artist(it.str("id"), it.objOrNull("attributes")?.str("name").orEmpty()) }
            .orEmpty()

    // ----------------------------------------------------------------- writes

    override fun createPlaylist(name: String, description: String, isPublic: Boolean): Playlist {
        // No isPublic: a library playlist is private unless the user shares
        // it, and migrating a library is not asking to publish it.
        val body = JSONObject().put("attributes", JSONObject()
            .put("name", name.take(100)).put("description", description.take(300))).toString()
        val made = request("POST", "/v1/me/library/playlists", body = body)
            ?.arrOrNull("data")?.objects()?.firstOrNull()
        val id = made?.strOrNull("id")
            ?: throw RuntimeException("Apple Music did not return the new playlist")
        return Playlist(id = id, name = made.objOrNull("attributes")?.str("name").orEmpty())
    }

    override fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        for (batch in chunk(trackIds, PLAYLIST_BATCH)) {
            val data = JSONArray(batch.map {
                JSONObject().put("id", it).put("type", if (isLibraryId(it)) "library-songs" else "songs")
            })
            request("POST", "/v1/me/library/playlists/${urlEncode(playlistId)}/tracks",
                body = JSONObject().put("data", data).toString())
        }
    }

    override fun saveTracks(trackIds: List<String>) = addToLibrary("songs", trackIds)

    override fun saveAlbums(albumIds: List<String>) = addToLibrary("albums", albumIds)

    /** Unreachable through the engine, which refuses the step first. Loud if
     *  anything else ever calls it: a silent no-op would report artists moved. */
    override fun followArtists(artistIds: List<String>) {
        throw UnsupportedOperationException(UNSUPPORTED_ARTISTS)
    }

    /** Apple ignores an id it cannot add rather than failing the batch. */
    private fun addToLibrary(type: String, ids: List<String>) {
        for (batch in chunk(ids, LIBRARY_BATCH)) {
            request("POST", "/v1/me/library", mapOf("ids[$type]" to batch.joinToString(",")))
        }
    }

    private fun term(title: String, artist: String): String =
        listOf(title, artist).filter { it.isNotEmpty() }.joinToString(" ").trim()
}

// ---------------------------------------------------------- shape conversion

private fun catalogOf(item: JSONObject): JSONObject? =
    item.objOrNull("relationships")?.objOrNull("catalog")?.arrOrNull("data")?.objects()
        ?.firstOrNull()

/** A song — catalogue or library — in this app's shape, with the CATALOGUE
 *  id whenever there is one. Twin of toTrack in lib/apple-music.js. */
internal fun appleTrack(s: JSONObject?): Track? {
    if (s == null) return null
    val rawId = s.strOrNull("id") ?: return null
    val type = s.str("type")
    if (type.isNotEmpty() && !type.endsWith("songs")) return null
    val cat = catalogOf(s)
    val a = cat?.objOrNull("attributes") ?: JSONObject()
    val own = s.objOrNull("attributes") ?: JSONObject()
    fun pick(k: String) = own.strOrNull(k) ?: a.str(k)
    fun pickLong(k: String) = own.longOrNull(k) ?: a.longOrNull(k)
    val artist = pick("artistName")
    return Track(
        id = cat?.strOrNull("id") ?: own.objOrNull("playParams")?.strOrNull("catalogId") ?: rawId,
        isrc = a.strOrNull("isrc") ?: own.str("isrc"),
        title = pick("name"),
        artists = if (artist.isEmpty()) emptyList() else listOf(artist),
        album = pick("albumName"),
        durationMs = pickLong("durationInMillis")?.takeIf { it > 0 }
    )
}

internal fun appleFromPlaylistItem(item: JSONObject?): Track? {
    if (item == null) return null
    if (item.str("type").endsWith("music-videos")) {
        return Track(id = "video", title = item.objOrNull("attributes")?.strOrNull("name")
            ?: "a video", skip = "music video")
    }
    return appleTrack(item)
}

internal fun appleAlbum(al: JSONObject?): Album? {
    if (al == null) return null
    val rawId = al.strOrNull("id") ?: return null
    val cat = catalogOf(al)
    val a = cat?.objOrNull("attributes") ?: JSONObject()
    val own = al.objOrNull("attributes") ?: JSONObject()
    fun pick(k: String) = own.strOrNull(k) ?: a.str(k)
    val artist = pick("artistName")
    return Album(
        id = cat?.strOrNull("id") ?: own.objOrNull("playParams")?.strOrNull("catalogId") ?: rawId,
        upc = a.strOrNull("upc") ?: own.str("upc"),
        title = pick("name"),
        artists = if (artist.isEmpty()) emptyList() else listOf(artist),
        trackCount = (own.intOrNull("trackCount") ?: a.intOrNull("trackCount"))?.takeIf { it > 0 },
        year = pick("releaseDate").take(4).toIntOrNull()
    )
}

internal fun messageFromApple(text: String): String =
    parseObject(text)?.arrOrNull("errors")?.objects()?.firstOrNull()?.let {
        it.strOrNull("detail") ?: it.strOrNull("title")
    } ?: if (parseObject(text) != null) "" else text.take(160)
