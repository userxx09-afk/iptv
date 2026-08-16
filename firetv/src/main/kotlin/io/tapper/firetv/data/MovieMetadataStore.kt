package io.tapper.firetv.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.tmdb.TmdbClient
import kotlinx.coroutines.CancellationException

/**
 * Movie/Show metadata (poster, synopsis, rating) looked up from TMDb by
 * title and year, and cached locally - the VOD equivalent of what
 * EpgDatabase already does for Live TV's "what's on now". See BrowseScreen's
 * GUIDE column and MovieInfoPanel for where this ends up on screen.
 *
 * TMDb is free for non-commercial use but requires a personal API key (see
 * Settings, and TMDb's own signup) - [lookup] never throws to the caller,
 * and simply returns null until a key is configured, so a fresh install
 * with none looks exactly like "no info available yet" rather than an error
 * screen.
 *
 * A null [lookup] result is otherwise ambiguous - it means either "TMDb
 * genuinely has nothing for this title" or "the lookup itself failed" (bad
 * key, no network, TMDb down), and those look identical to a caller that
 * only checks for null. [onError] and [lastError] exist so a *failure*
 * specifically can be surfaced somewhere the user can actually see it
 * (MovieInfoPanel, the app's event log), rather than both cases collapsing
 * into the same silent "No match found" - which is exactly what made a
 * wrong-format API key indistinguishable from a real miss.
 */
class MovieMetadataStore(
    context: Context,
    private val onError: (String) -> Unit = {},
) {

    data class Metadata(
        val title: String,
        val year: Int?,
        val overview: String?,
        val posterUrl: String?,
        val rating: Double?,
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tapper_movie_info", Context.MODE_PRIVATE)

    var apiKey: String
        // DEFAULT_TMDB_KEY only ever surfaces here when "tmdb_key" is
        // entirely ABSENT from prefs - getString's default is never
        // substituted for a key that's merely present-but-empty. So
        // Settings' "Remove key" (which saves "") still fully clears it:
        // this is a starting default for a fresh install, not a floor
        // nothing can go below.
        get() = prefs.getString("tmdb_key", DEFAULT_TMDB_KEY).orEmpty()
        set(v) = prefs.edit().putString("tmdb_key", v.trim()).apply()

    private val cache = CacheDb(context)

    /**
     * The reason the *most recent* [lookup] call returned null due to a
     * failure, or null if that lookup succeeded (with or without a match)
     * or hasn't run yet. Cleared at the start of every lookup that reaches
     * TMDb, so it never keeps reporting a stale failure after a later
     * lookup succeeds.
     */
    var lastError: String? = null; private set

    /**
     * Looks up [channel] by its cleaned title and any year folded into it.
     * Blocking (SQLite read, and on a cache miss a network call) - callers
     * are expected to run this off the main thread, the same way a
     * PlaylistRepository load already is.
     *
     * A confirmed "TMDb has nothing for this" is cached indefinitely, so a
     * title it genuinely doesn't carry (a local access channel filed under
     * Movies, a PPV event, a mis-categorised entry) is not re-queried on
     * every single focus. A network failure or a not-yet-configured key is
     * deliberately NOT cached as a miss - caching that would make a
     * temporary outage or a bad key look permanent until the cache is
     * cleared, rather than simply trying again next time.
     */
    suspend fun lookup(channel: Channel): Metadata? {
        if (channel.kind == ContentKind.LIVE) return null
        // Reset up front, not just on a successful network call below - a
        // cache hit (or a blank key/title) returns early without ever
        // reaching the try/catch that would otherwise clear this, which
        // used to leave one title's error message showing under the next
        // title focused right after it.
        lastError = null
        val key = apiKey
        if (key.isBlank()) return null
        val (cleanTitle, year) = parseTitle(channel.name)
        if (cleanTitle.isBlank()) return null

        val cacheKey = "${channel.kind.name}|${cleanTitle.lowercase()}|${year ?: ""}"
        // A non-null CacheEntry means this key has been asked before -
        // whether that resolved to a real Metadata or a confirmed miss, its
        // own .metadata (itself nullable) is the right thing to return
        // either way, without going back to TMDb.
        cache.read(cacheKey)?.let { return it.metadata }

        val result = try {
            TmdbClient(key).search(cleanTitle, year, isShow = channel.kind == ContentKind.SERIES)
        } catch (c: CancellationException) {
            // search() is now a suspend call (Phase 2's Ktor migration) -
            // must propagate, not fall into the generic catch below, or
            // scrolling away from a title while its lookup is in flight
            // would get swallowed as a normal failure instead of actually
            // cancelling, the same hazard TmdbClient.fetch() itself guards
            // against internally.
            throw c
        } catch (t: Throwable) {
            // Deliberately not cached as a miss (see the class doc comment
            // above lookup()) - a bad key or a dropped connection should be
            // retried next time, not remembered forever as "not found".
            val message = (t as? TmdbClient.TmdbException)?.message ?: "Couldn't reach TMDb: ${t.message}"
            lastError = message
            onError("$cleanTitle: $message")
            return null
        }
        val metadata = result?.let { Metadata(it.title, it.year, it.overview, it.posterUrl, it.rating) }
        cache.write(cacheKey, metadata)
        return metadata
    }

    /** Forces every title to be looked up fresh next time it's focused -
     *  the escape hatch if TMDb's data for something changes, or a cached
     *  miss turns out to have been a title-cleanup bug rather than a real
     *  absence. */
    fun clearCache() = cache.clear()

    companion object {
        // The app's own personal TMDb key, baked in as the out-of-the-box
        // default at the owner's explicit request - this is a single-user
        // sideloaded app, not a distributed one, so the usual "never commit
        // an API key" caution doesn't carry the same weight here. Settings
        // still shows and can fully clear/replace it (see apiKey above).
        private const val DEFAULT_TMDB_KEY = "eac8b6cb4fb0acb2293728b5a71399a8"

        /**
         * Strips the quality/source/language noise IPTV VOD titles are
         * commonly larded with (resolution, codec, HDR, release-group tags,
         * bracketed junk, a leading language tag before a dash) and pulls
         * out an embedded release year if there is one - "EN - Movie Name
         * (2023) 4K HDR" becomes ("Movie Name", 2023).
         *
         * Deliberately conservative about the year specifically: only the
         * matched year token itself is removed (not every 4-digit number in
         * the string), so a title that IS a year - "1917", "2012" - is not
         * gutted to nothing. If cleanup ever does leave nothing usable, the
         * original raw title is returned unchanged rather than an empty
         * query. Everything else here is deliberately loose - stripping a
         * leading tag, anything in parens, and known quality words is worth
         * more false-strips of a genuinely unusual title than leaving the
         * common case ("EN - Actual Title (2023) 4K") too cluttered for
         * TMDb to match at all.
         *
         * This is tuned against real examples from this account's own
         * catalogue - the noise list and the leading-tag pattern may still
         * need further adjustment as more titles are seen coming through it.
         */
        internal fun parseTitle(raw: String): Pair<String, Int?> {
            var s = raw.trim()
            val bracketYear = Regex("""[\[(](19|20)\d{2}[\])]""").find(s)
            val yearMatch = bracketYear ?: Regex("""\b(19|20)\d{2}\b""").find(s)
            val year = yearMatch?.value?.filter { it.isDigit() }?.toIntOrNull()
            if (yearMatch != null) s = s.removeRange(yearMatch.range)
            s = s.trim()

            // A short leading tag before a dash - "EN - Movie Name",
            // "MULTI - Movie Name" - is a language/region marker, not part
            // of the title. Requires real whitespace on BOTH sides of the
            // dash, not just anchoring at the start: a tag prefix is always
            // spaced out like that, while a genuinely hyphenated title
            // ("Spider-Man", "X-Men") never has a space before its own
            // hyphen - that distinction, not just position or length, is
            // what keeps this from mangling "Spider-Man No Way Home" into
            // "Man No Way Home", which an earlier, looser version of this
            // pattern (bare \s* on both sides) actually did.
            s = s.replace(Regex("""^\S{1,10}\s+-\s+"""), "")

            // Everything in parens or brackets - quality tags, language/
            // source tags, release-group noise. The year above was already
            // pulled out, so losing its own parens here costs nothing.
            s = s.replace(Regex("""[\[(][^\[\]()]*[\])]"""), " ")

            val noise = setOf(
                "EN", "4K", "2160P", "1080P", "720P", "HD", "SD", "HDR", "HDR10",
                "HEVC", "H264", "H265", "X264", "X265", "WEB-DL", "WEBDL", "WEBRIP",
                "BLURAY", "BLU-RAY", "BRRIP", "DVDRIP", "HDRIP", "CAM", "MULTI",
                "DUBBED", "SUB", "VOSTFR", "VF", "VO",
            )
            val cleaned = s.split(Regex("""\s+"""))
                .map { it.trim('-', '.', ':', '|') }
                .filter { it.isNotEmpty() && it.uppercase() !in noise }
                .joinToString(" ")
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '-', '.', ':', '|')
            return (cleaned.ifBlank { raw.trim() }) to year
        }
    }

    /** Own tiny SQLiteOpenHelper rather than folding into EpgDatabase - a
     *  single-row-per-title cache with no time-range queries is a different
     *  shape entirely from a schedule, and this app already keeps one class
     *  per store rather than one shared catch-all database. */
    private class CacheDb(context: Context) :
        SQLiteOpenHelper(context, "tapper_movie_info.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE info (
                  cache_key   TEXT PRIMARY KEY,
                  found       INTEGER NOT NULL,
                  title       TEXT,
                  year        INTEGER,
                  overview    TEXT,
                  poster      TEXT,
                  rating      REAL,
                  fetched_utc INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS info")
            onCreate(db)
        }

        /** Null means "this key has never been looked up" - the caller
         *  should go fetch it. A non-null [CacheEntry] means it was asked
         *  before, and its own [CacheEntry.metadata] (itself nullable)
         *  carries either the cached result or a confirmed miss. */
        fun read(cacheKey: String): CacheEntry? {
            val c = readableDatabase.rawQuery(
                "SELECT found, title, year, overview, poster, rating FROM info WHERE cache_key = ?",
                arrayOf(cacheKey),
            )
            c.use {
                if (!it.moveToFirst()) return null
                val found = it.getInt(0) != 0
                if (!found) return CacheEntry(null)
                val title = it.getString(1) ?: return null // malformed row - treat as never cached
                return CacheEntry(
                    Metadata(
                        title = title,
                        year = if (it.isNull(2)) null else it.getInt(2),
                        overview = it.getString(3),
                        posterUrl = it.getString(4),
                        rating = if (it.isNull(5)) null else it.getDouble(5),
                    )
                )
            }
        }

        fun write(cacheKey: String, metadata: Metadata?) {
            val values = ContentValues().apply {
                put("cache_key", cacheKey)
                put("found", if (metadata != null) 1 else 0)
                put("title", metadata?.title)
                if (metadata?.year != null) put("year", metadata.year) else putNull("year")
                put("overview", metadata?.overview)
                put("poster", metadata?.posterUrl)
                if (metadata?.rating != null) put("rating", metadata.rating) else putNull("rating")
                put("fetched_utc", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("info", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }

        fun clear() = writableDatabase.execSQL("DELETE FROM info")
    }

    /** Wraps a cache read so "never looked up" (a null CacheEntry from
     *  CacheDb.read) and "looked up, TMDb had nothing" (a non-null
     *  CacheEntry whose own metadata is null) are distinguishable - a plain
     *  Metadata? return type from read() could not tell those apart. Private
     *  to MovieMetadataStore rather than to CacheDb specifically, so both
     *  CacheDb and the lookup() method above - sibling members of the same
     *  outer class - can see it. */
    private class CacheEntry(val metadata: Metadata?)
}
