"use strict";
/*
 * qobuz.js — the Qobuz API, as much of it as migrating needs.
 *
 * IMPORTANT: this is the UNOFFICIAL Qobuz API — the same one the Lyrion/LMS
 * "Qobuz" community plugin and every open-source Qobuz client use. It is not a
 * sanctioned integration, it is against Qobuz's terms of service, and it can
 * change without notice. It is used at the user's own risk, and the README
 * says so where someone will read it before installing rather than after.
 *
 * That is the honest framing, and it is also why this file is deliberately
 * narrow. It reads playlists and favourites, searches the catalogue, and
 * writes playlists and favourites. It does NOT fetch stream URLs, which is the
 * one part of the Qobuz API that needs a rotating app_secret — MusicD-Remote
 * has that code (lib/qobuz-sig.js) and it is not ported here, because nothing
 * in a migration needs audio and shipping the machinery for it would invite
 * the question of why it exists.
 *
 * THE INTERFACE IS THE SAME AS lib/spotify.js ON PURPOSE. Same method names,
 * same argument order, same returned shapes. The migration engine then has no
 * idea which service is the source and which is the destination, and both
 * directions are one code path rather than two that drift apart. Where the two
 * services genuinely differ, the difference is absorbed here.
 *
 * TWO THINGS ABOUT QOBUZ'S DATA THAT BITE, both handled below:
 *   - `duration` is in SECONDS, where Spotify's duration_ms is milliseconds.
 *     Every track that leaves this file is converted, because a matcher
 *     comparing 213 against 213000 rejects every track in the library and
 *     looks exactly like "nothing is on Qobuz".
 *   - the edition is a SEPARATE FIELD. Qobuz has title "Blue Monday" and
 *     version "2016 Remaster" where Spotify has one string "Blue Monday - 2016
 *     Remaster". They are recomposed into one title here so that
 *     canon.stripVersion sees the same thing from both sides.
 */

const crypto = require("node:crypto");

const BASE = "https://www.qobuz.com/api.json/0.2/";

// The app_id the Lyrion/LMS Qobuz plugin uses. It identifies the APPLICATION,
// never a user. A token minted through the browser sign-in belongs to a
// different app and carries its own id — see setAppId.
const DEFAULT_APP_ID = "942852567";

const MAX_RETRIES = 4;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function md5Hex(s) {
  return crypto.createHash("md5").update(String(s), "utf8").digest("hex");
}

class Qobuz {
  /**
   * @param {object} session {token, userId, appId, displayName}
   * @param {object} [opts]
   */
  constructor(session, opts) {
    this.session = Object.assign({}, session || {});
    this.opts = opts || {};
    this.fetch = this.opts.fetch || globalThis.fetch;
  }

  /**
   * Which app this session acts as.
   *
   * A user_auth_token belongs to the app that minted it, and Qobuz refuses a
   * token presented with a different app_id — so the id and the token move
   * together. A token from the browser sign-in belongs to THAT app, not to the
   * default above, and presenting the wrong one is a 401 that reads exactly
   * like an expired sign-in.
   */
  get appId() { return this.session.appId || DEFAULT_APP_ID; }
  get userId() { return this.session.userId || ""; }

  async request(endpoint, params, opts) {
    const o = opts || {};
    // BASE already ends in "/", so a leading slash here produces ".../0.2//x"
    // — a URL that is wrong in a way nothing downstream can see, because the
    // failure arrives as an ordinary non-200.
    const path = String(endpoint || "").replace(/^\/+/, "");
    let attempt = 0;

    for (;;) {
      const qs = new URLSearchParams();
      for (const [k, v] of Object.entries(params || {})) {
        if (v !== undefined && v !== null && v !== "") qs.append(k, String(v));
      }
      qs.append("app_id", this.appId);

      const headers = { "X-App-Id": this.appId };
      if (this.session.token) headers["X-User-Auth-Token"] = this.session.token;

      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), o.timeoutMs || 20000);
      let res, text;
      try {
        res = await this.fetch(BASE + path + "?" + qs.toString(),
          { method: o.method || "GET", headers, signal: ctl.signal });
        text = await res.text();
      } catch (e) {
        clearTimeout(timer);
        if (attempt++ < 2) { await sleep(500 * attempt); continue; }
        throw new Error("Could not reach Qobuz: " + (e && e.message));
      } finally {
        clearTimeout(timer);
      }

      if (res.status === 429) {
        if (attempt++ >= MAX_RETRIES) {
          const e = new Error("Qobuz is rate limiting this app and did not let up.");
          e.code = 429;
          throw e;
        }
        // Qobuz does not send Retry-After. Backing off exponentially from a
        // second is a guess, but an unbounded hammer is not an alternative.
        const waitMs = Math.min(1000 * Math.pow(2, attempt), 30000);
        if (this.opts.onRateLimit) this.opts.onRateLimit(waitMs);
        await sleep(waitMs);
        continue;
      }

      if (res.status === 401) {
        const e = new Error("Qobuz sign-in has expired or was rejected — sign in again.");
        e.code = 401;
        e.auth = true;
        throw e;
      }

      if (!res.ok) {
        // THE BODY IS THE DIAGNOSIS. Qobuz distinguishes its failures in the
        // response TEXT and nowhere else, and every one of them arrives as an
        // ordinary non-200. Throwing the bare status is how two completely
        // different problems become one indistinguishable error.
        const e = new Error("Qobuz HTTP " + res.status +
          (text ? ": " + String(text).slice(0, 200) : ""));
        e.code = res.status;
        throw e;
      }

      try { return JSON.parse(text); } catch (e) {
        throw new Error("Qobuz returned an unexpected (non-JSON) response");
      }
    }
  }

  // ------------------------------------------------------------------- auth

  /**
   * Sign in with a username and password.
   *
   * THE FALLBACK, not the default — the browser flow in lib/qobuz-oauth.js is
   * offered first and this is for when the redirect cannot get back. Qobuz
   * wants the password MD5-hashed, which is what the LMS plugin does too; the
   * hash is what gets stored, and the plaintext never leaves this function.
   */
  async login(username, password, alreadyHashed) {
    if (!username || !password) throw new Error("A Qobuz email and password are required");
    const passwordMd5 = alreadyHashed ? String(password) : md5Hex(password);
    const r = await this.request("user/login", { username, password: passwordMd5 });
    const token = r && r.user_auth_token;
    if (!token || !r.user || !r.user.id) {
      throw new Error("Qobuz login failed — check the email and password");
    }
    Object.assign(this.session, {
      token: String(token),
      userId: String(r.user.id),
      displayName: r.user.display_name || r.user.login || username,
    });
    return Object.assign({}, this.session, { passwordMd5 });
  }

  /** Confirm the stored token still works, and learn the account's name. */
  async me() {
    const r = await this.request("user/get", { user_id: this.session.userId || undefined });
    const u = (r && r.user) || r || {};
    if (u.id) this.session.userId = String(u.id);
    this.session.displayName = u.display_name || u.login || this.session.displayName || "";
    return { id: this.session.userId, name: this.session.displayName };
  }

  // ------------------------------------------------------------------ reads

  async playlists() {
    const items = await this.pageAll("playlist/getUserPlaylists", {},
      (r) => r && r.playlists && r.playlists.items, 500);
    return items.filter(Boolean)
      // Qobuz lists playlists somebody else made and this user SUBSCRIBED to
      // alongside their own, and they are indistinguishable in the list except
      // by owner. Migrating a subscribed playlist is not wrong exactly, but it
      // is not what "my playlists" means, so ownership is reported and the UI
      // lets the user decide.
      .map((p) => ({
        id: String(p.id),
        name: p.name || "",
        description: p.description || "",
        public: !!p.is_public,
        ownerId: (p.owner && String(p.owner.id)) || "",
        trackCount: Number(p.tracks_count) || 0,
      }));
  }

  async playlistTracks(playlistId) {
    const items = await this.pageAll("playlist/get",
      { playlist_id: playlistId, extra: "tracks" },
      (r) => r && r.tracks && r.tracks.items, 500);
    return items.map(toTrack).filter(Boolean);
  }

  async savedTracks() {
    const items = await this.pageAll("favorite/getUserFavorites", { type: "tracks" },
      (r) => r && r.tracks && r.tracks.items, 500);
    return items.map(toTrack).filter(Boolean);
  }

  async savedAlbums() {
    const items = await this.pageAll("favorite/getUserFavorites", { type: "albums" },
      (r) => r && r.albums && r.albums.items, 500);
    return items.map(toAlbum).filter(Boolean);
  }

  async followedArtists() {
    const items = await this.pageAll("favorite/getUserFavorites", { type: "artists" },
      (r) => r && r.artists && r.artists.items, 500);
    return items.filter(Boolean).map((a) => ({ id: String(a.id), name: a.name || "" }));
  }

  /** Offset paging, with the section picked out by the caller because Qobuz
   *  names it differently on every endpoint. */
  async pageAll(endpoint, params, pick, limit = 500) {
    const out = [];
    let offset = 0;
    for (;;) {
      const r = await this.request(endpoint,
        Object.assign({}, params, { limit, offset }));
      const items = pick(r);
      if (!Array.isArray(items) || !items.length) break;
      out.push(...items);
      offset += items.length;
      if (items.length < limit) break;
      if (offset > 100000) break;
    }
    return out;
  }

  // --------------------------------------------------------------- searches

  /**
   * Search by ISRC.
   *
   * Qobuz has no documented ISRC filter — unlike Spotify's `isrc:` — but it
   * does index the code, so a plain query for it usually returns the right
   * track. "Usually" is not good enough on its own, which is why this returns
   * CANDIDATES rather than an answer: lib/match.js compares the ISRCs itself
   * and only accepts a real equality. A near-miss here costs one wasted
   * request, never a wrong match.
   */
  async searchByIsrc(isrc) {
    const r = await this.request("catalog/search",
      { query: String(isrc), type: "tracks", limit: 10 });
    return sectionItems(r, "tracks").map(toTrack).filter(Boolean);
  }

  async searchTracks(title, artist, limit = 12) {
    const q = [title, artist].filter(Boolean).join(" ");
    const r = await this.request("catalog/search", { query: q, type: "tracks", limit });
    return sectionItems(r, "tracks").map(toTrack).filter(Boolean);
  }

  /**
   * Albums by barcode. The counterpart of searchByIsrc, and the same caveat:
   * Qobuz has no documented barcode filter, but it indexes the code, so a
   * plain query usually finds it.
   *
   * No stamping is needed on this side — unlike Spotify's, Qobuz's album
   * listings DO carry `upc`, so lib/match.js compares the real barcodes itself
   * and a near-miss costs one wasted request rather than a wrong match.
   */
  async searchByUpc(upc) {
    const code = String(upc || "").trim();
    if (!code) return [];
    const r = await this.request("catalog/search",
      { query: code, type: "albums", limit: 10 });
    return sectionItems(r, "albums").map(toAlbum).filter(Boolean);
  }

  async searchAlbums(title, artist, limit = 12) {
    const q = [title, artist].filter(Boolean).join(" ");
    const r = await this.request("catalog/search", { query: q, type: "albums", limit });
    return sectionItems(r, "albums").map(toAlbum).filter(Boolean);
  }

  async searchArtists(name, limit = 10) {
    const r = await this.request("catalog/search",
      { query: String(name || ""), type: "artists", limit });
    return sectionItems(r, "artists").map((a) => ({ id: String(a.id), name: a.name || "" }));
  }

  /** Qobuz's album listing already carries the barcode, so unlike Spotify this
   *  never needs a second request — it is here only to keep the interfaces
   *  identical for the migration engine. */
  async albumDetail(albumId) {
    const r = await this.request("album/get", { album_id: albumId });
    return toAlbum(r);
  }

  /**
   * One album's track listing.
   *
   * No `extra` parameter: album/get returns the tracks on its own, and
   * MusicD-Remote — a Qobuz client that works — asks for nothing else. An
   * `extra` value the API does not define is a request it can refuse
   * outright, and a refused read here is INVISIBLE: safely() turns it into
   * "could not read the track listing" and the corroboration tier then
   * unmatches every barcode-less album. That is what shipped in 0.2.0.
   */
  async albumTracks(albumId) {
    const r = await this.request("album/get", { album_id: albumId });
    const items = (r && r.tracks && r.tracks.items) || [];
    // Tracks inside an album response do not repeat the album block, so the
    // album's own title and artist are threaded down: without them every track
    // migrates with an empty album name and the album tie-break never fires.
    const album = toAlbum(r);
    return items.map((t) => toTrack(t, album)).filter(Boolean);
  }

  // ----------------------------------------------------------------- writes

  async createPlaylist(name, opts) {
    const o = opts || {};
    const r = await this.request("playlist/create", {
      name: String(name || "Migrated playlist").slice(0, 200),
      description: String(o.description || "").slice(0, 500),
      // Private by default, always — the same rule as the Spotify side. A
      // migration is not a publication.
      is_public: o.public ? "true" : "false",
      is_collaborative: "false",
    }, { method: "POST" });
    if (!r || !r.id) throw new Error("Qobuz did not return the new playlist");
    return { id: String(r.id), name: r.name || "", url: "" };
  }

  /**
   * @param {string[]} trackIds
   *
   * Batched at 50 rather than the endpoint's limit, because these ids go in
   * the QUERY STRING: a thousand of them is a 9KB URL, and the failure when a
   * server decides that is too long is a 414 rather than anything that names
   * the cause.
   */
  async addToPlaylist(playlistId, trackIds) {
    for (const batch of chunk(trackIds, 50)) {
      await this.request("playlist/addTracks", {
        playlist_id: playlistId,
        track_ids: batch.join(","),
      }, { method: "POST" });
    }
  }

  async saveTracks(trackIds) {
    for (const batch of chunk(trackIds, 50)) {
      await this.request("favorite/create",
        { type: "tracks", track_ids: batch.join(",") }, { method: "POST" });
    }
  }

  async saveAlbums(albumIds) {
    for (const batch of chunk(albumIds, 50)) {
      await this.request("favorite/create",
        { type: "albums", album_ids: batch.join(",") }, { method: "POST" });
    }
  }

  async followArtists(artistIds) {
    for (const batch of chunk(artistIds, 50)) {
      await this.request("favorite/create",
        { type: "artists", artist_ids: batch.join(",") }, { method: "POST" });
    }
  }
}

// ------------------------------------------------------- shape conversion

/**
 * A Qobuz track in this app's shape.
 *
 * @param {object} [albumFallback] the album block, when the track came from an
 *   album listing that does not repeat it.
 *
 * THE TWO CONVERSIONS THAT MATTER ARE BOTH HERE: seconds to milliseconds, and
 * title+version to one title. Doing either at the call site would mean doing
 * it right in six places and wrong in the seventh.
 */
function toTrack(t, albumFallback) {
  if (!t || t.id == null) return null;
  const album = t.album || albumFallback || null;
  const title = t.version ? `${t.title || ""} (${t.version})` : (t.title || "");
  const performer = (t.performer && t.performer.name) ||
                    (album && album.artists && album.artists[0]) ||
                    (album && album.artist && album.artist.name) || "";
  return {
    id: String(t.id),
    isrc: t.isrc || "",
    title: title.trim(),
    artists: performer ? [performer] : [],
    album: (album && (album.title || album.name)) || "",
    // Qobuz counts in SECONDS. Everything downstream counts in milliseconds.
    durationMs: Number(t.duration) > 0 ? Number(t.duration) * 1000 : null,
  };
}

function toAlbum(a) {
  if (!a || a.id == null) return null;
  const title = a.version ? `${a.title || ""} (${a.version})` : (a.title || "");
  const artist = (a.artist && a.artist.name) ||
                 (Array.isArray(a.artists) && a.artists[0] &&
                   (a.artists[0].name || a.artists[0])) || "";
  return {
    id: String(a.id),
    upc: a.upc || "",
    title: String(title).trim(),
    artists: artist ? [String(artist)] : [],
    trackCount: Number(a.tracks_count) || null,
    year: yearOf(a),
  };
}

function yearOf(a) {
  const s = a && (a.release_date_original || a.release_date_stream || a.released_at);
  if (typeof s === "number") return new Date(s * 1000).getUTCFullYear() || null;
  const y = Number(String(s || "").slice(0, 4));
  return Number.isFinite(y) && y > 1000 ? y : null;
}

/** Qobuz's search response nests each type under its own key, and any of them
 *  may be missing entirely rather than empty. */
function sectionItems(r, key) {
  const s = r && r[key];
  if (s && Array.isArray(s.items)) return s.items;
  if (Array.isArray(s)) return s;
  return [];
}

function chunk(arr, n) {
  const list = (arr || []).filter((v) => v != null && v !== "");
  const out = [];
  for (let i = 0; i < list.length; i += n) out.push(list.slice(i, i + n));
  return out;
}

module.exports = { Qobuz, toTrack, toAlbum, md5Hex, chunk, DEFAULT_APP_ID, BASE };
