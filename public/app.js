"use strict";
/*
 * app.js — the front-end's plumbing, its state, the sign-ins and the
 * migration setup. run.js (progress, results, history) and clear.js (the
 * clear-out card) are loaded after it and share `window.Mandarin` with it.
 *
 * THE PAGE IS THE AUTHORITY ON EVERY API FIELD NAME, not the server. This
 * directory is bundled unchanged into the Android APK, where MigrateApi.kt
 * answers the /api/ routes — and nothing compiles the two together, so
 * ContractTest reads this file to check every route and option it sends is
 * served and read there. Anything added here has to be added there too.
 *
 * Vanilla, no build step, and it must stay that way: the APK bundles this
 * directory as-is.
 */

(function (M) {
  var state = null;
  var playlists = [];
  var chosen = new Set();
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

    // Spotify
    var sp = state.spotify;
    $("spotify-dot").className = "dot" + (sp.signedIn ? " on" : "");
    $("spotify-who").textContent = sp.signedIn ? (sp.name || "signed in") : "not signed in";
    $("spotify-body").hidden = sp.signedIn;
    $("spotify-signout").hidden = !sp.signedIn;
    $("redirect-uri").textContent = sp.redirectUri || "";
    if (sp.clientId && !$("spotify-client-id").value) {
      $("spotify-client-id").value = sp.clientId;
    }
    // The redirect address this app would actually hand Spotify, checked
    // against Spotify's loopback rule BEFORE a sign-in is spent finding out.
    var warn = $("redirect-warn");
    if (sp.redirectCheck && !sp.redirectCheck.ok) {
      warn.textContent = sp.redirectCheck.reason;
      warn.hidden = false;
    } else {
      warn.hidden = true;
    }
    if (sp.clientId) $("spotify-setup").open = false;

    $("cache-note").textContent = state.cacheSize
      ? state.cacheSize + " cached lookups — a re-run reuses these"
      : "no cached lookups yet";

    var blocked = whyNotReady();
    $("go").disabled = !!blocked;
    $("go-hint").textContent = blocked || "";

    updateGoLabel();
    renderPlaylistSection();
    M.renderClear();
  }

  /**
   * What is stopping a migration, or "" — named specifically.
   */
  function whyNotReady() {
    if (!state) return "Loading…";
    var ends = direction.split("-to-");
    for (var i = 0; i < ends.length; i++) {
      var why = signInNeeded(ends[i]);
      if (why) return why;
    }
    return "";
  }

  function signInNeeded(service) {
    if (service === "qobuz" && !state.qobuz.signedIn) return "Sign in to Qobuz first.";
    if (service === "spotify" && !state.spotify.signedIn) return "Sign in to Spotify first.";
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

  // ----------------------------------------------------------- Spotify

  $("spotify-save-id").addEventListener("click", function () {
    api("/api/spotify/client-id", { method: "POST", body: {
      clientId: $("spotify-client-id").value.trim() } })
      .then(function () { toast("Client ID saved."); }).then(refresh).catch(fail);
  });
  $("spotify-start").addEventListener("click", function () {
    if (!state || !state.spotify.clientId) {
      return toast("Save your Spotify Client ID first.");
    }
    openAuth("/api/spotify/oauth/start");
  });
  $("spotify-manual-toggle").addEventListener("click", function () {
    $("spotify-manual").hidden = !$("spotify-manual").hidden;
  });
  $("spotify-paste-go").addEventListener("click", function () {
    api("/api/spotify/paste", { method: "POST", body: { url: $("spotify-paste").value } })
      .then(function () { $("spotify-paste").value = ""; toast("Signed in to Spotify."); })
      .then(refresh).catch(fail);
  });
  $("spotify-signout").addEventListener("click", function () {
    api("/api/spotify/signout", { method: "POST" }).then(refresh).catch(fail);
  });

  $("redirect-uri").addEventListener("click", copyRedirect);
  $("redirect-uri").addEventListener("keydown", function (e) {
    if (e.key === "Enter" || e.key === " ") { e.preventDefault(); copyRedirect(); }
  });
  function copyRedirect() {
    var text = $("redirect-uri").textContent;
    if (!text) return;
    // Clipboard access needs a secure context, which plain http on a LAN
    // address is not. Selecting the text is the honest fallback — telling
    // someone it was copied when it was not is worse than not copying.
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(function () { toast("Copied."); },
        function () { selectNode($("redirect-uri")); toast("Select and copy it."); });
    } else {
      selectNode($("redirect-uri"));
      toast("Select and copy it.");
    }
  }
  function selectNode(node) {
    var r = document.createRange();
    r.selectNodeContents(node);
    var sel = window.getSelection();
    sel.removeAllRanges();
    sel.addRange(r);
  }

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

  // ---------------------------------------------------------- direction

  // Scoped to the migration card: the clear-out card reuses the look of these
  // buttons for its own choice, and must not be caught by this handler.
  var direction = "qobuz-to-spotify";
  Array.prototype.forEach.call(document.querySelectorAll("#setup .dir"), function (b) {
    b.addEventListener("click", function () {
      if (direction === b.getAttribute("data-dir")) return;
      direction = b.getAttribute("data-dir");
      Array.prototype.forEach.call(document.querySelectorAll("#setup .dir"), function (x) {
        x.classList.toggle("on", x === b);
      });
      // The playlist list belongs to the SOURCE service, so switching
      // direction invalidates it entirely. Keeping it would show Spotify
      // playlists while migrating from Qobuz.
      playlists = [];
      chosen.clear();
      render();
    });
  });

  function sourceService() { return direction.split("-to-")[0]; }

  // ---------------------------------------------------------- playlists

  $("k-playlists").addEventListener("change", renderPlaylistSection);
  $("o-dry").addEventListener("change", updateGoLabel);
  $("pl-load").addEventListener("click", function () { loadPlaylists(true); });
  $("pl-all").addEventListener("click", function () {
    playlists.forEach(function (p) { chosen.add(p.id); });
    renderPlaylists();
  });
  $("pl-none").addEventListener("click", function () {
    chosen.clear();
    renderPlaylists();
  });

  function renderPlaylistSection() {
    // Both sign-ins, not just the source's: the picker is part of a migration
    // that cannot start without both, and an empty list box on a page where
    // nothing is signed in reads as "you have no playlists".
    var want = $("k-playlists").checked && !whyNotReady();
    $("playlist-pick").hidden = !want;
    if (!want) return;
    if (!playlists.length) loadPlaylists(false);
    else renderPlaylists();
  }

  var loading = false;
  function loadPlaylists(force) {
    if (loading) return;
    if (playlists.length && !force) { renderPlaylists(); return; }
    loading = true;
    $("pl-count").textContent = "reading…";
    api("/api/playlists?service=" + sourceService()).then(function (j) {
      playlists = j.playlists || [];
      // Everything the user owns starts ticked; playlists they merely follow
      // do not, matching the server's own default.
      chosen = new Set(playlists.filter(function (p) { return p.mine; })
                                .map(function (p) { return p.id; }));
      renderPlaylists();
    }).catch(function (e) {
      $("pl-count").textContent = "";
      fail(e);
    }).then(function () { loading = false; });
  }

  function renderPlaylists() {
    var list = $("pl-list");
    list.textContent = "";
    $("pl-count").textContent = playlists.length
      ? chosen.size + " of " + playlists.length + " selected"
      : "none found";

    playlists.forEach(function (p) {
      var label = document.createElement("label");
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = chosen.has(p.id);
      cb.addEventListener("change", function () {
        if (cb.checked) chosen.add(p.id); else chosen.delete(p.id);
        $("pl-count").textContent = chosen.size + " of " + playlists.length + " selected";
      });
      label.appendChild(cb);

      var name = document.createElement("span");
      // textContent, never innerHTML: a playlist name is somebody else's text
      // and can contain anything at all.
      name.textContent = p.name || "(untitled)";
      label.appendChild(name);

      if (!p.mine) {
        var tag = document.createElement("span");
        tag.className = "tag";
        tag.textContent = "followed";
        label.appendChild(tag);
      }

      var n = document.createElement("span");
      n.className = "n";
      n.textContent = p.trackCount + (p.trackCount === 1 ? " track" : " tracks");
      label.appendChild(n);

      list.appendChild(label);
    });
  }

  // --------------------------------------------------------------- run

  function updateGoLabel() {
    $("go").textContent = $("o-dry").checked ? "Preview" : "Migrate";
  }

  $("go").addEventListener("click", function () {
    var body = {
      direction: direction,
      playlists: $("k-playlists").checked
        ? (playlists.length ? Array.from(chosen) : true)
        : false,
      albums: $("k-albums").checked,
      artists: $("k-artists").checked,
      tracks: $("k-tracks").checked,
      dryRun: $("o-dry").checked,
      strict: $("o-strict").checked,
      corroborate: $("o-corroborate").checked,
      includeOthersPlaylists: $("o-others").checked,
      onExisting: $("o-existing").value,
      playlistSuffix: $("o-suffix").value
    };
    if (!body.playlists && !body.albums && !body.artists && !body.tracks) {
      return toast("Pick at least one thing to move.");
    }
    if (Array.isArray(body.playlists) && !body.playlists.length &&
        !body.albums && !body.artists && !body.tracks) {
      return toast("No playlists are selected.");
    }
    $("go").disabled = true;
    api("/api/migrate", { method: "POST", body: body })
      .then(function (j) { M.watch(j.jobId); })
      .catch(function (e) { $("go").disabled = false; fail(e); });
  });


  // What the other two scripts use. Shared through one object rather than
  // globals, so the lint's no-undef still catches a name that does not exist.
  M.$ = $;
  M.api = api;
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
