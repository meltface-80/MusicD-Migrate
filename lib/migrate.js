"use strict";
/*
 * migrate.js — moving a library from one service to the other.
 *
 * ONE CODE PATH, BOTH DIRECTIONS. This module never asks which service it is
 * talking to. lib/spotify.js and lib/qobuz.js expose the same method names
 * returning the same shapes, so "Qobuz to Spotify" and "Spotify to Qobuz" are
 * the same run with the two objects swapped. Two code paths would have been
 * easier to write and would have drifted the first time one side was fixed.
 *
 * THE THREE PROMISES THIS MAKES
 *
 * 1. It never invents a match. Everything goes through lib/match.js, which
 *    refuses where the evidence is not decisive, and a refusal is REPORTED
 *    rather than swallowed. The output of a migration is not "done" — it is
 *    the list of tracks that did not move and why, which is the only part the
 *    user has to act on.
 *
 * 2. It is safe to run twice. Favourites are idempotent because both services'
 *    "save" endpoints are. Playlists are idempotent because a second run finds
 *    the playlist it made and adds only what is missing. This matters more
 *    than it sounds: the second run is the common one, after buying records,
 *    and a tool that duplicates a 900-track playlist every time is a tool
 *    nobody runs twice.
 *
 * 3. It never writes anything in a dry run. The plan is built identically —
 *    same reads, same searches, same matching, same cache writes — and the
 *    write phase is skipped. That makes the preview trustworthy: it is the
 *    real answer, not an estimate of it.
 *
 * WHAT COSTS TIME IS LOOKING, NOT WRITING. A 2,000-track library is up to
 * 4,000 searches against a rate-limited API, and the writes are 40 requests.
 * So: every lookup is cached in SQLite (including the misses), the target's
 * existing library is read once up front to skip everything already there,
 * and lookups run a few at a time rather than one after another.
 */

const { matchTrack, matchAlbum, matchArtist } = require("./match");
const { canon, stripVersion, primaryArtist } = require("./canon");

/** How many lookups are in flight at once. Low on purpose: both services rate
 *  limit, and the 429 backoff costs far more than the concurrency saves. */
const DEFAULT_CONCURRENCY = 4;

/** Progress is written to SQLite and polled by the UI. Throttled because a
 *  per-track write is a fsync per track. */
const PROGRESS_INTERVAL_MS = 400;

class Cancelled extends Error {
  constructor() { super("Cancelled"); this.cancelled = true; }
}

class Migration {
  /**
   * @param {object} o
   * @param {object} o.source        a Spotify or Qobuz client
   * @param {object} o.target        the other one
   * @param {string} o.sourceName    "qobuz" | "spotify"
   * @param {string} o.targetName    the other one
   * @param {object} o.store
   * @param {string} o.jobId
   * @param {object} o.options
   */
  constructor(o) {
    this.source = o.source;
    this.target = o.target;
    this.sourceName = o.sourceName;
    this.targetName = o.targetName;
    this.store = o.store;
    this.jobId = o.jobId;
    this.options = Object.assign({
      playlists: true,        // true, false, or an array of source playlist ids
      albums: true,
      artists: true,
      tracks: true,
      dryRun: false,
      strict: false,
      toleranceMs: undefined,
      onExisting: "add-missing",   // "add-missing" | "create-new" | "skip"
      concurrency: DEFAULT_CONCURRENCY,
      playlistSuffix: "",
      includeOthersPlaylists: false,
    }, o.options || {});

    this.cancelled = false;
    this.items = [];
    this.counts = { matched: 0, unmatched: 0, skipped: 0, already: 0, failed: 0, written: 0 };
    this.progress = { phase: "starting", label: "", done: 0, total: 0, counts: this.counts };
    this._lastProgressAt = 0;
    this._searches = 0;
    this._cacheHits = 0;
  }

  cancel() { this.cancelled = true; }

  checkCancelled() { if (this.cancelled) throw new Cancelled(); }

  report(patch) {
    Object.assign(this.progress, patch || {}, { counts: this.counts,
      searches: this._searches, cacheHits: this._cacheHits });
    const now = Date.now();
    const finalPhase = this.progress.phase === "done" || this.progress.phase === "failed";
    if (finalPhase || now - this._lastProgressAt >= PROGRESS_INTERVAL_MS) {
      this._lastProgressAt = now;
      this.store.updateProgress(this.jobId, this.progress);
    }
  }

  /**
   * @param {number} [countAs] how many things this row stands for. One,
   *   normally. A failed BATCH write is the exception: it is a single row that
   *   accounts for every id in the batch, and counting it as one understated
   *   the damage by forty-nine.
   */
  record(item, countAs) {
    this.items.push(item);
    const n = countAs === undefined ? 1 : Number(countAs) || 0;
    if (this.counts[item.status] !== undefined) this.counts[item.status] += n;
    // Flushed in batches: one transaction per hundred rows rather than a fsync
    // each. The job survives a crash to within a hundred tracks, which is the
    // right trade for a job that is re-runnable by design.
    if (this.items.length >= 100) this.flush();
  }

  flush() {
    if (!this.items.length) return;
    this.store.addItems(this.jobId, this.items);
    this.items = [];
  }

  // ------------------------------------------------------------------- run

  async run() {
    try {
      this.report({ phase: "reading", label: "Reading your libraries" });

      // The target's existing library, read once. Everything already there is
      // skipped without a single search, which on a re-run is most of the job.
      const existing = await this.readExisting();
      this.checkCancelled();

      if (this.options.tracks) await this.migrateSavedTracks(existing);
      if (this.options.albums) await this.migrateSavedAlbums(existing);
      if (this.options.artists) await this.migrateArtists(existing);
      if (this.options.playlists) await this.migratePlaylists();

      this.flush();
      this.report({ phase: "done", label: "Finished" });
      return { counts: this.counts };
    } catch (e) {
      this.flush();
      if (e && e.cancelled) {
        this.report({ phase: "cancelled", label: "Cancelled" });
        throw e;
      }
      this.report({ phase: "failed", label: e && e.message ? e.message : "Failed" });
      throw e;
    }
  }

  /**
   * What the destination already has.
   *
   * Indexed by ISRC and by a canonical "title|artist" key, because the same
   * recording arrives with different ids on the two services and the id is the
   * one thing that cannot be compared. Only the kinds actually being migrated
   * are read — a playlists-only run has no reason to page someone's 4,000
   * saved tracks.
   */
  async readExisting() {
    const out = { trackIsrc: new Set(), trackKey: new Set(),
                  albumUpc: new Set(), albumKey: new Set(), artistKey: new Set() };
    if (this.options.tracks) {
      for (const t of await this.target.savedTracks()) {
        if (t.isrc) out.trackIsrc.add(String(t.isrc).toUpperCase());
        out.trackKey.add(trackKey(t));
      }
    }
    this.checkCancelled();
    if (this.options.albums) {
      for (const a of await this.target.savedAlbums()) {
        if (a.upc) out.albumUpc.add(digits(a.upc));
        out.albumKey.add(albumKey(a));
      }
    }
    this.checkCancelled();
    if (this.options.artists) {
      for (const a of await this.target.followedArtists()) out.artistKey.add(canon(a.name));
    }
    return out;
  }

  // -------------------------------------------------------- saved tracks

  async migrateSavedTracks(existing) {
    const source = await this.source.savedTracks();
    this.checkCancelled();
    this.report({ phase: "matching", step: "tracks", total: source.length, done: 0,
                  label: `Favourite tracks — ${source.length} to check` });

    const toWrite = [];
    await this.eachWithConcurrency(source, async (t, i) => {
      this.checkCancelled();
      const label = trackLabel(t);
      if (existing.trackIsrc.has(String(t.isrc || "").toUpperCase()) && t.isrc) {
        this.record({ kind: "track", sourceId: t.id, sourceLabel: label,
                      status: "already", note: "already a favourite there" });
      } else if (existing.trackKey.has(trackKey(t))) {
        this.record({ kind: "track", sourceId: t.id, sourceLabel: label,
                      status: "already", note: "already a favourite there" });
      } else {
        const r = await this.resolveTrack(t);
        if (r.id) {
          toWrite.push(r.id);
          this.record({ kind: "track", sourceId: t.id, sourceLabel: label,
                        targetId: r.id, status: "matched", method: r.method, note: r.reason });
        } else {
          this.record({ kind: "track", sourceId: t.id, sourceLabel: label,
                        status: "unmatched", note: r.reason });
        }
      }
      this.report({ done: i + 1, label: `Favourite tracks — ${label}` });
    });

    await this.write("favourite tracks", toWrite,
      (ids) => this.target.saveTracks(ids));
  }

  // -------------------------------------------------------- saved albums

  async migrateSavedAlbums(existing) {
    const source = await this.source.savedAlbums();
    this.checkCancelled();
    this.report({ phase: "matching", step: "albums", total: source.length, done: 0,
                  label: `Favourite albums — ${source.length} to check` });

    const toWrite = [];
    await this.eachWithConcurrency(source, async (a, i) => {
      this.checkCancelled();
      const label = albumLabel(a);
      if ((a.upc && existing.albumUpc.has(digits(a.upc))) || existing.albumKey.has(albumKey(a))) {
        this.record({ kind: "album", sourceId: a.id, sourceLabel: label,
                      status: "already", note: "already a favourite there" });
      } else {
        const r = await this.resolveAlbum(a);
        if (r.id) {
          toWrite.push(r.id);
          this.record({ kind: "album", sourceId: a.id, sourceLabel: label,
                        targetId: r.id, status: "matched", method: r.method, note: r.reason });
        } else {
          this.record({ kind: "album", sourceId: a.id, sourceLabel: label,
                        status: "unmatched", note: r.reason });
        }
      }
      this.report({ done: i + 1, label: `Favourite albums — ${label}` });
    });

    await this.write("favourite albums", toWrite, (ids) => this.target.saveAlbums(ids));
  }

  // ------------------------------------------------------------- artists

  async migrateArtists(existing) {
    const source = await this.source.followedArtists();
    this.checkCancelled();
    this.report({ phase: "matching", step: "artists", total: source.length, done: 0,
                  label: `Artists — ${source.length} to check` });

    const toWrite = [];
    await this.eachWithConcurrency(source, async (a, i) => {
      this.checkCancelled();
      if (existing.artistKey.has(canon(a.name))) {
        this.record({ kind: "artist", sourceId: a.id, sourceLabel: a.name,
                      status: "already", note: "already followed there" });
      } else {
        const r = await this.resolveArtist(a);
        if (r.id) {
          toWrite.push(r.id);
          this.record({ kind: "artist", sourceId: a.id, sourceLabel: a.name,
                        targetId: r.id, status: "matched", method: r.method, note: r.reason });
        } else {
          this.record({ kind: "artist", sourceId: a.id, sourceLabel: a.name,
                        status: "unmatched", note: r.reason });
        }
      }
      this.report({ done: i + 1, label: `Artists — ${a.name}` });
    });

    await this.write("artists", toWrite, (ids) => this.target.followArtists(ids));
  }

  // ----------------------------------------------------------- playlists

  async migratePlaylists() {
    const all = await this.source.playlists();

    let wanted;
    if (Array.isArray(this.options.playlists)) {
      // The user named them. No ownership check is wanted or needed.
      wanted = all.filter((p) =>
        this.options.playlists.map(String).includes(String(p.id)));
    } else {
      // Filtering to the user's OWN playlists needs to know who they are, and
      // a client built straight from a stored session has never called me() —
      // /api/migrate does not call it either. When the stored id was empty,
      // `p.ownerId === ""` was false for every playlist, EVERY ONE was
      // filtered out, and the migration reported nothing while looking like it
      // had simply found nothing to do.
      const me = await this.sourceAccountId();
      wanted = all.filter((p) => this.options.includeOthersPlaylists ||
        !p.ownerId ||
        // Still unknown: the service would not say. There is then no way to
        // tell an owned playlist from a followed one, and including them is
        // the useful failure — silently migrating nothing is not.
        !me ||
        p.ownerId === me);
    }

    // Read once, before the loop: the destination's own playlists, so a second
    // run recognises what it made last time. Doing this per playlist would be
    // one full paged read per playlist.
    const targetPlaylists = await this.target.playlists();
    const byName = new Map();
    for (const p of targetPlaylists) byName.set(canon(p.name), p);

    for (let pi = 0; pi < wanted.length; pi++) {
      this.checkCancelled();
      const pl = wanted[pi];
      const targetName = (pl.name || "Playlist") + (this.options.playlistSuffix || "");
      const existingPl = byName.get(canon(targetName));

      if (existingPl && this.options.onExisting === "skip") {
        this.record({ kind: "playlist", sourceId: pl.id, sourceLabel: pl.name,
                      targetId: existingPl.id, status: "skipped",
                      note: `a playlist called "${targetName}" is already there` });
        continue;
      }

      const tracks = await this.source.playlistTracks(pl.id);
      this.report({ phase: "matching", step: "playlist", total: tracks.length, done: 0,
        label: `Playlist ${pi + 1} of ${wanted.length}: ${pl.name} (${tracks.length} tracks)` });

      // Already in the destination playlist, when reusing it. Only then: for a
      // brand-new playlist this is a request that can only return nothing.
      let present = new Set();
      if (existingPl && this.options.onExisting === "add-missing") {
        for (const t of await this.target.playlistTracks(existingPl.id)) {
          if (t && t.id) present.add(String(t.id));
        }
      }

      // Positions are preserved even though lookups run concurrently: results
      // are written back into a slot, and the slots are read in order
      // afterwards. Resolving in parallel and pushing as they land would
      // shuffle every playlist into completion order.
      const resolved = new Array(tracks.length).fill(null);
      await this.eachWithConcurrency(tracks, async (t, i) => {
        this.checkCancelled();
        if (t && t.skip) {
          this.record({ kind: "track", container: pl.name, sourceId: "local:" + i,
                        sourceLabel: t.title || "(unnamed)", status: "skipped",
                        note: `a ${t.skip}, which cannot be migrated` });
        } else {
          const r = await this.resolveTrack(t);
          if (r.id) {
            resolved[i] = r.id;
            this.record({ kind: "track", container: pl.name, sourceId: t.id,
                          sourceLabel: trackLabel(t), targetId: r.id,
                          status: "matched", method: r.method, note: r.reason });
          } else {
            this.record({ kind: "track", container: pl.name, sourceId: t.id,
                          sourceLabel: trackLabel(t), status: "unmatched", note: r.reason });
          }
        }
        this.report({ done: i + 1 });
      });

      const ordered = resolved.filter(Boolean).filter((id) => !present.has(String(id)));
      // De-duplicate WITHIN this playlist as well: two different source tracks
      // can legitimately resolve to one destination track (the album cut and
      // the single, same ISRC), and adding it twice is a playlist with a
      // duplicate the user did not have.
      const seen = new Set();
      const unique = ordered.filter((id) => !seen.has(id) && seen.add(id));

      if (this.options.dryRun) {
        this.record({ kind: "playlist", sourceId: pl.id, sourceLabel: pl.name,
                      status: "matched",
                      note: `would ${existingPl ? "add to" : "create"} "${targetName}" ` +
                            `with ${unique.length} track${unique.length === 1 ? "" : "s"}` });
        continue;
      }

      if (!unique.length) {
        this.record({ kind: "playlist", sourceId: pl.id, sourceLabel: pl.name,
                      targetId: existingPl ? existingPl.id : null, status: "already",
                      note: existingPl ? "every track was already in it"
                                       : "nothing in it could be matched, so it was not created" });
        continue;
      }

      this.report({ phase: "writing", label: `Writing ${targetName}` });
      try {
        let destId, note;
        if (existingPl && this.options.onExisting === "add-missing") {
          destId = existingPl.id;
          note = `added ${unique.length} track${unique.length === 1 ? "" : "s"} ` +
                 `to the existing "${targetName}"`;
        } else {
          const made = await this.target.createPlaylist(targetName, {
            public: false,
            description: `Migrated from ${titleCase(this.sourceName)} by MusicD Migrate.`,
          });
          destId = made.id;
          note = `created with ${unique.length} track${unique.length === 1 ? "" : "s"}`;
        }
        await this.target.addToPlaylist(destId, unique);
        this.counts.written += unique.length;
        this.record({ kind: "playlist", sourceId: pl.id, sourceLabel: pl.name,
                      targetId: destId, status: "matched", note });
      } catch (e) {
        this.record({ kind: "playlist", sourceId: pl.id, sourceLabel: pl.name,
                      status: "failed", note: e && e.message });
      }
    }
  }

  /**
   * Who the source account is, asking the service once if it does not know.
   *
   * Cached on the instance: a failure is worth one request per migration, not
   * one per playlist.
   */
  async sourceAccountId() {
    if (this._sourceAccountId !== undefined) return this._sourceAccountId;
    let id = String(this.source.userId || "");
    if (!id) {
      try {
        const account = await this.source.me();
        id = String((account && account.id) || this.source.userId || "");
      } catch (e) {
        if (e && (e.auth || e.code === 401)) throw e;
        id = "";
      }
    }
    this._sourceAccountId = id;
    return id;
  }

  // --------------------------------------------------------------- lookups

  /**
   * The lookup, and the whole reason a migration takes minutes rather than
   * hours: three chances to answer without a network request at all, then at
   * most two searches.
   */
  async resolveTrack(t) {
    if (!t || !t.id) return { id: null, reason: "the source returned no usable track" };

    const cached = this.store.cachedMatch(this.sourceName, t.id, this.targetName, "track");
    if (cached) {
      this._cacheHits++;
      return cached.toId
        ? { id: cached.toId, method: cached.method, reason: "from the match cache" }
        : { id: null, reason: cached.method || "not found on a previous run" };
    }

    const opts = { strict: this.options.strict, toleranceMs: this.options.toleranceMs };
    let result = null;

    if (t.isrc) {
      this._searches++;
      const cands = await safely(() => this.target.searchByIsrc(t.isrc), []);
      result = matchTrack(cands, t, opts);
    }

    if ((!result || !result.track) && !this.options.strict) {
      // Searched WITHOUT the edition suffix and with the lead artist only.
      // Searching for "Blue Monday - 2016 Remaster" by "New Order, Someone"
      // finds nothing on a service that calls it "Blue Monday" by "New Order",
      // and that is the single largest source of false misses.
      this._searches++;
      const title = stripVersion(t.title);
      const artist = t.artists && t.artists.length ? t.artists[0] : "";
      const cands = await safely(() => this.target.searchTracks(title, artist), []);
      const r2 = matchTrack(cands, t, opts);
      // Keep whichever actually matched, and otherwise whichever refusal is
      // more informative — the ISRC one says only "the search returned
      // nothing", which tells the user nothing they can act on.
      result = r2.track ? r2 : (result && result.track ? result : r2);
    }

    const id = result && result.track ? String(result.track.id) : null;
    this.store.cacheMatch(this.sourceName, t.id, this.targetName, "track", id,
      id ? result.method : (result ? result.reason : "not found"));
    return id ? { id, method: result.method, reason: result.reason }
              : { id: null, reason: (result && result.reason) || "not found" };
  }

  async resolveAlbum(a) {
    if (!a || !a.id) return { id: null, reason: "the source returned no usable album" };
    const cached = this.store.cachedMatch(this.sourceName, a.id, this.targetName, "album");
    if (cached) {
      this._cacheHits++;
      return cached.toId ? { id: cached.toId, method: cached.method, reason: "from the match cache" }
                         : { id: null, reason: cached.method || "not found on a previous run" };
    }

    // The barcode first, exactly as a track's ISRC goes first.
    //
    // Get it before searching, not after: an earlier version searched by title
    // and only THEN looked the barcode up, which meant the barcode was never
    // used to search for anything. Combined with Spotify's album search not
    // returning barcodes at all (see SpotifyClient.searchByUpc), the whole
    // barcode tier was dead and every album fell through to the title tiers —
    // 114 of 195 albums reported "not found" on a real library.
    let want = a;
    if (!a.upc) {
      const detailed = await safely(() => this.source.albumDetail(a.id), null);
      if (detailed && detailed.upc) want = Object.assign({}, a, { upc: detailed.upc });
    }

    let r = null;
    if (want.upc) {
      this._searches++;
      const byUpc = await safely(() => this.target.searchByUpc(want.upc), []);
      r = matchAlbum(byUpc, want, { strict: this.options.strict });
    }

    // No barcode, or the barcode found nothing: fall back to title and artist,
    // with the edition suffix removed so "Master Of Puppets (Remastered)"
    // searches for the album Spotify calls "Master of Puppets".
    if ((!r || !r.album) && !this.options.strict) {
      this._searches++;
      const title = stripVersion(a.title);
      const artist = a.artists && a.artists.length ? a.artists[0] : "";
      const cands = await safely(() => this.target.searchAlbums(title, artist), []);
      const r2 = matchAlbum(cands, want, { strict: this.options.strict });
      // Keep whichever matched; otherwise keep the more informative refusal,
      // since "the search returned nothing" tells the user nothing to act on.
      r = r2.album ? r2 : (r && r.album ? r : r2);
    }
    if (!r) r = matchAlbum([], want, { strict: this.options.strict });
    const id = r.album ? String(r.album.id) : null;
    this.store.cacheMatch(this.sourceName, a.id, this.targetName, "album", id,
      id ? r.method : r.reason);
    return id ? { id, method: r.method, reason: r.reason } : { id: null, reason: r.reason };
  }

  async resolveArtist(a) {
    if (!a || !a.id) return { id: null, reason: "the source returned no usable artist" };
    const cached = this.store.cachedMatch(this.sourceName, a.id, this.targetName, "artist");
    if (cached) {
      this._cacheHits++;
      return cached.toId ? { id: cached.toId, method: cached.method, reason: "from the match cache" }
                         : { id: null, reason: cached.method || "not found on a previous run" };
    }
    this._searches++;
    const cands = await safely(() => this.target.searchArtists(a.name), []);
    const r = matchArtist(cands, a);
    const id = r.artist ? String(r.artist.id) : null;
    this.store.cacheMatch(this.sourceName, a.id, this.targetName, "artist", id,
      id ? r.method : r.reason);
    return id ? { id, method: r.method, reason: r.reason } : { id: null, reason: r.reason };
  }

  // --------------------------------------------------------------- writing

  async write(what, ids, fn) {
    if (this.options.dryRun) return;
    if (!ids.length) return;
    this.report({ phase: "writing", label: `Saving ${ids.length} ${what}` });
    try {
      await fn(ids);
      this.counts.written += ids.length;
    } catch (e) {
      // The whole batch failed, so every id in it is unwritten. One row rather
      // than one per id, because the endpoint does not say which of the fifty
      // it objected to and a row per id would claim knowledge there is none of
      // — but it counts as all of them, which is the true number unwritten.
      this.record({ kind: "write", sourceId: what, sourceLabel: what, status: "failed",
                    note: `saving ${ids.length} ${what} failed: ${e && e.message}` },
                  ids.length);
    }
  }

  /** A tiny worker pool. `fn` gets the item and its index, so callers that
   *  care about order can write results into a slot. */
  async eachWithConcurrency(list, fn) {
    const n = Math.max(1, Math.min(Number(this.options.concurrency) || DEFAULT_CONCURRENCY, 8));
    let next = 0;
    const workers = Array.from({ length: Math.min(n, list.length) }, async () => {
      for (;;) {
        const i = next++;
        if (i >= list.length) return;
        await fn(list[i], i);
      }
    });
    await Promise.all(workers);
  }
}

// ------------------------------------------------------------------ helpers

/**
 * Run something that talks to a service, and treat a failure as "no results".
 *
 * Only ever wrapped around a SEARCH. A search that errors means this one track
 * cannot be looked up, and the right answer is to report it unmatched and
 * carry on — failing the whole migration because one lookup timed out would
 * throw away an hour of work over one track. Reads and writes are deliberately
 * NOT wrapped: those failing means something is actually wrong.
 */
async function safely(fn, fallback) {
  try {
    return await fn();
  } catch (e) {
    if (e && (e.auth || e.code === 401)) throw e; // a dead sign-in is not "no results"
    return fallback;
  }
}

function trackKey(t) {
  return canon(stripVersion(t.title)) + "|" + primaryArtist((t.artists || [])[0] || "");
}

function albumKey(a) {
  return canon(stripVersion(a.title)) + "|" + primaryArtist((a.artists || [])[0] || "");
}

function trackLabel(t) {
  const a = (t.artists || []).join(", ");
  return a ? `${t.title} — ${a}` : String(t.title || "");
}

function albumLabel(a) {
  const ar = (a.artists || []).join(", ");
  return ar ? `${a.title} — ${ar}` : String(a.title || "");
}

function digits(s) { return String(s || "").replace(/\D/g, "").replace(/^0+/, ""); }

function titleCase(s) {
  return String(s || "").charAt(0).toUpperCase() + String(s || "").slice(1);
}

module.exports = { Migration, Cancelled, trackKey, albumKey, trackLabel };
