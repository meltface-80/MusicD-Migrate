"use strict";
/*
 * store.js — everything this app remembers between runs.
 *
 * Three things live here and they have very different lifetimes:
 *
 *   settings     the two services' sign-ins. Written rarely, read constantly.
 *   match cache  "this Qobuz track is that Spotify track". The expensive thing
 *                in a migration is not writing, it is LOOKING UP: a 2,000-track
 *                library is 2,000 searches against a rate-limited API. Caching
 *                the answer makes a second run of the same migration nearly
 *                free, which matters because the second run is the common one —
 *                you migrate, you add records, you migrate again.
 *   jobs         what a run did, item by item. Kept because the interesting
 *                output of a migration is not "done", it is the list of the
 *                forty tracks that could NOT be matched, and that list is worth
 *                reading days later.
 *
 * SQLite rather than a JSON file for one reason: a migration writes a row per
 * track as it goes, and a crashed or killed container must not lose the job.
 */

const fs = require("fs");
const path = require("path");
const Database = require("better-sqlite3");

const SCHEMA = `
CREATE TABLE IF NOT EXISTS settings (
  key    TEXT PRIMARY KEY,
  value  TEXT NOT NULL
);

-- One row per (source track, target service). The source id is namespaced by
-- its service because Qobuz and Spotify ids are both opaque strings and would
-- otherwise collide silently.
CREATE TABLE IF NOT EXISTS match_cache (
  from_service  TEXT NOT NULL,
  from_id       TEXT NOT NULL,
  to_service    TEXT NOT NULL,
  kind          TEXT NOT NULL,
  to_id         TEXT,
  method        TEXT NOT NULL,
  at            INTEGER NOT NULL,
  PRIMARY KEY (from_service, from_id, to_service, kind)
);

CREATE TABLE IF NOT EXISTS jobs (
  id         TEXT PRIMARY KEY,
  created    INTEGER NOT NULL,
  finished   INTEGER,
  direction  TEXT NOT NULL,
  status     TEXT NOT NULL,
  dry_run    INTEGER NOT NULL DEFAULT 0,
  options    TEXT NOT NULL,
  progress   TEXT NOT NULL,
  error      TEXT
);

CREATE TABLE IF NOT EXISTS job_items (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  job_id        TEXT NOT NULL,
  kind          TEXT NOT NULL,
  container     TEXT,
  source_id     TEXT NOT NULL,
  source_label  TEXT NOT NULL,
  target_id     TEXT,
  status        TEXT NOT NULL,
  method        TEXT,
  note          TEXT
);

CREATE INDEX IF NOT EXISTS job_items_job ON job_items(job_id);
CREATE INDEX IF NOT EXISTS job_items_status ON job_items(job_id, status);

-- The scanned Roon library: one row per album, keyed by a hash of its
-- normalised title and artist rather than by Roon's own item_key.
--
-- Roon's item_keys are SESSION-SCOPED and must never be stored: they are
-- meaningless the moment the browse session is re-navigated, and a stored one
-- would drill into whatever now happens to sit at that key. 'position' is the
-- album's offset in Roon's alphabetically-stable album list, kept as a hint
-- for finding it again -- checked against the title before it is used, so a
-- stale hint can only cost a slower lookup, never the wrong album.
--
-- track_count is null until something drills into the album. A ten thousand
-- album library is a hundred load calls to list; drilling every one of them
-- for a track count would be twenty thousand.
CREATE TABLE IF NOT EXISTS roon_albums (
  core_id      TEXT NOT NULL,
  album_key    TEXT NOT NULL,
  title        TEXT NOT NULL,
  artist       TEXT NOT NULL,
  position     INTEGER NOT NULL,
  image_key    TEXT,
  track_count  INTEGER,
  scanned_at   INTEGER NOT NULL,
  PRIMARY KEY (core_id, album_key)
);
`;

function open(dir) {
  const dataDir = dir || path.join(__dirname, "..", "data");
  fs.mkdirSync(dataDir, { recursive: true });
  const db = new Database(path.join(dataDir, "migrate.db"));
  // The job runner writes a row per track while the UI polls progress on
  // another connection-less read. WAL is what stops those blocking each other.
  db.pragma("journal_mode = WAL");
  db.exec(SCHEMA);
  return new Store(db);
}

class Store {
  constructor(db) {
    this.db = db;
  }

  // ------------------------------------------------------------- settings

  get(key, fallback = null) {
    const row = this.db.prepare("SELECT value FROM settings WHERE key = ?").get(key);
    if (!row) return fallback;
    try {
      return JSON.parse(row.value);
    } catch (e) {
      // A value written by an older build, or a truncated write. Reporting the
      // fallback is right: this is a cache of a sign-in, not a source of truth
      // a user typed, and "signed out" is a recoverable state.
      return fallback;
    }
  }

  put(key, value) {
    this.db.prepare(
      "INSERT INTO settings (key, value) VALUES (?, ?) " +
      "ON CONFLICT(key) DO UPDATE SET value = excluded.value"
    ).run(key, JSON.stringify(value));
  }

  del(key) {
    this.db.prepare("DELETE FROM settings WHERE key = ?").run(key);
  }

  // ---------------------------------------------------------- match cache

  /**
   * @returns {{toId:string|null, method:string}|null}
   *
   * A cached MISS (toId null) is a real answer and is returned as one. The
   * alternative — treating "we looked and found nothing" as "we have not
   * looked" — makes every re-run pay again for exactly the tracks that are
   * slowest, because a miss costs the full fallback search.
   */
  cachedMatch(fromService, fromId, toService, kind) {
    const row = this.db.prepare(
      "SELECT to_id, method FROM match_cache " +
      "WHERE from_service = ? AND from_id = ? AND to_service = ? AND kind = ?"
    ).get(fromService, String(fromId), toService, kind);
    if (!row) return null;
    return { toId: row.to_id, method: row.method };
  }

  cacheMatch(fromService, fromId, toService, kind, toId, method) {
    this.db.prepare(
      "INSERT INTO match_cache (from_service, from_id, to_service, kind, to_id, method, at) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?) " +
      "ON CONFLICT(from_service, from_id, to_service, kind) DO UPDATE SET " +
      "  to_id = excluded.to_id, method = excluded.method, at = excluded.at"
    ).run(fromService, String(fromId), toService, kind,
          toId == null ? null : String(toId), method, Date.now());
  }

  /** Forget every cached lookup. Offered in the UI for when a service's
   *  catalogue has changed and a previous "not available" is worth retrying. */
  clearMatchCache() {
    this.db.prepare("DELETE FROM match_cache").run();
  }

  matchCacheSize() {
    return this.db.prepare("SELECT COUNT(*) AS n FROM match_cache").get().n;
  }

  // ---------------------------------------------------------- roon library

  /**
   * Store a page of scanned albums.
   *
   * One transaction per page, not per album: a ten thousand album scan is a
   * hundred transactions rather than ten thousand fsyncs, and a scan killed
   * halfway has still kept everything up to the last page -- which is what
   * makes it resumable.
   *
   * @returns {{stored:number, duplicates:number}} duplicates are albums whose
   *   normalised title and artist collide with one already stored: a second
   *   copy of the same record, a CD and a vinyl rip of it. One of them is
   *   enough to migrate, but the number is reported so the totals add up and
   *   the user is not left wondering why 10,014 albums became 9,987.
   */
  saveRoonAlbums(coreId, rows) {
    const insert = this.db.prepare(
      "INSERT INTO roon_albums " +
      "  (core_id, album_key, title, artist, position, image_key, scanned_at) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?) " +
      "ON CONFLICT(core_id, album_key) DO NOTHING"
    );
    const now = Date.now();
    let stored = 0;
    const all = this.db.transaction((items) => {
      for (const r of items) {
        stored += insert.run(coreId, r.albumKey, r.title, r.artist || "",
                             r.position || 0, r.imageKey || null, now).changes;
      }
    });
    all(rows || []);
    return { stored, duplicates: (rows || []).length - stored };
  }

  roonAlbums(coreId) {
    return this.db.prepare(
      "SELECT * FROM roon_albums WHERE core_id = ? ORDER BY position"
    ).all(coreId).map(hydrateRoonAlbum);
  }

  roonAlbum(coreId, albumKey) {
    const row = this.db.prepare(
      "SELECT * FROM roon_albums WHERE core_id = ? AND album_key = ?"
    ).get(coreId, albumKey);
    return row ? hydrateRoonAlbum(row) : null;
  }

  roonAlbumCount(coreId) {
    return this.db.prepare("SELECT COUNT(*) AS n FROM roon_albums WHERE core_id = ?")
      .get(coreId).n;
  }

  /** Filled in when something drills into an album, so the next run need not. */
  setRoonAlbumTrackCount(coreId, albumKey, trackCount) {
    this.db.prepare(
      "UPDATE roon_albums SET track_count = ? WHERE core_id = ? AND album_key = ?"
    ).run(trackCount == null ? null : Number(trackCount), coreId, albumKey);
  }

  clearRoonAlbums(coreId) {
    if (coreId) this.db.prepare("DELETE FROM roon_albums WHERE core_id = ?").run(coreId);
    else this.db.prepare("DELETE FROM roon_albums").run();
  }

  // ----------------------------------------------------------------- jobs

  createJob(id, direction, options, dryRun) {
    this.db.prepare(
      "INSERT INTO jobs (id, created, direction, status, dry_run, options, progress) " +
      "VALUES (?, ?, ?, 'running', ?, ?, ?)"
    ).run(id, Date.now(), direction, dryRun ? 1 : 0,
          JSON.stringify(options || {}), JSON.stringify({ phase: "starting" }));
  }

  updateProgress(id, progress) {
    this.db.prepare("UPDATE jobs SET progress = ? WHERE id = ?")
      .run(JSON.stringify(progress || {}), id);
  }

  finishJob(id, status, error) {
    this.db.prepare("UPDATE jobs SET status = ?, finished = ?, error = ? WHERE id = ?")
      .run(status, Date.now(), error || null, id);
  }

  job(id) {
    const row = this.db.prepare("SELECT * FROM jobs WHERE id = ?").get(id);
    return row ? hydrateJob(row) : null;
  }

  jobs(limit = 25) {
    return this.db.prepare("SELECT * FROM jobs ORDER BY created DESC LIMIT ?")
      .all(limit).map(hydrateJob);
  }

  /**
   * Any job still marked running when the process starts again was killed —
   * the container stopped, the phone dropped the service. Nothing resumes it,
   * so leaving it as "running" would show a spinner that never ends.
   */
  markOrphansInterrupted() {
    const n = this.db.prepare(
      "UPDATE jobs SET status = 'interrupted', finished = ?, " +
      "error = 'The app stopped while this was running.' WHERE status = 'running'"
    ).run(Date.now()).changes;
    return n;
  }

  addItem(jobId, item) {
    this.db.prepare(
      "INSERT INTO job_items (job_id, kind, container, source_id, source_label, " +
      "target_id, status, method, note) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    ).run(jobId, item.kind, item.container || null, String(item.sourceId),
          item.sourceLabel || "", item.targetId == null ? null : String(item.targetId),
          item.status, item.method || null, item.note || null);
  }

  /** One transaction per batch: a per-row transaction on WAL is a fsync each,
   *  and a 2,000-track playlist felt every one of them. */
  addItems(jobId, items) {
    const insert = this.db.transaction((rows) => {
      for (const r of rows) this.addItem(jobId, r);
    });
    insert(items || []);
  }

  /**
   * Rows come back in the app's shape, not the table's.
   *
   * The columns are snake_case because SQL is, and everything that reads them
   * — the API, the CSV export, the UI — is camelCase because JavaScript is.
   * Translating here rather than at each reader is what stops one of them
   * quietly reading `row.targetId` off a row that only has `target_id` and
   * rendering `undefined` for every match.
   */
  items(jobId, status) {
    const rows = status
      ? this.db.prepare(
          "SELECT * FROM job_items WHERE job_id = ? AND status = ? ORDER BY id"
        ).all(jobId, status)
      : this.db.prepare("SELECT * FROM job_items WHERE job_id = ? ORDER BY id").all(jobId);
    return rows.map(hydrateItem);
  }

  itemCounts(jobId) {
    const rows = this.db.prepare(
      "SELECT status, COUNT(*) AS n FROM job_items WHERE job_id = ? GROUP BY status"
    ).all(jobId);
    const out = {};
    for (const r of rows) out[r.status] = r.n;
    return out;
  }

  deleteJob(id) {
    this.db.prepare("DELETE FROM job_items WHERE job_id = ?").run(id);
    this.db.prepare("DELETE FROM jobs WHERE id = ?").run(id);
  }

  close() {
    this.db.close();
  }
}

/** snake_case out of SQLite, camelCase into the rest of the app. */
function hydrateRoonAlbum(row) {
  return {
    albumKey: row.album_key,
    title: row.title,
    artist: row.artist,
    position: row.position,
    imageKey: row.image_key,
    trackCount: row.track_count === null ? null : row.track_count,
    scannedAt: row.scanned_at,
  };
}

function hydrateJob(row) {
  return {
    id: row.id,
    created: row.created,
    finished: row.finished,
    direction: row.direction,
    status: row.status,
    dryRun: !!row.dry_run,
    options: safeParse(row.options, {}),
    progress: safeParse(row.progress, {}),
    error: row.error || null,
  };
}

function hydrateItem(row) {
  return {
    id: row.id,
    kind: row.kind,
    container: row.container || null,
    sourceId: row.source_id,
    sourceLabel: row.source_label,
    targetId: row.target_id || null,
    status: row.status,
    method: row.method || null,
    note: row.note || null,
  };
}

function safeParse(s, fallback) {
  try {
    return JSON.parse(s);
  } catch (e) {
    return fallback;
  }
}

module.exports = { open, Store };
