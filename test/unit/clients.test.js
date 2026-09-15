"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { Spotify, toTrack: spTrack, toAlbum: spAlbum, quoteTerm } = require("../../lib/spotify");
const { Qobuz, toTrack: qzTrack, toAlbum: qzAlbum } = require("../../lib/qobuz");
const PKCE = require("../../lib/spotify-pkce");

/** A fetch that answers from a scripted list and records what it was asked. */
function fakeFetch(script) {
  const calls = [];
  const queue = script.slice();
  const fn = async (url, init) => {
    calls.push({ url: String(url), init: init || {} });
    const next = queue.length > 1 ? queue.shift() : queue[0];
    return {
      ok: next.status >= 200 && next.status < 300,
      status: next.status,
      headers: { get: (k) => (next.headers || {})[String(k).toLowerCase()] || null },
      text: async () => (typeof next.body === "string" ? next.body : JSON.stringify(next.body)),
    };
  };
  fn.calls = calls;
  return fn;
}

const liveSession = { clientId: "cid", accessToken: "tok", refreshToken: "ref",
                      expiresAt: Date.now() + 600000, userId: "me" };

// ------------------------------------------------------------------- PKCE

test("PKCE verifier and challenge are well formed", () => {
  const v = PKCE.createVerifier();
  assert.strictEqual(v.length, 64);
  assert.match(v, /^[A-Za-z0-9\-._~]+$/);
  const c = PKCE.challengeFor(v);
  assert.match(c, /^[A-Za-z0-9\-_]+$/, "base64url, no padding");
  assert.strictEqual(PKCE.challengeFor(v), c, "the challenge is a pure function");
  // The published test vector from RFC 7636 appendix B.
  assert.strictEqual(
    PKCE.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
    "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
});

test("checkRedirectUri enforces Spotify's loopback rule", () => {
  assert.ok(PKCE.checkRedirectUri("https://example.com/cb").ok);
  assert.ok(PKCE.checkRedirectUri("http://127.0.0.1:3380/api/spotify/callback").ok);
  const localhost = PKCE.checkRedirectUri("http://localhost:3380/cb");
  assert.strictEqual(localhost.ok, false);
  assert.match(localhost.reason, /127\.0\.0\.1/);
  const lan = PKCE.checkRedirectUri("http://192.168.1.50:3380/cb");
  assert.strictEqual(lan.ok, false);
  assert.match(lan.reason, /paste-back/);
});

test("parseCallback accepts a URL, a query string or a bare code", () => {
  assert.strictEqual(PKCE.parseCallback("http://x/cb?code=abc&state=s1").code, "abc");
  assert.strictEqual(PKCE.parseCallback("code=abc&state=s1").state, "s1");
  assert.strictEqual(PKCE.parseCallback("abc").code, "abc");
  assert.strictEqual(PKCE.parseCallback("http://x/cb?error=access_denied").error,
    "access_denied");
  assert.strictEqual(PKCE.parseCallback("").code, "");
});

test("buildAuthorizeUrl refuses to build half a sign-in", () => {
  assert.throws(() => PKCE.buildAuthorizeUrl({ redirectUri: "u", challenge: "c" }),
    /Client ID/);
  const url = PKCE.buildAuthorizeUrl({ clientId: "cid", redirectUri: "http://127.0.0.1/cb",
    challenge: "ch", state: "st" });
  const p = new URL(url).searchParams;
  assert.strictEqual(p.get("code_challenge_method"), "S256");
  assert.strictEqual(p.get("response_type"), "code");
  assert.ok(p.get("scope").includes("user-library-modify"));
});

// ------------------------------------------------------ Spotify conversion

test("Spotify tracks convert, and non-tracks are refused", () => {
  const t = spTrack({ id: "1", name: "Song", duration_ms: 200000, type: "track",
    artists: [{ name: "A" }, { name: "B" }], album: { name: "Rec" },
    external_ids: { isrc: "GBAYE0601498" } });
  assert.deepStrictEqual(t, { id: "1", isrc: "GBAYE0601498", title: "Song",
    artists: ["A", "B"], album: "Rec", durationMs: 200000 });
  assert.strictEqual(spTrack({ id: null, name: "Local" }), null, "a local file has no id");
  assert.strictEqual(spTrack({ id: "e1", type: "episode" }), null, "a podcast is not a track");
  assert.strictEqual(spTrack(null), null);
});

test("Spotify albums convert, including the release year", () => {
  assert.deepStrictEqual(spAlbum({ id: "a", name: "Rumours", total_tracks: 11,
    artists: [{ name: "Fleetwood Mac" }], external_ids: { upc: "075992736121" },
    release_date: "1977-02-04" }),
    { id: "a", upc: "075992736121", title: "Rumours", artists: ["Fleetwood Mac"],
      trackCount: 11, year: 1977 });
});

test("quoteTerm strips quotes that would break the search phrase", () => {
  assert.strictEqual(quoteTerm('The "Real" Thing'), '"The  Real  Thing"');
});

// -------------------------------------------------------- Qobuz conversion

test("Qobuz durations convert from seconds to milliseconds", () => {
  const t = qzTrack({ id: 7, title: "Song", duration: 213, isrc: "GBAYE0601498",
    performer: { name: "A" }, album: { title: "Rec" } });
  assert.strictEqual(t.durationMs, 213000,
    "seconds must become milliseconds or nothing ever matches");
});

test("Qobuz recomposes title and version into one title", () => {
  assert.strictEqual(
    qzTrack({ id: 1, title: "Blue Monday", version: "2016 Remaster", duration: 100 }).title,
    "Blue Monday (2016 Remaster)");
  assert.strictEqual(qzTrack({ id: 1, title: "Blue Monday", duration: 100 }).title,
    "Blue Monday");
});

test("Qobuz album tracks inherit the album block they came from", () => {
  const album = qzAlbum({ id: "al", title: "Rec", artist: { name: "A" }, tracks_count: 2 });
  const t = qzTrack({ id: 1, title: "Song", duration: 100 }, album);
  assert.strictEqual(t.album, "Rec");
  assert.deepStrictEqual(t.artists, ["A"]);
});

test("Qobuz albums convert, with the year from any of its date fields", () => {
  assert.strictEqual(qzAlbum({ id: 1, title: "X", release_date_original: "1977-02-04" }).year,
    1977);
  assert.strictEqual(qzAlbum({ id: 1, title: "X" }).year, null);
});

// ---------------------------------------------------------- rate limiting

test("a Spotify 429 is obeyed with the delay Spotify asked for", async () => {
  const fetch = fakeFetch([
    { status: 429, headers: { "retry-after": "1" }, body: "" },
    { status: 200, body: { id: "me", display_name: "Me" } },
  ]);
  const waits = [];
  const sp = new Spotify(liveSession, { fetch, onRateLimit: (ms) => waits.push(ms) });
  const me = await sp.me();
  assert.strictEqual(me.id, "me");
  assert.strictEqual(waits.length, 1);
  assert.ok(waits[0] >= 1000 && waits[0] <= 2000, "waited about the second it was told to");
});

test("a Spotify 401 refreshes once, then gives up rather than looping", async () => {
  const fetch = fakeFetch([{ status: 401, body: { error: { message: "expired" } } }]);
  const sp = new Spotify(Object.assign({}, liveSession, { refreshToken: "" }),
    { fetch });
  await assert.rejects(() => sp.me(), /expired/);
});

// ----------------------------------------------------------- write batching

test("Spotify playlist writes batch at 100 and use track URIs", async () => {
  const fetch = fakeFetch([{ status: 200, body: {} }]);
  const sp = new Spotify(liveSession, { fetch });
  await sp.addToPlaylist("pl", Array.from({ length: 250 }, (_, i) => "t" + i));
  assert.strictEqual(fetch.calls.length, 3, "250 tracks is three requests, not 250");
  const first = JSON.parse(fetch.calls[0].init.body);
  assert.strictEqual(first.uris.length, 100);
  assert.strictEqual(first.uris[0], "spotify:track:t0");
});

test("Spotify saved-track writes batch at 50", async () => {
  const fetch = fakeFetch([{ status: 200, body: {} }]);
  const sp = new Spotify(liveSession, { fetch });
  await sp.saveTracks(Array.from({ length: 120 }, (_, i) => "t" + i));
  assert.strictEqual(fetch.calls.length, 3);
  assert.strictEqual(JSON.parse(fetch.calls[0].init.body).ids.length, 50);
});

test("following artists puts the ids in the query, where Spotify wants them", async () => {
  const fetch = fakeFetch([{ status: 200, body: {} }]);
  const sp = new Spotify(liveSession, { fetch });
  await sp.followArtists(["a1", "a2"]);
  assert.match(fetch.calls[0].url, /ids=a1%2Ca2/);
  assert.match(fetch.calls[0].url, /type=artist/);
});

test("Qobuz writes batch at 50 and go in the query string", async () => {
  const fetch = fakeFetch([{ status: 200, body: { status: "success" } }]);
  const qz = new Qobuz({ token: "t", userId: "1" }, { fetch });
  await qz.addToPlaylist("pl", Array.from({ length: 130 }, (_, i) => "t" + i));
  assert.strictEqual(fetch.calls.length, 3);
  assert.match(fetch.calls[0].url, /track_ids=/);
  assert.match(fetch.calls[0].url, /app_id=/);
});

test("empty write lists send no requests at all", async () => {
  const fetch = fakeFetch([{ status: 200, body: {} }]);
  const sp = new Spotify(liveSession, { fetch });
  await sp.saveTracks([]);
  await sp.saveAlbums([null, ""]);
  assert.strictEqual(fetch.calls.length, 0);
});

// ------------------------------------------------------------------ paging

test("Spotify paging stops at the reported total", async () => {
  const page = (n, total) => ({ status: 200,
    body: { total, items: Array.from({ length: n }, (_, i) => ({ id: "p" + i })) } });
  const fetch = fakeFetch([page(50, 60), page(10, 60), page(0, 60)]);
  const sp = new Spotify(liveSession, { fetch });
  const all = await sp.pageAll("/me/playlists", {}, null, 50);
  assert.strictEqual(all.length, 60);
  assert.strictEqual(fetch.calls.length, 2, "it stopped without a third empty request");
});

test("Qobuz paging stops on a short page", async () => {
  const fetch = fakeFetch([
    { status: 200, body: { tracks: { items: Array.from({ length: 2 }, (_, i) => (
      { id: i, title: "T" + i, duration: 100 })) } } },
  ]);
  const qz = new Qobuz({ token: "t" }, { fetch });
  const all = await qz.savedTracks();
  assert.strictEqual(all.length, 2);
  assert.strictEqual(fetch.calls.length, 1);
});

// --------------------------------------------------------------------------
// Barcode search. Spotify's album search returns SimplifiedAlbumObject with
// no external_ids, so a candidate never carries a barcode and the matcher's
// barcode tier could not fire. The `upc:` filter is the fix, and because the
// result still has no barcode on it, THE FILTER IS THE EVIDENCE — the code is
// stamped on so the tier can see it.

test("a Spotify barcode search uses the upc: filter and stamps the result", async () => {
  const http = fakeFetch([{ status: 200, body: { albums: { items: [
    { id: "sal", name: "Master of Puppets", total_tracks: 8,
      artists: [{ name: "Metallica" }] },   // note: NO external_ids, as Spotify sends
  ] } } }]);
  const sp = new Spotify(liveSession, { fetch: http });
  const albums = await sp.searchByUpc("075992736121");
  assert.match(http.calls[0].url, /q=upc%3A075992736121/);
  assert.match(http.calls[0].url, /type=album/);
  assert.strictEqual(albums.length, 1);
  assert.strictEqual(albums[0].upc, "075992736121",
    "the searched-for barcode is stamped on, or the matcher's tier cannot fire");
});

test("a barcode search that returns a crowd is NOT trusted as one", async () => {
  // A barcode identifies one release. If the filter hands back a pile, it is
  // not behaving like a filter, and stamping would be a confidently wrong
  // match — the exact failure this app refuses to make.
  const many = Array.from({ length: 6 }, (_, i) =>
    ({ id: "a" + i, name: "Something Else", total_tracks: 9, artists: [{ name: "Nope" }] }));
  const http = fakeFetch([{ status: 200, body: { albums: { items: many } } }]);
  const sp = new Spotify(liveSession, { fetch: http });
  const albums = await sp.searchByUpc("075992736121");
  assert.strictEqual(albums.length, 6);
  assert.ok(albums.every((a) => a.upc === ""),
    "nothing is stamped, so these are judged on title and artist like anything else");
});

test("an empty barcode makes no request at all", async () => {
  const http = fakeFetch([{ status: 200, body: {} }]);
  const sp = new Spotify(liveSession, { fetch: http });
  assert.deepStrictEqual(await sp.searchByUpc(""), []);
  assert.deepStrictEqual(await sp.searchByUpc(null), []);
  assert.strictEqual(http.calls.length, 0);
});

test("Qobuz searches the barcode as a plain query and needs no stamp", async () => {
  // Unlike Spotify's, Qobuz's album listings DO carry upc, so the matcher
  // compares the real codes itself.
  const http = fakeFetch([{ status: 200, body: { albums: { items: [
    { id: 7, title: "Master Of Puppets", upc: "075992736121", tracks_count: 8,
      artist: { name: "Metallica" } },
  ] } } }]);
  const qz = new Qobuz({ token: "t" }, { fetch: http });
  const albums = await qz.searchByUpc("075992736121");
  assert.match(http.calls[0].url, /query=075992736121/);
  assert.match(http.calls[0].url, /type=albums/);
  assert.strictEqual(albums[0].upc, "075992736121", "read from the response, not stamped");
});

// ------------------------------------------------- an album's track listing
//
// albumTracks was added to the interface and to both clients for the
// corroboration tier — the thing that decides a barcode-less album — and NOT
// ONE of these tests existed. Only the fake was tested, and a fake agrees with
// whatever you wrote. On a real library the tier then refused every album,
// which is exactly the failure mode this whole repository is shaped against:
// the corroboration silently could not read anything, so nothing matched.

test("a Qobuz album's tracks come from album/get, with no extra parameter", async () => {
  // album/get RETURNS THE TRACKS ON ITS OWN. MusicD-Remote — a Qobuz client
  // that works — asks for `{album_id}` and reads `tracks.items`, and passing
  // an `extra` value the API does not define is a request that can be refused
  // outright. A refused read here is not a visible error: safely() turns it
  // into "could not read the track listing" and every album is unmatched.
  const http = fakeFetch([{ status: 200, body: {
    id: "0060254776324", title: "Kind Of Blue", upc: "0060254776324",
    tracks_count: 5, artist: { name: "Miles Davis" },
    tracks: { items: [
      { id: 1, title: "So What", duration: 545, isrc: "USSM17700001" },
      { id: 2, title: "Freddie Freeloader", duration: 574 },
    ] },
  } }]);
  const qz = new Qobuz({ token: "t" }, { fetch: http });
  const tracks = await qz.albumTracks("0060254776324");

  assert.match(http.calls[0].url, /album\/get/);
  assert.match(http.calls[0].url, /album_id=0060254776324/);
  assert.ok(!/extra=/.test(http.calls[0].url),
    "album/get carries the tracks already; an undefined extra risks a refusal");

  assert.deepStrictEqual(tracks.map((t) => t.title), ["So What", "Freddie Freeloader"]);
  assert.strictEqual(tracks[0].durationMs, 545000, "Qobuz counts SECONDS");
  assert.strictEqual(tracks[0].isrc, "USSM17700001");
  assert.strictEqual(tracks[0].album, "Kind Of Blue",
    "the album block does not repeat per track, so it is threaded down");
});

test("a Qobuz album with no tracks block is an empty listing, not a throw", async () => {
  const http = fakeFetch([{ status: 200, body: { id: "x", title: "Nothing" } }]);
  const qz = new Qobuz({ token: "t" }, { fetch: http });
  assert.deepStrictEqual(await qz.albumTracks("x"), []);
});

test("a Spotify album's tracks are paged, and carry no ISRC", async () => {
  // SimplifiedTrackObject: no external_ids and no album block. That is fine
  // for what this is for — comparing TITLES against the other side's listing
  // — but a caller must not expect an ISRC from it.
  const page1 = { items: Array.from({ length: 50 }, (_, i) =>
    ({ id: "t" + i, name: "Track " + i, duration_ms: 200000, artists: [{ name: "Band" }] })),
    total: 52 };
  const page2 = { items: [
    { id: "t50", name: "Track 50", duration_ms: 1000, artists: [{ name: "Band" }] },
    { id: "t51", name: "Track 51", duration_ms: 1000, artists: [{ name: "Band" }] }],
    total: 52 };
  const http = fakeFetch([{ status: 200, body: page1 }, { status: 200, body: page2 }]);
  const sp = new Spotify(liveSession, { fetch: http });
  const tracks = await sp.albumTracks("sal");

  assert.match(http.calls[0].url, /albums\/sal\/tracks/);
  assert.strictEqual(tracks.length, 52, "a box set is more than one page");
  assert.strictEqual(tracks[0].title, "Track 0");
  assert.strictEqual(tracks[0].isrc, "", "a simplified track has none");
});

test("an album id is escaped rather than concatenated", async () => {
  // Qobuz ids are digits and Spotify's are base62, but they arrive from a
  // search response rather than from us, and a path built by concatenation is
  // one odd id away from requesting something else entirely.
  const http = fakeFetch([{ status: 200, body: { items: [], total: 0 } }]);
  const sp = new Spotify(liveSession, { fetch: http });
  await sp.albumTracks("a/b?c");
  assert.match(http.calls[0].url, /albums\/a%2Fb%3Fc\/tracks/);
});
