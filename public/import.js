"use strict";
/*
 * import.js — the library-file card: choose a CSV, see what was found in it,
 * import its albums into Qobuz favourites. Loaded after app.js; see the note
 * there about window.Mandarin.
 *
 * The file goes to the server AS IT IS, as the request body: a track-per-row
 * export of a big library is tens of megabytes, and reading it into a
 * JavaScript string first would hold it twice in a phone's WebView for no
 * reason. The server parses it and answers with a summary.
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
   * Send the chosen file. fetch() rather than api(): api() sends JSON, and
   * this is the file's own bytes, so the server can stream it into the
   * parser.
   */
  $("file-input").addEventListener("change", function () {
    var file = this.files && this.files[0];
    this.value = ""; // so choosing the same file again still fires
    if (!file) return;
    $("file-note").textContent = "Reading " + file.name + "…";
    fetch("/api/import/file?name=" + encodeURIComponent(file.name), {
      method: "POST",
      headers: { "Content-Type": "text/csv" },
      body: file
    }).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (j) {
        if (!res.ok) throw new Error(j.error || ("HTTP " + res.status));
        return j;
      });
    }).then(function (j) {
      toast(plural(j.albums, "album") + " found in " + j.fileName + ".");
      return M.refresh();
    }).catch(function (e) {
      $("file-note").textContent = "No file chosen.";
      fail(e);
    });
  });

  $("file-forget").addEventListener("click", function () {
    api("/api/import/forget", { method: "POST" }).then(M.refresh).catch(fail);
  });

  $("go").addEventListener("click", function () {
    var body = {
      direction: "csv-to-qobuz",
      albums: true,
      dryRun: $("o-dry").checked,
      strict: $("o-strict").checked,
      corroborate: $("o-corroborate").checked
    };
    $("go").disabled = true;
    api("/api/migrate", { method: "POST", body: body })
      .then(function (j) { M.watch(j.jobId); })
      .catch(function (e) { $("go").disabled = false; fail(e); });
  });

  M.renderImport = renderImport;
})(window.Mandarin);
