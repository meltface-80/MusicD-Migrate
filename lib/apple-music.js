"use strict";
/*
 * apple-music.js — the Apple Music API, as much of it as migrating needs.
 *
 * The OFFICIAL API, like Spotify's and unlike Qobuz's. It needs two tokens on
 * every request, and they come from two different people:
 *
 *   - a DEVELOPER TOKEN, an ES256 JWT signed with a MusicKit private key from
 *     an Apple Developer account. That key is the user's own: it cannot be
 *     baked into this repository or the APK the way Spotify's Client ID is,
 *     because a Client ID is public by design and this is a private key. So
 *     the user pastes their .p8 key, Team ID and Key ID, and this file signs
 *     a fresh token whenever it needs one. Nothing else ever sees the key.
 *
 *   - a MUSIC USER TOKEN, which says "this person allowed it". There is no
 *     OAuth redirect for it: Apple hands it to MusicKit JS running in a
 *     browser, after the user signs in on Apple's own window. index.js and
 *     MigrateApi.kt serve the page that does that and accept the token back.
 *     It lasts about six months and cannot be refreshed — a 401 or 403 means
 *     "connect again", and says so.
 *
 * LIBRARY IDS AND CATALOG IDS ARE DIFFERENT THINGS. A song in somebody's
 * library is "i.abc123"; the same song in the catalogue is "1613600188". Every
 * write here takes CATALOG ids, every search returns them, and the target's
 * playlist read is compared against search results — so every read here
 * reports the catalogue id wherever the library item has one (`include=
 * catalog`), and falls back to the library id only for something the user
 * uploaded themselves, which has no catalogue entry at all. The ISRC and the
 * barcode live on the catalogue object too, which is the other reason to ask
 * for it.
 *
 * THERE IS NO "FOLLOW AN ARTIST". The API can add albums, songs and playlists
 * to a library and has no follow at all, so artists are declared `unsupported`
 * in both directions and the run RECORDS that rather than reporting nothing.
 * The library's artist list is not a substitute: it is every artist of every
 * song the user owns, and "follow all of them on Spotify" is not something
 * anybody asked for.
 *
 * EVERYTHING LEAVES HERE IN THIS APP'S SHAPE — the same functions returning
 * the same shapes as lib/spotify.js and lib/qobuz.js. Kept in step with
 * AppleMusicClient.kt by hand.
 */

const crypto = require("crypto");

const API = "https://api.music.apple.com";

/** The MusicKit JS the sign-in page loads. Apple's own CDN, version 3. */
const MUSICKIT_JS = "https://js-cdn.music.apple.com/musickit/v3/musickit.js";

/**
 * How long a signed developer token lives. Apple allows six months; a short
 * one costs nothing because it is re-signed on demand, and a token that leaks
 * out of a browser tab is then worth a day rather than half a year.
 */
const TOKEN_TTL_S = 24 * 3600;
/** Re-signed this long before it expires, so none expires in flight. */
const TOKEN_SLACK_S = 3600;
/**
 * The token handed to MusicKit JS in a browser for the sign-in. An hour: the
 * sign-in takes a minute, and a tab is the one place a token can leak from.
 */
const BROWSER_TOKEN_TTL_S = 3600;

/** How many 429s in a row to sit through before giving up on one request. */
const MAX_RETRIES = 5;
/** Past this, fail honestly rather than hold a migration open pretending. */
const MAX_RETRY_WAIT_MS = 60_000;

/*
 * Pacing, exactly as in lib/spotify.js and for the same reason: obeying a 429
 * is not enough, the client must stop earning them. Apple does not publish
 * its limit, so the gap starts at zero and only grows once Apple complains.
 */
const PACE_START_MS = 250;
const PACE_MAX_MS = 2_000;
const PACE_DECAY_AFTER = 50;

/*
 * Batch sizes. Apple documents no maximum for either write. The library add
 * carries its ids in the QUERY STRING, so it is kept to the 50 every other
 * library write in this app uses; a playlist add carries them in the body and
 * matches Spotify's 100.
 */
const LIBRARY_BATCH = 50;
const PLAYLIST_BATCH = 100;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------ the key

/**
 * Check what the user pasted, and say what is wrong in words they can act on.
 *
 * @returns {{teamId, keyId, privateKey}} trimmed
 * @throws Error naming the field
 */
function checkKey(k) {
  const o = k || {};
  const teamId = String(o.teamId || "").trim().toUpperCase();
  const keyId = String(o.keyId || "").trim().toUpperCase();
  const privateKey = String(o.privateKey || "").trim();
  if (!/^[A-Z0-9]{10}$/.test(teamId)) {
    throw new Error("A Team ID is 10 letters and numbers. It is in the top right of " +
      "developer.apple.com/account, under your name.");
  }
  if (!/^[A-Z0-9]{10}$/.test(keyId)) {
    throw new Error("A Key ID is 10 letters and numbers. It is shown next to the key " +
      "under Certificates, Identifiers & Profiles → Keys.");
  }
  let key;
  try {
    key = crypto.createPrivateKey(privateKey);
  } catch (e) {
    throw new Error("That is not a private key. Paste the whole of the .p8 file, " +
      "including the BEGIN PRIVATE KEY and END PRIVATE KEY lines.");
  }
  if (key.asymmetricKeyType !== "ec" ||
      (key.asymmetricKeyDetails && key.asymmetricKeyDetails.namedCurve !== "prime256v1")) {
    throw new Error("That key is not a MusicKit key. A MusicKit key is an EC P-256 " +
      "key in a .p8 file.");
  }
  return { teamId, keyId, privateKey };
}

/**
 * Sign a developer token.
 *
 * ES256, so the signature is the raw 64-byte r‖s that JOSE wants — NOT the
 * DER encoding crypto.sign produces by default, which Apple rejects with a 401
 * that reads exactly like a bad key. `dsaEncoding` is the whole difference.
 */
function developerToken(k, nowS, ttlS) {
  const iat = Math.floor(nowS === undefined ? Date.now() / 1000 : nowS);
  const ttl = ttlS || TOKEN_TTL_S;
  const b64 = (v) => Buffer.from(JSON.stringify(v)).toString("base64url");
  const head = b64({ alg: "ES256", kid: k.keyId, typ: "JWT" });
  const body = b64({ iss: k.teamId, iat, exp: iat + ttl });
  const sig = crypto.sign("sha256", Buffer.from(head + "." + body),
    { key: k.privateKey, dsaEncoding: "ieee-p1363" });
  return { token: head + "." + body + "." + sig.toString("base64url"),
           expiresAt: (iat + ttl) * 1000 };
}

// ------------------------------------------------------------------- client

class AppleMusic {
  /**
   * @param {object} session {teamId, keyId, privateKey, userToken, storefront}
   * @param {object} [opts] {fetch, onRateLimit}
   */
  constructor(session, opts) {
    this.session = Object.assign({}, session || {});
    this.opts = opts || {};
    this.fetch = this.opts.fetch || globalThis.fetch;
    this._dev = null;
    this._holdUntil = 0;
    this._paceMs = 0;
    this._nextSlot = 0;
    this._sinceThrottle = 0;
  }

  /** Apple names no account to the API; every library playlist is the user's
   *  own or one they subscribed to, told apart by `canEdit`. */
  get userId() { return ""; }

  /**
   * Things this service will not do, recorded as a skipped row with the
   * reason. Checked by the engine on the source AND on the target.
   */
  get unsupported() {
    return {
      artists: "Apple Music has no way to follow an artist — its API can add albums, " +
        "songs and playlists to a library and nothing else — so followed artists are " +
        "not moved to or from it.",
    };
  }

  developerToken() {
    if (!this._dev || this._dev.expiresAt - TOKEN_SLACK_S * 1000 <= Date.now()) {
      this._dev = developerToken(this.session);
    }
    return this._dev.token;
  }

  /** The user's country store, which every catalogue path names. Asked once. */
  async storefront() {
    if (this.session.storefront) return this.session.storefront;
    const j = await this.request("GET", "/v1/me/storefront");
    const sf = j && j.data && j.data[0] && j.data[0].id;
    if (!sf) throw new Error("Apple Music did not say which country store this account uses");
    this.session.storefront = String(sf);
    return this.session.storefront;
  }

  async catalog(rest) {
    return "/v1/catalog/" + encodeURIComponent(await this.storefront()) + rest;
  }

  /**
   * One request, with the rate-limit story handled the way lib/spotify.js
   * handles it: one worker's 429 holds all of them, Retry-After is obeyed and
   * never shortened, and after the first 429 requests are paced.
   */
  async request(method, path, opts) {
    const o = opts || {};
    if (!this.session.userToken) throw authError("Connect Apple Music first.");
    let attempt = 0;

    for (;;) {
      const hold = this._holdUntil - Date.now();
      if (hold > 0) await sleep(hold);
      // Claimed synchronously, before the await, so four workers take four
      // different slots. A gap of 0 makes this a no-op.
      if (this._paceMs > 0) {
        const now = Date.now();
        const slot = Math.max(now, this._nextSlot);
        this._nextSlot = slot + this._paceMs;
        if (slot > now) await sleep(slot - now);
      }

      const url = API + path +
        (o.query ? (path.includes("?") ? "&" : "?") + new URLSearchParams(o.query).toString()
                 : "");
      const headers = {
        Authorization: "Bearer " + this.developerToken(),
        "Music-User-Token": this.session.userToken,
      };
      let body;
      if (o.body !== undefined) {
        headers["Content-Type"] = "application/json";
        body = JSON.stringify(o.body);
      }

      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), o.timeoutMs || 30000);
      let res, text;
      try {
        res = await this.fetch(url, { method, headers, body, signal: ctl.signal });
        text = await res.text();
      } catch (e) {
        clearTimeout(timer);
        if (attempt++ < 2) { await sleep(500 * attempt); continue; }
        throw new Error("Could not reach Apple Music: " + (e && e.message));
      } finally {
        clearTimeout(timer);
      }

      if (res.status === 429) {
        // Apple does not document Retry-After. When it sends one it is
        // obeyed and never shortened; when it does not, back off
        // exponentially, as for Qobuz.
        if (attempt++ >= MAX_RETRIES) {
          throw rateError("Apple Music is rate limiting this app and did not let up.");
        }
        const asked = Number(res.headers.get("retry-after"));
        const waitMs = asked > 0 ? asked * 1000 + 250 : Math.min(1000 * Math.pow(2, attempt), 30000);
        if (waitMs > MAX_RETRY_WAIT_MS) {
          throw rateError("Apple Music asked this app to wait " + Math.round(waitMs / 1000) +
            "s before its next request, which is longer than a migration will hold " +
            "open. Wait and re-run. Nothing already matched has been lost and none of " +
            "this was cached.");
        }
        this._paceMs = Math.min(Math.max(this._paceMs * 2, PACE_START_MS), PACE_MAX_MS);
        this._sinceThrottle = 0;
        // The top of the loop sleeps on the hold; sleeping here too waits twice.
        this._holdUntil = Date.now() + waitMs;
        if (this.opts.onRateLimit) this.opts.onRateLimit(waitMs);
        continue;
      }

      // Two different people's tokens can be refused, and the user fixes them
      // in two different places. Apple answers 401 for a bad DEVELOPER token
      // and 403 for a Music User Token that has expired or been revoked, or
      // an account with no Apple Music subscription.
      if (res.status === 401) {
        throw authError("Apple Music refused the developer token (401). Check the Team " +
          "ID, Key ID and key, and that the key has MusicKit enabled.");
      }
      if (res.status === 403) {
        throw authError("Apple Music refused this account (403)" +
          (messageFrom(text) ? ": " + messageFrom(text) : "") + ". Connect Apple Music " +
          "again — the sign-in lasts about six months — and check the account has an " +
          "Apple Music subscription.");
      }
      if (res.status === 404) { const e = new Error("Not found on Apple Music"); e.code = 404; throw e; }

      if (this._paceMs > 0 && ++this._sinceThrottle >= PACE_DECAY_AFTER) {
        this._sinceThrottle = 0;
        this._paceMs = this._paceMs <= PACE_START_MS ? 0 : Math.floor(this._paceMs * 0.75);
      }

      if (!res.ok) {
        const e = new Error("Apple Music HTTP " + res.status +
          (messageFrom(text) ? ": " + messageFrom(text) : ""));
        e.code = res.status;
        throw e;
      }

      if (!text) return null; // 202 and 204, which every write answers with
      try { return JSON.parse(text); } catch (e) {
        throw new Error("Apple Music returned an unexpected (non-JSON) response");
      }
    }
  }

  /**
   * Walk a collection to the end by following `next`, mapping each page as it
   * arrives — see pageAll in lib/spotify.js for why that is not optional.
   * Apple pages by a `next` path rather than by a total, and the path it gives
   * already carries the offset, so the query is sent on the first page only.
   */
  async pageAll(path, query, map) {
    const out = [];
    let next = path;
    let q = query;
    for (let pages = 0; next; pages++) {
      const page = await this.request("GET", next, { query: q });
      const items = (page && page.data) || [];
      for (const item of items) {
        const mapped = map(item);
        if (mapped) out.push(mapped);
      }
      next = (page && page.next) || "";
      // Apple's `next` drops the query it was asked with except the offset,
      // so `include` and `limit` are put back on every page.
      q = next ? keepOnNext(query, next) : null;
      if (pages > 2000) break; // a runaway pager is a bug, not a big library
    }
    return out;
  }

  // ------------------------------------------------------------------ reads

  async me() {
    const sf = await this.storefront();
    return { id: "", name: "Apple Music (" + sf.toUpperCase() + ")" };
  }

  async playlists() {
    return this.pageAll("/v1/me/library/playlists", { limit: 100 }, (p) => {
      if (!p || !p.id) return null;
      const a = p.attributes || {};
      return {
        id: String(p.id),
        name: a.name || "",
        description: (a.description && a.description.standard) || "",
        public: !!a.isPublic,
        // A playlist the user cannot edit is one they added from the
        // catalogue — the equivalent of a followed playlist elsewhere.
        ownerId: a.canEdit === false ? "apple-music" : "",
        // Apple does not say how long a library playlist is without reading
        // it, and a request per playlist to fill a label is not worth it.
        trackCount: null,
      };
    });
  }

  async playlistTracks(playlistId) {
    return this.pageAll("/v1/me/library/playlists/" + encodeURIComponent(playlistId) +
      "/tracks", { include: "catalog", limit: 100 }, fromPlaylistItem);
  }

  /**
   * The library's songs.
   *
   * Apple Music has no separate "liked songs": the library IS the user's
   * collection of tracks, which is what Spotify's saved tracks and Qobuz's
   * favourite tracks are. It includes the songs of every album in the
   * library, so it is larger than a Spotify user would expect — the README
   * says so.
   */
  async savedTracks() {
    return this.pageAll("/v1/me/library/songs", { include: "catalog", limit: 100 }, toTrack);
  }

  async savedAlbums() {
    return this.pageAll("/v1/me/library/albums", { include: "catalog", limit: 100 }, toAlbum);
  }

  /** Never offered — see `unsupported`. Answers so the engine's read of the
   *  target's existing library is not a crash. */
  async followedArtists() { return []; }

  async albumDetail(albumId) {
    const id = String(albumId);
    const j = isLibraryId(id)
      ? await this.request("GET", "/v1/me/library/albums/" + encodeURIComponent(id),
          { query: { include: "catalog" } })
      : await this.request("GET", await this.catalog("/albums/" + encodeURIComponent(id)));
    return toAlbum(j && j.data && j.data[0]);
  }

  async albumTracks(albumId) {
    const id = String(albumId);
    const path = isLibraryId(id)
      ? "/v1/me/library/albums/" + encodeURIComponent(id) + "/tracks"
      : await this.catalog("/albums/" + encodeURIComponent(id) + "/tracks");
    return this.pageAll(path, isLibraryId(id) ? { limit: 100 } : null, toTrack);
  }

  // --------------------------------------------------------------- searches

  /** The definitive lookup: the catalogue filters on ISRC directly. */
  async searchByIsrc(isrc) {
    const j = await this.request("GET", await this.catalog("/songs"),
      { query: { "filter[isrc]": String(isrc).toUpperCase() } });
    return ((j && j.data) || []).map(toTrack).filter(Boolean);
  }

  /**
   * Plain text, like Qobuz: Apple's search has no field filters to AND
   * together, so a spelling difference costs ranking rather than every row.
   */
  async searchTracks(title, artist, limit = 25) {
    const j = await this.request("GET", await this.catalog("/search"),
      { query: { term: term(title, artist), types: "songs", limit } });
    return ((j && j.results && j.results.songs && j.results.songs.data) || [])
      .map(toTrack).filter(Boolean);
  }

  /**
   * Albums by BARCODE. Unlike Spotify's, Apple's answer carries the barcode
   * itself, so nothing is stamped: the tier compares the real code.
   */
  async searchByUpc(upc) {
    const code = String(upc || "").trim();
    if (!code) return [];
    const j = await this.request("GET", await this.catalog("/albums"),
      { query: { "filter[upc]": code } });
    return ((j && j.data) || []).map(toAlbum).filter(Boolean);
  }

  async searchAlbums(title, artist, limit = 25) {
    const j = await this.request("GET", await this.catalog("/search"),
      { query: { term: term(title, artist), types: "albums", limit } });
    return ((j && j.results && j.results.albums && j.results.albums.data) || [])
      .map(toAlbum).filter(Boolean);
  }

  async searchArtists(name, limit = 10) {
    const j = await this.request("GET", await this.catalog("/search"),
      { query: { term: String(name || ""), types: "artists", limit } });
    return ((j && j.results && j.results.artists && j.results.artists.data) || [])
      .map((a) => ({ id: String(a.id), name: (a.attributes && a.attributes.name) || "" }));
  }

  // ----------------------------------------------------------------- writes

  async createPlaylist(name, opts) {
    const o = opts || {};
    // No isPublic: a library playlist is private unless the user shares it,
    // and someone migrating their library has not asked to publish it.
    const j = await this.request("POST", "/v1/me/library/playlists", {
      body: { attributes: { name: String(name || "Migrated playlist").slice(0, 100),
                            description: String(o.description || "").slice(0, 300) } },
    });
    const made = j && j.data && j.data[0];
    if (!made || !made.id) throw new Error("Apple Music did not return the new playlist");
    return { id: String(made.id), name: (made.attributes && made.attributes.name) || "" };
  }

  async addToPlaylist(playlistId, trackIds) {
    for (const batch of chunk(trackIds, PLAYLIST_BATCH)) {
      await this.request("POST", "/v1/me/library/playlists/" + encodeURIComponent(playlistId) +
        "/tracks", { body: { data: batch.map((id) => ({
          id, type: isLibraryId(id) ? "library-songs" : "songs" })) } });
    }
  }

  async saveTracks(trackIds) { await this.addToLibrary("songs", trackIds); }

  async saveAlbums(albumIds) { await this.addToLibrary("albums", albumIds); }

  async followArtists() {
    // Unreachable through the engine, which refuses the step first. Loud if
    // anything else ever calls it: a silent no-op would report artists moved.
    throw new Error(this.unsupported.artists);
  }

  /**
   * Add catalogue items to the library. Apple ignores an id it cannot add
   * rather than failing the batch, which is why the engine's report — not
   * this call's success — is what says what moved.
   */
  async addToLibrary(type, ids) {
    for (const batch of chunk(ids, LIBRARY_BATCH)) {
      await this.request("POST", "/v1/me/library", { query: { ["ids[" + type + "]"]: batch.join(",") } });
    }
  }
}

// ------------------------------------------------------- shape conversion

/** The catalogue object behind a library item, when it has one. */
function catalogOf(item) {
  const rel = item && item.relationships && item.relationships.catalog;
  return (rel && Array.isArray(rel.data) && rel.data[0]) || null;
}

function isLibraryId(id) { return /^[ilp]\./.test(String(id)); }

/**
 * A song — catalogue or library — in this app's shape.
 *
 * The id is the CATALOGUE id whenever there is one, from the `catalog`
 * relationship or the playParams. See the note at the top of the file.
 */
function toTrack(s) {
  if (!s || !s.id) return null;
  if (s.type && !/songs$/.test(s.type)) return null;
  const cat = catalogOf(s);
  const a = (cat && cat.attributes) || {};
  const own = s.attributes || {};
  const pick = (k) => own[k] != null && own[k] !== "" ? own[k] : a[k];
  const play = own.playParams || {};
  const artist = pick("artistName");
  return {
    id: String((cat && cat.id) || play.catalogId || s.id),
    isrc: a.isrc || own.isrc || "",
    title: pick("name") || "",
    artists: artist ? [String(artist)] : [],
    album: pick("albumName") || "",
    durationMs: Number(pick("durationInMillis")) || null,
  };
}

function fromPlaylistItem(item) {
  if (!item) return null;
  if (/music-videos$/.test(String(item.type || ""))) {
    return { skip: "music video", title: (item.attributes && item.attributes.name) || "a video" };
  }
  return toTrack(item);
}

function toAlbum(al) {
  if (!al || !al.id) return null;
  const cat = catalogOf(al);
  const a = (cat && cat.attributes) || {};
  const own = al.attributes || {};
  const pick = (k) => own[k] != null && own[k] !== "" ? own[k] : a[k];
  const play = own.playParams || {};
  const artist = pick("artistName");
  return {
    id: String((cat && cat.id) || play.catalogId || al.id),
    upc: a.upc || own.upc || "",
    title: pick("name") || "",
    artists: artist ? [String(artist)] : [],
    trackCount: Number(pick("trackCount")) || null,
    year: Number(String(pick("releaseDate") || "").slice(0, 4)) || null,
  };
}

// ------------------------------------------------------------------ helpers

function term(title, artist) {
  return [title, artist].filter(Boolean).join(" ").trim();
}

/** The parts of the first page's query Apple's `next` does not carry. */
function keepOnNext(query, next) {
  if (!query) return null;
  const has = new URLSearchParams(String(next).split("?")[1] || "");
  const out = {};
  for (const [k, v] of Object.entries(query)) if (!has.has(k)) out[k] = v;
  return Object.keys(out).length ? out : null;
}

function chunk(arr, n) {
  const list = (arr || []).filter((v) => v != null && v !== "");
  const out = [];
  for (let i = 0; i < list.length; i += n) out.push(list.slice(i, i + n));
  return out;
}

function messageFrom(text) {
  try {
    const j = JSON.parse(text);
    const e = j && Array.isArray(j.errors) && j.errors[0];
    return (e && (e.detail || e.title)) || "";
  } catch (e) {
    return String(text || "").slice(0, 160);
  }
}

function authError(msg) { const e = new Error(msg); e.code = 401; e.auth = true; return e; }
function rateError(msg) { const e = new Error(msg); e.code = 429; return e; }

module.exports = { AppleMusic, checkKey, developerToken, toTrack, toAlbum, fromPlaylistItem,
                   isLibraryId, MUSICKIT_JS, API, TOKEN_TTL_S, BROWSER_TOKEN_TTL_S, LIBRARY_BATCH, PLAYLIST_BATCH };
