package com.musicd.migrate.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.musicd.migrate.CachedMatch
import com.musicd.migrate.JobItem
import com.musicd.migrate.JobRow
import com.musicd.migrate.RoonAlbumRow
import com.musicd.migrate.Store
import com.musicd.migrate.roon.RoonScanSummary
import org.json.JSONObject

/**
 * The store on a phone. The same three tables lib/store.js creates, with the
 * same rules — see Store.kt in :core for why each exists.
 *
 * It lives here rather than in :core because :core is plain Kotlin/JVM so it
 * can be unit-tested without an Android SDK, which rules out
 * android.database.sqlite. MemoryStore stands in for this under test, and it
 * implements every rule this does — including the cached-miss distinction and
 * the orphan sweep — because those are what the engine's behaviour turns on.
 */
class SqliteStore(context: Context) : Store {

    private val helper = object : SQLiteOpenHelper(context, "migrate.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)""")
            db.execSQL("""
                CREATE TABLE match_cache (
                  from_service TEXT NOT NULL, from_id TEXT NOT NULL,
                  to_service TEXT NOT NULL, kind TEXT NOT NULL,
                  to_id TEXT, method TEXT NOT NULL, at INTEGER NOT NULL,
                  PRIMARY KEY (from_service, from_id, to_service, kind))""")
            db.execSQL("""
                CREATE TABLE jobs (
                  id TEXT PRIMARY KEY, created INTEGER NOT NULL, finished INTEGER,
                  direction TEXT NOT NULL, status TEXT NOT NULL,
                  dry_run INTEGER NOT NULL DEFAULT 0, options TEXT NOT NULL,
                  progress TEXT NOT NULL, error TEXT)""")
            db.execSQL("""
                CREATE TABLE job_items (
                  id INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT NOT NULL,
                  kind TEXT NOT NULL, container TEXT, source_id TEXT NOT NULL,
                  source_label TEXT NOT NULL, target_id TEXT, status TEXT NOT NULL,
                  method TEXT, note TEXT)""")
            db.execSQL("CREATE INDEX job_items_job ON job_items(job_id)")
            db.execSQL("CREATE INDEX job_items_status ON job_items(job_id, status)")
            createRoonAlbums(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            // Migrating, never dropping. An installed copy of this app holds
            // two services' sign-ins and a match cache that cost hours of
            // rate-limited lookups to build; recreating the database would
            // throw both away and look like the app had signed itself out.
            if (old < 2) createRoonAlbums(db)
        }

        private fun createRoonAlbums(db: SQLiteDatabase) {
            // The same table lib/store.js creates. See Store.RoonAlbumRow for
            // why the key is a hash rather than Roon's own item_key.
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS roon_albums (
                  core_id TEXT NOT NULL, album_key TEXT NOT NULL,
                  title TEXT NOT NULL, artist TEXT NOT NULL,
                  position INTEGER NOT NULL, image_key TEXT,
                  track_count INTEGER, scanned_at INTEGER NOT NULL,
                  PRIMARY KEY (core_id, album_key))""")
        }

        override fun onConfigure(db: SQLiteDatabase) {
            // The job writes a row per track while the page polls progress.
            // WAL is what stops those blocking each other.
            db.enableWriteAheadLogging()
        }
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    // ------------------------------------------------------------ roon library

    override fun saveRoonAlbums(coreId: String, rows: List<RoonAlbumRow>): Pair<Int, Int> {
        val now = System.currentTimeMillis()
        var stored = 0
        val d = db
        // One transaction per page, not per album: a ten thousand album scan
        // is a hundred commits rather than ten thousand fsyncs, and a scan
        // killed halfway has kept everything up to the last page.
        d.beginTransaction()
        try {
            for (r in rows) {
                val values = ContentValues().apply {
                    put("core_id", coreId)
                    put("album_key", r.albumKey)
                    put("title", r.title)
                    put("artist", r.artist)
                    put("position", r.position)
                    put("image_key", r.imageKey)
                    put("scanned_at", now)
                }
                // CONFLICT_IGNORE: the first copy of a record wins, matching
                // ON CONFLICT DO NOTHING on the JavaScript side.
                val id = d.insertWithOnConflict("roon_albums", null, values,
                    SQLiteDatabase.CONFLICT_IGNORE)
                if (id != -1L) stored++
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        return stored to (rows.size - stored)
    }

    private fun roonRow(c: android.database.Cursor) = RoonAlbumRow(
        albumKey = c.getString(c.getColumnIndexOrThrow("album_key")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        artist = c.getString(c.getColumnIndexOrThrow("artist")),
        position = c.getInt(c.getColumnIndexOrThrow("position")),
        imageKey = c.getColumnIndexOrThrow("image_key").let {
            if (c.isNull(it)) null else c.getString(it) },
        trackCount = c.getColumnIndexOrThrow("track_count").let {
            if (c.isNull(it)) null else c.getInt(it) }
    )

    override fun roonAlbums(coreId: String): List<RoonAlbumRow> =
        db.rawQuery("SELECT * FROM roon_albums WHERE core_id = ? ORDER BY position",
            arrayOf(coreId)).use { c ->
            val out = ArrayList<RoonAlbumRow>()
            while (c.moveToNext()) out.add(roonRow(c))
            out
        }

    override fun roonAlbum(coreId: String, albumKey: String): RoonAlbumRow? =
        db.rawQuery("SELECT * FROM roon_albums WHERE core_id = ? AND album_key = ?",
            arrayOf(coreId, albumKey)).use { c -> if (c.moveToFirst()) roonRow(c) else null }

    override fun roonAlbumCount(coreId: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM roon_albums WHERE core_id = ?",
            arrayOf(coreId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun setRoonAlbumTrackCount(coreId: String, albumKey: String, trackCount: Int?) {
        val values = ContentValues().apply {
            if (trackCount == null) putNull("track_count") else put("track_count", trackCount)
        }
        db.update("roon_albums", values, "core_id = ? AND album_key = ?",
            arrayOf(coreId, albumKey))
    }

    override fun clearRoonAlbums(coreId: String) {
        db.delete("roon_albums", "core_id = ?", arrayOf(coreId))
    }

    /* The scan's own position is a setting rather than a table: it is one row
     * and it has the same lifetime as the sign-ins. lib/store.js keeps it the
     * same way, under the same key. */
    override fun saveRoonScan(summary: RoonScanSummary) {
        putSetting("roon.scan", JSONObject()
            .put("coreId", summary.coreId).put("offset", summary.offset)
            .put("total", summary.total).put("stored", summary.stored)
            .put("duplicates", summary.duplicates).put("done", summary.done).toString())
    }

    override fun roonScan(): RoonScanSummary? {
        val raw = setting("roon.scan") ?: return null
        return try {
            val o = JSONObject(raw)
            RoonScanSummary(o.getString("coreId"), o.getInt("offset"), o.getInt("total"),
                o.getInt("stored"), o.getInt("duplicates"), o.getBoolean("done"))
        } catch (e: Exception) {
            // A row written by an older build, or a truncated write. Losing a
            // resume point costs one rescan; a crash here would cost the app.
            null
        }
    }

    // -------------------------------------------------------------- settings

    override fun setting(key: String): String? =
        db.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    override fun putSetting(key: String, value: String) {
        db.execSQL("INSERT INTO settings (key, value) VALUES (?, ?) " +
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value", arrayOf(key, value))
    }

    override fun deleteSetting(key: String) {
        db.delete("settings", "key = ?", arrayOf(key))
    }

    // ----------------------------------------------------------- match cache

    override fun cachedMatch(fromService: String, fromId: String, toService: String,
                             kind: String): CachedMatch? =
        db.rawQuery("SELECT to_id, method FROM match_cache WHERE from_service = ? " +
            "AND from_id = ? AND to_service = ? AND kind = ?",
            arrayOf(fromService, fromId, toService, kind)).use { c ->
            // A row that EXISTS with a null to_id is a cached MISS and is a
            // real answer. Returning null for it would make every re-run pay
            // again for exactly the tracks that are slowest.
            if (c.moveToFirst()) CachedMatch(if (c.isNull(0)) null else c.getString(0),
                c.getString(1)) else null
        }

    override fun cacheMatch(fromService: String, fromId: String, toService: String,
                            kind: String, toId: String?, method: String) {
        db.execSQL("INSERT INTO match_cache " +
            "(from_service, from_id, to_service, kind, to_id, method, at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(from_service, from_id, to_service, kind) DO UPDATE SET " +
            "to_id = excluded.to_id, method = excluded.method, at = excluded.at",
            arrayOf<Any?>(fromService, fromId, toService, kind, toId, method,
                System.currentTimeMillis()))
    }

    override fun clearMatchCache() { db.delete("match_cache", null, null) }

    override fun matchCacheSize(): Int =
        db.rawQuery("SELECT COUNT(*) FROM match_cache", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    // ------------------------------------------------------------------ jobs

    override fun createJob(id: String, direction: String, options: String, dryRun: Boolean) {
        db.execSQL("INSERT INTO jobs (id, created, direction, status, dry_run, options, " +
            "progress) VALUES (?, ?, ?, 'running', ?, ?, ?)",
            arrayOf<Any?>(id, System.currentTimeMillis(), direction, if (dryRun) 1 else 0,
                options, """{"phase":"starting"}"""))
    }

    override fun updateProgress(id: String, progressJson: String) {
        db.execSQL("UPDATE jobs SET progress = ? WHERE id = ?", arrayOf(progressJson, id))
    }

    override fun finishJob(id: String, status: String, error: String?) {
        db.execSQL("UPDATE jobs SET status = ?, finished = ?, error = ? WHERE id = ?",
            arrayOf<Any?>(status, System.currentTimeMillis(), error, id))
    }

    override fun job(id: String): JobRow? =
        db.rawQuery("SELECT id, created, finished, direction, status, dry_run, options, " +
            "progress, error FROM jobs WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) readJob(c) else null
        }

    override fun jobs(limit: Int): List<JobRow> =
        db.rawQuery("SELECT id, created, finished, direction, status, dry_run, options, " +
            "progress, error FROM jobs ORDER BY created DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            val out = ArrayList<JobRow>()
            while (c.moveToNext()) out.add(readJob(c))
            out
        }

    private fun readJob(c: android.database.Cursor) = JobRow(
        id = c.getString(0),
        created = c.getLong(1),
        finished = if (c.isNull(2)) null else c.getLong(2),
        direction = c.getString(3),
        status = c.getString(4),
        dryRun = c.getInt(5) != 0,
        optionsJson = c.getString(6),
        progressJson = c.getString(7),
        error = if (c.isNull(8)) null else c.getString(8)
    )

    /**
     * Any job still marked running when the app starts again was killed — the
     * service was stopped, the phone ran out of memory. Nothing resumes it, so
     * leaving it "running" would show a spinner that never ends.
     */
    override fun markOrphansInterrupted(): Int {
        val values = ContentValues().apply {
            put("status", "interrupted")
            put("finished", System.currentTimeMillis())
            put("error", "The app stopped while this was running.")
        }
        return db.update("jobs", values, "status = 'running'", null)
    }

    /** One transaction per batch: a per-row transaction on WAL is a disk sync
     *  each, and a 2,000-track playlist feels every one of them. */
    override fun addItems(jobId: String, items: List<JobItem>) {
        if (items.isEmpty()) return
        db.beginTransaction()
        try {
            for (i in items) {
                db.execSQL("INSERT INTO job_items (job_id, kind, container, source_id, " +
                    "source_label, target_id, status, method, note) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(jobId, i.kind, i.container, i.sourceId, i.sourceLabel,
                        i.targetId, i.status, i.method, i.note))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun items(jobId: String, status: String?): List<JobItem> {
        val sql = "SELECT kind, container, source_id, source_label, target_id, status, " +
            "method, note FROM job_items WHERE job_id = ?" +
            (if (status != null) " AND status = ?" else "") + " ORDER BY id"
        val args = if (status != null) arrayOf(jobId, status) else arrayOf(jobId)
        return db.rawQuery(sql, args).use { c ->
            val out = ArrayList<JobItem>()
            while (c.moveToNext()) {
                out.add(JobItem(
                    kind = c.getString(0),
                    container = if (c.isNull(1)) null else c.getString(1),
                    sourceId = c.getString(2),
                    sourceLabel = c.getString(3),
                    targetId = if (c.isNull(4)) null else c.getString(4),
                    status = c.getString(5),
                    method = if (c.isNull(6)) null else c.getString(6),
                    note = if (c.isNull(7)) null else c.getString(7)))
            }
            out
        }
    }

    override fun itemCounts(jobId: String): Map<String, Int> =
        db.rawQuery("SELECT status, COUNT(*) FROM job_items WHERE job_id = ? GROUP BY status",
            arrayOf(jobId)).use { c ->
            val out = LinkedHashMap<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    override fun deleteJob(id: String) {
        db.delete("job_items", "job_id = ?", arrayOf(id))
        db.delete("jobs", "id = ?", arrayOf(id))
    }

    override fun close() = helper.close()
}
