"use strict";
/*
 * Drives the real Express app over a real socket. No service is contacted:
 * everything here is either refused before a request would be made, or reads
 * rows the test put in the store itself.
 */
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), "mdm-srv-"));
process.env.DATA_DIR = DATA_DIR;
process.env.PORT = "0";
process.env.MIGRATE_PIN = "1234";

const { server } = require("../../index");
const PKCE = require("../../lib/spotify-pkce");
const store = require("../../lib/store").open(DATA_DIR);

const base = () => `http://127.0.0.1:${server.address().port}`;
const withPin = (init) => Object.assign({}, init, {
  headers: Object.assign({ "x-migrate-pin": "1234" }, (init && init.headers) || {}),
});

test.after(() => { server.close(); store.close(); });

test("every route is refused without the PIN", async () => {
  const res = await fetch(base() + "/api/state");
  assert.strictEqual(res.status, 401);
  assert.strictEqual((await res.json()).needPin, true);
});

test("a wrong PIN is refused", async () => {
  const res = await fetch(base() + "/api/state",
    { headers: { "x-migrate-pin": "9999" } });
  assert.strictEqual(res.status, 401);
});

test("the static page is behind the PIN too, not just the API", async () => {
  assert.strictEqual((await fetch(base() + "/")).status, 401);
});

test("the OAuth callbacks are reachable without it, because a redirect cannot carry a header",
  async () => {
    const res = await fetch(base() + "/api/spotify/callback?error=access_denied");
    assert.strictEqual(res.status, 200);
    assert.match(await res.text(), /cancelled or refused/);
  });

test("the right PIN gets through", async () => {
  const res = await fetch(base() + "/api/state", withPin());
  assert.strictEqual(res.status, 200);
  const j = await res.json();
  assert.strictEqual(j.pinRequired, true);
  assert.strictEqual(j.qobuz.signedIn, false);
});

test("state reports the redirect URI it would actually use", async () => {
  const j = await (await fetch(base() + "/api/state", withPin())).json();
  // /login rather than /api/spotify/callback — see lib/spotify-pkce.js.
  assert.match(j.spotify.redirectUri, /^http:\/\/127\.0\.0\.1:\d+\/login$/);
  assert.strictEqual(j.spotify.redirectCheck.ok, true, "127.0.0.1 is one Spotify accepts");
});

test("a migration is refused while not signed in", async () => {
  const res = await fetch(base() + "/api/migrate", withPin({
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ direction: "qobuz-to-spotify", tracks: true }),
  }));
  assert.strictEqual(res.status, 400);
  assert.match((await res.json()).error, /Sign in to Qobuz/);
});

test("with nothing saved, the baked-in client id is the one in force", async () => {
  // Deliberately before the save test below, which writes an id into the same
  // store. Spotify has frozen new registrations, so a setup step nobody can
  // complete is an unusable app; the id is baked in and the page shows the
  // one actually in force rather than an empty box.
  const j = await (await fetch(base() + "/api/state", withPin())).json();
  assert.strictEqual(j.spotify.clientId, PKCE.DEFAULT_CLIENT_ID);
  assert.match(PKCE.DEFAULT_CLIENT_ID, /^[0-9a-f]{32}$/,
    "a Spotify client id is 32 hex characters");
});

test("a sign-in starts with no id saved, and starts with the baked-in one", async () => {
  // This used to be a 400 telling the user to set an id first. There is
  // nowhere to get one, so it was a dead end.
  const res = await fetch(base() + "/api/spotify/oauth/start", withPin({ redirect: "manual" }));
  assert.strictEqual(res.status, 302);
  const to = new URL(res.headers.get("location"));
  assert.strictEqual(to.host, "accounts.spotify.com");
  assert.strictEqual(to.searchParams.get("client_id"), PKCE.DEFAULT_CLIENT_ID);
  // The path is the whole reason a shared id works at all.
  assert.match(to.searchParams.get("redirect_uri"), /\/api\/spotify\/callback$|\/login$/);
});

test("a malformed Spotify client id is refused with an actionable message", async () => {
  const res = await fetch(base() + "/api/spotify/client-id", withPin({
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ clientId: "not-a-client-id" }),
  }));
  assert.strictEqual(res.status, 400);
  assert.match((await res.json()).error, /32 letters and numbers/);
});

test("a well-formed client id is accepted and reported back", async () => {
  const id = "a".repeat(32);
  const res = await fetch(base() + "/api/spotify/client-id", withPin({
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ clientId: id }),
  }));
  assert.strictEqual(res.status, 200);
  const j = await (await fetch(base() + "/api/state", withPin())).json();
  assert.strictEqual(j.spotify.clientId, id);
  assert.notStrictEqual(id, PKCE.DEFAULT_CLIENT_ID,
    "a saved id WINS over the baked-in one — it is a fallback, not an override");
});

test("an unknown job is a 404, not a crash", async () => {
  assert.strictEqual((await fetch(base() + "/api/job/nope", withPin())).status, 404);
  assert.strictEqual((await fetch(base() + "/api/job/nope/report.csv", withPin())).status, 404);
});

test("the CSV report escapes quotes, commas and formula-leading cells", async () => {
  store.createJob("csvjob", "qobuz-to-spotify", {}, false);
  store.addItems("csvjob", [
    { kind: "track", container: 'My "Best" Mix', sourceId: "1",
      sourceLabel: "Song, with a comma", status: "unmatched",
      note: 'nothing called "Song, with a comma"' },
    { kind: "track", sourceId: "2", sourceLabel: "-Dash Leading Title",
      status: "unmatched", note: "not found" },
  ]);
  store.finishJob("csvjob", "done", null);

  const res = await fetch(base() + "/api/job/csvjob/report.csv", withPin());
  assert.strictEqual(res.status, 200);
  assert.match(res.headers.get("content-type") || "", /text\/csv/);
  const lines = (await res.text()).split("\r\n");
  assert.strictEqual(lines[0], "kind,playlist,item,status,method,note,targetId");
  assert.ok(lines[1].includes('"My ""Best"" Mix"'), "a quote is doubled and the cell quoted");
  assert.ok(lines[1].includes('"Song, with a comma"'), "a comma forces quoting");
  assert.ok(lines[2].includes("'-Dash Leading Title"),
    "a leading dash is defused so a spreadsheet does not run it as a formula");
});

test("job items can be filtered to the unmatched ones", async () => {
  store.createJob("mixed", "qobuz-to-spotify", {}, false);
  store.addItems("mixed", [
    { kind: "track", sourceId: "1", sourceLabel: "A", status: "matched", targetId: "x" },
    { kind: "track", sourceId: "2", sourceLabel: "B", status: "unmatched", note: "gone" },
  ]);
  const all = await (await fetch(base() + "/api/job/mixed/items", withPin())).json();
  assert.strictEqual(all.total, 2);
  const un = await (await fetch(base() + "/api/job/mixed/items?status=unmatched",
    withPin())).json();
  assert.strictEqual(un.total, 1);
  assert.strictEqual(un.items[0].sourceLabel, "B");
  assert.strictEqual(un.items[0].targetId, null, "items come back in the app's shape");
});

test("a job listing carries its counts", async () => {
  const j = await (await fetch(base() + "/api/jobs", withPin())).json();
  const mixed = j.jobs.find((x) => x.id === "mixed");
  assert.deepStrictEqual(mixed.counts, { matched: 1, unmatched: 1 });
});

test("cancelling a job that is not running is a 404", async () => {
  const res = await fetch(base() + "/api/job/mixed/cancel", withPin({ method: "POST" }));
  assert.strictEqual(res.status, 404);
});

test("a job can be deleted, and takes its items with it", async () => {
  const res = await fetch(base() + "/api/job/mixed", withPin({ method: "DELETE" }));
  assert.strictEqual(res.status, 200);
  assert.strictEqual((await fetch(base() + "/api/job/mixed", withPin())).status, 404);
  assert.strictEqual(store.items("mixed").length, 0);
});

test("clearing the match cache reports the new size", async () => {
  store.cacheMatch("qobuz", "1", "spotify", "track", "s1", "isrc");
  const res = await fetch(base() + "/api/cache/clear", withPin({ method: "POST" }));
  assert.deepStrictEqual(await res.json(), { ok: true, cacheSize: 0 });
});

// --------------------------------------------------------------------------
// Regression: Spotify redirects to /login, not /api/spotify/callback.
//
// The shared community Client IDs — the only ones available while Spotify has
// new registrations frozen — whitelist exactly one loopback path, /login.
// Advertising /api/spotify/callback got "redirect_uri: Not matching
// configuration" before the user could even sign in.

test("the advertised Spotify redirect URI is /login", async () => {
  const j = await (await fetch(base() + "/api/state", withPin())).json();
  assert.match(j.spotify.redirectUri, /^http:\/\/127\.0\.0\.1:\d+\/login$/,
    "anything else is refused by the shared community Client IDs");
  assert.strictEqual(j.spotify.redirectCheck.ok, true);
});

test("/login is served, and is not swallowed by the static handler", async () => {
  // public/ has no file called "login", so express.static passes it through —
  // but only if the route exists at all. Before the fix this was a 404.
  const res = await fetch(base() + "/login?error=access_denied");
  assert.strictEqual(res.status, 200);
  assert.match(await res.text(), /cancelled or refused/);
});

test("the old callback path is still served, so a registered URI keeps working",
  async () => {
    const res = await fetch(base() + "/api/spotify/callback?error=access_denied");
    assert.strictEqual(res.status, 200);
    assert.match(await res.text(), /cancelled or refused/);
  });

test("both callback paths bypass the PIN, since a redirect carries no header",
  async () => {
    // Not 401: a browser following Spotify's redirect cannot send the PIN.
    for (const p of ["/login", "/api/spotify/callback"]) {
      const res = await fetch(base() + p + "?error=access_denied");
      assert.strictEqual(res.status, 200, p + " must not be behind the PIN");
    }
  });

// ---------------------------------------------------------------- Roon
//
// No Roon Core is contacted: every one of these is either refused before a
// packet would leave, or reads rows the test put in the store itself. What is
// being checked is the part that WOULD be wrong without a test — a refusal
// that says the wrong thing, or a route that pairs as a side effect of the
// page being loaded.

test("asking for the state never starts looking for a Roon Core", async () => {
  // A page refresh must not broadcast on somebody's network. The Roon client
  // is created on the first press of "Find my Roon Core" and not before.
  const j = await (await fetch(base() + "/api/state", withPin())).json();
  assert.strictEqual(j.roon.stage, "idle");
  assert.strictEqual(j.roon.paired, false);
  assert.strictEqual(j.roon.albums, 0);
  assert.strictEqual(j.roon.scan, null);
});

test("a scan is refused until there is a Core to scan", async () => {
  const res = await fetch(base() + "/api/roon/scan", withPin({ method: "POST" }));
  assert.strictEqual(res.status, 400);
  assert.match((await res.json()).error, /Pair with a Roon Core/);
});

test("migrating from Roon is refused with the step that is missing, not a 500", async () => {
  const res = await fetch(base() + "/api/migrate", withPin({
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ direction: "roon-to-spotify", albums: true, dryRun: true }),
  }));
  assert.strictEqual(res.status, 400);
  assert.match((await res.json()).error, /Pair with a Roon Core/);
});

test("Roon is never offered as a destination", async () => {
  // There is no way to put an album into somebody's local library. An unknown
  // direction falls back to the default rather than inventing one, and the
  // refusal that follows names Qobuz, not Roon.
  const res = await fetch(base() + "/api/migrate", withPin({
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ direction: "spotify-to-roon", albums: true, dryRun: true }),
  }));
  assert.strictEqual(res.status, 400);
  assert.match((await res.json()).error, /Qobuz/);
});

test("asking Roon for playlists answers with the reason, not an empty list", async () => {
  // An empty list reads as "you have no playlists". The truth is that this app
  // will not migrate them, and the reason is the argument for why Roon albums
  // are safe and Roon tracks are not.
  const res = await fetch(base() + "/api/playlists?service=roon", withPin());
  assert.strictEqual(res.status, 400);
  assert.match((await res.json()).error, /no length to match it on/);
});

test("the library CSV says there is no scan rather than sending an empty file", async () => {
  const res = await fetch(base() + "/api/roon/library.csv", withPin());
  assert.strictEqual(res.status, 404);
  assert.match(await res.text(), /has been scanned yet/);
});

test("the library CSV carries the albums and what each was matched to", async () => {
  // The actual deliverable of a Roon scan: not "it worked", but a row per
  // album saying whether it was found on each service and, when it was not,
  // why. The reasons come out of the match cache.
  store.saveRoonAlbums("roon", [
    { albumKey: "ra_one", title: "Kind Of Blue", artist: "Miles Davis", position: 0 },
    { albumKey: "ra_two", title: "-Minus", artist: "Someone", position: 1 },
  ]);
  store.setRoonAlbumTrackCount("roon", "ra_one", 5);
  store.cacheMatch("roon", "ra_one", "spotify", "album", "spAlbum1", "exact+tracklist");
  store.cacheMatch("roon", "ra_two", "spotify", "album", null,
    "an album of that name is there but its track listing does not agree");

  const res = await fetch(base() + "/api/roon/library.csv", withPin());
  assert.strictEqual(res.status, 200);
  assert.match(res.headers.get("content-type") || "", /text\/csv/);
  const lines = (await res.text()).split("\r\n");

  assert.strictEqual(lines[0],
    "artist,album,tracks,spotify,spotifyNote,qobuz,qobuzNote,roonKey");
  assert.match(lines[1], /^Miles Davis,Kind Of Blue,5,spAlbum1,,,,ra_one$/);
  // A title beginning with "-" is executed as a formula by Excel and Sheets.
  assert.match(lines[2], /^Someone,'-Minus,,,/);
  assert.match(lines[2], /track listing does not agree/);

  store.clearRoonAlbums("roon");
});
