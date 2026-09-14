"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { matchTrack, matchAlbum, matchArtist, normIsrc } = require("../../lib/match");

const t = (o) => Object.assign({
  id: "x", title: "Song", artists: ["Band"], album: "Record", durationMs: 200000,
}, o);

test("normIsrc keeps 12 chars and rejects anything else", () => {
  assert.strictEqual(normIsrc("gb-aye-06-01498"), "GBAYE0601498");
  assert.strictEqual(normIsrc("GBAYE0601498"), "GBAYE0601498");
  assert.strictEqual(normIsrc("TOOSHORT"), "");
  assert.strictEqual(normIsrc(null), "");
});

// --------------------------------------------------------------- tier: isrc

test("an ISRC match wins with no duration corroboration at all", () => {
  const r = matchTrack(
    [t({ id: "a", isrc: "GBAYE0601498", title: "Utterly Different", durationMs: 1 })],
    t({ isrc: "gb-aye-06-01498" }));
  assert.strictEqual(r.method, "isrc");
  assert.strictEqual(r.track.id, "a");
});

test("several ISRC hits pick deterministically, preferring the same album", () => {
  const cands = [
    t({ id: "zzz", isrc: "GBAYE0601498", album: "Greatest Hits" }),
    t({ id: "aaa", isrc: "GBAYE0601498", album: "Record" }),
  ];
  const want = t({ isrc: "GBAYE0601498", album: "Record" });
  assert.strictEqual(matchTrack(cands, want).track.id, "aaa");
  // Same answer whichever order the service returned them in.
  assert.strictEqual(matchTrack(cands.slice().reverse(), want).track.id, "aaa");
});

test("a missing ISRC on the other side falls through, it does not refuse", () => {
  const r = matchTrack([t({ id: "a" })], t({ isrc: "GBAYE0601498" }));
  assert.strictEqual(r.method, "exact");
});

// -------------------------------------------------------------- tier: exact

test("title, artist and duration agreeing is an exact match", () => {
  const r = matchTrack([t({ id: "a", durationMs: 201500 })], t({}));
  assert.strictEqual(r.method, "exact");
  assert.match(r.reason, /title, artist and duration/);
});

test("artists that overlap only on the lead credit still match", () => {
  const r = matchTrack(
    [t({ id: "a", artists: ["Calvin Harris", "Rihanna"] })],
    t({ artists: ["Calvin Harris"] }));
  assert.strictEqual(r.method, "exact");
});

// -------------------------------------------------------------- tier: close

test("an edition suffix is matched through, with the tighter gate", () => {
  const r = matchTrack(
    [t({ id: "a", title: "Song - 2011 Remaster", durationMs: 202000 })],
    t({ title: "Song" }));
  assert.strictEqual(r.method, "close");
});

test("a stripped-title match outside the tighter gate is refused", () => {
  // 4s apart: inside the 5s exact tolerance, outside the 3s close tolerance.
  const r = matchTrack(
    [t({ id: "a", title: "Song - 2011 Remaster", durationMs: 204000 })],
    t({ title: "Song" }));
  assert.strictEqual(r.method, null);
});

// ----------------------------------------------------------- the refusals

test("a live version is never accepted for the studio recording", () => {
  const r = matchTrack(
    [t({ id: "a", title: "Song (Live)", durationMs: 200000 })],
    t({ title: "Song" }));
  assert.strictEqual(r.method, null, "a live take must not match the studio cut");
});

test("a radio edit is refused on duration", () => {
  const r = matchTrack(
    [t({ id: "a", title: "Song", durationMs: 180000 })],
    t({ title: "Song", durationMs: 420000 }));
  assert.strictEqual(r.method, null);
  assert.match(r.reason, /different recording/);
});

test("a cover by another artist is refused, and says so", () => {
  const r = matchTrack(
    [t({ id: "a", artists: ["Some Covers Band"] })],
    t({ artists: ["Band"] }));
  assert.strictEqual(r.method, null);
  assert.match(r.reason, /cover/);
});

test("no duration on the wanted track refuses every title tier", () => {
  const r = matchTrack([t({ id: "a" })], t({ durationMs: null }));
  assert.strictEqual(r.method, null);
  assert.match(r.reason, /no duration/);
});

test("an empty candidate list refuses", () => {
  assert.strictEqual(matchTrack([], t({})).method, null);
  assert.strictEqual(matchTrack(null, t({})).method, null);
});

test("strict mode accepts only ISRC", () => {
  const exact = [t({ id: "a" })];
  assert.strictEqual(matchTrack(exact, t({}), { strict: true }).method, null);
  assert.strictEqual(
    matchTrack([t({ id: "a", isrc: "GBAYE0601498" })], t({ isrc: "GBAYE0601498" }),
      { strict: true }).method, "isrc");
});

test("the refusal reason distinguishes absent from wrong-length", () => {
  const absent = matchTrack([t({ id: "a", title: "Something Else" })], t({ title: "Song" }));
  assert.match(absent.reason, /nothing called/);
  const wrongLength = matchTrack([t({ id: "a", durationMs: 400000 })], t({}));
  assert.match(wrongLength.reason, /400s there and 200s here/);
});

// ------------------------------------------------------------------ albums

test("albums match on barcode first", () => {
  const r = matchAlbum([{ id: "a", upc: "0075992736121", title: "Nope", artists: ["Other"] }],
    { upc: "75992736121", title: "Rumours", artists: ["Fleetwood Mac"] });
  assert.strictEqual(r.method, "upc");
});

test("a deluxe edition is not accepted for the standard one", () => {
  const r = matchAlbum(
    [{ id: "a", title: "Rumours (Deluxe Edition)", artists: ["Fleetwood Mac"], trackCount: 34 }],
    { title: "Rumours", artists: ["Fleetwood Mac"], trackCount: 11 });
  assert.strictEqual(r.method, null);
});

test("an exact album title matches even when track counts are unknown", () => {
  const r = matchAlbum([{ id: "a", title: "Rumours", artists: ["Fleetwood Mac"] }],
    { title: "Rumours", artists: ["Fleetwood Mac"] });
  assert.strictEqual(r.method, "exact");
});

// ----------------------------------------------------------------- artists

test("artists match on an exact name only", () => {
  assert.strictEqual(matchArtist([{ id: "a", name: "Portishead" }],
    { name: "portishead" }).method, "name");
  assert.strictEqual(matchArtist([{ id: "a", name: "Portishead Tribute" }],
    { name: "Portishead" }).method, null);
});
