"use strict";
/*
 * match.js — which track on the other service is this track.
 *
 * This is the only module in the app that can be wrong in a way nobody
 * notices. A failed migration is obvious and recoverable; a migration that
 * quietly put the karaoke version, the radio edit or a covers-band recording
 * into someone's playlist looks like it worked. So the rule is the same one
 * lib/trackmatch.js uses in MusicD-Remote, carried over deliberately:
 *
 *     WHERE THE EVIDENCE IS NOT DECISIVE, MATCH NOTHING.
 *
 * A track reported as unmatched costs the user one line in a report they can
 * act on. A track matched to the wrong recording costs them a playlist they
 * now cannot trust, and they will not find out for months.
 *
 * THREE TIERS, AND THEY ARE NOT INTERCHANGEABLE
 *
 *   isrc   The ISRC is the recording's identifier — the same code on both
 *          services means the same master of the same performance. It is the
 *          only tier that needs no corroboration, and it is the reason both
 *          clients ask for ISRCs on every read.
 *   exact  Titles agree character for character once normalised, the artists
 *          overlap, and the durations agree. Three independent facts.
 *   close  Titles agree only after an EDITION suffix is removed ("- 2011
 *          Remaster"). Weaker evidence, so the duration gate is tighter.
 *
 * Anything below `close` is not a match. There is no "best guess" tier,
 * because a best guess is indistinguishable from a real match once it is
 * written into a playlist.
 *
 * Pure and offline. No network, no account, no service — every decision here
 * is a function of its arguments, which is what makes it testable, and
 * test/unit/match.test.js tests each tier and each refusal.
 */

const { canon, stripVersion, primaryArtist, artistSet, similarity, overlap } = require("./canon");

/**
 * How far two services' durations for the same recording may sit apart.
 *
 * Within one service, the same master rounds the same way and ±2s is
 * generous. ACROSS services it is not: they encode from different deliveries
 * and trim leading/trailing silence differently, and 3-4 second gaps between
 * Qobuz and Spotify for an identical recording are ordinary. Five seconds
 * admits those and still excludes the things worth excluding — a radio edit is
 * typically 60-90s shorter, a live take minutes longer, an extended mix
 * longer again.
 */
const DEFAULT_TOLERANCE_MS = 5000;

/**
 * The tighter gate for a title that only matched after its suffix was removed.
 *
 * At that point the title has stopped being independent evidence — two
 * different recordings can reduce to the same stripped string — so the
 * duration is carrying the decision on its own and has to be held to a
 * standard that a genuinely different recording would fail.
 */
const CLOSE_TOLERANCE_MS = 3000;

/**
 * How much of a source album's track listing has to be present on a candidate
 * before a barcode-less album match is allowed.
 *
 * 0.7, and the number is a judgement about what the two failure modes cost.
 * Too high refuses a record the user owns because one track is titled
 * differently or a bonus track is missing from their rip — one line in a
 * report they can act on. Too low accepts a different record with the same
 * name, which looks like it worked. Seven tracks in ten agreeing is not
 * something two different records do; three in ten routinely is, for a
 * compilation or a live set.
 */
const TRACKLIST_MIN_COVERAGE = 0.7;

/** ISRCs are 12 characters, case- and punctuation-insensitive in the wild. */
function normIsrc(v) {
  const s = String(v == null ? "" : v).toUpperCase().replace(/[^A-Z0-9]/g, "");
  return s.length === 12 ? s : "";
}

/**
 * A track as this app handles it, whichever service it came from.
 *
 * @typedef {object} Track
 * @property {string}   id
 * @property {string}   [isrc]
 * @property {string}   title
 * @property {string[]} artists      every credited artist, most important first
 * @property {string}   [album]
 * @property {number}   [durationMs]
 */

/**
 * @param {Track[]} candidates  what the target service offered
 * @param {Track}   want        the track being migrated
 * @param {object}  [opts]
 * @param {number}  [opts.toleranceMs]
 * @param {boolean} [opts.strict]  ISRC only. Fewer tracks move, and every one
 *   that does is certain — offered in the UI for libraries where that trade is
 *   the right way round.
 * @returns {{track:Track|null, method:string|null, reason:string, score:number}}
 *
 * `reason` is filled in on success AND failure, and it is not decoration: it
 * is what the unmatched report shows the user, and "not found" with no
 * explanation is what makes this class of feature impossible to act on. A
 * report that says «"Blue Monday" is 448s there and 273s here — a different
 * recording» tells someone they have the 12" and can fix it by hand.
 */
function matchTrack(candidates, want, opts) {
  const o = opts || {};
  const tol = Number.isFinite(o.toleranceMs) ? o.toleranceMs : DEFAULT_TOLERANCE_MS;
  const closeTol = Math.min(tol, CLOSE_TOLERANCE_MS);
  const list = Array.isArray(candidates) ? candidates.filter(Boolean) : [];

  if (!list.length) return refuse("the search returned nothing");

  // ---------------------------------------------------------------- tier 1
  const wantIsrc = normIsrc(want && want.isrc);
  if (wantIsrc) {
    const hits = list.filter((c) => normIsrc(c.isrc) === wantIsrc);
    if (hits.length) {
      // More than one is normal and harmless: the same recording appears on
      // the album, the single and three compilations, all carrying the one
      // ISRC. They are the same audio, so any is correct — but the choice has
      // to be DETERMINISTIC or two runs of the same migration disagree and the
      // match cache thrashes. Album title first, then id.
      const best = pickStable(hits, want);
      return {
        track: best, method: "isrc", score: 1,
        reason: "matched on ISRC " + wantIsrc,
      };
    }
    // No ISRC hit is not a refusal. Neither service's catalogue is complete
    // and Qobuz in particular omits the ISRC on plenty of older releases, so
    // falling through to the title tiers is right. What would be wrong is
    // treating the ISRC as decisive evidence AGAINST a candidate: a missing
    // code on one side is missing data, not a different recording.
  }

  if (o.strict) {
    return refuse(wantIsrc
      ? "no track with ISRC " + wantIsrc + " on the other service (strict matching is on)"
      : "this track has no ISRC, and strict matching only accepts ISRC matches");
  }

  // ------------------------------------------------------- tiers 2 and 3
  const wantTitle = canon(want && want.title);
  if (!wantTitle) return refuse("the track has no title to match on");

  const wantStripped = canon(stripVersion(want && want.title));
  const wantArtists = artistSet(want && want.artists);
  const wantPrimary = primaryArtist(
    Array.isArray(want && want.artists) ? (want.artists[0] || "") : (want && want.artists));
  const wantMs = numberOrNull(want && want.durationMs);
  const wantAlbum = canon(want && want.album);

  if (wantMs == null) {
    // Without a duration, a title-and-artist agreement is two facts where the
    // tier needs three, and the two it has are exactly the two that a cover,
    // a re-recording and a live take also satisfy. This is the refusal that
    // does the most work in the whole module.
    return refuse("no duration for this track, so a title match cannot be checked");
  }

  const scored = [];
  for (const c of list) {
    const cTitle = canon(c.title);
    if (!cTitle) continue;
    const cMs = numberOrNull(c.durationMs);
    if (cMs == null) continue;

    const cArtists = artistSet(c.artists);
    const cPrimary = primaryArtist(Array.isArray(c.artists) ? (c.artists[0] || "") : c.artists);
    const artistOverlap = overlap(wantArtists, cArtists);
    const samePrimary = !!wantPrimary && wantPrimary === cPrimary;
    // Either the full credits intersect, or the lead artist is the same person.
    // Both are needed as alternatives: Spotify credits every featured artist
    // where Qobuz names one, and Qobuz sometimes credits an orchestra Spotify
    // files under the conductor.
    if (artistOverlap === 0 && !samePrimary) continue;

    const gap = Math.abs(cMs - wantMs);
    const cStripped = canon(stripVersion(c.title));

    let method = null;
    if (cTitle === wantTitle && gap <= tol) method = "exact";
    else if (cStripped === wantStripped && gap <= closeTol) method = "close";
    if (!method) continue;

    // Ranking only, never admission — everything here has already passed a
    // gate. Duration closeness dominates because it is the one signal that
    // separates two pressings of the same title, and the album agreeing is
    // the tie-break that keeps an album migration's tracks on one release.
    const durationScore = 1 - Math.min(gap / Math.max(tol, 1), 1);
    const albumScore = wantAlbum && canon(c.album) ? similarity(wantAlbum, canon(c.album)) : 0;
    const base = method === "exact" ? 0.8 : 0.6;
    const score = base
      + 0.10 * durationScore
      + 0.06 * artistOverlap
      + 0.04 * albumScore;

    scored.push({ c, method, score, gap });
  }

  if (!scored.length) {
    return refuse(explainNoTier(list, want, wantTitle, wantStripped, wantArtists, wantMs, tol));
  }

  scored.sort((a, b) =>
    b.score - a.score ||
    a.gap - b.gap ||
    String(a.c.id).localeCompare(String(b.c.id)));

  const top = scored[0];
  return {
    track: top.c,
    method: top.method,
    score: Number(top.score.toFixed(4)),
    reason: top.method === "exact"
      ? `matched on title, artist and duration (${Math.round(top.gap / 100) / 10}s apart)`
      : `matched on title without its edition suffix, artist and duration ` +
        `(${Math.round(top.gap / 100) / 10}s apart)`,
  };
}

/**
 * Why nothing passed — named precisely, because the three ways this fails need
 * three different things from the user.
 *
 * "Title is there, duration is not" means they own a different edition and can
 * fix it by hand in a minute. "The title is not there at all" means the track
 * is not on the other service and nothing will fix it. Reporting both as "not
 * found" throws away the difference.
 */
function explainNoTier(list, want, wantTitle, wantStripped, wantArtists, wantMs, tol) {
  const titled = list.filter((c) => {
    const t = canon(c.title);
    return t === wantTitle || canon(stripVersion(c.title)) === wantStripped;
  });
  if (!titled.length) {
    return `nothing called "${want.title}" by that artist on the other service`;
  }
  const byArtist = titled.filter((c) => overlap(wantArtists, artistSet(c.artists)) > 0 ||
    primaryArtist(Array.isArray(c.artists) ? (c.artists[0] || "") : c.artists) ===
      primaryArtist(Array.isArray(want.artists) ? (want.artists[0] || "") : want.artists));
  if (!byArtist.length) {
    return `"${want.title}" is there but credited to a different artist — ` +
           `likely a cover, so it was not taken`;
  }
  const lens = byArtist.map((c) => {
    const ms = numberOrNull(c.durationMs);
    return ms == null ? "?" : Math.round(ms / 1000) + "s";
  }).join(" / ");
  return `"${want.title}" is ${lens} there and ${Math.round(wantMs / 1000)}s here — ` +
         `more than ${Math.round(tol / 1000)}s apart, so a different recording`;
}

/**
 * Albums, by UPC then by title and artist.
 *
 * The same shape as a track match and the same rule, with one difference: an
 * album has no duration, so the corroborating fact is the TRACK COUNT. A
 * deluxe edition and a standard edition share a title and an artist and differ
 * in exactly that, which is the confusion worth catching.
 */
function matchAlbum(candidates, want, opts) {
  const o = opts || {};
  const list = Array.isArray(candidates) ? candidates.filter(Boolean) : [];
  if (!list.length) return refuse("the search returned nothing");

  const wantUpc = String((want && want.upc) || "").replace(/\D/g, "").replace(/^0+/, "");
  if (wantUpc) {
    const hit = list.find((c) =>
      String(c.upc || "").replace(/\D/g, "").replace(/^0+/, "") === wantUpc);
    if (hit) return { track: hit, album: hit, method: "upc", score: 1,
                      reason: "matched on barcode " + wantUpc };
  }
  if (o.strict) return refuse("no album with that barcode (strict matching is on)");

  const wantTitle = canon(want && want.title);
  if (!wantTitle) return refuse("the album has no title to match on");
  const wantStripped = canon(stripVersion(want && want.title));
  const wantArtists = artistSet(want && want.artists);
  const wantTracks = numberOrNull(want && want.trackCount);

  const scored = [];
  for (const c of list) {
    const cTitle = canon(c.title);
    if (!cTitle) continue;
    const artistOverlap = overlap(wantArtists, artistSet(c.artists));
    const samePrimary = primaryArtist(first(want && want.artists)) === primaryArtist(first(c.artists));
    if (artistOverlap === 0 && !samePrimary) continue;

    const exact = cTitle === wantTitle;
    const close = !exact && canon(stripVersion(c.title)) === wantStripped;
    if (!exact && !close) continue;

    const cTracks = numberOrNull(c.trackCount);
    // A stripped-title match with a track count that disagrees is the standard
    // edition being offered for the deluxe, or the reverse. Refuse it: the
    // user asked for the record they own.
    if (close && wantTracks != null && cTracks != null && cTracks !== wantTracks) continue;

    const countScore = (wantTracks != null && cTracks != null)
      ? (cTracks === wantTracks ? 1 : 0) : 0.5;
    const score = (exact ? 0.8 : 0.6) + 0.12 * countScore + 0.08 * artistOverlap;
    scored.push({ c, method: exact ? "exact" : "close", score });
  }

  if (!scored.length) {
    return refuse(`no album called "${want.title}" by that artist on the other service`);
  }
  scored.sort((a, b) => b.score - a.score || String(a.c.id).localeCompare(String(b.c.id)));
  const top = scored[0];
  return { track: top.c, album: top.c, method: top.method, score: Number(top.score.toFixed(4)),
           reason: top.method === "exact" ? "matched on album title and artist"
                                          : "matched on album title without its edition suffix",
           // Everything that passed the title and artist gates, best first.
           // A caller with no barcode to go on corroborates these against the
           // album's track listing rather than taking the top one on trust —
           // see tracklistCorroborates.
           shortlist: scored.map((x) => x.c) };
}

/**
 * How much of a source album's track listing turns up on a candidate.
 *
 * Titles are compared canonicalised AND with their edition suffix removed,
 * because a remastered edition on Spotify writes its tracks as "So What -
 * Remastered" where a local rip just says "So What". Comparing the raw strings
 * would find nothing on exactly the albums this exists for.
 *
 * Direction matters: the fraction is of what the USER OWNS that is present,
 * not of what the candidate holds. A deluxe edition with eleven bonus tracks
 * still contains all of the standard edition, and refusing it for being
 * bigger is not the job — reporting the sizes is.
 *
 * @returns {{coverage:number, shared:number, wantCount:number, candCount:number}}
 */
function tracklistAgreement(wantTitles, candTitles) {
  const want = trackTitleSet(wantTitles);
  const cand = trackTitleSet(candTitles);
  if (!want.size || !cand.size) {
    return { coverage: 0, shared: 0, wantCount: want.size, candCount: cand.size };
  }
  let shared = 0;
  for (const t of want) if (cand.has(t)) shared++;
  return { coverage: shared / want.size, shared,
           wantCount: want.size, candCount: cand.size };
}

function trackTitleSet(titles) {
  const out = new Set();
  for (const t of Array.isArray(titles) ? titles : []) {
    const raw = t && typeof t === "object" ? t.title : t;
    const c = canon(stripVersion(raw));
    if (c) out.add(c);
  }
  return out;
}

/**
 * The album tier for a source with NO BARCODE — Roon.
 *
 * Spotify and Qobuz both hand over a barcode, which is decisive, and that is
 * what the `upc` tier is. A Roon library hands over nothing of the kind: a
 * browse row is a title and an artist, and title plus artist is not decisive
 * for an album any more than it is for a track. "Greatest Hits" by almost
 * anybody is several different records; a live album and a studio album share
 * a name often enough; a covers band files under a name that normalises to the
 * same string.
 *
 * So the album's own TRACK LISTING is made to carry the decision. It is the
 * one piece of independent evidence a Roon album has, and it is a strong one:
 * two different records by the same artist with the same title do not have the
 * same eleven track titles.
 *
 * This is a GATE, not a ranker. matchAlbum has already ordered the candidates
 * by title exactness, artist overlap and track count; this says yes or no to
 * one of them, and a caller works down the list.
 *
 * @param {Array} wantTitles the source album's track titles (or track objects)
 * @param {Array} candTitles the candidate's
 * @param {object} [opts]
 * @param {number} [opts.minCoverage]
 * @returns {{ok:boolean, coverage:number, shared:number, wantCount:number,
 *            candCount:number, reason:string}}
 */
function tracklistCorroborates(wantTitles, candTitles, opts) {
  const o = opts || {};
  const min = o.minCoverage === undefined ? TRACKLIST_MIN_COVERAGE : o.minCoverage;
  const a = tracklistAgreement(wantTitles, candTitles);

  // The two "could not check" cases are kept apart from "checked and it
  // disagrees", because they call for different action from the user: one is
  // something to look into, the other is a record that is not there. They are
  // also flagged `unreadable`, so the engine can count them as a FAILURE
  // rather than as a miss — and, crucially, not cache them. See
  // Migration.corroborate.
  if (!a.wantCount) {
    return Object.assign(a, { ok: false, unreadable: true,
      reason: "could not read this album's track listing from the source, so " +
        "there was nothing to corroborate a title-and-artist match with" });
  }
  if (!a.candCount) {
    return Object.assign(a, { ok: false, unreadable: true,
      reason: "the other service would not list that album's tracks, so the " +
        "match could not be corroborated" });
  }
  if (a.coverage < min) {
    return Object.assign(a, { ok: false,
      reason: "an album of that name is there but its track listing does not " +
        "agree: " + a.shared + " of your " + a.wantCount + " tracks on its " +
        a.candCount + ". Probably a different record with the same name" });
  }
  const sameSize = a.candCount === a.wantCount;
  return Object.assign(a, { ok: true,
    reason: sameSize
      ? "matched on title, artist and all " + a.wantCount + " track titles"
      : "matched on title, artist and track listing (" + a.shared + " of your " +
        a.wantCount + " tracks, on an edition of " + a.candCount + ")" });
}

/**
 * Artists, by name.
 *
 * There is no corroborating fact available here at all — an artist has no
 * duration, no barcode and no track count — so this tier is exact-name-only
 * and there is deliberately no fuzzy fallback. Following the wrong "John
 * Williams" is the cost of one, and the benefit is following an artist whose
 * name someone spelled differently, which is not worth it.
 */
function matchArtist(candidates, want) {
  const list = Array.isArray(candidates) ? candidates.filter(Boolean) : [];
  if (!list.length) return refuse("the search returned nothing");
  const wantName = canon(want && want.name);
  if (!wantName) return refuse("the artist has no name to match on");

  const hits = list.filter((c) => canon(c.name) === wantName);
  if (!hits.length) {
    return refuse(`no artist called "${want.name}" on the other service`);
  }
  // Several artists share a name exactly — "Nirvana" is three bands. Whichever
  // the service ranks first is its own popularity answer and is the best
  // available signal; the list order is preserved from the search.
  return { track: hits[0], artist: hits[0], method: "name", score: 1,
           reason: hits.length > 1
             ? `matched on name (${hits.length} artists share it — took the most popular)`
             : "matched on name" };
}

// --------------------------------------------------------------- internals

function refuse(reason) {
  return { track: null, album: null, artist: null, method: null, score: 0, reason };
}

function numberOrNull(v) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function first(v) {
  if (Array.isArray(v)) return v[0] || "";
  return v == null ? "" : v;
}

/** Deterministic choice among equally-correct candidates — see matchTrack. */
function pickStable(hits, want) {
  const wantAlbum = canon(want && want.album);
  const sorted = hits.slice().sort((a, b) => {
    const sa = wantAlbum ? similarity(wantAlbum, canon(a.album)) : 0;
    const sb = wantAlbum ? similarity(wantAlbum, canon(b.album)) : 0;
    if (sb !== sa) return sb - sa;
    return String(a.id).localeCompare(String(b.id));
  });
  return sorted[0];
}

module.exports = {
  matchTrack, matchAlbum, matchArtist, normIsrc,
  tracklistAgreement, tracklistCorroborates,
  DEFAULT_TOLERANCE_MS, CLOSE_TOLERANCE_MS, TRACKLIST_MIN_COVERAGE,
};
