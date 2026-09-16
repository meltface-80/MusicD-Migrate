"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { matchTrack, matchAlbum, matchArtist, normIsrc,
        tracklistAgreement, tracklistCorroborates,
        TRACKLIST_MIN_COVERAGE } = require("../../lib/match");

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

test("a tag that repeats the title's own words is not a different record", () => {
  // From a real library: the service appends a parenthetical the title
  // already says. Refusing that is refusing a record over nothing.
  const r = matchAlbum([{ id: "a", title: "Always Let Me Go - Live In Tokyo (Live In Tokyo)",
    artists: ["Keith Jarrett"] }],
    { title: "Always Let Me Go - Live In Tokyo", artists: ["Keith Jarrett"] });
  assert.ok(r.album, r.reason);
  assert.match(r.reason, /ignoring "\(Live In Tokyo\)"/);
  assert.match(r.reason, /only repeats what the title or the artist already says/);
});

test("a tag that is just the artist's name adds nothing either", () => {
  // Either side may carry it: services append "(Nina Simone)", Roon rips
  // append ": Stan Getz".
  const theirs = matchAlbum([{ id: "a", title: "33 Hits (Nina Simone)",
    artists: ["Nina Simone"] }], { title: "33 Hits", artists: ["Nina Simone"] });
  assert.ok(theirs.album, theirs.reason);
  assert.match(theirs.reason, /ignoring "\(Nina Simone\)"/);

  const ours = matchAlbum([{ id: "a", title: "Jazz 'Round Midnight",
    artists: ["Stan Getz"] }],
    { title: "Jazz 'Round Midnight: Stan Getz", artists: ["Stan Getz"] });
  assert.ok(ours.album, ours.reason);
});

test("a tag that says something NEW is still a different record", () => {
  // The whole reason the rule above is allowed. None of these repeats
  // anything: they each add a fact, and the fact is what makes it a different
  // record — a live take, a remix album, a second volume.
  const refuse = (mine, theirs, artist) => {
    const r = matchAlbum([{ id: "a", title: theirs, artists: [artist] }],
      { title: mine, artists: [artist] });
    assert.strictEqual(r.album, null,
      `${theirs} must not be accepted for ${mine}: ${r.reason}`);
  };
  refuse("Rio", "Rio (Live)", "Keith Jarrett");
  refuse("Blue Lines", "Blue Lines - The Remixes", "Massive Attack");
  refuse("Greatest Hits", "Greatest Hits: Volume 2", "Queen");
  refuse("Pearls & Embarrassments", "Pearls & Embarrassments, Vol. 2", "Someone");
  refuse("Aftersun", "Aftersun (Acoustic)", "Someone");
  // The shortest tag is tried first, so a title with two of them cannot be
  // cut back to something nobody owns.
  refuse("Day of the Gusano", "Day Of The Gusano - Live In Mexico (Live)", "Slipknot");
});

test("a refusal names what the other service DID have, by that artist", () => {
  // 1,465 rows of a real report said "no album called X by that artist" and
  // nothing else, which invites "but I own it, it is definitely on there".
  // The answer is usually in what came back: the same record under a longer
  // title, or a different performance.
  const r = matchAlbum([{ id: "a", title: "Rio (Live)", artists: ["Keith Jarrett"] }],
    { title: "Rio", artists: ["Keith Jarrett"] });
  assert.strictEqual(r.album, null, "a live record is not the studio one");
  assert.match(r.reason, /closest that artist has is "Rio \(Live\)"/);
  assert.match(r.reason, /not the same record as "Rio"/);
});

test("a refusal says when the album is there but by somebody else", () => {
  // The other shape, and a different thing for the user to do about it: a
  // covers band, a tribute act, or a tag that names the composer where the
  // service names the performer.
  const r = matchAlbum([{ id: "a", title: "Rumours", artists: ["The Rumour Mill"] }],
    { title: "Rumours", artists: ["Fleetwood Mac"] });
  assert.strictEqual(r.album, null);
  assert.match(r.reason, /but by The Rumour Mill/);
  assert.match(r.reason, /not by Fleetwood Mac/);
});

test("nothing at all coming back still says exactly that", () => {
  // The third refusal, kept apart from the other two on purpose: there is
  // nothing for the user to look at.
  assert.match(matchAlbum([], { title: "Rumours", artists: ["Fleetwood Mac"] }).reason,
    /the search returned nothing/);
});

// ----------------------------------------------------------------- artists

test("artists match on an exact name only", () => {
  assert.strictEqual(matchArtist([{ id: "a", name: "Portishead" }],
    { name: "portishead" }).method, "name");
  assert.strictEqual(matchArtist([{ id: "a", name: "Portishead Tribute" }],
    { name: "Portishead" }).method, null);
});

// -------------------------------------------------------- the tracklist tier
//
// The gate that makes a barcode-less album match — a Roon album — decisive.
// Pure, so the threshold and the wording are testable without a Core.

const tl = (...titles) => titles.map((title) => ({ title }));

test("a remastered edition's tracks agree with a local rip's", () => {
  // Spotify writes "So What - Remastered"; a rip just says "So What". Without
  // stripping the suffix the two listings share nothing and the album the user
  // owns is refused.
  const mine = tl("So What", "Freddie Freeloader", "Blue In Green",
                  "All Blues", "Flamenco Sketches");
  const theirs = mine.map((x) => ({ title: x.title + " - Remastered" }));
  const c = tracklistCorroborates(mine, theirs);
  assert.strictEqual(c.ok, true);
  assert.strictEqual(c.coverage, 1);
  assert.match(c.reason, /all 5 track titles/);
});

test("coverage is of what the user owns, not of what the candidate holds", () => {
  const mine = tl("A", "B", "C", "D");
  const deluxe = tl("A", "B", "C", "D", "E", "F", "G", "H");
  const a = tracklistAgreement(mine, deluxe);
  assert.strictEqual(a.coverage, 1, "a deluxe edition contains all of the standard");
  assert.strictEqual(a.wantCount, 4);
  assert.strictEqual(a.candCount, 8);
  assert.strictEqual(tracklistCorroborates(mine, deluxe).ok, true);

  // And the other way round: the user owns the deluxe, only the standard is
  // there. Three quarters of it is, which clears the bar.
  const back = tracklistCorroborates(deluxe, mine);
  assert.strictEqual(back.coverage, 0.5);
  assert.strictEqual(back.ok, false, "half of a record is not that record");
});

test("a different record with the same name is refused, with the numbers", () => {
  const c = tracklistCorroborates(tl("One", "Two", "Three", "Four"),
                                  tl("Nine", "Ten", "Eleven", "One"));
  assert.strictEqual(c.ok, false);
  assert.match(c.reason, /1 of your 4 tracks on its 4/);
  assert.match(c.reason, /different record with the same name/);
});

test("the threshold is where it says it is", () => {
  const ten = tl("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
  const seven = tl("a", "b", "c", "d", "e", "f", "g");
  const six = tl("a", "b", "c", "d", "e", "f");
  assert.strictEqual(TRACKLIST_MIN_COVERAGE, 0.7);
  assert.strictEqual(tracklistCorroborates(ten, seven).ok, true, "7 of 10 passes");
  assert.strictEqual(tracklistCorroborates(ten, six).ok, false, "6 of 10 does not");
  // And it is a parameter, not a law of nature.
  assert.strictEqual(tracklistCorroborates(ten, six, { minCoverage: 0.6 }).ok, true);
});

test("could not check and checked-and-disagrees are different refusals", () => {
  // They call for different things from the user: one is something to look
  // into, the other is a record that is not there. Collapsing them into "not
  // found" throws away the difference, and the unmatched report IS the
  // deliverable of a migration.
  const nothingFromSource = tracklistCorroborates([], tl("a", "b"));
  assert.strictEqual(nothingFromSource.ok, false);
  assert.match(nothingFromSource.reason, /from the source/);

  const nothingFromTarget = tracklistCorroborates(tl("a", "b"), []);
  assert.strictEqual(nothingFromTarget.ok, false);
  assert.match(nothingFromTarget.reason, /other service would not list/);

  assert.notStrictEqual(nothingFromSource.reason, nothingFromTarget.reason);
});

test("duplicate track titles on one album do not inflate the agreement", () => {
  // A box set that repeats a title across discs, or a rip with a doubled
  // track. Counting titles rather than distinct titles would let two shared
  // names cover a four-track album.
  const mine = tl("Intro", "Intro", "Theme", "Coda");
  const a = tracklistAgreement(mine, tl("Intro", "Intro", "Intro", "Intro"));
  assert.strictEqual(a.wantCount, 3, "three distinct titles, not four");
  assert.strictEqual(a.shared, 1);
});

test("a title that normalises to nothing is not a track", () => {
  const a = tracklistAgreement(tl("", "   ", "Real Track"), tl("Real Track"));
  assert.strictEqual(a.wantCount, 1);
  assert.strictEqual(a.coverage, 1);
});

test("matchAlbum hands back everything that passed its gates", () => {
  const want = { id: "q", title: "The Record", artists: ["Band"], trackCount: null };
  const r = matchAlbum([
    { id: "b", title: "The Record", artists: ["Band"] },
    { id: "a", title: "The Record", artists: ["Band"] },
    { id: "no", title: "Something Else", artists: ["Band"] },
  ], want);
  assert.deepStrictEqual(r.shortlist.map((x) => x.id), ["a", "b"],
    "in score order, and a caller with no barcode works down it");
});
