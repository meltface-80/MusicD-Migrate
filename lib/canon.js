"use strict";
/*
 * canon.js — turning two services' idea of a name into one comparable string.
 *
 * Qobuz and Spotify disagree about punctuation, capitalisation, accents, how a
 * featured artist is written, and above all about EDITION SUFFIXES. The same
 * recording is "Blue Monday" on one and "Blue Monday - 2016 Remaster" on the
 * other, and neither is wrong. Comparing the raw strings finds almost nothing;
 * comparing them stripped of everything finds the wrong track.
 *
 * So this module produces two forms of a title and the caller uses both:
 *
 *   canon(t)         "blue monday 2016 remaster"   — everything, normalised
 *   stripVersion(t)  "blue monday"                 — the edition suffix removed
 *
 * A match on `canon` is worth more than a match on `stripVersion`, and
 * lib/match.js scores them that way. Stripping is never done in isolation:
 * without a suffix, a radio edit, a live version and the album cut are the same
 * string, which is exactly the confusion that produces a confidently wrong
 * result.
 *
 * Pure and offline — no network, no account, no service. Every rule here is
 * testable, and test/unit/canon.test.js tests them.
 */

/**
 * Lowercase, unaccented, punctuation-free, single-spaced.
 *
 * NFKD then stripping the combining marks is what turns "Björk" and "Bjork"
 * into the same string, and "½" into "1 2". Both services do send accented
 * names, and they do not always agree on the accent.
 */
function canon(s) {
  return String(s == null ? "" : s)
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    // A typographic apostrophe is a different code point from a typewriter
    // one, and both appear. Dropping it entirely rather than mapping it means
    // "don't" and "dont" agree, which they must.
    .replace(/['‘’ʼ]/g, "")
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/*
 * The words that mark an edition rather than a different recording.
 *
 * "remaster" is here and "live" is NOT, deliberately. A remaster is the same
 * performance and migrating to it is right; a live version is a different
 * performance and silently accepting one for the other is the failure this
 * whole module is shaped to avoid. Same for "acoustic", "demo", "remix",
 * "instrumental", "edit" and "version" — all of them name a DIFFERENT
 * recording, so none of them is stripped.
 */
const EDITION_WORDS = [
  "remaster", "remastered", "remasterised", "remasterized",
  "deluxe", "deluxe edition", "expanded", "expanded edition",
  "special edition", "anniversary edition", "bonus track",
  "bonus track version", "explicit", "explicit version",
  "album version", "original mix", "mono", "stereo",
  "digital remaster", "reissue", "re issue",
];

// "(2011 Remaster)", "[Remastered]", " - 2011 Remaster", " - Deluxe Edition"
const SUFFIX = /\s*(?:[([][^)\]]*[)\]]|-\s+[^-]*)\s*$/;

/**
 * The title with any trailing EDITION suffix removed.
 *
 * Only a suffix that actually names an edition is taken. "Paranoid Android
 * (Live)" keeps its suffix, because the suffix is the whole difference between
 * that and the studio recording — and a caller that matched it away would be
 * putting the wrong recording in someone's playlist.
 *
 * Repeats, because both shapes occur together: "Song (feat. X) - 2011
 * Remaster" has two suffixes and removing one leaves the other.
 */
function stripVersion(title) {
  let out = String(title == null ? "" : title).trim();
  for (let i = 0; i < 4; i++) {
    const m = out.match(SUFFIX);
    if (!m) break;
    const inner = canon(m[0].replace(/^[\s([-]+|[)\]\s]+$/g, ""));
    if (!inner) break;
    const isEdition = EDITION_WORDS.some((w) => inner === w || inner.includes(w)) ||
                      /^\d{4} (remaster|mix|version)$/.test(inner) ||
                      /^feat /.test(inner) || /^featuring /.test(inner) ||
                      /^with /.test(inner);
    if (!isEdition) break;
    const next = out.slice(0, out.length - m[0].length).trim();
    // Stripping must never empty the title: a track genuinely called
    // "(Remastered)" would otherwise match everything.
    if (!next) break;
    out = next;
  }
  return out;
}

/**
 * The artist a track is filed under, with collaborators dropped.
 *
 * Spotify returns an ARRAY of artists and Qobuz returns one `performer` string
 * that may already contain "feat." — so "Calvin Harris" and "Calvin Harris,
 * Rihanna" are routinely the two services' answer for the same track. Matching
 * on the first-named artist is what makes those agree; the full list is still
 * compared separately and scores higher when it does agree.
 */
function primaryArtist(s) {
  const raw = String(s == null ? "" : s);
  const cut = raw.split(/\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|&)\s+|[,;]|\//i)[0];
  return canon(cut || raw);
}

/**
 * A collaboration marker always separates artists. A list punctuation mark
 * only sometimes does — see splitArtists.
 */
const COLLAB = /\s+(?:feat\.?|ft\.?|featuring|with|vs\.?)\s+/i;
const LIST_PUNCT = /\s*[,;\/]\s*|\s+&\s+/;

/**
 * Split a string that may name several artists — but not through a NAME.
 *
 * "Carla Bley/Steve Swallow/Andy Sheppard" is three people. "AC/DC" is one
 * band, "Belle & Sebastian" is one band, and "Earth, Wind & Fire" is one band
 * with both a comma and an ampersand in its name. Splitting those leaves
 * "AC", "Belle" and "Earth", which match nothing and search for nothing.
 *
 * The rule: a comma, semicolon, slash or ampersand separates artists only
 * when EVERY segment it produces is more than one word. Two people are
 * "Vincent Peirani & Emile Parisien" and "Miles Davis, John Coltrane" — first
 * name and last name, on both sides. A band with punctuation in its name
 * almost always has a one-word piece: AC, DC, Belle, Fire, Die, For.
 *
 * `feat.`, `ft.`, `featuring`, `with` and `vs` are different: they are never
 * part of a name, so they always split.
 *
 * Measured on a real 9,635-album library: 88 albums were refused because a
 * band's own name had been cut in half this way, and the repair costs nothing
 * elsewhere — every genuine multi-artist string in that library has
 * multi-word segments.
 */
function splitArtists(value) {
  const first = String(value == null ? "" : value).split(COLLAB);
  const out = [];
  for (const piece of first) {
    const parts = piece.split(LIST_PUNCT).map((x) => x.trim()).filter(Boolean);
    const separates = parts.length > 1 && parts.every((p) => p.split(/\s+/).length > 1);
    // A piece with no letters or digits in it is punctuation, not an artist.
    const real = (x) => /[a-z0-9]/i.test(x);
    if (separates) out.push(...parts.filter(real));
    else if (real(piece)) out.push(piece.trim());
  }
  return out;
}

/**
 * The artists a string names, each AS WRITTEN, in order.
 *
 * The same split as artistSet, but keeping the original spelling, because
 * this one feeds a SEARCH QUERY rather than a comparison. Roon hands over an
 * album's artists as one string joined with slashes — "Carla Bley/Steve
 * Swallow/Andy Sheppard" — and a query containing all three finds nothing on
 * either service. Of 403 albums in a real 9,514-album Roon library whose
 * artist held a slash, 364 came back "not found" and 21 matched: 5% against
 * 63% for the library as a whole.
 */
function artistNames(value) {
  const parts = Array.isArray(value) ? value : splitArtists(value);
  const out = [];
  for (const p of parts) {
    const t = String(p == null ? "" : p).trim();
    if (t && out.indexOf(t) < 0) out.push(t);
  }
  return out;
}

/** A leading "The"/"A"/"Los"… — never the difference between two acts. */
const LEADING_ARTICLE = /^(?:the|a|an|los|las|les)\s+/;

/**
 * Words that name an ENSEMBLE rather than a different act: "Vijay Iyer" and
 * "Vijay Iyer Trio" are the same artist billed two ways, and jazz does this
 * constantly. Deliberately short, and deliberately without "tribute",
 * "covers", "band" or "project": a tribute act IS somebody else, and that is
 * the mistake this whole module exists to avoid.
 */
const ENSEMBLE_WORDS = ["trio", "quartet", "quintet", "sextet", "septet", "octet",
                        "orchestra", "ensemble", "quartett", "big band"];

/**
 * Every form of every artist a value names, canonicalised.
 *
 * Every FORM, not just every name, and that is the point. `overlap` needs one
 * shared entry, so listing the name as written alongside the pieces it might
 * be made of lets two spellings of the same thing agree without letting a
 * different artist in. Each entry is still a name that appears in the string.
 *
 * The forms are: the whole string; each artist it splits into; and each of
 * those without a leading article or a trailing ensemble word.
 *
 * The whole string matters most, and 42 albums of a real library are why:
 * Roon writes "Siouxsie and the Banshees", the service writes "Siouxsie & The
 * Banshees", canon turns "&" into "and" so the two are the SAME string — but
 * the split ran first, cut the service's name into "Siouxsie" and "The
 * Banshees", and nothing was left to agree with. Same for "To/Die/For"
 * against "To Die For", and "Derek and the Dominos" against "Derek & The
 * Dominos".
 */
function artistSet(value) {
  const out = new Set();
  const add = (x) => { const c = canon(x); if (c) out.add(c); };
  const values = Array.isArray(value) ? value : [value];
  for (const v of values) {
    add(v);                                   // the name exactly as written
    for (const name of artistNames(v)) {
      add(name);
      const c = canon(name);
      const bare = c.replace(LEADING_ARTICLE, "");
      if (bare) add(bare);
      for (const w of ENSEMBLE_WORDS) {
        if (bare.endsWith(" " + w)) add(bare.slice(0, -(w.length + 1)).trim());
      }
    }
  }
  return out;
}

/**
 * How alike two canonical strings are, 0..1 — the Sørensen-Dice coefficient
 * over character bigrams.
 *
 * Used only to RANK candidates that have already passed a gate, never to let
 * one through. A similarity threshold on its own is how "Yesterday" matches
 * "Yesterdays"; the gates in lib/match.js are what prevent that, and this just
 * orders what survives them.
 */
function similarity(a, b) {
  const x = String(a || ""), y = String(b || "");
  if (!x || !y) return 0;
  if (x === y) return 1;
  if (x.length < 2 || y.length < 2) return x === y ? 1 : 0;
  const grams = new Map();
  for (let i = 0; i < x.length - 1; i++) {
    const g = x.slice(i, i + 2);
    grams.set(g, (grams.get(g) || 0) + 1);
  }
  let hits = 0;
  for (let i = 0; i < y.length - 1; i++) {
    const g = y.slice(i, i + 2);
    const n = grams.get(g) || 0;
    if (n > 0) {
      grams.set(g, n - 1);
      hits++;
    }
  }
  return (2 * hits) / (x.length - 1 + y.length - 1);
}

/** How much of `set` is also in `other`, 0..1. Empty on either side is 0. */
function overlap(set, other) {
  if (!set.size || !other.size) return 0;
  let hits = 0;
  for (const v of set) if (other.has(v)) hits++;
  return hits / Math.min(set.size, other.size);
}

module.exports = { canon, stripVersion, primaryArtist, artistNames, artistSet,
                   splitArtists, similarity, overlap, EDITION_WORDS,
                   ENSEMBLE_WORDS };
