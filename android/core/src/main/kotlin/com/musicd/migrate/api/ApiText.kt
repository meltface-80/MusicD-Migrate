package com.musicd.migrate.api

import com.musicd.migrate.*
import com.musicd.migrate.http.Response

/*
 * ApiText.kt — the JSON, CSV and HTML the routes answer with. The page reads
 * every field here by name, so a rename is a broken page: ApiTest asserts on
 * these shapes. Split out of MigrateApi.kt.
 */

internal fun ok() = Response.json(200, """{"ok":true}""")

internal fun obj(vararg pairs: Pair<String, String>): String =
    pairs.joinToString(",", "{", "}") { jsonQuote(it.first) + ":" + jsonQuote(it.second) }

internal fun mimeFor(path: String): String = when (path.substringAfterLast('.', "")) {
    "html" -> "text/html; charset=utf-8"
    "js" -> "application/javascript; charset=utf-8"
    "css" -> "text/css; charset=utf-8"
    "json", "webmanifest" -> "application/json; charset=utf-8"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "svg" -> "image/svg+xml"
    "ico" -> "image/x-icon"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

/**
 * A CSV cell.
 *
 * A leading =, +, - or @ is EXECUTED AS A FORMULA by Excel and Sheets
 * when the file is opened, and track titles beginning with "-" are not
 * rare.
 */
internal fun csvCell(v: String): String {
    val guarded = if (v.isNotEmpty() && v[0] in "=+-@") "'$v" else v
    return if (guarded.any { it == '"' || it == ',' || it == '\r' || it == '\n' }) {
        "\"" + guarded.replace("\"", "\"\"") + "\""
    } else guarded
}

internal fun escapeHtml(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

/** What an OAuth redirect lands on. */
internal fun closingPage(message: String): String = """<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mandarin Migration</title>
<style>
  :root { color-scheme: light dark; }
  body { font: 16px/1.5 system-ui, sans-serif; margin: 0; display: grid;
 place-items: center; min-height: 100svh; padding: 24px; text-align: center; }
  p { max-width: 32rem; }
</style>
<p>${escapeHtml(message)}</p>
<script>
  try { if (window.opener) window.opener.postMessage("mandarin-migration-auth", "*"); } catch (e) {}
</script>"""

internal fun jobJson(store: Store, job: JobRow, running: Boolean): String = buildString {
    append("{\"id\":").append(jsonQuote(job.id))
    append(",\"created\":").append(job.created)
    append(",\"finished\":").append(job.finished ?: "null")
    append(",\"direction\":").append(jsonQuote(job.direction))
    append(",\"status\":").append(jsonQuote(job.status))
    append(",\"dryRun\":").append(job.dryRun)
    append(",\"progress\":").append(
        if (job.progressJson.isBlank()) "{}" else job.progressJson)
    append(",\"error\":").append(if (job.error == null) "null" else jsonQuote(job.error))
    append(",\"running\":").append(running)
    append(",\"counts\":{")
    append(store.itemCounts(job.id).entries.joinToString(",") {
        jsonQuote(it.key) + ":" + it.value
    })
    append("}}")
}

internal fun itemJson(it: JobItem): String = buildString {
    append("{\"kind\":").append(jsonQuote(it.kind))
    append(",\"container\":").append(
        if (it.container == null) "null" else jsonQuote(it.container))
    append(",\"sourceId\":").append(jsonQuote(it.sourceId))
    append(",\"sourceLabel\":").append(jsonQuote(it.sourceLabel))
    append(",\"targetId\":").append(
        if (it.targetId == null) "null" else jsonQuote(it.targetId))
    append(",\"status\":").append(jsonQuote(it.status))
    append(",\"method\":").append(if (it.method == null) "null" else jsonQuote(it.method))
    append(",\"note\":").append(if (it.note == null) "null" else jsonQuote(it.note))
    append("}")
}

/**
 * The unmatched list as a spreadsheet — the actual deliverable of a
 * migration, in a form someone can work through. A list that can only be
 * scrolled in a WebView is a list nobody finishes.
 */
internal fun csvReport(store: Store, status: String?, job: JobRow): Response {
    val rows = store.items(job.id, status)
    // `id` is the item's own id on the service it was read from. For a
    // clear-out that column is the record of what was there.
    val sb = StringBuilder("kind,playlist,item,status,method,note,targetId,id")
    for (r in rows) {
        sb.append("\r\n")
        sb.append(listOf(r.kind, r.container.orEmpty(), r.sourceLabel, r.status,
            r.method.orEmpty(), r.note.orEmpty(), r.targetId.orEmpty(),
            if (r.sourceId == "-") "" else r.sourceId)
            .joinToString(",") { csvCell(it) })
    }
    return Response.bytes(200, "text/csv; charset=utf-8",
        sb.toString().toByteArray(Charsets.UTF_8),
        mapOf("Content-Disposition" to
            "attachment; filename=\"mandarin-${job.direction}-${job.id.take(8)}.csv\""))
}
