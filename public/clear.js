"use strict";
/*
 * clear.js — the clear-out card. Loaded after app.js; see the note there.
 */

(function (M) {
  var $ = M.$, api = M.api, fail = M.fail;

  // ---------------------------------------------------------- clearing
  //
  // The one irreversible thing on the page, so the order is fixed: tick what
  // to remove, PREVIEW it (a dry run that lists every item and removes
  // nothing), and only then does the confirmation appear — for exactly the
  // selection that was previewed. Change anything and it has to be previewed
  // again. The real run needs the service's name typed, and the server checks
  // that name itself rather than trusting this page.

  var clearService = "qobuz";
  var clearPreview = null;      // { key, jobId, count } of the last finished preview
  var pendingClearKey = null;   // the selection a running preview was started for

  Array.prototype.forEach.call(document.querySelectorAll(".clr"), function (b) {
    b.addEventListener("click", function () {
      clearService = b.getAttribute("data-service");
      Array.prototype.forEach.call(document.querySelectorAll(".clr"), function (x) {
        x.classList.toggle("on", x === b);
      });
      renderClear();
    });
  });
  ["c-tracks", "c-albums", "c-artists", "c-playlists", "c-others"].forEach(function (id) {
    $(id).addEventListener("change", renderClear);
  });
  $("clear-type").addEventListener("input", renderClear);

  function serviceLabel(svc) { return svc.charAt(0).toUpperCase() + svc.slice(1); }

  /** Everything the server is sent. `confirm` is empty for a preview. */
  function clearRequest(dryRun) {
    var clearBody = {
      service: clearService,
      tracks: $("c-tracks").checked,
      albums: $("c-albums").checked,
      artists: $("c-artists").checked,
      playlists: $("c-playlists").checked,
      includeOthersPlaylists: $("c-others").checked,
      dryRun: dryRun,
      confirm: dryRun ? "" : $("clear-type").value.trim()
    };
    return clearBody;
  }

  /** What a preview has to have been OF for its confirmation to stand. */
  function clearKey() {
    var b = clearRequest(true);
    return [b.service, b.tracks, b.albums, b.artists, b.playlists,
            b.includeOthersPlaylists].join("|");
  }

  function renderClear() {
    if (!M.state) return;
    var b = clearRequest(true);
    var any = b.tracks || b.albums || b.artists || b.playlists;
    var why = M.signInNeeded(clearService) || (any ? "" : "Tick what to remove.");
    $("clear-preview").disabled = !!why || !!M.currentJob;
    $("clear-hint").textContent = why;

    var ready = !why && clearPreview && clearPreview.key === clearKey();
    $("clear-confirm").hidden = !ready;
    if (!ready) {
      $("clear-type").value = "";
      $("clear-go").disabled = true;
      return;
    }
    var label = serviceLabel(clearService);
    var n = clearPreview.count;
    $("clear-summary").textContent = n
      ? n + (n === 1 ? " item" : " items") + " will be removed from " + label +
        " — the list is in the report below. To go ahead, type " + label + "."
      : "The preview found nothing to remove.";
    $("clear-type").placeholder = "Type " + label + " to confirm";
    $("clear-preview-csv").href = "/api/job/" + clearPreview.jobId + "/report.csv";
    $("clear-go").disabled = !n || !!M.currentJob ||
      $("clear-type").value.trim().toLowerCase() !== clearService;
  }

  /** A finished run, as far as the clear-out's confirmation is concerned. */
  function notePreview(job, id) {
    if (!M.isClear(job)) return;
    if (job.dryRun && job.status === "done" && pendingClearKey) {
      clearPreview = { key: pendingClearKey, jobId: id, count: (job.counts || {}).listed || 0 };
    } else if (!job.dryRun) {
      // Spent. Anything further needs a fresh look at what is left.
      clearPreview = null;
    }
    pendingClearKey = null;
    renderClear();
  }

  function startClear(dryRun) {
    var body = clearRequest(dryRun);
    pendingClearKey = dryRun ? clearKey() : null;
    if (dryRun) clearPreview = null;
    $("clear-preview").disabled = true;
    $("clear-go").disabled = true;
    api("/api/clear", { method: "POST", body: body })
      .then(function (j) { M.watch(j.jobId); })
      .catch(function (e) { pendingClearKey = null; renderClear(); fail(e); });
  }

  $("clear-preview").addEventListener("click", function () { startClear(true); });
  $("clear-go").addEventListener("click", function () { startClear(false); });

  M.renderClear = renderClear;
  M.notePreview = notePreview;
})(window.Mandarin);
