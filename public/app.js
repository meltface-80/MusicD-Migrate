"use strict";
/*
 * app.js — the front-end's plumbing, its state and the Qobuz sign-in.
 * import.js (the library-file card), run.js (progress, results, history) and
 * clear.js (the clear-out card) are loaded after it and share
 * `window.Mandarin` with it.
 *
 * THE PAGE IS THE AUTHORITY ON EVERY API FIELD NAME, not the server. This
 * directory is bundled unchanged into the Android APK, where MigrateApi.kt
 * answers the /api/ routes — and nothing compiles the two together, so
 * ContractTest reads these files to check every route and option they send is
 * served and read there. Anything added here has to be added there too.
 *
 * Vanilla, no build step, and it must stay that way: the APK bundles this
 * directory as-is.
 */

(function (M) {
  var state = null;
  M.currentJob = null;

  // ------------------------------------------------------------ plumbing

  function $(id) { return document.getElementById(id); }

  function api(path, opts) {
    var o = opts || {};
    var headers = o.headers || {};
    if (o.body !== undefined) headers["Content-Type"] = "application/json";
    return fetch(path, {
      method: o.method || "GET",
      headers: headers,
      body: o.body === undefined ? undefined : JSON.stringify(o.body)
    }).catch(function (e) {
      throw unreachable(e);
    }).then(function (res) {
      if (res.status === 401) {
        return res.json().catch(function () { return {}; }).then(function (j) {
          throw new Error((j && j.error) || "Not authorised");
        });
      }
      var type = res.headers.get("content-type") || "";
      if (type.indexOf("application/json") < 0) {
        return res.text().then(function (t) {
          if (!res.ok) throw new Error(t.slice(0, 200) || ("HTTP " + res.status));
          return t;
        });
      }
      return res.json().then(function (j) {
        if (!res.ok) throw new Error((j && j.error) || ("HTTP " + res.status));
        return j;
      });
    });
  }

  /**
   * "Failed to fetch" in words a person can act on. fetch() rejects like this
   * only when no answer came back at all — and the server is this app's own,
   * on this phone, so the usual cause is Android having paused the app while
   * another one (the file picker) was in front.
   */
  function unreachable(e) {
    return new Error("Couldn't reach the app's own server (" + ((e && e.message) || e) +
      "). Android may have paused it while you were in another app — try again in a " +
      "moment, and if it keeps happening, close the app and open it again.");
  }

  var toastTimer = null;
  function toast(msg) {
    var el = $("toast");
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.hidden = true; }, 5200);
  }

  function fail(e) { toast((e && e.message) || String(e)); }

  // --------------------------------------------------------- the state

  function refresh() {
    return api("/api/state").then(function (s) {
      state = s;
      M.state = s;
      render();
      return s;
    });
  }

  function render() {
    if (!state) return;
    $("version").textContent = "v" + (state.version || "?");

    // Qobuz
    var q = state.qobuz;
    $("qobuz-dot").className = "dot" + (q.signedIn ? " on" : "");
    $("qobuz-who").textContent = q.signedIn ? (q.name || "signed in") : "not signed in";
    $("qobuz-body").hidden = q.signedIn;
    $("qobuz-signout").hidden = !q.signedIn;

    $("cache-note").textContent = state.cacheSize
      ? state.cacheSize + " cached lookups — a re-run reuses these"
      : "no cached lookups yet";

    var blocked = whyNotReady();
    $("go").disabled = !!blocked;
    $("go-hint").textContent = blocked || "";

    M.renderImport();
    M.renderClear();
  }

  /**
   * What is stopping an import, or "" — named specifically.
   */
  function whyNotReady() {
    if (!state) return "Loading…";
    return signInNeeded("qobuz") || (state["import"] ? "" : "Choose a library file first.");
  }

  function signInNeeded(service) {
    if (service === "qobuz" && !state.qobuz.signedIn) return "Sign in to Qobuz first.";
    return "";
  }

  // ------------------------------------------------------------- Qobuz

  $("qobuz-start").addEventListener("click", function () {
    openAuth("/api/qobuz/oauth/start");
  });
  $("qobuz-manual-toggle").addEventListener("click", function () {
    $("qobuz-manual").hidden = !$("qobuz-manual").hidden;
  });
  $("qobuz-paste-go").addEventListener("click", function () {
    api("/api/qobuz/oauth/paste", { method: "POST", body: { url: $("qobuz-paste").value } })
      .then(function () { $("qobuz-paste").value = ""; toast("Signed in to Qobuz."); })
      .then(refresh).catch(fail);
  });
  $("qobuz-login").addEventListener("click", function () {
    api("/api/qobuz/login", { method: "POST", body: {
      username: $("qobuz-user").value, password: $("qobuz-pass").value } })
      .then(function () { $("qobuz-pass").value = ""; toast("Signed in to Qobuz."); })
      .then(refresh).catch(fail);
  });
  $("qobuz-signout").addEventListener("click", function () {
    api("/api/qobuz/signout", { method: "POST" }).then(refresh).catch(fail);
  });

  /**
   * Open a sign-in.
   *
   * A popup where one is allowed, so the app keeps its place and can be told
   * when the sign-in finished. Where it is blocked — and in the APK's WebView,
   * which has no popups — the same URL in this tab works, and coming back
   * lands on a fresh load that reads the new state anyway.
   */
  function openAuth(url) {
    var win = null;
    try { win = window.open(url, "mandarin-auth", "width=520,height=720"); } catch (e) { win = null; }
    if (!win) { window.location.href = url; return; }
    toast("Finish signing in in the other window.");
  }

  // The page the redirect lands on says so, rather than this polling for it.
  window.addEventListener("message", function (e) {
    if (e && e.data === "mandarin-migration-auth") refresh().catch(fail);
  });
  // A tab that was navigated away and came back: re-read on focus.
  window.addEventListener("focus", function () {
    if (!M.currentJob) refresh().catch(function () {});
  });

  // What the other scripts use. Shared through one object rather than
  // globals, so the lint's no-undef still catches a name that does not exist.
  M.$ = $;
  M.api = api;
  M.unreachable = unreachable;
  M.toast = toast;
  M.fail = fail;
  M.refresh = refresh;
  M.whyNotReady = whyNotReady;
  M.signInNeeded = signInNeeded;

  // ------------------------------------------------------------- start
  //
  // On DOMContentLoaded, which fires only after every script at the end of
  // the page has run — so run.js and clear.js have registered themselves.

  document.addEventListener("DOMContentLoaded", function () {
  refresh().then(function () {
    $("main").hidden = false;
    M.loadHistory();
    // A job left running by a reload: pick it back up rather than showing an
    // idle page while the server is still working.
    if (state.job && state.job.running) M.watch(state.job.id);
  }).catch(function (e) {
    $("main").hidden = false;
    fail(e);
  });
  });
})(window.Mandarin = {});
