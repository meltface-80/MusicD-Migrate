"use strict";
/*
 * spotify.js — the Spotify Web API, as much of it as migrating needs.
 *
 * This is the OFFICIAL API, unlike the Qobuz side: it is documented,
 * versioned, and it will not change under us without notice. What it does have
 * is a hard rate limit, and that shapes nearly every decision here.
 *
 * THE RATE LIMIT IS THE DESIGN CONSTRAINT. Spotify answers 429 with a
 * Retry-After, and a migration is thousands of requests — one search per
 * track that has no cached match. Three things follow, and all three are in
 * this file rather than in the caller:
 *
 *   - every 429 is obeyed, with the delay Spotify asked for, not a guess;
 *   - every write is batched to the endpoint's maximum (100 playlist tracks,
 *     50 saved tracks, 50 albums, 50 artists), because a 2,000-track playlist
 *     is 20 requests batched and 2,000 unbatched;
 *   - reads page at the maximum too.
 *
 * A caller that hand-rolled any of those would work fine on a test account and
 * fall over on a real library, which is the failure mode worth designing out.
 *
 * EVERYTHING LEAVES HERE IN THIS APP'S SHAPE, never Spotify's. `toTrack` and
 * friends are the only place that knows what `external_ids.isrc` is called, so
 * lib/match.js and the migration engine are written once against one shape and
 * work in both directions. That is also why the Qobuz client exposes exactly
 * the same functions returning exactly the same shapes.
 */

const PKCE = require("./spotify-pkce");

const API = "https://api.spotify.com/v1";

/** How many 429s in a row to sit through before giving up on one request. */
const MAX_RETRIES = 5;
/** Spotify has been known to ask for minutes. Past this, fail honestly rather
 *  than hold a migration open for an hour pretending to work. */
const MAX_RETRY_WAIT_MS = 60_000;

/**
 * How many results a `upc:` search may return and still be trusted as a
 * barcode lookup. A barcode identifies one release; a crowd means the filter
 * is not filtering, and nothing is stamped. See searchByUpc.
 */
const UPC_TRUST_LIMIT = 3;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --------------------------------------------------------------------- auth

/**
 * Trade the one-time code for tokens. No client secret — see spotify-pkce.js.
 * @returns {{accessToken, refreshToken, expiresAt, scope}}
 */
async function exchangeCode(opts) {
  const o = opts || {};
  return tokenRequest({
    grant_type: "authorization_code",
    code: String(o.code || ""),
    redirect_uri: String(o.redirectUri || ""),
    client_id: String(o.clientId || ""),
    code_verifier: String(o.verifier || ""),
  }, o);
}

/**
 * A fresh access token from the refresh token.
 *
 * Spotify rotates refresh tokens: the response MAY carry a new one, and when
 * it does the old one stops working. Returning `refreshToken` as the new one
 * when present and the old one when not — rather than leaving the caller to
 * notice — is what stops a long-running install silently losing its sign-in.
 */
async function refreshTokens(opts) {
  const o = opts || {};
  const out = await tokenRequest({
    grant_type: "refresh_token",
    refresh_token: String(o.refreshToken || ""),
    client_id: String(o.clientId || ""),
  }, o);
  if (!out.refreshToken) out.refreshToken = String(o.refreshToken || "");
  return out;
}

async function tokenRequest(form, opts) {
  const fetchFn = (opts && opts.fetch) || globalThis.fetch;
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), (opts && opts.timeoutMs) || 20000);
  let res, text;
  try {
    res = await fetchFn(PKCE.TOKEN_URL, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(form).toString(),
      signal: ctl.signal,
    });
    text = await res.text();
  } catch (e) {
    throw new Error("Could not reach Spotify to sign in: " + (e && e.message));
  } finally {
    clearTimeout(timer);
  }

  let data = null;
  try { data = JSON.parse(text); } catch (e) { data = null; }

  if (!res.ok) {
    // Spotify's token errors are genuinely informative and the user can act on
    // most of them — a wrong client id, a redirect URI that is not registered,
    // a code already spent. Passing the description through beats "HTTP 400".
    const desc = (data && (data.error_description || data.error)) || text.slice(0, 200);
    const e = new Error("Spotify refused the sign-in: " + desc);
    e.code = res.status;
    throw e;
  }
  if (!data || !data.access_token) throw new Error("Spotify returned no access token");
  return {
    accessToken: String(data.access_token),
    refreshToken: data.refresh_token ? String(data.refresh_token) : "",
    // 60s of slack: a token that expires while a request is in flight comes
    // back as a 401 that looks exactly like a revoked sign-in.
    expiresAt: Date.now() + (Number(data.expires_in || 3600) - 60) * 1000,
    scope: String(data.scope || ""),
  };
}

// ------------------------------------------------------------------- client

class Spotify {
  /**
   * @param {object} session {clientId, accessToken, refreshToken, expiresAt}
   * @param {object} [opts]
   * @param {function} [opts.onTokens] called whenever tokens change, so the
   *   caller can persist them. A refresh that is not persisted means the next
   *   run starts from an expired token and, if Spotify rotated the refresh
   *   token, cannot recover at all.
   */
  constructor(session, opts) {
    this.session = Object.assign({}, session || {});
    this.opts = opts || {};
    this.fetch = this.opts.fetch || globalThis.fetch;
    this.onTokens = this.opts.onTokens || (() => {});
  }

  get userId() { return this.session.userId || ""; }

  /**
   * A valid access token, refreshing at most ONCE however many callers ask.
   *
   * The sharing is the point, not an optimisation. Spotify ROTATES refresh
   * tokens: the first refresh invalidates the one it was given. Lookups run
   * concurrently, so with an expired token every worker in flight reaches
   * here at the same moment, and without this they each refresh with the same
   * now-single-use token. One wins; the others are told the token is revoked,
   * and the loser's answer can overwrite the winner's — which does not fail a
   * request, it **destroys the sign-in**, with no way back but signing in
   * again.
   *
   * A Qobuz `user_auth_token` does not expire or rotate, so this cannot
   * happen on that side; it is exactly the difference between a run into
   * Qobuz and a run into Spotify. An hour is also all it takes to reach: the
   * access token lasts one, and a ten thousand album library does not.
   */
  async accessToken() {
    const s = this.session;
    if (s.accessToken && Number(s.expiresAt || 0) > Date.now()) return s.accessToken;
    if (!s.refreshToken) throw authError("Not signed in to Spotify.");
    // Already refreshing: wait for that one rather than starting another.
    if (this._refreshing) return this._refreshing;
    const p = (async () => {
      const fresh = await refreshTokens({
        clientId: s.clientId, refreshToken: s.refreshToken, fetch: this.fetch,
      });
      Object.assign(this.session, fresh);
      this.onTokens(Object.assign({}, this.session));
      return this.session.accessToken;
    })();
    this._refreshing = p;
    try {
      return await p;
    } finally {
      if (this._refreshing === p) this._refreshing = null;
    }
  }

  /**
   * One request, with the whole rate-limit and expiry story handled.
   *
   * `retryOn401` exists because a token can be revoked between the expiry
   * check above and the request landing — the user signed out in the Spotify
   * app, or changed their password. One forced refresh distinguishes that from
   * a genuinely dead sign-in, and doing it twice would loop.
   */
  async request(method, path, opts) {
    const o = opts || {};
    let attempt = 0;
    let refreshed = false;

    for (;;) {
      const token = await this.accessToken();
      const url = API + path + (o.query ? "?" + new URLSearchParams(o.query).toString() : "");
      const headers = { Authorization: "Bearer " + token };
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
        throw new Error("Could not reach Spotify: " + (e && e.message));
      } finally {
        clearTimeout(timer);
      }

      if (res.status === 429) {
        if (attempt++ >= MAX_RETRIES) {
          throw rateError("Spotify is rate limiting this app and did not let up.");
        }
        // Retry-After is in SECONDS and is authoritative. Guessing shorter is
        // what turns one 429 into a cascade of them.
        const waitS = Number(res.headers.get("retry-after") || 2);
        const waitMs = Math.min(Math.max(waitS, 1) * 1000 + 250, MAX_RETRY_WAIT_MS);
        if (this.opts.onRateLimit) this.opts.onRateLimit(waitMs);
        await sleep(waitMs);
        continue;
      }

      if (res.status === 401 && !refreshed && this.session.refreshToken) {
        refreshed = true;
        this.session.expiresAt = 0; // force the refresh on the next loop
        continue;
      }

      if (res.status === 401) throw authError("Spotify sign-in has expired — sign in again.");
      if (res.status === 403) {
        const detail = messageFrom(text);
        throw authError("Spotify refused that (403)" + (detail ? ": " + detail : "") +
          ". If this is the first run after an update, sign in to Spotify again — " +
          "a new permission was added.");
      }
      if (res.status === 404) { const e = new Error("Not found on Spotify"); e.code = 404; throw e; }

      if (!res.ok) {
        const e = new Error("Spotify HTTP " + res.status +
          (messageFrom(text) ? ": " + messageFrom(text) : ""));
        e.code = res.status;
        throw e;
      }

      if (!text) return null; // 204, which every write here answers with
      try { return JSON.parse(text); } catch (e) {
        throw new Error("Spotify returned an unexpected (non-JSON) response");
      }
    }
  }

  /**
   * Walk an offset-paged collection to the end, keeping only what `map`
   * returns.
   *
   * The mapper is not a convenience: it is what stops this holding the whole
   * library's raw JSON at once. Spotify's `/me/albums` returns the FULL album
   * object, and a full album object carries `available_markets` — about 180
   * country codes — on the album AND on every one of its tracks. That is
   * roughly 15KB of JSON for one saved album.
   *
   * An earlier version collected the raw items and let the caller map them
   * afterwards, so every page stayed live until the walk finished. Node's
   * heap is big enough to hide that; the APK's is not, and on a real phone it
   * ended the run before it had matched anything:
   *
   *     the app hit a OutOfMemoryError: … growth limit 268435456; giving up
   *     on allocation because <1% of heap free after GC
   *
   * Kept in step with pageAll in SpotifyClient.kt by hand.
   */
  async pageAll(path, query, pick, limit, map) {
    const out = [];
    let offset = 0;
    const per = limit || 50;
    for (;;) {
      const page = await this.request("GET", path,
        { query: Object.assign({}, query, { limit: per, offset }) });
      const items = pick ? pick(page) : (page && page.items);
      if (!Array.isArray(items) || !items.length) break;
      for (const item of items) {
        const mapped = map ? map(item) : item;
        if (mapped) out.push(mapped);
      }
      offset += items.length;
      const total = page && Number(page.total);
      if (Number.isFinite(total) && offset >= total) break;
      if (items.length < per) break;
      if (offset > 100000) break; // a runaway pager is a bug, not a big library
    }
    return out;
  }

  // ------------------------------------------------------------------ reads

  async me() {
    const j = await this.request("GET", "/me");
    this.session.userId = j && j.id ? String(j.id) : "";
    return { id: this.session.userId, name: (j && (j.display_name || j.id)) || "" };
  }

  async playlists() {
    return this.pageAll("/me/playlists", {}, null, 50, (p) => p && ({
      id: String(p.id),
      name: p.name || "",
      description: p.description || "",
      public: !!p.public,
      ownerId: (p.owner && String(p.owner.id)) || "",
      trackCount: (p.tracks && Number(p.tracks.total)) || 0,
    }));
  }

  async playlistTracks(playlistId) {
    // `fields` trims the response hard. A 2,000-track playlist is megabytes of
    // JSON unfiltered, most of it album art URLs in three sizes per track.
    const fields = "total,items(is_local,track(id,name,duration_ms,type," +
                   "artists(name),album(name),external_ids(isrc)))";
    return this.pageAll(
      "/playlists/" + encodeURIComponent(playlistId) + "/tracks",
      { fields, additional_types: "track" }, null, 100, fromPlaylistItem);
  }

  async savedTracks() {
    return this.pageAll("/me/tracks", {}, null, 50, (i) => toTrack(i && i.track));
  }

  async savedAlbums() {
    return this.pageAll("/me/albums", {}, null, 50, (i) => toAlbum(i && i.album));
  }

  /**
   * Followed artists page by CURSOR, not offset — the one endpoint here that
   * does, so pageAll cannot be used and this loop exists instead. Passing an
   * offset to it is silently ignored and returns the first page forever.
   */
  async followedArtists() {
    const out = [];
    let after = "";
    for (;;) {
      const q = { type: "artist", limit: 50 };
      if (after) q.after = after;
      const page = await this.request("GET", "/me/following", { query: q });
      const items = (page && page.artists && page.artists.items) || [];
      if (!items.length) break;
      out.push(...items.map((a) => ({ id: String(a.id), name: a.name || "" })));
      after = (page.artists.cursors && page.artists.cursors.after) || "";
      if (!after) break;
    }
    return out;
  }

  // --------------------------------------------------------------- searches

  /** The definitive lookup: Spotify indexes ISRC and filters on it directly. */
  async searchByIsrc(isrc) {
    const j = await this.request("GET", "/search",
      { query: { q: "isrc:" + String(isrc), type: "track", limit: 10 } });
    return ((j && j.tracks && j.tracks.items) || []).map(toTrack).filter(Boolean);
  }

  async searchTracks(title, artist, limit = 12) {
    // Field filters rather than a bag of words: `track:"x" artist:"y"` makes
    // Spotify match the title against titles instead of against lyrics,
    // album names and playlist descriptions, which a plain query does.
    const q = `track:${quoteTerm(title)}` + (artist ? ` artist:${quoteTerm(artist)}` : "");
    const j = await this.request("GET", "/search", { query: { q, type: "track", limit } });
    return ((j && j.tracks && j.tracks.items) || []).map(toTrack).filter(Boolean);
  }

  /**
   * Albums by BARCODE — the album's answer to searchByIsrc, and the reason
   * album matching went from mostly-missing to mostly-found.
   *
   * Spotify's album SEARCH returns SimplifiedAlbumObject, which has no
   * `external_ids` — so a candidate from searchAlbums always carries
   * `upc: ""`, and lib/match.js's barcode tier could never fire against it.
   * It compared a real Qobuz barcode with an empty string for every candidate
   * and fell through to the title tiers every single time, where the
   * track-count gate then rejected any edition mismatch. Measured on a real
   * library: 114 of 195 favourite albums reported "not found".
   *
   * The `upc:` filter is the fix (documented for /search, albums only). What
   * comes back still has no barcode on it, so THE FILTER IS THE EVIDENCE: a
   * hit is Spotify asserting the album carries that code, and the barcode is
   * stamped onto the result so the tier can see it.
   *
   * Stamped only when the result set is SMALL. A barcode identifies one
   * release; if this returns a crowd, the filter is not behaving like a filter
   * and treating it as decisive would be exactly the confidently-wrong match
   * this app refuses to make. In that case the albums come back unstamped and
   * are judged on title, artist and track count like anything else.
   */
  async searchByUpc(upc) {
    const code = String(upc || "").trim();
    if (!code) return [];
    const j = await this.request("GET", "/search",
      { query: { q: "upc:" + code, type: "album", limit: 10 } });
    const albums = ((j && j.albums && j.albums.items) || []).map(toAlbum).filter(Boolean);
    if (albums.length === 0 || albums.length > UPC_TRUST_LIMIT) return albums;
    return albums.map((a) => Object.assign({}, a, { upc: code }));
  }

  async searchAlbums(title, artist, limit = 12) {
    const q = `album:${quoteTerm(title)}` + (artist ? ` artist:${quoteTerm(artist)}` : "");
    const j = await this.request("GET", "/search", { query: { q, type: "album", limit } });
    const albums = ((j && j.albums && j.albums.items) || []).map(toAlbum).filter(Boolean);
    return albums;
  }

  async searchArtists(name, limit = 10) {
    const j = await this.request("GET", "/search",
      { query: { q: `artist:${quoteTerm(name)}`, type: "artist", limit } });
    return ((j && j.artists && j.artists.items) || [])
      .map((a) => ({ id: String(a.id), name: a.name || "" }));
  }

  /**
   * A saved album's barcode, which the /me/albums listing does not include.
   *
   * Only called when an album is actually being matched, one request each —
   * the alternative, fetching every album's details up front, is a request per
   * album whether or not it turns out to be needed.
   */
  async albumDetail(albumId) {
    const j = await this.request("GET", "/albums/" + encodeURIComponent(albumId));
    return toAlbum(j);
  }

  async albumTracks(albumId) {
    return this.pageAll(
      "/albums/" + encodeURIComponent(albumId) + "/tracks", {}, null, 50, toTrack);
  }

  // ----------------------------------------------------------------- writes

  async createPlaylist(name, opts) {
    const o = opts || {};
    if (!this.session.userId) await this.me();
    const j = await this.request("POST",
      "/users/" + encodeURIComponent(this.session.userId) + "/playlists", {
        body: {
          name: String(name || "Migrated playlist").slice(0, 100),
          // Private by default, always. Someone migrating their library has
          // not asked to publish it, and a public default would put their
          // listening on their profile without them choosing it.
          public: !!o.public,
          description: String(o.description || "").slice(0, 300),
        },
      });
    if (!j || !j.id) throw new Error("Spotify did not return the new playlist");
    return { id: String(j.id), name: j.name || "", url: (j.external_urls || {}).spotify || "" };
  }

  /** @param {string[]} trackIds  any length; batched to the endpoint's 100. */
  async addToPlaylist(playlistId, trackIds) {
    for (const batch of chunk(trackIds, 100)) {
      await this.request("POST", "/playlists/" + encodeURIComponent(playlistId) + "/tracks",
        { body: { uris: batch.map((id) => "spotify:track:" + id) } });
    }
  }

  async saveTracks(trackIds) {
    for (const batch of chunk(trackIds, 50)) {
      await this.request("PUT", "/me/tracks", { body: { ids: batch } });
    }
  }

  async saveAlbums(albumIds) {
    for (const batch of chunk(albumIds, 50)) {
      await this.request("PUT", "/me/albums", { body: { ids: batch } });
    }
  }

  async followArtists(artistIds) {
    for (const batch of chunk(artistIds, 50)) {
      // ids go in the QUERY for this one, not the body, unlike every other
      // write here. Sending them in the body succeeds with a 204 and follows
      // nobody, which is the worst possible way for an API to disagree.
      await this.request("PUT", "/me/following",
        { query: { type: "artist", ids: batch.join(",") }, body: { ids: batch } });
    }
  }
}

// ------------------------------------------------------- shape conversion

/**
 * A Spotify track object in this app's shape, or null if it is not a track we
 * can migrate.
 *
 * The nulls matter. A playlist can contain a PODCAST EPISODE (no ISRC, no
 * artist, an id that is not a track id) and a LOCAL FILE (a track object with
 * `id: null` that exists only on that person's machine). Both come back inside
 * `items[].track` looking close enough to a track to crash a naive mapper, and
 * neither can be migrated to anything.
 */
function toTrack(t) {
  if (!t || typeof t !== "object") return null;
  if (t.type && t.type !== "track") return null;
  if (!t.id) return null;
  return {
    id: String(t.id),
    isrc: (t.external_ids && t.external_ids.isrc) || "",
    title: t.name || "",
    artists: Array.isArray(t.artists) ? t.artists.map((a) => (a && a.name) || "").filter(Boolean)
                                      : [],
    album: (t.album && t.album.name) || "",
    durationMs: Number(t.duration_ms) || null,
  };
}

/** As toTrack, but reporting WHY an item was skipped so the job can say so. */
function fromPlaylistItem(item) {
  if (!item) return null;
  if (item.is_local) {
    return { skip: "local file", title: (item.track && item.track.name) || "a local file" };
  }
  const t = toTrack(item.track);
  if (!t) {
    const raw = item.track || {};
    if (raw.type === "episode") {
      return { skip: "podcast episode", title: raw.name || "an episode" };
    }
    return null;
  }
  return t;
}

function toAlbum(a) {
  if (!a || !a.id) return null;
  return {
    id: String(a.id),
    upc: (a.external_ids && a.external_ids.upc) || "",
    title: a.name || "",
    artists: Array.isArray(a.artists) ? a.artists.map((x) => (x && x.name) || "").filter(Boolean)
                                      : [],
    trackCount: Number(a.total_tracks) || null,
    year: Number(String(a.release_date || "").slice(0, 4)) || null,
  };
}

// ------------------------------------------------------------------ helpers

/**
 * A search term, quoted.
 *
 * A double quote inside the term ends the quoted phrase and turns the rest
 * into loose words — so a track called 'The "Real" Thing' searched raw finds
 * nothing and looks like the track is missing. Spotify has no escape for it
 * inside a quoted phrase, so the quote is dropped rather than escaped.
 */
function quoteTerm(s) {
  return '"' + String(s == null ? "" : s).replace(/"/g, " ").trim() + '"';
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
    return (j && j.error && (j.error.message || j.error)) || "";
  } catch (e) {
    return String(text || "").slice(0, 160);
  }
}

function authError(msg) { const e = new Error(msg); e.code = 401; e.auth = true; return e; }
function rateError(msg) { const e = new Error(msg); e.code = 429; return e; }

module.exports = { Spotify, exchangeCode, refreshTokens, toTrack, toAlbum, chunk, quoteTerm, API };
