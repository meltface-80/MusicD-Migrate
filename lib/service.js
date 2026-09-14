"use strict";
/*
 * service.js — what a "service" has to be able to do, and the two answers.
 *
 * WHY THIS FILE EXISTS
 *
 * Until now every service could do everything: lib/spotify.js and lib/qobuz.js
 * both read a library and both write one, so the migration engine held a
 * `source` and a `target` and never had to care that they were the same shape.
 *
 * Roon breaks that. A Roon library is somebody's own files on their own disk,
 * reached through the browse API — it can be READ and it cannot be WRITTEN.
 * There is no "add this album to Roon"; the album would have to exist as a
 * file first. So a Roon client implements the reading half and nothing else.
 *
 * WHY IT IS A RUNTIME CHECK AND NOT JUST A COMMENT
 *
 * JavaScript has no interfaces, so the failure mode for handing a source-only
 * client to the engine as a TARGET is a TypeError several layers down, inside
 * a worker, inside safely() — which exists precisely to turn a failed search
 * into one unmatched track. A whole migration would report four thousand
 * misses and look like a library that simply is not on the other service.
 * That is the invisible failure CLAUDE.md is about, so it is checked once, at
 * the top, and it throws.
 *
 * The lists are the SAME lists as MusicSource and MusicTarget in
 * android/core/.../Model.kt, and ContractTest.kt fails if they drift.
 */

/**
 * Reading a library. Roon does these; Spotify and Qobuz do them too.
 *
 * `albumDetail` is in here rather than with the searches because it is asked
 * of the SOURCE — it is how a source album's barcode is fetched before
 * anything is searched for. A source that has no barcodes to give (Roon)
 * still has to answer; it answers null.
 */
const SOURCE_METHODS = [
  "me",
  "playlists",
  "playlistTracks",
  "savedTracks",
  "savedAlbums",
  "followedArtists",
  "albumDetail",
  /**
   * An album's track listing. In the READ half because it is asked of both
   * sides: of the source to learn what the user owns, and of a candidate on
   * the target to corroborate a match made without a barcode. See
   * tracklistCorroborates in lib/match.js — for a Roon album it is the only
   * independent evidence there is.
   */
  "albumTracks",
];

/**
 * Searching and writing. Only a service that can be migrated INTO needs these.
 *
 * A target is also a source: the engine reads the target's existing library up
 * front so that everything already there is skipped rather than searched for.
 */
const TARGET_METHODS = SOURCE_METHODS.concat([
  "searchByIsrc",
  "searchTracks",
  "searchAlbums",
  "searchByUpc",
  "searchArtists",
  "createPlaylist",
  "addToPlaylist",
  "saveTracks",
  "saveAlbums",
  "followArtists",
]);

function missing(svc, methods) {
  if (!svc || typeof svc !== "object") return methods.slice();
  return methods.filter((m) => typeof svc[m] !== "function");
}

/** True if this object can be read from. */
function isSource(svc) { return missing(svc, SOURCE_METHODS).length === 0; }

/** True if this object can also be searched and written to. */
function isTarget(svc) { return missing(svc, TARGET_METHODS).length === 0; }

/**
 * @param {object} svc
 * @param {string} [label] how to name it in the error — "source" / "target".
 */
function assertSource(svc, label) {
  const gaps = missing(svc, SOURCE_METHODS);
  if (gaps.length) {
    throw new Error(`${label || "service"} cannot be read from: ` +
      `missing ${gaps.join(", ")}`);
  }
}

function assertTarget(svc, label) {
  const gaps = missing(svc, TARGET_METHODS);
  if (gaps.length) {
    // Named on purpose. "roon cannot be migrated into" is a sentence the user
    // can act on; a TypeError on `undefined is not a function` is not.
    throw new Error(`${label || "service"} cannot be migrated into: ` +
      `missing ${gaps.join(", ")}`);
  }
}

module.exports = { SOURCE_METHODS, TARGET_METHODS, isSource, isTarget,
                   assertSource, assertTarget };
