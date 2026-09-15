"use strict";
/*
 * app.js — the whole front-end.
 *
 * THE PAGE IS THE AUTHORITY ON EVERY API FIELD NAME, not the server. This
 * directory is bundled unchanged into the Android APK, where a Kotlin
 * reimplementation answers the same /api/ routes — so if this file and
 * index.js ever disagree about a field name, the APK is what breaks, and
 * nothing in the Docker build would notice. Anything added here has to be
 * added on both sides.
 *
 * Vanilla, no build step, and it must stay that way for the same reason.
 */

(function () {
  var state = null;
  var pin = "";
  var playlists = [];
  var chosen = new Set();
  var pollTimer = null;
  var currentJob = null;

  // ------------------------------------------------------------ plumbing

  function $(id) { return document.getElementById(id); }

  function api(path, opts) {
    var o = opts || {};
    var headers = o.headers || {};
    if (pin) headers["x-migrate-pin"] = pin;
    if (o.body !== undefined) headers["Content-Type"] = "application/json";
    return fetch(path, {
      method: o.method || "GET",
      headers: headers,
      body: o.body === undefined ? undefined : JSON.stringify(o.body)
    }).then(function (res) {
      if (res.status === 401) {
        return res.json().catch(function () { return {}; }).then(function (j) {
          if (j && j.needPin) { showPin(); throw new Error("PIN required"); }
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

  // ----------------------------------------------------------- the PIN

  function showPin() {
    $("main").hidden = true;
    $("pin-gate").hidden = false;
    $("pin-input").focus();
  }

  $("pin-go").addEventListener("click", function () {
    pin = $("pin-input").value.trim();
    // Stored so a reload does not ask again. It is a shared PIN for a tool on
    // somebody's own network, not a credential — and a PIN retyped on every
    // refresh is a PIN people turn off.
    try { localStorage.setItem("migrate-pin", pin); } catch (e) { /* private mode */ }
    $("pin-gate").hidden = true;
    refresh().then(function () { $("main").hidden = false; })
             .catch(function () { showPin(); });
  });
  $("pin-input").addEventListener("keydown", function (e) {
    if (e.key === "Enter") $("pin-go").click();
  });

  // --------------------------------------------------------- the state

  function refresh() {
    return api("/api/state").then(function (s) {
      state = s;
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

    renderRoon();

    $("cache-note").textContent = state.cacheSize
      ? state.cacheSize + " cached lookups — a re-run reuses these"
      : "no cached lookups yet";

    var blocked = whyNotReady();
    $("go").disabled = !!blocked;
    $("go-hint").textContent = blocked || "";

    updateGoLabel();
    renderKinds();
    renderPlaylistSection();
  }

  /**
   * What is stopping a run, or "" — named specifically.
   *
   * Only the two ends actually involved. "Sign in to both services first" for
   * a Roon migration would be asking for a sign-in the run does not use.
   */
  function whyNotReady() {
    if (!state) return "Loading…";
    var ends = direction.split("-to-");
    for (var i = 0; i < ends.length; i++) {
      var end = ends[i];
      if (end === "qobuz" && !state.qobuz.signedIn) return "Sign in to Qobuz first.";
      if (end === "spotify" && !state.spotify.signedIn) return "Sign in to Spotify first.";
      if (end === "roon") {
        var r = state.roon || {};
        if (!r.paired) return "Pair with your Roon Core first.";
        if (!r.albums) return "Scan your Roon library first.";
        if (r.scanning) return "Wait for the library scan to finish.";
      }
    }
    return "";
  }

  // -------------------------------------------------------------- Roon

  function renderRoon() {
    var r = (state && state.roon) || { stage: "idle" };
    var paired = !!r.paired;
    $("roon-dot").className = "dot" + (paired ? " on" :
      (r.stage === "error" ? " bad" : (r.stage === "idle" ? "" : " busy")));
    $("roon-who").textContent = paired
      ? (r.coreName || "paired")
      : (r.stage === "idle" ? "not connected" : r.stage.replace(/-/g, " "));
    $("roon-body").hidden = paired;
    $("roon-library").hidden = !paired;
    $("roon-forget").hidden = !(paired || r.stage === "error");
    $("roon-detail").textContent = paired ? "" : (r.detail || "");
    $("roon-detail-paired").textContent = paired ? (r.detail || "") : "";

    var note = $("roon-scan-note");
    var p = r.progress;
    $("roon-bar").hidden = !r.scanning;
    $("roon-scan-cancel").hidden = !r.scanning;
    $("roon-scan").disabled = !!r.scanning;
    if (r.scanning && p) {
      var pct = p.total ? Math.min(100, Math.round((p.done / p.total) * 100)) : 0;
      $("roon-fill").style.width = pct + "%";
      note.textContent = "Scanning — " + p.done + (p.total ? " of " + p.total : "") +
        " albums" + (p.duplicates ? ", " + p.duplicates + " duplicates" : "");
    } else if (r.scanError) {
      // Said out loud. "Nothing scanned yet" after a failed scan reads as an
      // empty library rather than as something that went wrong.
      note.textContent = "The last scan stopped: " + r.scanError;
    } else if (r.albums) {
      note.textContent = r.albums + " albums scanned" +
        (r.scan && r.scan.duplicates
          ? " (" + r.scan.duplicates + " duplicate " +
            (r.scan.duplicates === 1 ? "copy" : "copies") + " of records you own twice)"
          : "") +
        (r.scan && r.scan.done === false ? " — the scan did not finish" : "");
    } else {
      note.textContent = paired ? "Nothing scanned yet." : "";
    }

    var csv = $("roon-csv");
    csv.hidden = !r.albums;
    csv.href = "/api/roon/library.csv" + (pin ? "?pin=" + encodeURIComponent(pin) : "");

    // Poll while anything is in flight, and stop when nothing is. A Roon pair
    // can sit on "waiting to be enabled" for minutes while the user walks to
    // the Roon window, so the page has to keep looking.
    var busy = r.scanning || (r.stage !== "idle" && r.stage !== "paired" &&
                              r.stage !== "error");
    if (busy && !roonTimer) roonTimer = setInterval(function () {
      refresh().catch(function () {});
    }, 2000);
    if (!busy && roonTimer) { clearInterval(roonTimer); roonTimer = null; }
  }

  var roonTimer = null;

  $("roon-connect").addEventListener("click", function () {
    $("roon-connect").disabled = true;
    api("/api/roon/connect", { method: "POST", body: {} })
      .then(function () { toast("Looking for a Roon Core…"); })
      .then(refresh)
      .catch(fail)
      .then(function () { $("roon-connect").disabled = false; });
  });
  $("roon-connect-manual").addEventListener("click", function () {
    var host = $("roon-host").value.trim();
    if (!host) return toast("Type the Core's address.");
    api("/api/roon/connect", { method: "POST", body: {
      host: host, port: Number($("roon-port").value) || 9330 } })
      .then(function () { toast("Connecting…"); }).then(refresh).catch(fail);
  });
  $("roon-forget").addEventListener("click", function () {
    api("/api/roon/forget", { method: "POST" })
      .then(function () { toast("Looking again…"); }).then(refresh).catch(fail);
  });
  $("roon-scan").addEventListener("click", function () {
    var r = (state && state.roon) || {};
    var resume = !!(r.scan && r.scan.done === false && r.scan.offset > 0);
    api("/api/roon/scan", { method: "POST", body: { resume: resume } })
      .then(function () { toast(resume ? "Carrying on from where it stopped…" : "Scanning…"); })
      .then(refresh).catch(fail);
  });
  $("roon-scan-cancel").addEventListener("click", function () {
    api("/api/roon/scan/cancel", { method: "POST" })
      .then(function () { toast("Stopping the scan…"); }).then(refresh).catch(fail);
  });

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
    var full = pin ? url + (url.indexOf("?") < 0 ? "?" : "&") + "pin=" + encodeURIComponent(pin)
                   : url;
    var win = null;
    try { win = window.open(full, "musicd-auth", "width=520,height=720"); } catch (e) { win = null; }
    if (!win) { window.location.href = full; return; }
    toast("Finish signing in in the other window.");
  }

  // The page the redirect lands on says so, rather than this polling for it.
  window.addEventListener("message", function (e) {
    if (e && e.data === "musicd-migrate-auth") refresh().catch(fail);
  });
  // A tab that was navigated away and came back: re-read on focus.
  window.addEventListener("focus", function () {
    if (!currentJob) refresh().catch(function () {});
  });

  // ---------------------------------------------------------- direction

  var direction = "qobuz-to-spotify";
  Array.prototype.forEach.call(document.querySelectorAll(".dir"), function (b) {
    b.addEventListener("click", function () {
      if (direction === b.getAttribute("data-dir")) return;
      direction = b.getAttribute("data-dir");
      Array.prototype.forEach.call(document.querySelectorAll(".dir"), function (x) {
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

  /**
   * Turn off the kinds the source cannot offer, and say why.
   *
   * Not merely disabled: the reason is printed. A greyed-out box with no
   * explanation reads as a bug, and the reason here is the whole argument for
   * why Roon albums are safe to migrate and Roon tracks are not.
   */
  function renderKinds() {
    var fromRoon = sourceService() === "roon";
    var note = $("kinds-note");
    ["k-playlists", "k-tracks"].forEach(function (id) {
      var cb = $(id);
      cb.disabled = fromRoon;
      if (fromRoon) cb.checked = false;
      cb.parentNode.classList.toggle("off", fromRoon);
    });
    note.hidden = !fromRoon;
    note.textContent = fromRoon
      ? "Playlists and favourite tracks are not migrated from Roon: a Roon browse row " +
        "carries no track length, and a title and artist alone are what a cover, a " +
        "re-recording and a live take all satisfy. Albums and artists are."
      : "";
  }

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
      .then(function (j) { watch(j.jobId); })
      .catch(function (e) { $("go").disabled = false; fail(e); });
  });

  $("cancel").addEventListener("click", function () {
    if (!currentJob) return;
    api("/api/job/" + currentJob + "/cancel", { method: "POST" })
      .then(function () { toast("Stopping…"); }).catch(fail);
  });

  function watch(jobId) {
    currentJob = jobId;
    $("results").hidden = true;
    $("progress").hidden = false;
    $("prog-fill").style.width = "0%";
    $("prog-label").textContent = "Starting…";
    $("prog-counts").textContent = "";
    clearInterval(pollTimer);
    pollTimer = setInterval(poll, 900);
    poll();
  }

  function poll() {
    if (!currentJob) return;
    api("/api/job/" + currentJob).then(function (job) {
      var p = job.progress || {};
      $("prog-title").textContent = job.dryRun ? "Previewing" : "Migrating";
      $("prog-label").textContent = p.label || "";
      var pct = p.total ? Math.round(100 * (p.done || 0) / p.total) : 0;
      $("prog-fill").style.width = pct + "%";
      renderCounts($("prog-counts"), p.counts || {}, p);

      if (!job.running && job.status !== "running") {
        clearInterval(pollTimer);
        pollTimer = null;
        finish(job);
      }
    }).catch(function (e) {
      clearInterval(pollTimer);
      pollTimer = null;
      currentJob = null;
      $("progress").hidden = true;
      $("go").disabled = false;
      fail(e);
    });
  }

  function finish(job) {
    var id = currentJob;
    currentJob = null;
    $("progress").hidden = true;
    $("go").disabled = false;
    $("results").hidden = false;
    $("res-title").textContent =
      job.status === "done" ? (job.dryRun ? "Preview — nothing was written" : "Finished")
      : job.status === "cancelled" ? "Stopped"
      : job.status === "interrupted" ? "Interrupted"
      : "Failed";
    renderCounts($("res-counts"), job.counts || {}, null);
    if (job.error) toast(job.error);
    $("res-csv").href = "/api/job/" + id + "/report.csv" +
      (pin ? "?pin=" + encodeURIComponent(pin) : "");
    $("res-unmatched").onclick = function () { showItems(id, "unmatched"); };
    $("res-items").hidden = true;
    loadHistory();
    refresh().catch(function () {});
  }

  $("res-close").addEventListener("click", function () { $("results").hidden = true; });

  var LABELS = {
    matched: ["moved", "good"],
    already: ["already there", ""],
    unmatched: ["not found", "warn"],
    skipped: ["skipped", ""],
    failed: ["failed", "bad"],
    written: ["written", ""]
  };

  function renderCounts(el, counts, progress) {
    el.textContent = "";
    Object.keys(LABELS).forEach(function (k) {
      var n = counts[k];
      if (!n) return;
      var span = document.createElement("span");
      var cls = LABELS[k][1];
      span.className = "pill" + (cls ? " " + cls : "");
      var b = document.createElement("b");
      b.textContent = String(n);
      span.appendChild(b);
      span.appendChild(document.createTextNode(" " + LABELS[k][0]));
      el.appendChild(span);
    });
    if (progress && progress.searches) {
      var s = document.createElement("span");
      s.className = "pill";
      s.textContent = progress.searches + " lookups, " +
        (progress.cacheHits || 0) + " from cache";
      el.appendChild(s);
    }
  }

  function showItems(jobId, status) {
    api("/api/job/" + jobId + "/items?status=" + encodeURIComponent(status) + "&limit=500")
      .then(function (j) {
        var box = $("res-items");
        box.textContent = "";
        box.hidden = false;
        if (!j.items.length) {
          var p = document.createElement("div");
          p.className = "item";
          p.textContent = "Everything was matched.";
          box.appendChild(p);
          return;
        }
        j.items.forEach(function (it) {
          var row = document.createElement("div");
          row.className = "item";
          var what = document.createElement("div");
          what.className = "what";
          what.textContent = it.sourceLabel;
          row.appendChild(what);
          if (it.container) {
            var where = document.createElement("div");
            where.className = "where";
            where.textContent = "in " + it.container;
            row.appendChild(where);
          }
          if (it.note) {
            var why = document.createElement("div");
            why.className = "why";
            why.textContent = it.note;
            row.appendChild(why);
          }
          box.appendChild(row);
        });
        if (j.total > j.items.length) {
          var more = document.createElement("div");
          more.className = "item why";
          more.textContent = "…and " + (j.total - j.items.length) +
            " more — download the CSV for all of them.";
          box.appendChild(more);
        }
      }).catch(fail);
  }

  // ----------------------------------------------------------- history

  function loadHistory() {
    api("/api/jobs").then(function (j) {
      var box = $("history-list");
      box.textContent = "";
      if (!j.jobs.length) {
        var none = document.createElement("p");
        none.className = "hint";
        none.textContent = "Nothing has been migrated yet.";
        box.appendChild(none);
        return;
      }
      j.jobs.forEach(function (job) {
        var row = document.createElement("div");
        row.className = "hrow" + (job.status === "failed" ? " failed" : "");

        var d = document.createElement("span");
        d.className = "dirlabel";
        d.textContent = job.direction === "qobuz-to-spotify" ? "Qobuz → Spotify"
                                                             : "Spotify → Qobuz";
        row.appendChild(d);

        if (job.dryRun) {
          var tag = document.createElement("span");
          tag.className = "tag";
          tag.textContent = "preview";
          row.appendChild(tag);
        }

        var when = document.createElement("span");
        when.className = "when";
        when.textContent = new Date(job.created).toLocaleString() + " · " + job.status;
        row.appendChild(when);

        var counts = document.createElement("span");
        counts.className = "when spacer";
        var c = job.counts || {};
        counts.textContent = [
          c.matched ? c.matched + " moved" : "",
          c.unmatched ? c.unmatched + " not found" : ""
        ].filter(Boolean).join(", ");
        row.appendChild(counts);

        var view = document.createElement("button");
        view.className = "ghost";
        view.textContent = "Report";
        view.addEventListener("click", function () {
          $("results").hidden = false;
          $("res-title").textContent = "Report";
          renderCounts($("res-counts"), job.counts || {}, null);
          $("res-csv").href = "/api/job/" + job.id + "/report.csv" +
            (pin ? "?pin=" + encodeURIComponent(pin) : "");
          $("res-unmatched").onclick = function () { showItems(job.id, "unmatched"); };
          showItems(job.id, "unmatched");
          $("results").scrollIntoView({ behavior: "smooth", block: "start" });
        });
        row.appendChild(view);

        box.appendChild(row);
      });
    }).catch(function () { /* history is a nicety; never block the page on it */ });
  }

  $("cache-clear").addEventListener("click", function () {
    api("/api/cache/clear", { method: "POST" })
      .then(function () { toast("Cached lookups forgotten."); })
      .then(refresh).catch(fail);
  });

  // ------------------------------------------------------------- start

  try { pin = localStorage.getItem("migrate-pin") || ""; } catch (e) { pin = ""; }

  refresh().then(function () {
    $("main").hidden = false;
    loadHistory();
    // A job left running by a reload: pick it back up rather than showing an
    // idle page while the server is still working.
    if (state.job && state.job.running) watch(state.job.id);
  }).catch(function (e) {
    if (!/PIN/.test(e.message || "")) { $("main").hidden = false; fail(e); }
  });
})();
