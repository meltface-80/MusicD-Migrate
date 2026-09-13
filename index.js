#!/usr/bin/env node
"use strict";
/*
 * index.js — the HTTP server, and the only file that knows about Express.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 *
 * WHAT THIS FILE IS AND IS NOT. It is the sign-in flows, the route table, and
 * one job at a time. It contains no matching, no paging, no batching and no
 * service knowledge: all of that is in lib/, where it is unit-tested without a
 * network, and — the reason it matters — where the Android build reimplements
 * it against the same tests rather than against this file.
 *
 * ONE JOB AT A TIME, ON PURPOSE. Two migrations running at once would race on
 * the match cache, double the request rate into two services that both rate
 * limit, and produce two progress bars for one library. The UI asks, the
 * server refuses a second, and that is the whole concurrency story.
 */

const path = require("path");
const crypto = require("crypto");
const express = require("express");
const compression = require("compression");

const storeMod = require("./lib/store");
const QobuzOAuth = require("./lib/qobuz-oauth");
const PKCE = require("./lib/spotify-pkce");
const { Qobuz } = require("./lib/qobuz");
const { Spotify, exchangeCode } = require("./lib/spotify");
const { Migration } = require("./lib/migrate");

for (const level of ["log", "warn", "error"]) {
  const orig = console[level].bind(console);
  console[level] = (...args) => orig(new Date().toISOString(), ...args);
}

const PORT = Number(process.env.PORT) || 3380;
const HOST = process.env.HOST || "0.0.0.0";
const PIN = String(process.env.MIGRATE_PIN || "").trim();

const store = storeMod.open(process.env.DATA_DIR);
const orphans = store.markOrphansInterrupted();
if (orphans) console.log(`marked ${orphans} interrupted job(s) from a previous run`);

const app = express();
app.disable("x-powered-by");
app.use(compression());
app.use(express.json({ limit: "1mb" }));

/*
 * The optional shared PIN.
 *
 * This server holds two services' sign-in tokens and can write to somebody's
 * library, and in Docker it necessarily listens on every interface — a
 * published port is how the redirect reaches it from a phone. On a home
 * network that is usually fine and a PIN would be friction for nothing, so it
 * is OFF by default and turned on with MIGRATE_PIN. When it is on it covers
 * every route including the static page, because a gate with an unauthenticated
 * hole in it is not a gate.
 *
 * The OAuth callbacks are the deliberate exception: they arrive from the
 * user's browser following a redirect from Qobuz or Spotify, which cannot
 * carry a header, and they are useless without the one-time state this process
 * generated moments earlier.
 */
const OPEN_PATHS = new Set(["/api/qobuz/oauth/callback", "/api/spotify/callback"]);
app.use((req, res, next) => {
  if (!PIN || OPEN_PATHS.has(req.path)) return next();
  const given = req.get("x-migrate-pin") || (req.query && req.query.pin) || "";
  if (safeEqual(String(given), PIN)) return next();
  res.status(401).json({ error: "This app is PIN protected. Enter the PIN to continue.",
                         needPin: true });
});

app.use(express.static(path.join(__dirname, "public"), { maxAge: "1h" }));

// ---------------------------------------------------------------- sessions

/** The live migration, or null. See the note at the top about one at a time. */
let current = null;

function qobuzClient() {
  const s = store.get("qobuz.session");
  if (!s || !s.token) return null;
  return new Qobuz(s);
}

function spotifyClient() {
  const s = store.get("spotify.session");
  const clientId = store.get("spotify.clientId", "");
  if (!s || !s.refreshToken || !clientId) return null;
  return new Spotify(Object.assign({}, s, { clientId }), {
    // Persisted on every refresh. Spotify rotates refresh tokens, so a refresh
    // that is not written down can leave the install unable to recover.
    onTokens: (tokens) => store.put("spotify.session", {
      accessToken: tokens.accessToken, refreshToken: tokens.refreshToken,
      expiresAt: tokens.expiresAt, userId: tokens.userId || "",
    }),
  });
}

// ------------------------------------------------------------------ state

app.get("/api/state", async (req, res) => {
  const q = store.get("qobuz.session");
  const sp = store.get("spotify.session");
  res.json({
    pinRequired: !!PIN,
    qobuz: { signedIn: !!(q && q.token), name: (q && q.displayName) || "" },
    spotify: {
      signedIn: !!(sp && sp.refreshToken),
      name: (sp && sp.name) || "",
      clientId: store.get("spotify.clientId", ""),
      redirectUri: PKCE.callbackUrlFrom(req, "/api/spotify/callback"),
      redirectCheck: PKCE.checkRedirectUri(PKCE.callbackUrlFrom(req, "/api/spotify/callback")),
    },
    cacheSize: store.matchCacheSize(),
    job: current ? { id: current.jobId, running: true } : null,
    version: require("./package.json").version,
  });
});

// -------------------------------------------------------------- Qobuz auth

app.get("/api/qobuz/oauth/start", (req, res) => {
  try {
    const redirect = QobuzOAuth.callbackUrlFrom(req, "/api/qobuz/oauth/callback");
    store.put("qobuz.pending", { redirect, at: Date.now() });
    res.redirect(QobuzOAuth.buildAuthorizeUrl(redirect));
  } catch (e) {
    res.status(400).send(escapeHtml(e.message));
  }
});

app.get("/api/qobuz/oauth/callback", async (req, res) => {
  const code = QobuzOAuth.extractCode(req.originalUrl);
  if (!code) return res.send(closingPage("Qobuz did not send a sign-in code back. " +
    "The sign-in may have been cancelled — close this and try again."));
  try {
    const { token, userId } = await QobuzOAuth.exchangeCode(code);
    await finishQobuz(token, userId, QobuzOAuth.APP_ID);
    res.send(closingPage("Signed in to Qobuz. You can close this tab."));
  } catch (e) {
    res.send(closingPage("Qobuz sign-in failed: " + escapeHtml(e.message)));
  }
});

/** The paste-back path, for when the redirect cannot reach this server. */
app.post("/api/qobuz/oauth/paste", async (req, res) => {
  const code = QobuzOAuth.extractCode((req.body && req.body.url) || "");
  if (!code) return res.status(400).json({ error: "No sign-in code found in that." });
  try {
    const { token, userId } = await QobuzOAuth.exchangeCode(code);
    await finishQobuz(token, userId, QobuzOAuth.APP_ID);
    res.json({ ok: true });
  } catch (e) {
    res.status(400).json({ error: e.message });
  }
});

app.post("/api/qobuz/login", async (req, res) => {
  const { username, password } = req.body || {};
  try {
    const qz = new Qobuz({});
    const s = await qz.login(username, password);
    // The MD5 of the password is what Qobuz itself wants and is what gets
    // stored — the plaintext is never written down. It is not a hash in any
    // protective sense (it is unsalted and Qobuz accepts it in place of the
    // password), which is why the browser sign-in is offered first.
    store.put("qobuz.session", { token: s.token, userId: s.userId,
      displayName: s.displayName, appId: qz.appId });
    res.json({ ok: true, name: s.displayName });
  } catch (e) {
    res.status(400).json({ error: e.message });
  }
});

async function finishQobuz(token, userId, appId) {
  const qz = new Qobuz({ token, userId, appId });
  let name = "";
  try {
    name = (await qz.me()).name;
  } catch (e) {
    // user/get is a nicety — the token is already proven by the exchange. A
    // failure here must not throw away a good sign-in.
    name = "";
  }
  store.put("qobuz.session", { token, userId, appId, displayName: name });
}

app.post("/api/qobuz/signout", (req, res) => {
  store.del("qobuz.session");
  res.json({ ok: true });
});

// ------------------------------------------------------------ Spotify auth

app.post("/api/spotify/client-id", (req, res) => {
  const id = String((req.body && req.body.clientId) || "").trim();
  if (!/^[0-9a-f]{32}$/i.test(id)) {
    return res.status(400).json({ error:
      "A Spotify Client ID is 32 letters and numbers. Copy it from your app on " +
      "developer.spotify.com/dashboard." });
  }
  store.put("spotify.clientId", id);
  res.json({ ok: true });
});

app.get("/api/spotify/oauth/start", (req, res) => {
  const clientId = store.get("spotify.clientId", "");
  if (!clientId) return res.status(400).send("Set your Spotify Client ID first.");
  const redirectUri = PKCE.callbackUrlFrom(req, "/api/spotify/callback");
  const verifier = PKCE.createVerifier();
  const state = PKCE.createState();
  // Stored rather than held in memory so the callback still works across a
  // restart, and so the paste-back path can find the verifier that goes with
  // the code the user is pasting.
  store.put("spotify.pending", { verifier, state, redirectUri, at: Date.now() });
  res.redirect(PKCE.buildAuthorizeUrl({ clientId, redirectUri, state,
    challenge: PKCE.challengeFor(verifier), forceDialog: true }));
});

app.get("/api/spotify/callback", async (req, res) => {
  const parsed = PKCE.parseCallback(req.originalUrl);
  try {
    await finishSpotify(parsed);
    res.send(closingPage("Signed in to Spotify. You can close this tab."));
  } catch (e) {
    res.send(closingPage("Spotify sign-in failed: " + escapeHtml(e.message)));
  }
});

app.post("/api/spotify/paste", async (req, res) => {
  try {
    await finishSpotify(PKCE.parseCallback((req.body && req.body.url) || ""));
    res.json({ ok: true });
  } catch (e) {
    res.status(400).json({ error: e.message });
  }
});

async function finishSpotify(parsed) {
  if (parsed.error) {
    throw new Error(parsed.error === "access_denied"
      ? "The sign-in was cancelled or refused on Spotify's page."
      : "Spotify reported: " + parsed.error);
  }
  if (!parsed.code) throw new Error("No sign-in code found in that.");

  const pending = store.get("spotify.pending");
  if (!pending || !pending.verifier) {
    throw new Error("No sign-in was in progress. Start again from this page.");
  }
  // The state ties the callback to the sign-in this process started. It is
  // checked only when one came back: the paste-back path routinely loses it,
  // because people copy the address bar of a page that failed to load.
  if (parsed.state && pending.state && parsed.state !== pending.state) {
    throw new Error("That sign-in does not match the one started here. Start again.");
  }

  const tokens = await exchangeCode({
    clientId: store.get("spotify.clientId", ""),
    code: parsed.code,
    redirectUri: pending.redirectUri,
    verifier: pending.verifier,
  });
  store.del("spotify.pending");
  store.put("spotify.session", tokens);

  const sp = spotifyClient();
  let name = "";
  try {
    name = (await sp.me()).name;
  } catch (e) {
    name = "";
  }
  store.put("spotify.session", Object.assign({}, store.get("spotify.session"),
    { name, userId: sp.session.userId || "" }));
}

app.post("/api/spotify/signout", (req, res) => {
  store.del("spotify.session");
  store.del("spotify.pending");
  res.json({ ok: true });
});

// -------------------------------------------------------------- the library

/** The source's playlists, so the user can choose which to bring over. */
app.get("/api/playlists", async (req, res) => {
  const which = String(req.query.service || "");
  const client = which === "qobuz" ? qobuzClient() : which === "spotify" ? spotifyClient() : null;
  if (!client) return res.status(400).json({ error: "Not signed in to " + which + "." });
  try {
    if (!client.userId) await client.me();
    const all = await client.playlists();
    res.json({ playlists: all.map((p) => Object.assign({}, p,
      { mine: !p.ownerId || p.ownerId === String(client.userId || "") })) });
  } catch (e) {
    res.status(e.code === 401 ? 401 : 502).json({ error: e.message });
  }
});

// ------------------------------------------------------------------- jobs

app.post("/api/migrate", async (req, res) => {
  if (current) return res.status(409).json({ error: "A migration is already running." });

  const body = req.body || {};
  const direction = body.direction === "spotify-to-qobuz" ? "spotify-to-qobuz"
                                                          : "qobuz-to-spotify";
  const fromName = direction === "qobuz-to-spotify" ? "qobuz" : "spotify";
  const toName = direction === "qobuz-to-spotify" ? "spotify" : "qobuz";

  const qz = qobuzClient();
  const sp = spotifyClient();
  if (!qz) return res.status(400).json({ error: "Sign in to Qobuz first." });
  if (!sp) return res.status(400).json({ error: "Sign in to Spotify first." });

  const source = fromName === "qobuz" ? qz : sp;
  const target = toName === "qobuz" ? qz : sp;

  const options = {
    playlists: Array.isArray(body.playlists) ? body.playlists : !!body.playlists,
    albums: !!body.albums,
    artists: !!body.artists,
    tracks: !!body.tracks,
    dryRun: !!body.dryRun,
    strict: !!body.strict,
    onExisting: ["add-missing", "create-new", "skip"].includes(body.onExisting)
      ? body.onExisting : "add-missing",
    includeOthersPlaylists: !!body.includeOthersPlaylists,
    playlistSuffix: String(body.playlistSuffix || "").slice(0, 40),
    concurrency: Number(body.concurrency) || undefined,
  };

  const jobId = crypto.randomUUID();
  store.createJob(jobId, direction, options, options.dryRun);

  const migration = new Migration({ source, target, sourceName: fromName, targetName: toName,
    store, jobId, options });
  current = migration;

  // Deliberately NOT awaited: a migration takes minutes and the browser is not
  // going to hold a request open for it. The job id comes back now and the UI
  // polls /api/job/:id.
  migration.run()
    .then(() => store.finishJob(jobId, "done", null))
    .catch((e) => store.finishJob(jobId, e && e.cancelled ? "cancelled" : "failed",
                                  e && e.message))
    .finally(() => {
      // Only clear it if it is still this job: a cancel followed immediately
      // by a new migration would otherwise have the old one clear the new.
      if (current === migration) current = null;
    });

  res.json({ jobId });
});

app.get("/api/job/:id", (req, res) => {
  const job = store.job(req.params.id);
  if (!job) return res.status(404).json({ error: "No such job." });
  res.json(Object.assign({}, job, {
    counts: store.itemCounts(req.params.id),
    running: !!(current && current.jobId === job.id),
  }));
});

app.get("/api/job/:id/items", (req, res) => {
  const job = store.job(req.params.id);
  if (!job) return res.status(404).json({ error: "No such job." });
  const status = req.query.status ? String(req.query.status) : null;
  const all = store.items(req.params.id, status);
  const limit = Math.min(Number(req.query.limit) || 500, 5000);
  res.json({ total: all.length, items: all.slice(0, limit) });
});

/**
 * The unmatched list as a spreadsheet.
 *
 * This is the actual deliverable of a migration: the forty records that did
 * not come across, in a form someone can work through. A list that can only be
 * scrolled in a browser tab is a list nobody finishes.
 */
app.get("/api/job/:id/report.csv", (req, res) => {
  const job = store.job(req.params.id);
  if (!job) return res.status(404).send("No such job.");
  const status = req.query.status ? String(req.query.status) : null;
  const rows = store.items(req.params.id, status);
  const header = ["kind", "playlist", "item", "status", "method", "note", "targetId"];
  const csv = [header.join(",")].concat(rows.map((r) => [
    r.kind, r.container || "", r.sourceLabel, r.status, r.method || "", r.note || "",
    r.targetId || "",
  ].map(csvCell).join(","))).join("\r\n");
  res.type("text/csv").set("Content-Disposition",
    `attachment; filename="musicd-migrate-${job.id.slice(0, 8)}.csv"`).send(csv);
});

app.post("/api/job/:id/cancel", (req, res) => {
  if (!current || current.jobId !== req.params.id) {
    return res.status(404).json({ error: "That job is not running." });
  }
  current.cancel();
  res.json({ ok: true });
});

app.get("/api/jobs", (req, res) => {
  res.json({
    jobs: store.jobs(25).map((j) => Object.assign({}, j, { counts: store.itemCounts(j.id) })),
  });
});

app.delete("/api/job/:id", (req, res) => {
  if (current && current.jobId === req.params.id) {
    return res.status(409).json({ error: "That job is still running." });
  }
  store.deleteJob(req.params.id);
  res.json({ ok: true });
});

app.post("/api/cache/clear", (req, res) => {
  store.clearMatchCache();
  res.json({ ok: true, cacheSize: store.matchCacheSize() });
});

// --------------------------------------------------------------- internals

/**
 * Constant-time comparison for the PIN.
 *
 * A `===` on a secret leaks its length and its first differing byte through
 * timing. The PIN is short and the window is small, but "small" is not a
 * reason to write the comparison the wrong way when the right way is one line.
 */
function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  if (x.length !== y.length) return false;
  return crypto.timingSafeEqual(x, y);
}

function csvCell(v) {
  const s = String(v == null ? "" : v);
  // A leading =, +, - or @ is executed as a formula by Excel and Sheets when
  // the file is opened. Track titles beginning with "-" are not rare.
  const guarded = /^[=+\-@]/.test(s) ? "'" + s : s;
  return /[",\r\n]/.test(guarded) ? '"' + guarded.replace(/"/g, '""') + '"' : guarded;
}

function escapeHtml(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

/** What the OAuth redirect lands on. Plain, self-contained, and it tells the
 *  opener that it is done so the app can refresh without being polled. */
function closingPage(message) {
  return `<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MusicD Migrate</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 16px/1.5 system-ui, sans-serif; margin: 0; display: grid;
         place-items: center; min-height: 100svh; padding: 24px; text-align: center; }
  p { max-width: 32rem; }
</style>
<p>${escapeHtml(message)}</p>
<script>
  try { if (window.opener) window.opener.postMessage("musicd-migrate-auth", "*"); } catch (e) {}
</script>`;
}

app.use((err, req, res, next) => {
  console.error("unhandled:", err && err.message);
  res.status(500).json({ error: (err && err.message) || "Something went wrong." });
});

const server = app.listen(PORT, HOST, () => {
  console.log(`MusicD Migrate listening on http://${HOST}:${PORT}`);
  if (PIN) console.log("PIN protection is on.");
});

for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, () => {
    console.log(sig + " — shutting down");
    if (current) current.cancel();
    server.close(() => { store.close(); process.exit(0); });
    // A migration mid-request can hold the server open past any reasonable
    // wait. The job is re-runnable and the store is transactional, so the
    // hard stop costs nothing but the last hundred rows.
    setTimeout(() => process.exit(0), 4000).unref();
  });
}

module.exports = { app, server };
