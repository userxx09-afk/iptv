package io.tapper.firetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Guide storage.
 *
 * Plain SQLiteOpenHelper rather than Room: this is one table and four queries,
 * and Room would add a KSP codegen step plus three dependencies for no benefit
 * here. The catalogue stays in memory; only the EPG needs a database, because a
 * full guide is hundreds of thousands of rows and cannot be held in RAM on a
 * Fire TV stick.
 */
class EpgDatabase(context: Context) : SQLiteOpenHelper(context, "tapper_epg.db", null, 2) {

    companion object {
        /**
         * Guide ids are matched loosely on purpose.
         *
         * A panel reports epg_channel_id as "CNN.us" while its own XMLTV file
         * writes "cnn.us"; trailing spaces are common too. An exact match then
         * silently finds nothing, which looks exactly like "the guide didn't
         * download" even though it did. Both sides are normalised here.
         */
        fun normalizeId(raw: String?): String =
            raw?.trim()?.lowercase().orEmpty()
    }

    data class Programme(
        val channelId: String,
        val startUtc: Long,
        val endUtc: Long,
        val title: String,
        val description: String?,
    ) {
        fun progressAt(now: Long): Float {
            val span = (endUtc - startUtc).coerceAtLeast(1L)
            return ((now - startUtc).toFloat() / span).coerceIn(0f, 1f)
        }
    }

    /**
     * Write-ahead logging, so a guide refresh does not lock out the UI.
     *
     * In the default journal mode a writer blocks every reader for the whole
     * transaction. The guide import is one long write, so every "what's on now"
     * query behind it stalled - which is why the app was unusable while the
     * guide updated. Under WAL, readers see the previous committed state and
     * keep running while the import proceeds.
     */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE epg (
              source_id TEXT NOT NULL,
              ch        TEXT NOT NULL,
              start_utc INTEGER NOT NULL,
              end_utc   INTEGER NOT NULL,
              title     TEXT NOT NULL,
              descr     TEXT
            )
            """.trimIndent()
        )
        // Every guide read is a time-range scan grouped by channel, so the index
        // must lead with (source_id, ch) and then start_utc. Without it the
        // "what's on now" query for a visible page becomes a full table scan.
        //
        // UNIQUE, not just indexed: providers routinely repeat a <programme>
        // node in their XMLTV (a guide stitched from two feeds, a mirrored
        // entry), which lands two rows with the same (source_id, ch, start_utc)
        // here. Every screen that lists a channel's schedule keys its LazyColumn
        // on that same triple, so a duplicate row is not a display glitch - it's
        // "java.lang.IllegalArgumentException: Key ... was already used" and the
        // screen crashes. Enforcing uniqueness at the table means that pair can
        // never reach a list.
        db.execSQL("CREATE UNIQUE INDEX epg_lookup ON epg(source_id, ch, start_utc)")
        db.execSQL("CREATE INDEX epg_window ON epg(source_id, start_utc, end_utc)")
        db.execSQL("CREATE TABLE epg_meta (source_id TEXT PRIMARY KEY, fetched_utc INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS epg")
        db.execSQL("DROP TABLE IF EXISTS epg_meta")
        onCreate(db)
    }

    /**
     * Streaming replace: rows are written as they are parsed, so the whole
     * guide is never held in memory at once.
     *
     * The previous version accumulated every programme in a list before
     * inserting - for a large provider that is several hundred thousand objects
     * and enough to exhaust a Fire TV stick's heap, which is what made the app
     * die partway through a guide refresh.
     *
     * The delete and all inserts share one transaction, so a failure leaves the
     * previous guide intact rather than half a new one.
     */
    fun replaceStreaming(
        sourceId: String,
        onProgress: (Int) -> Unit = {},
        produce: ((Programme) -> Unit) -> Unit,
    ): Int {
        val db = writableDatabase
        var count = 0

        // Rows land in a staging table first, so the live guide stays queryable
        // for the entire download. Only the swap at the end touches it, and that
        // is a single fast statement rather than a minutes-long lock.
        //
        // Named per call, not a fixed "epg_staging" - nothing here serializes
        // refreshes against each other (the periodic background worker, a
        // manual "Refresh guide now" press, and the foreground auto-refresh
        // fired when a just-loaded guide turns out to be stale can all
        // legitimately overlap in time), so a shared fixed name meant whichever
        // call finished first dropped the table a still-running one was
        // mid-swap on, throwing "no such table: epg_staging" right out from
        // under it. A name scoped to this one call can never collide with
        // another concurrent refresh's table, whatever source either is for.
        val stagingTable = "epg_staging_" + java.util.UUID.randomUUID().toString().replace("-", "")
        db.execSQL(
            "CREATE TABLE $stagingTable (source_id TEXT NOT NULL, ch TEXT NOT NULL, " +
                "start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT)"
        )
        // Same uniqueness the live table enforces, applied while staging so a
        // source that repeats a <programme> entry mid-parse can't even get as
        // far as the swap - OR REPLACE below keeps the last copy and moves on.
        db.execSQL("CREATE UNIQUE INDEX ${stagingTable}_unique ON $stagingTable(source_id, ch, start_utc)")

        val stmt = db.compileStatement(
            "INSERT OR REPLACE INTO $stagingTable(source_id, ch, start_utc, end_utc, title, descr) VALUES (?,?,?,?,?,?)"
        )
        // Committed in chunks. One transaction spanning the whole import would
        // hold a write lock for minutes and grow the WAL without bound.
        var open = false
        fun begin() { if (!open) { db.beginTransaction(); open = true } }
        fun commit() { if (open) { db.setTransactionSuccessful(); db.endTransaction(); open = false } }

        try {
            begin()
            produce { r ->
                stmt.clearBindings()
                stmt.bindString(1, sourceId)
                stmt.bindString(2, normalizeId(r.channelId))
                stmt.bindLong(3, r.startUtc)
                stmt.bindLong(4, r.endUtc)
                stmt.bindString(5, r.title)
                r.description?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                stmt.executeInsert()
                count++
                if (count % 2000 == 0) {
                    commit(); begin()
                    onProgress(count)
                }
            }
            commit()

            if (count > 0) {
                db.beginTransaction()
                try {
                    db.delete("epg", "source_id = ?", arrayOf(sourceId))
                    db.execSQL(
                        "INSERT INTO epg(source_id, ch, start_utc, end_utc, title, descr) " +
                            "SELECT source_id, ch, start_utc, end_utc, title, descr FROM $stagingTable"
                    )
                    db.replace("epg_meta", null, ContentValues().apply {
                        put("source_id", sourceId)
                        put("fetched_utc", System.currentTimeMillis())
                    })
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } finally {
            if (open) db.endTransaction()
            runCatching { db.execSQL("DROP TABLE IF EXISTS $stagingTable") }
        }
        return count
    }

    /**
     * Adds or updates specific rows without touching the rest of a source's
     * guide - the merge counterpart to [replaceStreaming]'s wipe-and-replace.
     *
     * For a supplemental guide layered on top of a source's primary one (see
     * EpgRepository's Marquee Sports Network merge): that primary guide is
     * usually fine for everything except the one channel it never carries, so
     * a full replace of the whole source's data on every fetch would just
     * fight [replaceStreaming] for which guide won each refresh. Scoping the
     * write to only the rows actually supplied here means the two can share a
     * source_id without either clobbering the other.
     */
    fun upsert(sourceId: String, rows: List<Programme>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO epg(source_id, ch, start_utc, end_utc, title, descr) VALUES (?,?,?,?,?,?)"
            )
            for (r in rows) {
                stmt.clearBindings()
                stmt.bindString(1, sourceId)
                stmt.bindString(2, normalizeId(r.channelId))
                stmt.bindLong(3, r.startUtc)
                stmt.bindLong(4, r.endUtc)
                stmt.bindString(5, r.title)
                r.description?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Replaces a source's guide wholesale. One transaction: committing per row
     * would take minutes for a real guide, and a half-written guide is worse
     * than none.
     */
    fun replaceAll(sourceId: String, rows: List<Programme>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("epg", "source_id = ?", arrayOf(sourceId))
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO epg(source_id, ch, start_utc, end_utc, title, descr) VALUES (?,?,?,?,?,?)"
            )
            for (r in rows) {
                stmt.clearBindings()
                stmt.bindString(1, sourceId)
                stmt.bindString(2, normalizeId(r.channelId))
                stmt.bindLong(3, r.startUtc)
                stmt.bindLong(4, r.endUtc)
                stmt.bindString(5, r.title)
                r.description?.let { stmt.bindString(6, it) } ?: stmt.bindNull(6)
                stmt.executeInsert()
            }
            db.replace("epg_meta", null, ContentValues().apply {
                put("source_id", sourceId)
                put("fetched_utc", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun fetchedAt(sourceId: String): Long =
        readableDatabase.rawQuery(
            "SELECT fetched_utc FROM epg_meta WHERE source_id = ?", arrayOf(sourceId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    /** Distinct guide channel ids, so settings can report match coverage. */
    fun guideChannelIds(sourceId: String): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT ch FROM epg WHERE source_id = ?", arrayOf(sourceId)
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /**
     * What's airing now, or next, on a raw guide id. Used only to label the
     * manual-override picker: a guide id is frequently a cryptic provider
     * code ("I279.6244.schedulesdirect.org"), and the programme title
     * currently on it is the one thing that actually lets a person confirm
     * they've found the right entry before assigning it to a channel.
     */
    fun sampleTitle(sourceId: String, ch: String, now: Long): String? =
        readableDatabase.rawQuery(
            "SELECT title FROM epg WHERE source_id = ? AND ch = ? AND end_utc > ? " +
                "ORDER BY start_utc LIMIT 1",
            arrayOf(sourceId, ch, now.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun countFor(sourceId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM epg WHERE source_id = ?", arrayOf(sourceId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * What is on right now, for every channel at once. One query for the whole
     * list rather than one per visible row - per-row queries are what make
     * these apps stutter while scrolling a long channel list.
     */
    fun nowPlaying(sourceId: String, now: Long): Map<String, Programme> {
        val out = HashMap<String, Programme>()
        readableDatabase.rawQuery(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg " +
                "WHERE source_id = ? AND start_utc <= ? AND end_utc > ?",
            arrayOf(sourceId, now.toString(), now.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = Programme(
                    c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)
                )
            }
        }
        return out
    }

    fun upcoming(sourceId: String, channelId: String, now: Long, limit: Int = 8): List<Programme> {
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg " +
                "WHERE source_id = ? AND ch = ? AND end_utc > ? ORDER BY start_utc LIMIT ?",
            arrayOf(sourceId, normalizeId(channelId), now.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Programme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
        }
        return out
    }

    /** Programme title search, restricted to the future and the current show. */
    fun search(sourceId: String, query: String, now: Long, limit: Int = 60): List<Programme> {
        val out = ArrayList<Programme>()
        readableDatabase.rawQuery(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg " +
                "WHERE source_id = ? AND end_utc > ? AND title LIKE ? " +
                "ORDER BY start_utc LIMIT ?",
            arrayOf(sourceId, now.toString(), "%" + query + "%", limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Programme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
        }
        return out
    }

    fun prune(cutoffUtc: Long) {
        writableDatabase.delete("epg", "end_utc < ?", arrayOf(cutoffUtc.toString()))
    }
}
