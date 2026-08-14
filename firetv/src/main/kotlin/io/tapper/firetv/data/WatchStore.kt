package io.tapper.firetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Watch progress, and the merge rules that let several devices share it
 * through a plain folder rather than a server.
 */
class WatchStore(context: Context) : SQLiteOpenHelper(context, "tapper_watch.db", null, 1) {

    data class Entry(
        val itemId: String,
        val sourceId: String,
        val seriesId: String?,
        val season: Int,
        val number: Int,
        val title: String,
        val positionMs: Long,
        val durationMs: Long,
        val watched: Boolean,
        val clock: Long,
        val deviceId: String,
    ) {
        /**
         * Complete at 92%, or with under two minutes left, whichever comes
         * first. The dual rule matters: 92% of a 3-hour film still leaves 14
         * minutes, and nobody who sat through the credits wants it back in
         * Continue Watching.
         */
        fun isComplete(): Boolean {
            if (durationMs <= 0) return watched
            val remaining = durationMs - positionMs
            return watched || positionMs >= durationMs * 92 / 100 || remaining in 0..120_000
        }

        fun toJson(): JSONObject = JSONObject()
            .put("item", itemId).put("source", sourceId)
            .put("series", seriesId ?: JSONObject.NULL)
            .put("season", season).put("number", number).put("title", title)
            .put("pos", positionMs).put("dur", durationMs)
            .put("watched", watched).put("clock", clock).put("device", deviceId)

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                itemId = o.getString("item"),
                sourceId = o.optString("source"),
                seriesId = o.optString("series").takeIf { it.isNotBlank() && it != "null" },
                season = o.optInt("season"),
                number = o.optInt("number"),
                title = o.optString("title"),
                positionMs = o.optLong("pos"),
                durationMs = o.optLong("dur"),
                watched = o.optBoolean("watched"),
                clock = o.optLong("clock"),
                deviceId = o.optString("device"),
            )
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE watch (
              item_id   TEXT NOT NULL PRIMARY KEY,
              source_id TEXT NOT NULL,
              series_id TEXT,
              season    INTEGER NOT NULL DEFAULT 0,
              number    INTEGER NOT NULL DEFAULT 0,
              title     TEXT NOT NULL DEFAULT '',
              pos_ms    INTEGER NOT NULL DEFAULT 0,
              dur_ms    INTEGER NOT NULL DEFAULT 0,
              watched   INTEGER NOT NULL DEFAULT 0,
              clock     INTEGER NOT NULL DEFAULT 0,
              device_id TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX watch_series ON watch(series_id, season, number)")
        db.execSQL("CREATE INDEX watch_recent ON watch(clock DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS watch"); onCreate(db)
    }

    /**
     * A Lamport clock, not the wall clock.
     *
     * Fire TV sticks have no real-time clock and can come up years out of date
     * after a power cut. A device whose clock lags would then write entries
     * that always lose the merge, silently losing its progress. Taking
     * max(now, highestSeen + 1) keeps ordering sane whatever the device thinks
     * the time is.
     */
    fun nextClock(): Long {
        val highest = readableDatabase.rawQuery("SELECT MAX(clock) FROM watch", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        return maxOf(System.currentTimeMillis(), highest + 1)
    }

    fun put(entry: Entry) {
        val existing = get(entry.itemId)
        // Monotonic OR: a late event from a sleeping device must not un-watch.
        val merged = if (existing == null) entry else mergeOne(existing, entry)
        writableDatabase.replace("watch", null, ContentValues().apply {
            put("item_id", merged.itemId); put("source_id", merged.sourceId)
            merged.seriesId?.let { put("series_id", it) }
            put("season", merged.season); put("number", merged.number)
            put("title", merged.title)
            put("pos_ms", merged.positionMs); put("dur_ms", merged.durationMs)
            put("watched", if (merged.watched) 1 else 0)
            put("clock", merged.clock); put("device_id", merged.deviceId)
        })
    }

    /** The merge rule, applied identically locally and when reading remote shards. */
    fun mergeOne(a: Entry, b: Entry): Entry {
        val watched = a.watched || b.watched
        val newer = if (b.clock >= a.clock) b else a
        return newer.copy(
            watched = watched,
            durationMs = maxOf(a.durationMs, b.durationMs),
            // Position is meaningless once complete, and keeping a stale one
            // would drop a finished episode back into Continue Watching.
            positionMs = if (watched) 0 else newer.positionMs,
            clock = maxOf(a.clock, b.clock),
        )
    }

    fun get(itemId: String): Entry? =
        readableDatabase.rawQuery("SELECT * FROM watch WHERE item_id = ?", arrayOf(itemId))
            .use { if (it.moveToFirst()) read(it) else null }

    fun all(): List<Entry> =
        readableDatabase.rawQuery("SELECT * FROM watch", null).use { c ->
            ArrayList<Entry>().apply { while (c.moveToNext()) add(read(c)) }
        }

    /** Everything written by this device, which is all it may publish. */
    fun ownedBy(deviceId: String): List<Entry> =
        readableDatabase.rawQuery("SELECT * FROM watch WHERE device_id = ?", arrayOf(deviceId))
            .use { c -> ArrayList<Entry>().apply { while (c.moveToNext()) add(read(c)) } }

    /** Partly-watched items, most recent first. */
    fun continueWatching(limit: Int = 30): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT * FROM watch WHERE watched = 0 AND dur_ms > 0 " +
                "AND pos_ms * 100 / dur_ms BETWEEN 2 AND 92 ORDER BY clock DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> ArrayList<Entry>().apply { while (c.moveToNext()) add(read(c)) } }

    /** Highest completed episode of a series, used to work out what is next. */
    fun lastCompleted(seriesId: String): Entry? =
        readableDatabase.rawQuery(
            "SELECT * FROM watch WHERE series_id = ? AND watched = 1 " +
                "ORDER BY season DESC, number DESC LIMIT 1",
            arrayOf(seriesId)
        ).use { if (it.moveToFirst()) read(it) else null }

    private fun read(c: android.database.Cursor) = Entry(
        itemId = c.getString(c.getColumnIndexOrThrow("item_id")),
        sourceId = c.getString(c.getColumnIndexOrThrow("source_id")),
        seriesId = c.getString(c.getColumnIndexOrThrow("series_id")),
        season = c.getInt(c.getColumnIndexOrThrow("season")),
        number = c.getInt(c.getColumnIndexOrThrow("number")),
        title = c.getString(c.getColumnIndexOrThrow("title")),
        positionMs = c.getLong(c.getColumnIndexOrThrow("pos_ms")),
        durationMs = c.getLong(c.getColumnIndexOrThrow("dur_ms")),
        watched = c.getInt(c.getColumnIndexOrThrow("watched")) == 1,
        clock = c.getLong(c.getColumnIndexOrThrow("clock")),
        deviceId = c.getString(c.getColumnIndexOrThrow("device_id")),
    )

    fun exportOwn(deviceId: String, deviceLabel: String): String {
        val arr = JSONArray()
        ownedBy(deviceId).forEach { arr.put(it.toJson()) }
        return JSONObject()
            .put("version", 1)
            .put("device", deviceId)
            .put("label", deviceLabel)
            .put("written", System.currentTimeMillis())
            .put("entries", arr)
            .toString()
    }

    /** Merges one remote shard. Returns how many rows it changed. */
    fun importShard(json: String): Int {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return 0
        val arr = root.optJSONArray("entries") ?: return 0
        var changed = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val incoming = runCatching { Entry.fromJson(o) }.getOrNull() ?: continue
                val before = get(incoming.itemId)
                put(incoming)
                if (before == null || before != get(incoming.itemId)) changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    companion object {
        /** Stable per-install id; the shard filename is derived from it. */
        fun deviceId(context: Context): String {
            val prefs = context.getSharedPreferences("tapper_device", Context.MODE_PRIVATE)
            return prefs.getString("id", null) ?: UUID.randomUUID().toString().take(8).also {
                prefs.edit().putString("id", it).apply()
            }
        }

        fun deviceLabel(context: Context): String {
            val prefs = context.getSharedPreferences("tapper_device", Context.MODE_PRIVATE)
            return prefs.getString("label", null) ?: android.os.Build.MODEL ?: "Device"
        }

        fun setDeviceLabel(context: Context, label: String) {
            context.getSharedPreferences("tapper_device", Context.MODE_PRIVATE)
                .edit().putString("label", label).apply()
        }
    }
}
