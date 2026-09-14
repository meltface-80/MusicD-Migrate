"use strict";
/*
 * The Roon library walk, against a scripted browse tree.
 *
 * No socket and no Core: RoonClient takes browse/load/withSession as a seam,
 * so a fake tree here exercises the paging, the offset hints, the album drill
 * and the refusals. What it cannot cover is a real Core's own behaviour — the
 * exact titles it uses for its hierarchies, whether a box set pages the way
 * this assumes — and that has to be said out loud rather than implied by a
 * green suite.
 */
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const storeMod = require("../../lib/store");
const { RoonClient, albumKey } = require("../../lib/roon");
const { isSource, isTarget } = require("../../lib/service");
const { Migration } = require("../../lib/migrate");

function tmpStore() {
  return storeMod.open(fs.mkdtempSync(path.join(os.tmpdir(), "roon-")));
}

/**
 * A Core whose browse tree is a plain object.
 *
 * `albums` is the album list in Roon's order; each album may carry `tracks`,
 * which is what drilling into it returns. Every call is counted, because "how
 * many times did this talk to the Core" is a correctness property here: the
 * whole design is that a ten thousand album library costs a hundred calls to
 * list and four per album only when something needs its track listing.
 */
function scriptedCore(o) {
  const opts = o || {};
  const albums = opts.albums || [];
  const artists = opts.artists || [];
  const calls = { browse: 0, load: 0, sessions: 0 };
  // Where each browse session currently is: the album list, or inside one.
  const at = new Map();

  return {
    calls,
    coreId: opts.coreId || "core-1",
    coreName: "Study Mac",
    albums,
    async browse(req) {
      calls.browse++;
      const key = req.multi_session_key;
      if (req.pop_all) {
        at.set(key, { level: req.hierarchy === "artists" ? "artists" : "albums" });
        return opts.browseAnswer || { action: "list", list: { count: albums.length } };
      }
      if (req.item_key) {
        const found = albums.find((a) => a.itemKey === req.item_key);
        if (!found) return { action: "message", message: "that item is gone" };
        at.set(key, { level: "album", album: found });
        return { action: "list", list: { count: (found.tracks || []).length } };
      }
      return { action: "list", list: { count: 0 } };
    },
    async load(req) {
      calls.load++;
      const where = at.get(req.multi_session_key) || { level: "albums" };
      let rows;
      if (where.level === "artists") rows = artists.map((name) => ({ title: name }));
      else if (where.level === "album") rows = (where.album.tracks || []).slice();
      else {
        rows = albums.map((a) => ({
          title: a.title, subtitle: a.artist, item_key: a.itemKey,
          image_key: a.imageKey, hint: "action_list",
        }));
      }
      const page = rows.slice(req.offset, req.offset + req.count);
      return { items: page, list: { count: rows.length } };
    },
    async withSession(fn) {
      calls.sessions++;
      return fn("s1");
    },
  };
}

function album(title, artist, extra) {
  return Object.assign({ title, artist, itemKey: "k:" + title }, extra || {});
}

function track(title, artist) {
  return { title, subtitle: artist || "", item_key: "t:" + title, hint: "action_list" };
}

// --------------------------------------------------------------- the shape

test("a Roon client is a source and is not a target", () => {
  const client = new RoonClient({ core: scriptedCore(), store: tmpStore() });
  assert.strictEqual(isSource(client), true);
  assert.strictEqual(isTarget(client), false,
    "there is no way to put an album into somebody's local library");
});

test("tracks and playlists are refused with a reason, not reported as none", async () => {
  const client = new RoonClient({ core: scriptedCore(), store: tmpStore() });
  assert.match(client.unsupported.tracks, /length/,
    "the reason has to be the real one: a Roon track carries no duration");
  assert.match(client.unsupported.playlists, /list of tracks/);
});

test("a migration records why tracks were skipped instead of finishing green", async () => {
  // The failure this prevents: a run that reports "0 tracks matched" and looks
  // like a library with nothing in it, rather than like a thing this app
  // declines to do.
  const store = tmpStore();
  const source = new RoonClient({ core: scriptedCore(), store });
  const target = {};
  for (const m of ["me", "playlists", "playlistTracks", "savedTracks", "savedAlbums",
                   "followedArtists", "albumDetail", "searchByIsrc", "searchTracks",
                   "searchAlbums", "searchByUpc", "searchArtists", "createPlaylist",
                   "addToPlaylist", "saveTracks", "saveAlbums", "followArtists"]) {
    target[m] = async () => [];
  }
  store.createJob("j1", "roon->spotify", {}, true);
  const m = new Migration({ source, target, sourceName: "roon", targetName: "spotify",
    store, jobId: "j1",
    options: { tracks: true, playlists: true, albums: false, artists: false, dryRun: true } });
  const result = await m.run();

  const items = store.items("j1");
  assert.strictEqual(items.length, 2, "one row per kind that was asked for and refused");
  assert.strictEqual(result.counts.skipped, 2);
  for (const it of items) {
    assert.strictEqual(it.status, "skipped");
    assert.ok(it.note && it.note.length > 40,
      "the row carries the explanation, since the report is what the user reads");
  }
});

// ---------------------------------------------------------------- the scan

test("a scan walks every page and stores what it found", async () => {
  // 250 albums: three pages, the last one short.
  const albums = [];
  for (let i = 0; i < 250; i++) albums.push(album("Album " + i, "Artist " + (i % 20)));
  const core = scriptedCore({ albums });
  const store = tmpStore();
  const client = new RoonClient({ core, store });

  const progress = [];
  const summary = await client.scan({ onProgress: (p) => progress.push(p.done) });

  assert.strictEqual(summary.stored, 250);
  assert.strictEqual(summary.total, 250);
  assert.strictEqual(summary.done, true);
  assert.strictEqual(store.roonAlbumCount("core-1"), 250);
  assert.deepStrictEqual(progress, [100, 200, 250]);
  assert.strictEqual(core.calls.load, 3, "one load per page and not one per album");
  assert.strictEqual(core.calls.sessions, 1, "one pooled browse session for the whole scan");
});

test("two copies of the same record are one album, and the count says so", async () => {
  // A CD rip and a vinyl rip of the same record. One of them is enough to
  // migrate — but if the number just quietly dropped, the user would be left
  // wondering why 3 albums became 2.
  const core = scriptedCore({ albums: [
    album("Kind Of Blue", "Miles Davis"),
    album("kind of blue", "Miles Davis"),
    album("Blue Train", "John Coltrane"),
  ] });
  const client = new RoonClient({ core, store: tmpStore() });
  const summary = await client.scan();
  assert.strictEqual(summary.stored, 2);
  assert.strictEqual(summary.duplicates, 1, "reported, not hidden");
});

test("a scan that is interrupted resumes where it stopped", async () => {
  const albums = [];
  for (let i = 0; i < 250; i++) albums.push(album("Album " + i, "A"));
  const core = scriptedCore({ albums });
  const store = tmpStore();
  const client = new RoonClient({ core, store });

  let seen = 0;
  await client.scan({ cancelled: () => seen++ >= 1 });   // stops after one page
  assert.strictEqual(store.roonAlbumCount("core-1"), 100);
  const half = store.get("roon.scan", null);
  assert.strictEqual(half.offset, 100);
  assert.strictEqual(half.done, false);

  const loadsBefore = core.calls.load;
  const summary = await client.scan({ resume: true });
  assert.strictEqual(summary.offset, 250);
  assert.strictEqual(store.roonAlbumCount("core-1"), 250);
  assert.strictEqual(core.calls.load - loadsBefore, 2,
    "it resumed at page two rather than paying for the first page again");
});

test("a rescan replaces the inventory rather than merging into it", async () => {
  const store = tmpStore();
  const first = scriptedCore({ albums: [album("Gone", "A"), album("Kept", "B")] });
  await new RoonClient({ core: first, store }).scan();
  assert.strictEqual(store.roonAlbumCount("core-1"), 2);

  // "Gone" has been deleted from Roon. It must not live on in the export.
  const second = scriptedCore({ albums: [album("Kept", "B")] });
  await new RoonClient({ core: second, store }).scan();
  assert.strictEqual(store.roonAlbumCount("core-1"), 1);
  assert.deepStrictEqual(store.roonAlbums("core-1").map((a) => a.title), ["Kept"]);
});

test("a Core that reports a count it does not serve does not loop forever", async () => {
  // A count of 5,000 with two albums actually there, and the inflated count
  // repeated on every page -- which is the shape that matters. Reaching the
  // end by `offset >= total` alone never happens here, so a short page has to
  // be what ends the walk, or the scan spins on an empty page for ever.
  const albums = [album("One", "A"), album("Two", "B")];
  const core = scriptedCore({ albums });
  core.browse = async () => ({ action: "list", list: { count: 5000 } });
  const inner = core.load;
  let pages = 0;
  core.load = async (req) => {
    pages++;
    assert.ok(pages < 20, "the scan is looping: it never decided the list had ended");
    const page = await inner(req);
    return { items: page.items, list: { count: 5000 } };
  };
  const client = new RoonClient({ core, store: tmpStore() });
  const summary = await client.scan();
  assert.strictEqual(summary.stored, 2);
  assert.strictEqual(pages, 1, "one short page is the whole list");
});

test("a Core that declines says so in its own words", async () => {
  const core = scriptedCore({ albums: [] });
  core.browse = async () => ({ action: "message", message: "Library is still importing" });
  const client = new RoonClient({ core, store: tmpStore() });
  await assert.rejects(() => client.scan(), /Library is still importing/,
    "the Core's own reason beats anything this app could infer");
});

test("a browse that answers with no list at all is an error", async () => {
  const core = scriptedCore({ albums: [] });
  core.browse = async () => ({ action: "none" });
  const client = new RoonClient({ core, store: tmpStore() });
  await assert.rejects(() => client.scan(), /gave no list/);
});

// ------------------------------------------------------- reading the albums

test("the scanned albums come back with no barcode and an honest null count", async () => {
  const core = scriptedCore({ albums: [album("Kind Of Blue", "Miles Davis")] });
  const store = tmpStore();
  const client = new RoonClient({ core, store });
  await client.scan();

  const albums = await client.savedAlbums();
  assert.strictEqual(albums.length, 1);
  assert.strictEqual(albums[0].upc, "", "Roon has no barcodes: missing data, not evidence");
  assert.strictEqual(albums[0].trackCount, null, "null until something drills in");
  assert.deepStrictEqual(albums[0].artists, ["Miles Davis"]);
  assert.strictEqual(albums[0].id, albumKey("Kind Of Blue", "Miles Davis"),
    "the id is stable across scans, so the match cache survives one");
});

test("reading albums before a scan is an error, not an empty library", async () => {
  const client = new RoonClient({ core: scriptedCore(), store: tmpStore() });
  await assert.rejects(() => client.savedAlbums(), /has been scanned yet/,
    "a migration that reported this as an empty library would finish green " +
    "having done nothing");
});

test("an album id survives a rescan that moved everything", async () => {
  const store = tmpStore();
  const before = scriptedCore({ albums: [album("Ziggy Stardust", "David Bowie")] });
  await new RoonClient({ core: before, store }).scan();
  const idBefore = (await new RoonClient({ core: before, store }).savedAlbums())[0].id;

  // Ten records bought, all sorting above it.
  const after = scriptedCore({ albums:
    Array.from({ length: 10 }, (_, i) => album("A" + i, "Someone"))
      .concat([album("Ziggy Stardust", "David Bowie")]) });
  const client = new RoonClient({ core: after, store });
  await client.scan();
  const moved = (await client.savedAlbums()).find((a) => a.title === "Ziggy Stardust");
  assert.strictEqual(moved.id, idBefore,
    "keying on the offset would have invalidated every cached match");
});

test("the scan status reports what is on hand", async () => {
  const core = scriptedCore({ albums: [album("A", "B"), album("C", "D")] });
  const store = tmpStore();
  const client = new RoonClient({ core, store });
  assert.deepStrictEqual(client.scanStatus(), { albums: 0, scan: null });
  await client.scan();
  const status = client.scanStatus();
  assert.strictEqual(status.albums, 2);
  assert.strictEqual(status.scan.done, true);
});

// ---------------------------------------------------- drilling into an album

test("an album's track listing is drilled on demand and the count remembered", async () => {
  const one = album("Blue Train", "John Coltrane", {
    tracks: [track("Blue Train"), track("Moment's Notice"), track("Locomotion")],
  });
  const core = scriptedCore({ albums: [one] });
  const store = tmpStore();
  const client = new RoonClient({ core, store });
  await client.scan();

  const id = albumKey("Blue Train", "John Coltrane");
  const tracks = await client.albumTracks(id);
  assert.deepStrictEqual(tracks.map((t) => t.title),
    ["Blue Train", "Moment's Notice", "Locomotion"]);
  assert.strictEqual(tracks[0].durationMs, null,
    "a Roon browse row carries no length, and pretending it is zero would be worse");
  assert.strictEqual(tracks[0].isrc, "");
  assert.strictEqual(store.roonAlbum("core-1", id).trackCount, 3,
    "learned for free while we were in there");
  assert.strictEqual((await client.savedAlbums())[0].trackCount, 3);
});

test("a header row inside an album is not a track", async () => {
  const one = album("Box Set", "Someone", {
    tracks: [
      { title: "Disc 1", hint: "header" },
      track("First"),
      { title: "No key at all" },
      track("Second"),
    ],
  });
  const core = scriptedCore({ albums: [one] });
  const client = new RoonClient({ core, store: tmpStore() });
  await client.scan();
  const tracks = await client.albumTracks(albumKey("Box Set", "Someone"));
  assert.deepStrictEqual(tracks.map((t) => t.title), ["First", "Second"],
    "a disc banner counted as a track would make every track count wrong");
});

test("an album level that over-reports its count does not loop either", async () => {
  // Same guard as the scan, in the other pager. A level claiming 500 tracks
  // and serving three would otherwise be read for ever, and the album drill
  // is the call that runs once per candidate album.
  const one = album("Short Album", "A", { tracks: [track("a"), track("b"), track("c")] });
  const core = scriptedCore({ albums: [one] });
  const inner = core.load;
  let albumPages = 0;
  core.load = async (req) => {
    const page = await inner(req);
    const insideAlbum = page.items.length > 0 && page.items[0].item_key &&
      String(page.items[0].item_key).startsWith("t:");
    if (!insideAlbum) return page;
    albumPages++;
    assert.ok(albumPages < 20, "the album drill is looping on an over-reported count");
    return { items: page.items, list: { count: 500 } };
  };
  const client = new RoonClient({ core, store: tmpStore() });
  await client.scan();
  const loadsBefore = core.calls.load;
  const tracks = await client.albumTracks(albumKey("Short Album", "A"));
  assert.strictEqual(tracks.length, 3);
  assert.strictEqual(albumPages, 1);
  // Two loads: one to confirm the offset hint, one for the album's contents.
  // A third would mean it went back for a page it had already been told was
  // the last one -- which over ten thousand candidate albums is ten thousand
  // needless round trips to somebody's Core.
  assert.strictEqual(core.calls.load - loadsBefore, 2);
});

test("the remembered offset is used, and confirmed before it is trusted", async () => {
  const albums = [];
  for (let i = 0; i < 150; i++) {
    albums.push(album("Album " + String(i).padStart(3, "0"), "Artist",
      { tracks: [track("t" + i)] }));
  }
  const core = scriptedCore({ albums });
  const store = tmpStore();
  const client = new RoonClient({ core, store });
  await client.scan();

  // Album 140 is on the second page. With the offset hint it is one load; a
  // blind scan would be two pages.
  const loadsBefore = core.calls.load;
  await client.albumTracks(albumKey("Album 140", "Artist"));
  assert.strictEqual(core.calls.load - loadsBefore, 2,
    "one load to confirm the hint, one to read the album's contents");
});

test("a stale offset costs a scan, never the wrong album", async () => {
  // This is the whole reason the hint is confirmed against the title. Getting
  // it wrong means migrating a record the user does not own, which is the
  // failure mode this repository exists to avoid.
  const store = tmpStore();
  const before = scriptedCore({ albums: [
    album("Aja", "Steely Dan", { tracks: [track("Black Cow")] }),
    album("Ziggy Stardust", "David Bowie", { tracks: [track("Five Years")] }),
  ] });
  await new RoonClient({ core: before, store }).scan();

  // Roon's list has been reordered under us: everything shifted by one.
  const after = scriptedCore({ albums: [
    album("Abbey Road", "The Beatles", { tracks: [track("Come Together")] }),
    album("Aja", "Steely Dan", { tracks: [track("Black Cow")] }),
    album("Ziggy Stardust", "David Bowie", { tracks: [track("Five Years")] }),
  ] });
  const client = new RoonClient({ core: after, store });
  const tracks = await client.albumTracks(albumKey("Aja", "Steely Dan"));
  assert.deepStrictEqual(tracks.map((t) => t.title), ["Black Cow"],
    "the album at the remembered offset is now a different one, and the title " +
    "check is what stops it being returned");
});

test("an album that has been deleted from Roon is not an error", async () => {
  const store = tmpStore();
  const before = scriptedCore({ albums: [album("Sold", "Someone", { tracks: [track("x")] })] });
  await new RoonClient({ core: before, store }).scan();

  const after = scriptedCore({ albums: [] });
  const client = new RoonClient({ core: after, store });
  assert.deepStrictEqual(await client.albumTracks(albumKey("Sold", "Someone")), [],
    "one record sold between a scan and a migration must not stop the run");
});

test("an album this client never scanned drills into nothing", async () => {
  const client = new RoonClient({ core: scriptedCore(), store: tmpStore() });
  assert.deepStrictEqual(await client.albumTracks("ra_nonsense"), []);
});

// -------------------------------------------------------------- the artists

test("artists are walked live, paged, and de-duplicated", async () => {
  const names = [];
  for (let i = 0; i < 150; i++) names.push("Artist " + i);
  names.push("Artist 7");                     // the same name twice in Roon's list
  const core = scriptedCore({ albums: [], artists: names });
  const client = new RoonClient({ core, store: tmpStore() });

  const artists = await client.followedArtists();
  assert.strictEqual(artists.length, 150);
  assert.strictEqual(core.calls.load, 2, "paged at 100");
  assert.strictEqual(new Set(artists.map((a) => a.id)).size, 150);
});

test("who the source is comes from the Core, not from a guess", async () => {
  const core = scriptedCore({ coreId: "core-xyz" });
  const client = new RoonClient({ core, store: tmpStore(), coreId: () => core.coreId });
  assert.deepStrictEqual(await client.me(), { id: "core-xyz", name: "Study Mac" });
  assert.strictEqual(client.accountId, "core-xyz");
});
