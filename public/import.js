"use strict";
/*
 * import.js — the library-file card: choose a CSV, see what was found in it,
 * import its albums into Qobuz favourites. Loaded after app.js; see the note
 * there about window.Mandarin.
 *
 * The file is read here and its text sent as the request body (see readFile
 * for why it is not streamed); the server parses it and answers with a
 * summary.
 */

(function (M) {
  var $ = M.$, api = M.api, toast = M.toast, fail = M.fail;

  function plural(n, one, many) { return n + " " + (n === 1 ? one : (many || one + "s")); }

  /** The summary of the chosen file, from /api/state, or nothing. */
  function renderImport() {
    var s = M.state;
    if (!s) return;
    var f = s["import"];
    $("file-forget").hidden = !f;
    $("file-summary").hidden = !f;
    $("file-note").textContent = f ? f.fileName : "No file chosen.";

    var blocked = M.whyNotReady();
    $("go").disabled = !!blocked || !!M.currentJob;
    $("go-hint").textContent = blocked || "";
    updateGoLabel();
    if (!f) return;

    var counts = $("file-counts");
    counts.textContent = "";
    // [count, one, many, class] — a count of zero is left out, except albums.
    [[f.albums, "album", "albums", ""],
     [f.shape === "tracks" ? f.tracks : 0, "track", "tracks", ""],
     [f.withBarcode, "with a barcode", "with a barcode", "good"],
     [f.withListing, "with a track listing", "with a track listing", ""],
     [f.unmatchable, "cannot be matched", "cannot be matched", "warn"]
    ].forEach(function (c, i) {
      if (!c[0] && i > 0) return;
      var span = document.createElement("span");
      span.className = "pill" + (c[3] ? " " + c[3] : "");
      var b = document.createElement("b");
      b.textContent = String(c[0]);
      span.appendChild(b);
      span.appendChild(document.createTextNode(" " + (c[0] === 1 ? c[1] : c[2])));
      counts.appendChild(span);
    });

    var warnings = $("file-warnings");
    warnings.textContent = "";
    (f.warnings || []).forEach(function (w) {
      var li = document.createElement("li");
      li.textContent = w;
      warnings.appendChild(li);
    });

    $("file-columns").textContent = "Read as " +
      (f.shape === "tracks" ? "one row per track" : "one row per album") +
      " from " + plural(f.rows, "row") + ". Columns used: " + (f.columns || []).join(", ") +
      ((f.ignoredColumns || []).length ? ". Ignored: " + f.ignoredColumns.join(", ") : "") + ".";
  }

  function updateGoLabel() {
    $("go").textContent = $("o-dry").checked ? "Preview" : "Import";
  }
  $("o-dry").addEventListener("change", updateGoLabel);

  /**
   * Read the chosen file HERE, then send its text.
   *
   * It used to be handed to fetch() as the File itself, for the WebView to
   * stream from the picker's content:// URI while uploading. Reading first
   * does two things: the body has a known length (a stream of unknown length
   * goes CHUNKED, which the app's server once could not read — and the page
   * then saw only "Failed to fetch"), and a file the phone will not let us
   * read fails HERE, with its own message, instead of looking like a dead
   * server. FileReader rather than file.text(): an older WebView lacks it.
   */
  function readFile(file) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { resolve(String(r.result || "")); };
      r.onerror = function () {
        reject(new Error("Couldn't read " + file.name + " from this phone (" +
          ((r.error && r.error.name) || "unknown error") + "). Try choosing it again, or " +
          "copy it into Downloads first."));
      };
      r.readAsText(file);
    });
  }

  /** POST the file's text, once more after a pause if nothing answered. */
  function upload(name, text, retried) {
    return fetch("/api/import/file?name=" + encodeURIComponent(name), {
      method: "POST",
      headers: { "Content-Type": "text/csv; charset=utf-8" },
      body: text
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (j) {
        if (!res.ok) throw new Error(j.error || ("HTTP " + res.status));
        return j;
      });
    }, function (e) {
      // No answer at all. Coming back from the file picker the app may
      // still be waking up: one retry, then say so in words.
      if (retried) throw M.unreachable(e);
      return new Promise(function (r) { setTimeout(r, 1500); })
        .then(function () { return upload(name, text, true); });
    });
  }

  $("file-input").addEventListener("change", function () {
    var file = this.files && this.files[0];
    if (!file) return;
    var input = this;
    $("file-note").textContent = "Reading " + file.name + "…";
    readFile(file).then(function (text) {
      // Cleared only now that it is read, so choosing the same file again
      // still fires a change.
      input.value = "";
      $("file-note").textContent = "Sending " + file.name + "…";
      return upload(file.name, text, false);
    }).then(function (j) {
      toast(plural(j.albums, "album") + " found in " + j.fileName + ".");
      return M.refresh();
    }).catch(function (e) {
      input.value = "";
      $("file-note").textContent = "No file chosen.";
      fail(e);
    });
  });

  $("file-forget").addEventListener("click", function () {
    api("/api/import/forget", { method: "POST" }).then(M.refresh).catch(fail);
  });

  /** Start the import: a preview when dryRun, the real thing otherwise.
   *  Also what the results panel's "Import N albums" button calls. */
  function startImport(dryRun) {
    var body = {
      direction: "csv-to-qobuz",
      albums: true,
      dryRun: dryRun,
      strict: $("o-strict").checked,
      corroborate: $("o-corroborate").checked
    };
    $("go").disabled = true;
    api("/api/migrate", { method: "POST", body: body })
      .then(function (j) { M.watch(j.jobId); })
      .catch(function (e) { $("go").disabled = false; fail(e); });
  }

  $("go").addEventListener("click", function () { startImport($("o-dry").checked); });

  M.startImport = startImport;
  M.renderImport = renderImport;
})(window.Mandarin);
