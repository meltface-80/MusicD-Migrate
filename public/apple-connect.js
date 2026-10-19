"use strict";
/*
 * apple-connect.js — the Apple Music sign-in, the one sign-in with no redirect.
 *
 * 1. Ask this app's server for a short-lived developer token, signed with the
 *    user's own MusicKit key. The key itself never reaches the browser.
 * 2. Load MusicKit JS from Apple and configure it with that token.
 * 3. On the button press — a user gesture, or Apple's window is a blocked
 *    popup — call authorize(), which opens Apple's own sign-in.
 * 4. Hand the Music User Token back to the server, and tell the app's tab.
 *
 * Bundled into the APK unchanged, like app.js, and every route it calls is
 * served by both halves: ContractTest reads this file as well as app.js.
 * Vanilla and no build step, for the same reason.
 */

(function () {
  var MUSICKIT_JS = "https://js-cdn.music.apple.com/musickit/v3/musickit.js";

  function $(id) { return document.getElementById(id); }

  // The PIN, when the server has one: from the address openAuth() built, or
  // from what the app's own tab remembered on this origin.
  var pin = "";
  try { pin = new URLSearchParams(location.search).get("pin") || ""; } catch (e) { pin = ""; }
  if (!pin) {
    try { pin = localStorage.getItem("migrate-pin") || ""; } catch (e) { /* private mode */ }
  }

  function say(text) { $("ac-status").textContent = text; }

  function api(path, body) {
    var headers = {};
    if (pin) headers["x-migrate-pin"] = pin;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    return fetch(path, {
      method: body === undefined ? "GET" : "POST",
      headers: headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (j) {
        if (!res.ok) throw new Error((j && j.error) || ("HTTP " + res.status));
        return j;
      });
    });
  }

  function loadMusicKit() {
    return new Promise(function (resolve, reject) {
      if (window.MusicKit) return resolve();
      document.addEventListener("musickitloaded", function () { resolve(); });
      var s = document.createElement("script");
      s.src = MUSICKIT_JS;
      s.async = true;
      s.setAttribute("data-web-components", "");
      // Said out loud: a page that waits for ever on a script that will never
      // arrive reads as a hang, not as a blocked download.
      s.onerror = function () {
        reject(new Error("Could not load Apple's MusicKit from js-cdn.music.apple.com. " +
          "Check this device can reach it — a content blocker may be stopping it."));
      };
      document.head.appendChild(s);
    });
  }

  var music = null;

  api("/api/apple/developer-token").then(function (j) {
    return loadMusicKit().then(function () {
      return window.MusicKit.configure({
        developerToken: j.developerToken,
        app: { name: "MusicD Migrate", build: j.version || "1" }
      });
    });
  }).then(function (instance) {
    music = instance || window.MusicKit.getInstance();
    say("Ready. Press the button and allow access in Apple's window.");
    $("ac-go").disabled = false;
  }).catch(function (e) {
    var why = (e && e.message) || String(e);
    // MusicKit needs a secure context. A plain-http LAN address is not one;
    // 127.0.0.1 and localhost are. Saying which is the only useful answer.
    if (!window.isSecureContext) {
      why += " This page is not a secure context (plain http on a network address), " +
        "which Apple's MusicKit may refuse. Open MusicD Migrate at http://localhost " +
        "or behind HTTPS and try again.";
    }
    say(why);
  });

  $("ac-go").addEventListener("click", function () {
    if (!music) return;
    $("ac-go").disabled = true;
    say("Waiting for Apple…");
    music.authorize().then(function (userToken) {
      if (!userToken) throw new Error("Apple did not hand back a sign-in token.");
      return api("/api/apple/token", { userToken: userToken });
    }).then(function () {
      say("Connected to Apple Music. You can close this tab and go back to MusicD Migrate.");
      try { if (window.opener) window.opener.postMessage("musicd-migrate-auth", "*"); } catch (e) {
        // The opener is a nicety: the app's tab re-reads its state on focus too.
      }
    }).catch(function (e) {
      $("ac-go").disabled = false;
      say("Apple Music sign-in did not finish: " + ((e && e.message) || String(e)) +
        " — press the button to try again.");
    });
  });
})();
