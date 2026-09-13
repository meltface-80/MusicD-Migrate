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
