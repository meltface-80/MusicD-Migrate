"use strict";
/*
 * run.js — a run in flight and after it: progress, the results panel, the
 * report list and the history. Loaded after app.js; see the note there.
 */

(function (M) {
  var $ = M.$, api = M.api, toast = M.toast, fail = M.fail;

  $("cancel").addEventListener("click", function () {
    if (!M.currentJob) return;
    api("/api/job/" + M.currentJob + "/cancel", { method: "POST" })
      .then(function () { toast("Stopping…"); }).catch(fail);
  });

  var pollTimer = null;

  function watch(jobId) {
    M.currentJob = jobId;
    // Nothing else may start while this runs; the server refuses it anyway,
    // and a button that does nothing when pressed reads as a bug.
    $("go").disabled = true;
    M.renderClear();
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
    if (!M.currentJob) return;
    api("/api/job/" + M.currentJob).then(function (job) {
      var p = job.progress || {};
      $("prog-title").textContent = isClear(job)
        ? (job.dryRun ? "Listing what would be removed" : "Removing")
        : (job.dryRun ? "Previewing the import" : "Importing");
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
      M.currentJob = null;
      $("progress").hidden = true;
      $("go").disabled = !!M.whyNotReady();
      M.renderClear();
      fail(e);
    });
  }

  function finish(job) {
    var id = M.currentJob;
    M.currentJob = null;
    $("progress").hidden = true;
    $("go").disabled = !!M.whyNotReady();
    M.notePreview(job, id);
    showReport(job, id,
      job.status === "done"
        ? (isClear(job)
            ? (job.dryRun ? "Preview — nothing was removed" : "Removed")
            : (job.dryRun ? "Preview — nothing was written" : "Finished"))
      : job.status === "cancelled" ? "Stopped"
      : job.status === "interrupted" ? "Interrupted"
      : "Failed");
    $("res-items").hidden = true;
    offerImport(job);
    loadHistory();
    M.refresh().catch(function () {});
  }

  /**
   * After a finished import PREVIEW, the button that does it for real — for
   * the albums the preview matched, from the same file, with the same
   * options. Only from finish(): a preview reopened from the history may be
   * of a different file than the one now loaded.
   */
  function offerImport(job) {
    var btn = $("res-import");
    var n = (job.counts || {}).matched || 0;
    var ready = !isClear(job) && job.dryRun && job.status === "done" && n > 0 &&
      M.state && M.state["import"];
    btn.hidden = !ready;
    if (!ready) return;
    btn.textContent = "Import " + n + (n === 1 ? " album" : " albums") + " into Qobuz";
    btn.onclick = function () { btn.hidden = true; M.startImport(false); };
  }

  function isClear(job) { return /^clear-/.test((job && job.direction) || ""); }

  /**
   * The results panel, for a run that just finished or one reopened from the
   * history — the same panel either way, so the two cannot disagree.
   *
   * The list behind the button depends on the run. A migration's deliverable
   * is what did NOT move; a clear-out preview's is what WOULD go; a real
   * clear-out's is what could NOT be removed.
   */
  function showReport(job, id, title) {
    $("results").hidden = false;
    $("res-import").hidden = true;
    $("res-title").textContent = title;
    renderCounts($("res-counts"), job.counts || {}, null);
    // On the page, not in a toast: a run that stopped says why in one long
    // sentence, and five seconds of it is not enough to read, let alone act
    // on. It stays until the panel is closed.
    var err = $("res-error");
    err.textContent = job.error || "";
    err.hidden = !job.error;
    $("res-csv").href = "/api/job/" + id + "/report.csv";
    var list = !isClear(job) ? ["unmatched", "What was not found", "Everything was matched."]
      : job.dryRun ? ["listed", "What would be removed", "Nothing to remove."]
      : ["failed", "What could not be removed", "Everything listed was removed."];
    $("res-unmatched").textContent = list[1];
    $("res-unmatched").onclick = function () { showItems(id, list[0], list[2]); };
  }

  $("res-close").addEventListener("click", function () { $("results").hidden = true; });

  var LABELS = {
    matched: ["matched", "good"],
    already: ["already there", ""],
    unmatched: ["not found", "warn"],
    skipped: ["skipped", ""],
    failed: ["failed", "bad"],
    written: ["written", ""],
    listed: ["to remove", "warn"],
    removed: ["removed", ""]
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
    // A run being held by the service stays visible after the label has moved
    // on to the next album. Without this the only sign was a counter that
    // crawled, which is what "seems unresponsive" looked like.
    if (progress && progress.rateLimits) {
      var w = document.createElement("span");
      w.className = "pill";
      w.textContent = progress.rateLimits +
        (progress.rateLimits === 1 ? " rate-limit wait" : " rate-limit waits");
      el.appendChild(w);
    }
  }

  function showItems(jobId, status, emptyText) {
    api("/api/job/" + jobId + "/items?status=" + encodeURIComponent(status) + "&limit=500")
      .then(function (j) {
        var box = $("res-items");
        box.textContent = "";
        box.hidden = false;
        if (!j.items.length) {
          var p = document.createElement("div");
          p.className = "item";
          p.textContent = emptyText || "Everything was matched.";
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

  /**
   * A run's kind, as the history shows it: "csv-to-qobuz" as "File → Qobuz",
   * "clear-qobuz" as "Clear Qobuz". Runs from before 0.5.0 keep their old
   * labels ("Qobuz → Spotify"), derived from the id the same way.
   */
  function directionLabel(d) {
    var id = String(d || "");
    if (/^clear-/.test(id)) {
      var svc = id.slice(6);
      return "Clear " + svc.charAt(0).toUpperCase() + svc.slice(1);
    }
    var parts = id.split("-to-");
    if (parts.length !== 2) return id;
    return parts.map(function (p) {
      return p === "csv" ? "File" : p.charAt(0).toUpperCase() + p.slice(1);
    }).join(" → ");
  }


  function loadHistory() {
    api("/api/jobs").then(function (j) {
      var box = $("history-list");
      box.textContent = "";
      if (!j.jobs.length) {
        var none = document.createElement("p");
        none.className = "hint";
        none.textContent = "Nothing has run yet.";
        box.appendChild(none);
        return;
      }
      j.jobs.forEach(function (job) {
        var row = document.createElement("div");
        row.className = "hrow" + (job.status === "failed" ? " failed" : "");

        var d = document.createElement("span");
        d.className = "dirlabel";
        d.textContent = directionLabel(job.direction);
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
          c.matched ? c.matched + " matched" : "",
          c.unmatched ? c.unmatched + " not found" : "",
          c.listed ? c.listed + " to remove" : "",
          c.removed ? c.removed + " removed" : "",
          c.failed ? c.failed + " failed" : ""
        ].filter(Boolean).join(", ");
        row.appendChild(counts);

        var view = document.createElement("button");
        view.className = "ghost";
        view.textContent = "Report";
        view.addEventListener("click", function () {
          // Same panel as finish(): a run that stopped says why. Without that,
          // reopening a failed run from the history once showed the counts
          // and no reason at all — or the reason from a different run.
          showReport(job, job.id, "Report");
          $("res-unmatched").click();
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
      .then(M.refresh).catch(fail);
  });

  M.watch = watch;
  M.isClear = isClear;
  M.loadHistory = loadHistory;
})(window.Mandarin);
