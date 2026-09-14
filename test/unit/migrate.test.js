"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { Migration } = require("../../lib/migrate");
const storeMod = require("../../lib/store");

function tmpStore() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "mdm-"));
  const s = storeMod.open(dir);
  s._dir = dir;
  return s;
}

/**
 * A stand-in for a service client. Same interface as lib/spotify.js and
 * lib/qobuz.js — which is the point: the engine cannot tell them apart, and
 * neither can this.
 */
class FakeService {
  constructor(name, data) {
    this.name = name;
    this.userId = "me";
    this.catalogue = (data && data.catalogue) || [];
    this.lib = Object.assign({ tracks: [], albums: [], artists: [], playlists: {} },
      (data && data.lib) || {});
    this.written = { tracks: [], albums: [], artists: [], created: [], added: {} };
    this.searchCount = 0;
  }
  async savedTracks() { return this.lib.tracks.slice(); }
  async savedAlbums() { return this.lib.albums.slice(); }
  async followedArtists() { return this.lib.artists.slice(); }
  async playlists() {
    return Object.entries(this.lib.playlists).map(([id, p]) =>
      ({ id, name: p.name, ownerId: "me", trackCount: p.tracks.length, description: "" }));
  }
  async playlistTracks(id) {
    const p = this.lib.playlists[id];
    return p ? p.tracks.slice() : [];
  }
  async searchByIsrc(isrc) {
    this.searchCount++;
    return this.catalogue.filter((t) => t.isrc && t.isrc === isrc);
  }
  async searchTracks(title) {
    this.searchCount++;
    const q = String(title).toLowerCase();
    return this.catalogue.filter((t) => String(t.title).toLowerCase().includes(q));
  }
  /**
   * Album search, modelled on what Spotify ACTUALLY returns: a
   * SimplifiedAlbumObject, which has no external_ids and therefore NO
   * BARCODE. An earlier version of this fake handed back the catalogue entry
   * complete with its upc, so the title path "matched on barcode" in tests
   * while failing against the real service — the fake was hiding the exact bug
   * it should have exposed. Set searchAlbumsCarriesUpc to model a service
   * whose search does include it.
   */
  async searchAlbums(title) {
    this.searchCount++;
    const q = String(title).toLowerCase();
    const hits = (this.catalogueAlbums || []).filter((a) =>
      String(a.title).toLowerCase().includes(q));
    return this.searchAlbumsCarriesUpc ? hits
      : hits.map((a) => Object.assign({}, a, { upc: "" }));
  }
  async searchByUpc(upc) {
    this.upcSearchCount = (this.upcSearchCount || 0) + 1;
    this.searchCount++;
    if (this.upcSearchFails) throw new Error("barcode search blew up");
    return (this.catalogueAlbums || []).filter((a) => a.upc && a.upc === upc);
  }
  async searchArtists(name) {
    this.searchCount++;
    const q = String(name).toLowerCase();
    return (this.catalogueArtists || []).filter((a) =>
      String(a.name).toLowerCase() === q);
  }
  async albumDetail(id) { return (this.lib.albums || []).find((a) => a.id === id) || null; }
  async saveTracks(ids) { this.written.tracks.push(...ids); }
  async saveAlbums(ids) { this.written.albums.push(...ids); }
  async followArtists(ids) { this.written.artists.push(...ids); }
  async createPlaylist(name) {
    const id = "new" + (this.written.created.length + 1);
    this.written.created.push({ id, name });
    this.lib.playlists[id] = { name, tracks: [] };
    return { id, name };
  }
  async addToPlaylist(id, ids) {
    this.written.added[id] = (this.written.added[id] || []).concat(ids);
    const p = this.lib.playlists[id];
    if (p) p.tracks.push(...ids.map((x) => ({ id: x, title: "t", artists: [], durationMs: 1 })));
  }
}

const src = (o) => Object.assign({ id: "q1", title: "Song", artists: ["Band"],
  album: "Rec", durationMs: 200000 }, o);
const dst = (o) => Object.assign({ id: "s1", title: "Song", artists: ["Band"],
  album: "Rec", durationMs: 200000 }, o);

function run(source, target, options, store) {
  const s = store || tmpStore();
  s.createJob("job1", "a->b", options || {}, !!(options || {}).dryRun);
  const m = new Migration({ source, target, sourceName: "qobuz", targetName: "spotify",
    store: s, jobId: "job1", options });
  return m.run().then((r) => ({ result: r, store: s, migration: m,
    items: s.items("job1") }));
}

const NOTHING = { playlists: false, albums: false, artists: false, tracks: false };

// --------------------------------------------------------------------------

test("a matched favourite track is written to the target", async () => {
  const source = new FakeService("q", { lib: { tracks: [src({ isrc: "GBAYE0601498" })] } });
  const target = new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] });
  const { result, items } = await run(source, target,
    Object.assign({}, NOTHING, { tracks: true }));
  assert.deepStrictEqual(target.written.tracks, ["s1"]);
  assert.strictEqual(result.counts.matched, 1);
  assert.strictEqual(items[0].method, "isrc");
});

test("an unmatched track is reported with a reason and nothing is written", async () => {
  const source = new FakeService("q", { lib: { tracks: [src({ title: "Obscure B-side" })] } });
  const target = new FakeService("s", { catalogue: [] });
  const { result, items } = await run(source, target,
    Object.assign({}, NOTHING, { tracks: true }));
  assert.deepStrictEqual(target.written.tracks, []);
  assert.strictEqual(result.counts.unmatched, 1);
  assert.strictEqual(items[0].status, "unmatched");
  assert.ok(items[0].note && items[0].note.length > 0, "the reason is recorded, not blank");
});

test("a dry run does every lookup and writes nothing", async () => {
  const source = new FakeService("q", { lib: { tracks: [src({ isrc: "GBAYE0601498" })] } });
  const target = new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] });
  const { result, items } = await run(source, target,
    Object.assign({}, NOTHING, { tracks: true, dryRun: true }));
  assert.deepStrictEqual(target.written.tracks, [], "a dry run must not write");
  assert.strictEqual(result.counts.matched, 1, "but it still produces the real answer");
  assert.strictEqual(items[0].targetId, "s1");
});

test("tracks already in the target library are skipped without a search", async () => {
  const source = new FakeService("q", { lib: { tracks: [src({ isrc: "GBAYE0601498" })] } });
  const target = new FakeService("s", {
    catalogue: [dst({ isrc: "GBAYE0601498" })],
    lib: { tracks: [dst({ isrc: "GBAYE0601498" })] },
  });
  const { result } = await run(source, target, Object.assign({}, NOTHING, { tracks: true }));
  assert.strictEqual(result.counts.already, 1);
  assert.strictEqual(target.searchCount, 0, "no search for something already there");
  assert.deepStrictEqual(target.written.tracks, []);
});

test("the match cache spares the second run every lookup", async () => {
  const store = tmpStore();
  const mk = () => ({
    source: new FakeService("q", { lib: { tracks: [src({ isrc: "GBAYE0601498" })] } }),
    target: new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] }),
  });
  const a = mk();
  await run(a.source, a.target, Object.assign({}, NOTHING, { tracks: true }), store);
  assert.ok(a.target.searchCount > 0);

  const b = mk();
  store.createJob("job2", "a->b", {}, false);
  const m2 = new Migration({ source: b.source, target: b.target, sourceName: "qobuz",
    targetName: "spotify", store, jobId: "job2",
    options: Object.assign({}, NOTHING, { tracks: true }) });
  await m2.run();
  assert.strictEqual(b.target.searchCount, 0, "the second run searched nothing");
  assert.deepStrictEqual(b.target.written.tracks, ["s1"], "and still wrote the match");
});

test("a cached miss is honoured, not re-searched", async () => {
  const store = tmpStore();
  store.cacheMatch("qobuz", "q1", "spotify", "track", null, "not on Spotify");
  const source = new FakeService("q", { lib: { tracks: [src({})] } });
  const target = new FakeService("s", { catalogue: [dst({})] });
  const { result } = await run(source, target,
    Object.assign({}, NOTHING, { tracks: true }), store);
  assert.strictEqual(target.searchCount, 0);
  assert.strictEqual(result.counts.unmatched, 1);
});

// ------------------------------------------------------------- playlists

test("playlist track order survives concurrent lookups", async () => {
  const n = 25;
  const tracks = Array.from({ length: n }, (_, i) =>
    src({ id: "q" + i, title: "Song " + i, isrc: "GBAYE06014" + String(i).padStart(2, "0") }));
  const catalogue = tracks.map((t) => dst({ id: "s" + t.id.slice(1), title: t.title,
    isrc: t.isrc }));
  // Answer out of order, slowest first, so a naive implementation shuffles.
  const source = new FakeService("q", { lib: { playlists: { p1: { name: "Mix", tracks } } } });
  const target = new FakeService("s", { catalogue });
  const slow = target.searchByIsrc.bind(target);
  target.searchByIsrc = async (isrc) => {
    const i = Number(String(isrc).slice(-2));
    await new Promise((r) => setTimeout(r, (n - i) % 7));
    return slow(isrc);
  };

  await run(source, target, Object.assign({}, NOTHING, { playlists: true, concurrency: 6 }));
  const added = target.written.added[target.written.created[0].id];
  assert.deepStrictEqual(added, tracks.map((t) => "s" + t.id.slice(1)),
    "the destination playlist must be in the source's order");
});

test("re-running a playlist migration adds only what is missing", async () => {
  const store = tmpStore();
  const tracks = [src({ id: "q1", isrc: "GBAYE0601491", title: "One" }),
                  src({ id: "q2", isrc: "GBAYE0601492", title: "Two" })];
  const catalogue = [dst({ id: "s1", isrc: "GBAYE0601491", title: "One" }),
                     dst({ id: "s2", isrc: "GBAYE0601492", title: "Two" })];
  const source = new FakeService("q", { lib: { playlists: { p1: { name: "Mix", tracks } } } });
  const target = new FakeService("s", { catalogue });
  const opts = Object.assign({}, NOTHING, { playlists: true });

  await run(source, target, opts, store);
  const plId = target.written.created[0].id;
  assert.strictEqual(target.written.added[plId].length, 2);

  // A third track appears in the source, and the same migration runs again.
  tracks.push(src({ id: "q3", isrc: "GBAYE0601493", title: "Three" }));
  catalogue.push(dst({ id: "s3", isrc: "GBAYE0601493", title: "Three" }));
  store.createJob("job2", "a->b", opts, false);
  await new Migration({ source, target, sourceName: "qobuz", targetName: "spotify",
    store, jobId: "job2", options: opts }).run();

  assert.strictEqual(target.written.created.length, 1, "no second playlist was created");
  assert.deepStrictEqual(target.written.added[plId], ["s1", "s2", "s3"],
    "only the new track was added");
});

test("two source tracks resolving to one target track add it once", async () => {
  // The album cut and the single, same ISRC — both legitimately map to one
  // Spotify track, and adding it twice is a duplicate the user did not have.
  const tracks = [src({ id: "q1", isrc: "GBAYE0601491", title: "One" }),
                  src({ id: "q2", isrc: "GBAYE0601491", title: "One" })];
  const source = new FakeService("q", { lib: { playlists: { p1: { name: "Mix", tracks } } } });
  const target = new FakeService("s", {
    catalogue: [dst({ id: "s1", isrc: "GBAYE0601491", title: "One" })] });
  await run(source, target, Object.assign({}, NOTHING, { playlists: true }));
  assert.deepStrictEqual(target.written.added[target.written.created[0].id], ["s1"]);
});

test("local files and podcast episodes are skipped and named", async () => {
  const tracks = [{ skip: "local file", title: "bootleg.flac" },
                  { skip: "podcast episode", title: "Some Show" },
                  src({ isrc: "GBAYE0601498" })];
  const source = new FakeService("q", { lib: { playlists: { p1: { name: "Mix", tracks } } } });
  const target = new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] });
  const { result, items } = await run(source, target,
    Object.assign({}, NOTHING, { playlists: true }));
  assert.strictEqual(result.counts.skipped, 2);
  const skipped = items.filter((i) => i.status === "skipped");
  assert.match(skipped[0].note, /local file/);
  assert.match(skipped[1].note, /podcast episode/);
});

test("a playlist where nothing matches is not created at all", async () => {
  const source = new FakeService("q", {
    lib: { playlists: { p1: { name: "Rarities", tracks: [src({ title: "Unfindable" })] } } } });
  const target = new FakeService("s", { catalogue: [] });
  const { items } = await run(source, target, Object.assign({}, NOTHING, { playlists: true }));
  assert.strictEqual(target.written.created.length, 0);
  assert.ok(items.some((i) => i.kind === "playlist" && /not created/.test(i.note || "")));
});

test("playlists belonging to someone else are left alone by default", async () => {
  const source = new FakeService("q", {
    lib: { playlists: { p1: { name: "Someone's", tracks: [src({})] } } } });
  source.playlists = async () => [{ id: "p1", name: "Someone's", ownerId: "other",
    trackCount: 1 }];
  const target = new FakeService("s", { catalogue: [dst({})] });
  await run(source, target, Object.assign({}, NOTHING, { playlists: true }));
  assert.strictEqual(target.written.created.length, 0);
});

// -------------------------------------------------------------- robustness

test("a search that throws costs one track, not the whole migration", async () => {
  const source = new FakeService("q", { lib: {
    tracks: [src({ id: "q1", isrc: "GBAYE0601491" }), src({ id: "q2", isrc: "GBAYE0601492" })] } });
  const target = new FakeService("s", {
    catalogue: [dst({ id: "s2", isrc: "GBAYE0601492" })] });
  // BOTH lookups have to fail for the track to be unlookable: when only the
  // ISRC search throws, falling back to the text search is correct behaviour
  // and the track still matches. An earlier version of this test overrode only
  // the first and was asserting the wrong thing.
  const real = target.searchByIsrc.bind(target);
  target.searchByIsrc = async (isrc) => {
    if (isrc === "GBAYE0601491") throw new Error("service blew up");
    return real(isrc);
  };
  const realText = target.searchTracks.bind(target);
  target.searchTracks = async (title, artist) => {
    if (String(artist) === "Band" && String(title) === "Song") {
      // Only the failing track reaches here in this fixture.
      throw new Error("service blew up");
    }
    return realText(title, artist);
  };
  const { result } = await run(source, target, Object.assign({}, NOTHING, { tracks: true }));
  assert.strictEqual(result.counts.unmatched, 1);
  assert.strictEqual(result.counts.matched, 1);
  assert.deepStrictEqual(target.written.tracks, ["s2"]);
});

test("an expired sign-in stops the migration rather than reporting misses", async () => {
  const source = new FakeService("q", { lib: { tracks: [src({ isrc: "GBAYE0601491" })] } });
  const target = new FakeService("s", { catalogue: [] });
  target.searchByIsrc = async () => {
    const e = new Error("Spotify sign-in has expired"); e.auth = true; throw e;
  };
  await assert.rejects(() => run(source, target, Object.assign({}, NOTHING, { tracks: true })),
    /expired/);
});

test("cancelling stops the run and says so", async () => {
  const tracks = Array.from({ length: 40 }, (_, i) =>
    src({ id: "q" + i, title: "Song " + i }));
  const source = new FakeService("q", { lib: { tracks } });
  const target = new FakeService("s", { catalogue: [] });
  const store = tmpStore();
  store.createJob("jc", "a->b", {}, false);
  const m = new Migration({ source, target, sourceName: "qobuz", targetName: "spotify",
    store, jobId: "jc", options: Object.assign({}, NOTHING, { tracks: true }) });
  const p = m.run();
  m.cancel();
  await assert.rejects(() => p, (e) => e.cancelled === true);
});

test("a failed write is recorded as failed, not silently lost", async () => {
  const source = new FakeService("q", { lib: { tracks: [src({ isrc: "GBAYE0601498" })] } });
  const target = new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] });
  target.saveTracks = async () => { throw new Error("Spotify said no"); };
  const { result, items } = await run(source, target,
    Object.assign({}, NOTHING, { tracks: true }));
  assert.strictEqual(result.counts.failed, 1);
  assert.ok(items.some((i) => i.status === "failed" && /Spotify said no/.test(i.note)));
});

test("artists are matched on an exact name and followed", async () => {
  const source = new FakeService("q", { lib: { artists: [{ id: "qa", name: "Portishead" }] } });
  const target = new FakeService("s", {});
  target.catalogueArtists = [{ id: "sa", name: "portishead" }];
  const { result } = await run(source, target, Object.assign({}, NOTHING, { artists: true }));
  assert.deepStrictEqual(target.written.artists, ["sa"]);
  assert.strictEqual(result.counts.matched, 1);
});

test("albums match on barcode and are saved", async () => {
  const source = new FakeService("q", { lib: { albums: [
    { id: "qal", title: "Rumours", artists: ["Fleetwood Mac"], upc: "075992736121",
      trackCount: 11 }] } });
  const target = new FakeService("s", {});
  target.catalogueAlbums = [{ id: "sal", title: "Rumours", artists: ["Fleetwood Mac"],
    upc: "075992736121", trackCount: 11 }];
  const { result } = await run(source, target, Object.assign({}, NOTHING, { albums: true }));
  assert.deepStrictEqual(target.written.albums, ["sal"]);
  assert.strictEqual(result.counts.matched, 1);
});

test("nothing selected does nothing, and does not fail", async () => {
  const source = new FakeService("q", { lib: { tracks: [src({})] } });
  const target = new FakeService("s", { catalogue: [dst({})] });
  const { result } = await run(source, target, Object.assign({}, NOTHING));
  assert.strictEqual(result.counts.matched, 0);
  assert.strictEqual(target.searchCount, 0);
});

// --------------------------------------------------------------------------
// Regression: the migration has to know whose playlists are whose.
//
// migratePlaylists filters to the source's OWN playlists, which needs the
// account id. A client built straight from a stored session has never called
// me(), and /api/migrate does not call it either — so when the stored id was
// empty, `p.ownerId === ""` was false for every playlist and EVERY ONE was
// filtered out. The migration then reported nothing and looked like it had
// simply found nothing to do.

test("a migration establishes who the source account is before filtering", async () => {
  const tracks = [src({ isrc: "GBAYE0601498" })];
  const source = new FakeService("q", { lib: { playlists: { p1: { name: "Mix", tracks } } } });
  // As a client built from a stored session that never learned its own id.
  source.userId = "";
  source.meCalls = 0;
  source.me = async () => { source.meCalls++; source.userId = "me"; return { id: "me" }; };
  const target = new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] });

  await run(source, target, Object.assign({}, NOTHING, { playlists: true }));

  assert.strictEqual(source.meCalls, 1, "it asked the service who it is");
  assert.strictEqual(target.written.created.length, 1,
    "the user's own playlist must not be filtered out by an unknown account id");
});

test("a source that cannot say who it is migrates playlists rather than none", async () => {
  // If me() fails there is no way to tell an owned playlist from a followed
  // one. Including them is the useful failure; excluding every playlist and
  // reporting success is not.
  const tracks = [src({ isrc: "GBAYE0601498" })];
  const source = new FakeService("q", { lib: { playlists: { p1: { name: "Mix", tracks } } } });
  source.userId = "";
  source.me = async () => { throw new Error("user/get is having a day"); };
  const target = new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] });

  await run(source, target, Object.assign({}, NOTHING, { playlists: true }));
  assert.strictEqual(target.written.created.length, 1);
});

test("an explicit playlist selection is honoured even with an unknown account", async () => {
  const tracks = [src({ isrc: "GBAYE0601498" })];
  const source = new FakeService("q", { lib: { playlists: { p1: { name: "Mix", tracks } } } });
  source.userId = "";
  source.me = async () => { throw new Error("nope"); };
  const target = new FakeService("s", { catalogue: [dst({ isrc: "GBAYE0601498" })] });

  await run(source, target, Object.assign({}, NOTHING, { playlists: ["p1"] }));
  assert.strictEqual(target.written.created.length, 1,
    "an id the user picked needs no ownership check at all");
});

// --------------------------------------------------------------------------
// Regression: albums were matched by barcode in name only.
//
// Spotify's album SEARCH returns simplified objects with no external_ids, so
// every candidate carried upc:"" and the barcode tier compared a real Qobuz
// code against an empty string — it could never fire. Worse, resolveAlbum
// fetched the source barcode AFTER searching, so the code was never used to
// search for anything. Everything fell through to the title tiers, where the
// track-count gate rejected any edition mismatch: 114 of 195 favourite albums
// reported "not found" on a real library.

const album = (o) => Object.assign({
  id: "qal", title: "Master Of Puppets", artists: ["Metallica"],
  upc: "075992736121", trackCount: 8,
}, o);

test("an album is looked up by barcode BEFORE anything else", async () => {
  const source = new FakeService("q", { lib: { albums: [album({})] } });
  const target = new FakeService("s", {});
  // Deliberately a title the text search could never find, and a track count
  // that would fail the close-tier gate. Only the barcode can match this.
  target.catalogueAlbums = [{ id: "sal", title: "Meisterwerk der Marionetten",
    artists: ["Metallica"], upc: "075992736121", trackCount: 12 }];

  const { result, items } = await run(source, target,
    Object.assign({}, NOTHING, { albums: true }));
  assert.deepStrictEqual(target.written.albums, ["sal"]);
  assert.strictEqual(result.counts.matched, 1);
  assert.strictEqual(items[0].method, "upc", "the barcode tier must be what fired");
  assert.strictEqual(target.upcSearchCount, 1, "and it searched by barcode");
});

test("the edition that differs only by a suffix is found by barcode", async () => {
  // The real shape of the reported failure: Qobuz calls it
  // "(Remastered)" with 8 tracks, Spotify's entry has a different count, and
  // the close tier's track-count gate refuses it. The barcode settles it.
  const source = new FakeService("q", { lib: { albums: [
    album({ title: "Master Of Puppets (Remastered)", trackCount: 8 })] } });
  const target = new FakeService("s", {});
  target.catalogueAlbums = [{ id: "sal", title: "Master of Puppets",
    artists: ["Metallica"], upc: "075992736121", trackCount: 10 }];

  const { result } = await run(source, target, Object.assign({}, NOTHING, { albums: true }));
  assert.strictEqual(result.counts.matched, 1, "a barcode match beats the track-count gate");
  assert.deepStrictEqual(target.written.albums, ["sal"]);
});

test("no barcode on the album still falls back to title and artist", async () => {
  const source = new FakeService("q", { lib: { albums: [album({ upc: "" })] } });
  source.albumDetail = async () => null;   // and none to be fetched either
  const target = new FakeService("s", {});
  target.catalogueAlbums = [{ id: "sal", title: "Master Of Puppets",
    artists: ["Metallica"], upc: "", trackCount: 8 }];

  const { result, items } = await run(source, target,
    Object.assign({}, NOTHING, { albums: true }));
  assert.strictEqual(result.counts.matched, 1);
  assert.strictEqual(items[0].method, "exact", "the title tier still works");
  assert.strictEqual(target.upcSearchCount, undefined, "and no barcode search was wasted");
});

test("a barcode that finds nothing falls back rather than giving up", async () => {
  const source = new FakeService("q", { lib: { albums: [album({ upc: "000000000000" })] } });
  const target = new FakeService("s", {});
  target.catalogueAlbums = [{ id: "sal", title: "Master Of Puppets",
    artists: ["Metallica"], upc: "075992736121", trackCount: 8 }];

  const { result } = await run(source, target, Object.assign({}, NOTHING, { albums: true }));
  assert.strictEqual(result.counts.matched, 1, "the title tier picked it up");
});

test("a barcode search that throws costs the barcode tier, not the album", async () => {
  const source = new FakeService("q", { lib: { albums: [album({})] } });
  const target = new FakeService("s", {});
  target.upcSearchFails = true;
  target.catalogueAlbums = [{ id: "sal", title: "Master Of Puppets",
    artists: ["Metallica"], upc: "075992736121", trackCount: 8 }];

  const { result } = await run(source, target, Object.assign({}, NOTHING, { albums: true }));
  assert.strictEqual(result.counts.matched, 1);
});

test("strict mode still accepts only the barcode for albums", async () => {
  const source = new FakeService("q", { lib: { albums: [album({ upc: "" })] } });
  source.albumDetail = async () => null;
  const target = new FakeService("s", {});
  target.catalogueAlbums = [{ id: "sal", title: "Master Of Puppets",
    artists: ["Metallica"], upc: "", trackCount: 8 }];

  const { result } = await run(source, target,
    Object.assign({}, NOTHING, { albums: true, strict: true }));
  assert.strictEqual(result.counts.unmatched, 1, "no barcode, no match under strict");
});
