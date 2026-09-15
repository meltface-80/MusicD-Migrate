"use strict";
/*
 * roon.js — a Roon library as a migration SOURCE.
 *
 * WHAT ROON GIVES, AND WHAT IT DOES NOT
 *
 * Every other source in this app is a catalogue with identifiers: Spotify and
 * Qobuz both hand over an ISRC per track and a barcode per album, and the
 * matcher's decisive tiers are built on them. Roon hands over none. A browse
 * row is a title, a subtitle, an image key and a session-scoped item key —
 * that is the whole of it. No MBID, no ISRC, no barcode, no service id, and
 * NO TRACK LENGTH.
 *
 * That last one settles what can be migrated from Roon and what cannot:
 *
 *   - ALBUMS can. A title and an artist, corroborated by the album's own track
 *     listing, is enough evidence to be worth offering, and lib/match.js still
 *     refuses where it is not decisive.
 *   - TRACKS cannot, ever. Title and artist alone are exactly the two facts a
 *     cover, a re-recording and a live take also satisfy, which is why
 *     CLAUDE.md says no duration means no title-tier match. A Roon track
 *     carries no duration, so there is nothing to add to it.
 *   - PLAYLISTS cannot, because a playlist is a list of tracks.
 *
 * So this client DECLARES those two as unsupported with the reason, and the
 * engine records the reason in the report. Returning an empty list instead
 * would finish a run green having migrated nothing, which reads as an empty
 * library rather than as a thing this app will not do — and the whole point of
 * the unmatched report is that the user can tell those apart.
 *
 * THE CORE IS A SEAM
 *
 * Everything here goes through `core.browse`, `core.load` and
 * `core.withSession`, so the tests drive a scripted browse tree with no
 * socket, no Core and no network. See lib/roon-core.js for the real one.
 */

const crypto = require("node:crypto");
const { canon, primaryArtist } = require("./canon");
const { RoonError } = require("./roon-core");

/** Roon's own page size for a browse level. */
const PAGE = 100;

/**
 * The most rows read from one album's contents. 500 covers a box set; nothing
 * in a migration needs a second page of an album.
 */
const ALBUM_CONTENTS_MAX = 500;

/** How many artists are worth walking before calling it a library, not a list. */
const ARTISTS_MAX = 20000;

/** Separates the two halves of an album key so "ab|c" and "a|bc" differ. */
const KEY_SEP = "";

/**
 * A stable id for a Roon album.
 *
 * NOT Roon's item_key, which is session-scoped: it means nothing once the
 * browse session is re-navigated, and a stored one would drill into whatever
 * has since taken that key. NOT the offset either, which moves the moment a
 * record is added.
 *
 * A hash of the normalised title and artist instead, so it is the same id on
 * every scan — which is what lets the match cache survive a rescan. A library
 * with two copies of the same record collides here on purpose; see
 * Store.saveRoonAlbums.
 */
function albumKey(title, artist) {
  const basis = canon(title) + KEY_SEP + primaryArtist(artist || "");
  return "ra_" + crypto.createHash("sha1").update(basis).digest("hex").slice(0, 16);
}

function artistKey(name) {
  return "rn_" + crypto.createHash("sha1").update(canon(name)).digest("hex").slice(0, 16);
}

/** One browse row, in this app's spelling. */
function browseItem(o) {
  return {
    title: String((o && o.title) || ""),
    subtitle: String((o && o.subtitle) || ""),
    itemKey: (o && o.item_key) ? String(o.item_key) : null,
    imageKey: (o && o.image_key) ? String(o.image_key) : null,
    hint: (o && o.hint) ? String(o.hint) : null,
  };
}

/**
 * Roon answered, but not with a list.
 *
 * When the action is "message" the Core has said why in its own words, and
 * discarding that is how a user asking "why is my library empty?" gets told
 * about a protocol instead of the reason.
 */
function requireList(body, what) {
  const action = body && body.action;
  if (action === "list") return;
  if (action === "message") {
    throw new RoonError("Roon says: " + ((body && body.message) || "it declined"));
  }
  throw new RoonError("Roon gave no list for " + what +
    " (it answered \"" + (action || "nothing") + "\")");
}

class RoonClient {
  /**
   * @param {object} o
   * @param {object} o.core   browse/load/withSession — lib/roon-core.js, or a
   *   scripted stand-in in the tests
   * @param {object} o.store  lib/store.js
   * @param {Function} [o.coreId] the id the scan is filed under. A function
   *   because the Core's id is only known after pairing, which is after this
   *   object is built.
   */
  constructor(o) {
    const opts = o || {};
    this.core = opts.core;
    this.store = opts.store;
    this._coreId = opts.coreId || (() => (this.core && this.core.coreId) || "roon");
    this._log = opts.log || (() => {});
    this.serviceName = "roon";
    this.userId = "";

    /**
     * Kinds this source will not offer, and why — see the header. The engine
     * records the reason as a skipped row rather than reporting nothing found.
     */
    this.unsupported = {
      tracks: "Roon's browse API gives a track's title and artist but not its " +
        "length, and a title and artist alone are exactly what a cover, a " +
        "re-recording and a live version also satisfy. Matching on that would " +
        "quietly put the wrong recording in your library, so favourite tracks " +
        "are not migrated from Roon. Albums are.",
      playlists: "A playlist is a list of tracks, and a track from Roon carries " +
        "no length to match it on — see the note about favourite tracks. Roon " +
        "playlists are not migrated.",
    };
  }

  get accountId() { return this._coreId(); }

  async me() {
    const id = this._coreId();
    return { id, name: (this.core && this.core.coreName) || "Roon" };
  }

  // ------------------------------------------------------- unsupported reads

  /* Present so the object really is a MusicSource — lib/service.js checks —
   * and empty because `unsupported` above is what carries the reason. The
   * engine never reaches these; a caller that does gets nothing rather than a
   * crash. */
  async playlists() { return []; }
  async playlistTracks() { return []; }
  async savedTracks() { return []; }

  // ------------------------------------------------------------- the library

  /**
   * Walk the whole album list and store it.
   *
   * Paged at 100, one transaction per page, and the offset is remembered in
   * settings so a scan interrupted at album 6,000 resumes there rather than
   * starting again. Roon's album list is alphabetically stable, so an offset
   * means the same thing on the next pass — as long as the library has not
   * changed underneath, which a changed total detects.
   *
   * @param {object} [o]
   * @param {Function} [o.onProgress] ({done, total, stored, duplicates})
   * @param {boolean} [o.resume] continue from the remembered offset
   * @param {Function} [o.cancelled] () => boolean, checked between pages
   */
  async scan(o) {
    const opts = o || {};
    const onProgress = opts.onProgress || (() => {});
    const cancelled = opts.cancelled || (() => false);
    const coreId = this._coreId();
    const saved = this.store.get("roon.scan", null);

    let offset = 0;
    let stored = 0;
    let duplicates = 0;
    let resuming = false;
    if (opts.resume && saved && saved.coreId === coreId && saved.offset > 0) {
      offset = saved.offset;
      stored = saved.stored || 0;
      duplicates = saved.duplicates || 0;
      resuming = true;
      this._log("resuming the Roon scan at album " + offset);
    } else {
      // A fresh scan replaces the inventory rather than merging into it, so a
      // record deleted in Roon does not live on in an export forever.
      this.store.clearRoonAlbums(coreId);
    }
    // A scan that stopped for a reason must not leave the reason behind to be
    // shown against the next one.
    this.store.del("roon.scanError");

    return this.core.withSession(async (key) => {
      const head = await this.core.browse(
        { hierarchy: "albums", multi_session_key: key, pop_all: true });
      requireList(head, "the album list");
      let total = Number((head.list && head.list.count) || 0);

      // An offset only means the same album as long as the list is the same
      // length: Roon sorts alphabetically, so a record bought since shifts
      // everything after it and resuming would SKIP an album for every one
      // added. A changed total is the detector, and starting over is a minute
      // — being quietly one album short of a ten thousand album inventory is
      // not something the user would ever notice.
      if (resuming && total > 0 && saved.total > 0 && total !== saved.total) {
        this._log("the library changed while the scan was stopped (" + saved.total +
          " albums then, " + total + " now) — starting again");
        offset = 0;
        stored = 0;
        duplicates = 0;
        this.store.clearRoonAlbums(coreId);
      }

      for (;;) {
        if (cancelled()) break;
        const page = await this.core.load({ hierarchy: "albums",
          multi_session_key: key, offset, count: PAGE });
        const items = (page && Array.isArray(page.items) ? page.items : []).map(browseItem);
        if (page && page.list && page.list.count) total = Number(page.list.count);
        if (!items.length) break;

        const rows = items.map((it, i) => ({
          albumKey: albumKey(it.title, it.subtitle),
          title: it.title,
          artist: it.subtitle,
          position: offset + i,
          imageKey: it.imageKey,
        }));
        const result = this.store.saveRoonAlbums(coreId, rows);
        stored += result.stored;
        duplicates += result.duplicates;
        offset += items.length;

        this.store.put("roon.scan", { coreId, offset, total, stored, duplicates,
                                      at: Date.now(), done: false });
        onProgress({ done: offset, total, stored, duplicates });

        // A page short of a full one is the end of the list. Trusting `total`
        // alone would loop forever against a Core that reports a count it does
        // not actually serve.
        if (items.length < PAGE) break;
        if (total > 0 && offset >= total) break;
      }

      const summary = { coreId, offset, total, stored, duplicates,
                        at: Date.now(), done: !cancelled() };
      this.store.put("roon.scan", summary);
      return summary;
    });
  }

  /** What the last scan found, for the UI. */
  scanStatus() {
    const saved = this.store.get("roon.scan", null);
    const coreId = this._coreId();
    return {
      albums: this.store.roonAlbumCount(coreId),
      scan: saved && saved.coreId === coreId ? saved : null,
      error: this.store.get("roon.scanError", null),
    };
  }

  /**
   * The scanned albums, as the migration engine's Album shape.
   *
   * Reads the inventory rather than walking Roon: a migration must not decide
   * on its own to spend ten minutes re-reading a ten thousand album library,
   * and the scan is a thing the user starts and watches.
   *
   * Throws rather than returning nothing when there is no scan. "You have not
   * scanned yet" and "your library is empty" are different, and a migration
   * that reported the first as the second would finish green having done
   * nothing.
   */
  async savedAlbums() {
    const coreId = this._coreId();
    const rows = this.store.roonAlbums(coreId);
    if (!rows.length) {
      throw new RoonError("No Roon library has been scanned yet. Scan it first — " +
        "a migration reads the scan, not the Core, so it cannot do it for you " +
        "without appearing to have found nothing.");
    }
    return rows.map((r) => ({
      id: r.albumKey,
      upc: "",                    // Roon has none. Missing data, not evidence.
      title: r.title,
      artists: r.artist ? [r.artist] : [],
      trackCount: r.trackCount,   // null until something drills in
      year: null,
    }));
  }

  /**
   * Nothing more is known about a Roon album than the scan holds.
   *
   * The engine asks this for a barcode before it searches. Answering null is
   * the honest answer and costs nothing; drilling in here would spend two
   * browse calls per album to learn something Roon does not have.
   */
  async albumDetail() { return null; }

  /**
   * An album's track listing, drilled on demand.
   *
   * Costs a pop_all, a load, a browse and a load — about four calls — so it is
   * never done for the whole library. It exists for corroborating a candidate
   * album on the other service, where a track listing is the only evidence a
   * Roon album has to offer.
   *
   * Durations are null and that is not an oversight: a Roon browse row does
   * not carry one. Anything matching on these must not treat them as a
   * duration of zero.
   */
  async albumTracks(albumId) {
    const coreId = this._coreId();
    const row = this.store.roonAlbum(coreId, albumId);
    if (!row) return [];

    return this.core.withSession(async (key) => {
      await this.core.browse({ hierarchy: "albums", multi_session_key: key, pop_all: true });
      const item = await this._findAlbum(key, row);
      if (!item || !item.itemKey) {
        // Not an error: a record can be removed from Roon between a scan and a
        // migration, and one missing album must not stop a run.
        this._log("album \"" + row.title + "\" is no longer where the scan left it");
        return [];
      }
      const into = await this.core.browse(
        { hierarchy: "albums", multi_session_key: key, item_key: item.itemKey });
      requireList(into, "the album \"" + row.title + "\"");

      const rows = await this._loadLevel(key, ALBUM_CONTENTS_MAX);
      const tracks = rows
        // A header row is the album's own banner, not a track; a row with no
        // item key cannot be one either.
        .filter((it) => it.hint !== "header" && it.itemKey && it.title)
        .map((it, i) => ({
          id: albumId + ":" + i,
          isrc: "",
          title: it.title,
          artists: [it.subtitle || row.artist].filter(Boolean),
          album: row.title,
          durationMs: null,
        }));

      // Learned for free while we were in there, so the next run need not.
      this.store.setRoonAlbumTrackCount(coreId, albumId, tracks.length);
      return tracks;
    });
  }

  /**
   * Every artist in the library.
   *
   * Walked live rather than stored: it is a few hundred load calls at worst,
   * it is not the thing the user asked to inventory, and an artist list that
   * went stale would be worse than one that costs a minute.
   */
  async followedArtists() {
    return this.core.withSession(async (key) => {
      const head = await this.core.browse(
        { hierarchy: "artists", multi_session_key: key, pop_all: true });
      requireList(head, "the artist list");
      const rows = await this._loadLevel(key, ARTISTS_MAX, "artists");
      const out = [];
      const seen = new Set();
      for (const it of rows) {
        if (!it.title) continue;
        const id = artistKey(it.title);
        if (seen.has(id)) continue;
        seen.add(id);
        out.push({ id, name: it.title });
      }
      return out;
    });
  }

  // --------------------------------------------------------------- internals

  /** Page through the level the session is currently on. */
  async _loadLevel(key, max, hierarchy) {
    const h = hierarchy || "albums";
    const out = [];
    let offset = 0;
    for (;;) {
      const page = await this.core.load(
        { hierarchy: h, multi_session_key: key, offset, count: PAGE });
      const items = (page && Array.isArray(page.items) ? page.items : []).map(browseItem);
      if (!items.length) break;
      out.push(...items);
      offset += items.length;
      if (items.length < PAGE) break;
      if (offset >= max) break;
      const total = Number((page.list && page.list.count) || 0);
      if (total > 0 && offset >= total) break;
    }
    return out;
  }

  /**
   * Find a scanned album in the live list again.
   *
   * The remembered offset first, CONFIRMED against the title: a hint that has
   * gone stale — because records were added above it — must cost a scan, never
   * yield the wrong album. Only if that fails does it page through looking for
   * the title, which is the slow path and the reason the hint exists.
   */
  async _findAlbum(key, row) {
    const wantTitle = canon(row.title);
    const wantArtist = primaryArtist(row.artist || "");

    if (row.position >= 0) {
      const page = await this.core.load({ hierarchy: "albums",
        multi_session_key: key, offset: row.position, count: 1 });
      const items = (page && Array.isArray(page.items) ? page.items : []).map(browseItem);
      const one = items[0];
      if (one && canon(one.title) === wantTitle &&
          (!wantArtist || primaryArtist(one.subtitle || "") === wantArtist)) {
        return one;
      }
    }

    let offset = 0;
    for (;;) {
      const page = await this.core.load({ hierarchy: "albums",
        multi_session_key: key, offset, count: PAGE });
      const items = (page && Array.isArray(page.items) ? page.items : []).map(browseItem);
      if (!items.length) return null;
      const hit = items.find((it) => canon(it.title) === wantTitle &&
        (!wantArtist || primaryArtist(it.subtitle || "") === wantArtist));
      if (hit) return hit;
      offset += items.length;
      if (items.length < PAGE) return null;
      const total = Number((page.list && page.list.count) || 0);
      if (total > 0 && offset >= total) return null;
    }
  }
}

module.exports = { RoonClient, albumKey, artistKey, requireList, browseItem,
                   PAGE, ALBUM_CONTENTS_MAX };
