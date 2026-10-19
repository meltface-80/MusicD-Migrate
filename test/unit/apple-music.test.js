"use strict";
const test = require("node:test");
const assert = require("node:assert");
const crypto = require("node:crypto");
const { AppleMusic, checkKey, developerToken, toTrack, toAlbum, fromPlaylistItem } =
  require("../../lib/apple-music");

/*
 * There is no Apple Developer account in CI or in the container this was
 * written in, so nothing here has talked to the real API. What these tests
 * pin is the part that can be checked without one: the token is a valid
 * ES256 JWT, the request shapes match Apple's documentation, the library-vs-
 * catalogue id rule holds, and the rate-limit rules are the ones CLAUDE.md
 * sets for every client.
 */

const { privateKey: KEY, publicKey: PUB } =
  crypto.generateKeyPairSync("ec", { namedCurve: "prime256v1" });
const PEM = KEY.export({ type: "pkcs8", format: "pem" });
const CREDS = { teamId: "ABCDE12345", keyId: "KEY1234567", privateKey: PEM };

function fakeFetch(script) {
  const calls = [];
  const queue = script.slice();
  const fn = async (url, init) => {
    calls.push({ url: String(url), init: init || {} });
    const next = queue.length > 1 ? queue.shift() : queue[0];
    const r = typeof next === "function" ? next(String(url), init) : next;
    return {
      ok: r.status >= 200 && r.status < 300,
      status: r.status,
      headers: { get: (k) => (r.headers || {})[String(k).toLowerCase()] || null },
      text: async () => (r.body === undefined ? "" :
        typeof r.body === "string" ? r.body : JSON.stringify(r.body)),
    };
  };
  fn.calls = calls;
  return fn;
}

function client(script, extra) {
  const fetch = fakeFetch(script);
  const am = new AppleMusic(Object.assign({}, CREDS, { userToken: "mut", storefront: "gb" },
    extra && extra.session), Object.assign({ fetch }, extra && extra.opts));
  return { am, fetch };
}

const q = (url) => new URL(url).searchParams;

// -------------------------------------------------------------- the token

test("the developer token is an ES256 JWT Apple can verify", () => {
  const { token, expiresAt } = developerToken(CREDS, 1_700_000_000);
  const [h, b, s] = token.split(".");
  const head = JSON.parse(Buffer.from(h, "base64url"));
  const body = JSON.parse(Buffer.from(b, "base64url"));
  assert.deepStrictEqual(head, { alg: "ES256", kid: "KEY1234567", typ: "JWT" });
  assert.strictEqual(body.iss, "ABCDE12345");
  assert.strictEqual(body.iat, 1_700_000_000);
  assert.ok(body.exp > body.iat && body.exp - body.iat <= 15777000,
    "Apple refuses a token that lives longer than six months");
  assert.strictEqual(expiresAt, body.exp * 1000);
  // JOSE wants the raw 64-byte r||s. The DER form crypto.sign produces by
  // default is ~70 bytes and Apple answers it with a 401.
  const sig = Buffer.from(s, "base64url");
  assert.strictEqual(sig.length, 64);
  assert.ok(crypto.verify("sha256", Buffer.from(h + "." + b),
    { key: PUB, dsaEncoding: "ieee-p1363" }, sig));
});

test("checkKey says which field is wrong", () => {
  assert.deepStrictEqual(checkKey({ teamId: " abcde12345 ", keyId: "key1234567",
    privateKey: PEM }).teamId, "ABCDE12345");
  assert.throws(() => checkKey(Object.assign({}, CREDS, { teamId: "short" })), /Team ID/);
  assert.throws(() => checkKey(Object.assign({}, CREDS, { keyId: "" })), /Key ID/);
  assert.throws(() => checkKey(Object.assign({}, CREDS, { privateKey: "nonsense" })),
    /BEGIN PRIVATE KEY/);
  const rsa = crypto.generateKeyPairSync("rsa", { modulusLength: 1024 }).privateKey
    .export({ type: "pkcs8", format: "pem" });
  assert.throws(() => checkKey(Object.assign({}, CREDS, { privateKey: rsa })), /MusicKit/);
});

test("every request carries both tokens", async () => {
  const { am, fetch } = client([{ status: 200, body: { data: [] } }]);
  await am.searchByIsrc("gbaye0601498");
  const h = fetch.calls[0].init.headers;
  assert.match(h.Authorization, /^Bearer [\w-]+\.[\w-]+\.[\w-]+$/);
  assert.strictEqual(h["Music-User-Token"], "mut");
  assert.strictEqual(q(fetch.calls[0].url).get("filter[isrc]"), "GBAYE0601498");
  assert.match(fetch.calls[0].url, /\/v1\/catalog\/gb\/songs\?/);
});

test("the storefront is asked for once, and then used for every catalogue path", async () => {
  const { am, fetch } = client([
    { status: 200, body: { data: [{ id: "us", type: "storefronts" }] } },
    { status: 200, body: { data: [] } },
  ], { session: { storefront: "" } });
  await am.searchByUpc("0602547");
  await am.searchByUpc("0602548");
  assert.match(fetch.calls[0].url, /\/v1\/me\/storefront$/);
  assert.match(fetch.calls[1].url, /\/v1\/catalog\/us\/albums\?/);
  assert.match(fetch.calls[2].url, /\/v1\/catalog\/us\/albums\?/);
  assert.strictEqual(fetch.calls.length, 3);
});

// ---------------------------------------------------- library vs catalogue

const librarySong = {
  id: "i.abc", type: "library-songs",
  attributes: { name: "Entropy", artistName: "Beach Bunny", albumName: "Emotional Creature",
                durationInMillis: 221000, playParams: { id: "i.abc", isLibrary: true } },
  relationships: { catalog: { data: [{ id: "1613600188", type: "songs",
    attributes: { isrc: "USQE92100257", name: "Entropy" } }] } },
};

test("a library song reports its CATALOGUE id and the catalogue's ISRC", () => {
  // Writes take catalogue ids and the target's playlist read is compared
  // against search results; a library id here would make every track in an
  // existing playlist look missing and add it again.
  const t = toTrack(librarySong);
  assert.strictEqual(t.id, "1613600188");
  assert.strictEqual(t.isrc, "USQE92100257");
  assert.deepStrictEqual(t.artists, ["Beach Bunny"]);
  assert.strictEqual(t.durationMs, 221000);
});

test("an uploaded song with no catalogue entry keeps its library id", () => {
  const t = toTrack({ id: "i.up", type: "library-songs",
    attributes: { name: "Demo", artistName: "Me", durationInMillis: 1000 } });
  assert.strictEqual(t.id, "i.up");
  assert.strictEqual(t.isrc, "");
});

test("a library album takes its barcode from the catalogue", () => {
  const a = toAlbum({ id: "l.xyz", type: "library-albums",
    attributes: { name: "Rec", artistName: "Band", trackCount: 10, releaseDate: "1999-01-02" },
    relationships: { catalog: { data: [{ id: "555", attributes: { upc: "0602547" } }] } } });
  assert.deepStrictEqual(a, { id: "555", upc: "0602547", title: "Rec", artists: ["Band"],
    trackCount: 10, year: 1999 });
});

test("a music video in a playlist is skipped by name, not dropped", () => {
  const v = fromPlaylistItem({ id: "i.v", type: "library-music-videos",
    attributes: { name: "Clip" } });
  assert.deepStrictEqual(v, { skip: "music video", title: "Clip" });
});

test("library reads ask for the catalogue and follow `next` to the end", async () => {
  const { am, fetch } = client([
    { status: 200, body: { data: [librarySong], next: "/v1/me/library/songs?offset=100" } },
    { status: 200, body: { data: [Object.assign({}, librarySong, { id: "i.def",
      relationships: {} })] } },
  ]);
  const tracks = await am.savedTracks();
  assert.strictEqual(tracks.length, 2);
  assert.strictEqual(q(fetch.calls[0].url).get("include"), "catalog");
  // The second page keeps what `next` dropped — without `include` the ISRCs
  // would vanish from every page after the first.
  assert.strictEqual(q(fetch.calls[1].url).get("offset"), "100");
  assert.strictEqual(q(fetch.calls[1].url).get("include"), "catalog");
  assert.strictEqual(q(fetch.calls[1].url).getAll("offset").length, 1);
});

test("a playlist the user cannot edit reads as a followed one", async () => {
  const { am } = client([{ status: 200, body: { data: [
    { id: "p.1", attributes: { name: "Mine", canEdit: true } },
    { id: "p.2", attributes: { name: "Apple's", canEdit: false } },
  ] } }]);
  const [mine, theirs] = await am.playlists();
  assert.strictEqual(mine.ownerId, "");
  assert.notStrictEqual(theirs.ownerId, "");
  assert.strictEqual(mine.trackCount, null, "unknown, not zero");
});

test("albumTracks reads a library album from the library and a catalogue one from the catalogue", async () => {
  const { am, fetch } = client([{ status: 200, body: { data: [] } }]);
  await am.albumTracks("l.xyz");
  await am.albumTracks("555");
  assert.match(fetch.calls[0].url, /\/v1\/me\/library\/albums\/l\.xyz\/tracks/);
  assert.match(fetch.calls[1].url, /\/v1\/catalog\/gb\/albums\/555\/tracks/);
});

test("album search results carry their own barcode, so the barcode tier can fire", async () => {
  const { am, fetch } = client([{ status: 200, body: { results: { albums: { data: [
    { id: "555", type: "albums", attributes: { name: "Rec", artistName: "Band",
      upc: "0602547", trackCount: 10 } }] } } } }]);
  const [a] = await am.searchAlbums("Rec", "Band");
  assert.strictEqual(a.upc, "0602547");
  assert.strictEqual(q(fetch.calls[0].url).get("term"), "Rec Band");
  assert.strictEqual(q(fetch.calls[0].url).get("types"), "albums");
});

// ------------------------------------------------------------------ writes

test("library writes batch ids into the query by type", async () => {
  const { am, fetch } = client([{ status: 202 }]);
  const ids = Array.from({ length: 120 }, (_, i) => String(i));
  await am.saveAlbums(ids);
  assert.strictEqual(fetch.calls.length, 3, "120 ids at 50 a batch");
  assert.strictEqual(fetch.calls[0].init.method, "POST");
  assert.strictEqual(q(fetch.calls[0].url).get("ids[albums]").split(",").length, 50);
  await am.saveTracks(["9"]);
  assert.strictEqual(q(fetch.calls[3].url).get("ids[songs]"), "9");
});

test("a playlist is created private and filled with catalogue songs in order", async () => {
  const { am, fetch } = client([
    { status: 201, body: { data: [{ id: "p.new", attributes: { name: "Mix" } }] } },
    { status: 204 },
  ]);
  const made = await am.createPlaylist("Mix", { description: "from Spotify", public: true });
  assert.strictEqual(made.id, "p.new");
  const created = JSON.parse(fetch.calls[0].init.body);
  assert.strictEqual(created.attributes.isPublic, undefined, "never published");
  await am.addToPlaylist("p.new", ["3", "1", "2"]);
  assert.match(fetch.calls[1].url, /\/v1\/me\/library\/playlists\/p\.new\/tracks$/);
  assert.deepStrictEqual(JSON.parse(fetch.calls[1].init.body),
    { data: [{ id: "3", type: "songs" }, { id: "1", type: "songs" }, { id: "2", type: "songs" }] });
});

test("artists are declared unsupported, and a stray call is loud", async () => {
  const { am } = client([{ status: 200, body: {} }]);
  assert.match(am.unsupported.artists, /follow an artist/);
  await assert.rejects(am.followArtists(["1"]), /follow an artist/);
});

// ------------------------------------------------------- refusals and 429s

test("401 is the developer token, 403 is the user's sign-in — and both stop a run", async () => {
  const a = client([{ status: 401, body: "" }]).am;
  await assert.rejects(a.searchByIsrc("X"), (e) => e.auth && /Team ID/.test(e.message));
  const b = client([{ status: 403, body: { errors: [{ detail: "Forbidden" }] } }]).am;
  await assert.rejects(b.searchByIsrc("X"), (e) => e.auth && /Connect Apple Music again/.test(e.message));
});

test("no user token is an auth error before any request", async () => {
  const { am, fetch } = client([{ status: 200, body: {} }], { session: { userToken: "" } });
  await assert.rejects(am.searchByIsrc("X"), (e) => e.auth);
  assert.strictEqual(fetch.calls.length, 0);
});

test("Retry-After is obeyed, reported, and turns on pacing", async () => {
  const waits = [];
  const { am, fetch } = client([
    { status: 429, headers: { "retry-after": "0.01" } },
    { status: 200, body: { data: [] } },
  ], { opts: { onRateLimit: (ms) => waits.push(ms) } });
  assert.strictEqual(am._paceMs, 0, "an unthrottled client is never paced");
  await am.searchByIsrc("X");
  assert.strictEqual(fetch.calls.length, 2);
  assert.strictEqual(waits.length, 1);
  assert.ok(waits[0] >= 10, "never less than it asked for");
  assert.ok(am._paceMs > 0);
});

test("a Retry-After longer than a run will hold fails at once, quoting it", async () => {
  const { am, fetch } = client([{ status: 429, headers: { "retry-after": "600" } }]);
  await assert.rejects(am.searchByIsrc("X"), (e) => e.code === 429 && /600s/.test(e.message));
  assert.strictEqual(fetch.calls.length, 1);
});

test("one worker's 429 holds every worker, not just itself", async () => {
  // Apple counts per developer token, so four workers each backing off
  // privately all resume together and earn the next 429 together.
  let first = true;
  const { am, fetch } = client([(url) => {
    if (first) { first = false; return { status: 429, headers: { "retry-after": "0.6" } }; }
    return { status: 200, body: { data: [] } };
  }]);
  const a = am.searchByIsrc("A");
  await new Promise((r) => setTimeout(r, 20));
  const t0 = Date.now();
  await am.searchByIsrc("B"); // a second worker, arriving during the hold
  // 500ms, not just "some wait": pacing alone delays it ~250ms, which would
  // let a client with no shared hold pass.
  assert.ok(Date.now() - t0 >= 500, "the second worker waited out the first one's 429");
  await a;
  assert.strictEqual(fetch.calls.length, 3);
});

test("the browser's token is short-lived, and nothing but the token is signed into it", () => {
  const { BROWSER_TOKEN_TTL_S } = require("../../lib/apple-music");
  const { token } = developerToken(CREDS, 1000, BROWSER_TOKEN_TTL_S);
  const body = JSON.parse(Buffer.from(token.split(".")[1], "base64url"));
  assert.strictEqual(body.exp - body.iat, BROWSER_TOKEN_TTL_S);
  assert.ok(BROWSER_TOKEN_TTL_S <= 3600);
  assert.ok(!token.includes("PRIVATE"));
});
