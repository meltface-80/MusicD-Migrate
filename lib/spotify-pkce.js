"use strict";
/*
 * spotify-pkce.js — signing in to Spotify without a client secret.
 *
 * WHY PKCE AND NOT THE ORDINARY CODE FLOW. The ordinary flow needs a client
 * secret, and there is nowhere to put one here: this app is a container the
 * user runs and an APK on their phone, so any secret shipped with it is a
 * secret published to everybody who downloads it. PKCE exists exactly for
 * clients that cannot keep one — the app mints a random verifier per sign-in,
 * sends only its hash to Spotify, and proves possession by presenting the
 * original when it redeems the code. Nothing secret is stored in the build.
 *
 * WHY THE USER SUPPLIES THE CLIENT ID. Spotify will not issue tokens to an
 * unregistered application, and an application is registered against a fixed
 * list of redirect URIs. This app's address is whatever machine it is running
 * on — 192.168.1.50:3380, a hostname, a phone — so no client id registered by
 * anyone else could ever list the right one. On top of that, a Spotify app in
 * development mode serves at most 25 named users, so a shared id would work
 * for the first 25 people to try it and fail for everyone after. Creating an
 * app on Spotify's dashboard is free and takes about a minute, and the README
 * walks through it.
 *
 * Nothing here talks to the network: it builds strings and parses them. That
 * is what makes the whole sign-in testable without an account.
 */

const crypto = require("node:crypto");

const AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
const TOKEN_URL = "https://accounts.spotify.com/api/token";

/**
 * What this app asks to be allowed to do.
 *
 * Read AND write on each of the four things it migrates, because it migrates
 * in both directions and the same install is the source one day and the
 * destination the next. Nothing here grants playback control or access to
 * listening history: this app moves library contents and has no reason to
 * touch anything else, and a scope not asked for is a scope that cannot be
 * misused.
 */
const SCOPES = [
  "playlist-read-private",
  "playlist-read-collaborative",
  "playlist-modify-private",
  "playlist-modify-public",
  "user-library-read",
  "user-library-modify",
  "user-follow-read",
  "user-follow-modify",
];

/** The unreserved characters PKCE allows. 64 of them is ~380 bits. */
const VERIFIER_ALPHABET =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

/**
 * A fresh code verifier.
 *
 * randomInt rather than arithmetic on random bytes: 256 is not a multiple of
 * 64 here only by luck of the alphabet length, and the habit of reducing a
 * byte modulo an alphabet size is how RNG output quietly loses uniformity when
 * the alphabet changes. This cannot.
 */
function createVerifier(length = 64) {
  const n = Math.min(Math.max(Number(length) || 64, 43), 128); // the spec's range
  let out = "";
  for (let i = 0; i < n; i++) {
    out += VERIFIER_ALPHABET[crypto.randomInt(VERIFIER_ALPHABET.length)];
  }
  return out;
}

/** base64url(SHA-256(verifier)) — what Spotify is shown instead of the verifier. */
function challengeFor(verifier) {
  return crypto.createHash("sha256").update(String(verifier), "ascii")
    .digest("base64")
    .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** An opaque value echoed back through the redirect, to tie the two halves
 *  of one sign-in together and reject a callback nobody started. */
function createState() {
  return crypto.randomBytes(16).toString("hex");
}

/**
 * Where Spotify should send the browser back to.
 *
 * Derived from the request rather than configured, for the same reason the
 * Qobuz flow derives it: inside a container this process has no idea what
 * address it is reached on, and behind a proxy the Host header is the proxy's
 * upstream rather than the one the user typed.
 *
 * @param {object} req   an Express-style request
 * @param {string} path  e.g. "/api/spotify/callback"
 */
function callbackUrlFrom(req, path) {
  const h = (req && req.headers) || {};
  const host = h["x-forwarded-host"] || h.host || "";
  if (!host) return "";
  const proto = String(h["x-forwarded-proto"] || "").split(",")[0].trim()
             || (req && req.secure ? "https" : "http");
  return proto + "://" + host + path;
}

/**
 * Is this an address Spotify will accept as a redirect URI?
 *
 * Spotify tightened this in 2025: a redirect URI must be https, OR http on a
 * LOOPBACK IP LITERAL. "http://localhost:3380/..." is specifically refused —
 * the hostname resolves differently on different machines — while
 * "http://127.0.0.1:3380/..." is fine. Neither is obvious, and the error
 * Spotify shows for a bad one ("INVALID_CLIENT: Invalid redirect URI") names
 * nothing that would lead a person to the cause. So this is checked HERE, and
 * the UI tells the user which of the two situations they are in before they
 * spend a sign-in finding out.
 *
 * @returns {{ok:boolean, reason:string}}
 */
function checkRedirectUri(url) {
  let u;
  try {
    u = new URL(String(url || ""));
  } catch (e) {
    return { ok: false, reason: "That is not a URL Spotify could redirect to." };
  }
  if (u.protocol === "https:") return { ok: true, reason: "" };
  if (u.protocol !== "http:") {
    return { ok: false, reason: "Spotify only accepts http and https redirect URIs." };
  }
  if (u.hostname === "127.0.0.1" || u.hostname === "[::1]" || u.hostname === "::1") {
    return { ok: true, reason: "" };
  }
  if (u.hostname === "localhost") {
    return { ok: false, reason:
      "Spotify rejects http://localhost — it wants the loopback address as a number. " +
      "Open this app on http://127.0.0.1:" + (u.port || "3380") + " instead, or use the " +
      "paste-back option below." };
  }
  return { ok: false, reason:
    "Spotify only accepts an http redirect on 127.0.0.1, so it will not redirect to " +
    u.host + ". Register http://127.0.0.1:" + (u.port || "3380") + "/api/spotify/callback " +
    "in your Spotify app and use the paste-back option below." };
}

/** The page to send the user to. They sign in there, never here. */
function buildAuthorizeUrl(opts) {
  const o = opts || {};
  if (!o.clientId) throw new Error("A Spotify Client ID is required to sign in");
  if (!o.redirectUri) throw new Error("A redirect URI is required to sign in");
  if (!o.challenge) throw new Error("A code challenge is required to sign in");
  const q = new URLSearchParams({
    client_id: String(o.clientId),
    response_type: "code",
    redirect_uri: String(o.redirectUri),
    code_challenge_method: "S256",
    code_challenge: String(o.challenge),
    scope: (o.scopes || SCOPES).join(" "),
    state: String(o.state || ""),
  });
  // Forces the consent screen. Without it, re-authorising after a scope change
  // silently reuses the old grant and the new scope is never actually granted —
  // which surfaces later as a 403 on one endpoint and looks like a bug.
  if (o.forceDialog) q.set("show_dialog", "true");
  return AUTHORIZE_URL + "?" + q.toString();
}

/**
 * Pull the code (or Spotify's refusal) out of whatever came back.
 *
 * Accepts the whole redirect URL, a bare query string, or the code alone —
 * the same three shapes lib/qobuz-oauth.js accepts, and for the same reason:
 * the redirect normally lands here by itself, but when the user is signing in
 * from a phone that cannot reach 127.0.0.1 on the server, the only way back is
 * copying the address bar, and people copy all three of those.
 *
 * @returns {{code:string, state:string, error:string}}
 */
function parseCallback(urlOrCode) {
  const v = String(urlOrCode == null ? "" : urlOrCode).trim();
  if (!v) return { code: "", state: "", error: "" };

  if (v.startsWith("http") || v.includes("?") || v.includes("&") || v.includes("=")) {
    const qs = v.includes("?") ? v.slice(v.indexOf("?") + 1) : v.replace(/^\?/, "");
    const p = new URLSearchParams(qs);
    return {
      code: (p.get("code") || "").trim(),
      state: (p.get("state") || "").trim(),
      // Spotify reports a refused or cancelled sign-in as ?error=access_denied
      // rather than as a status. Reading it here is what lets the UI say "you
      // cancelled" instead of "no code came back".
      error: (p.get("error") || "").trim(),
    };
  }
  return { code: v, state: "", error: "" };
}

module.exports = {
  AUTHORIZE_URL, TOKEN_URL, SCOPES,
  createVerifier, challengeFor, createState,
  callbackUrlFrom, checkRedirectUri, buildAuthorizeUrl, parseCallback,
};
