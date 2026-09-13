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
  assert.match(j.spotify.redirectUri, /^http:\/\/127\.0\.0\.1:\d+\/api\/spotify\/callback$/);
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
