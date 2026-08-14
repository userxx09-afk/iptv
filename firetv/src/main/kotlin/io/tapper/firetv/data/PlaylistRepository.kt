package io.tapper.firetv.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.Country
import io.tapper.core.model.StreamRef
import io.tapper.core.playlist.M3uParser
import io.tapper.core.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Loads a source catalogue into memory.
 *
 * 13,510 channels is roughly 6MB of objects - fine even on a 1GB stick. Only
 * the EPG needs a database, because a guide is hundreds of thousands of rows.
 */
class PlaylistRepository(
    private val cacheDir: File,
    private val vault: CredentialVault,
    /** A category/country lookup that failed without taking the rest of the
     *  load down with it - see XtreamClient.liveChannels's onWarning doc.
     *  Not thrown, since the caller already has a perfectly usable (if
     *  under-categorised) catalogue by the time this fires; surfaced here
     *  purely so it ends up somewhere visible (the app's event log) instead
     *  of silently making a real fetch failure look identical to an account
     *  that genuinely has no category/country breakdown. */
    private val onWarning: (String) -> Unit = {},
) {
    companion object {
        private const val CACHE_MAX_AGE_MS = 30 * 60 * 60 * 1000L
    }

    /** One entry in a browse rail. */
    data class Group(val key: String?, val label: String, val channels: List<Channel>)

    enum class Axis { COUNTRY, CATEGORY }

    /** Grouping for one content kind. */
    data class Section(
        val kind: ContentKind,
        val items: List<Channel>,
        val byCountry: List<Group>,
        val byCategory: List<Group>,
    ) {
        /**
         * Pick the axis that actually divides this section. A provider whose
         * categories carry no country token yields a single "Ungrouped"
         * country rail, which is useless - fall through to categories then.
         */
        val defaultAxis: Axis
            get() = if (byCountry.count { it.key != null } > 1) Axis.COUNTRY else Axis.CATEGORY

        fun groups(axis: Axis) = if (axis == Axis.COUNTRY) byCountry else byCategory
    }

    data class Catalogue(
        val sourceId: String,
        val channels: List<Channel>,
        val sections: Map<ContentKind, Section>,
        val declaredEpgUrls: List<String>,
        val fromCache: Boolean,
        /**
         * Kinds an Xtream source actually queried this load, movies and series
         * included, regardless of whether either came back with any items.
         * Empty for M3U sources, where there is no separate "did we check"
         * step - a kind either has channels classified into it or it doesn't.
         */
        val attemptedKinds: Set<ContentKind> = emptySet(),
        /** Why get_vod_streams / get_series came back empty, keyed by kind -
         *  when it's a real failure rather than the account genuinely having
         *  none. Surfaced in the browse screen instead of the tab just
         *  silently not existing, which is indistinguishable from "nothing
         *  wrong, this account has no VOD" and was reported as exactly that -
         *  a whole series catalogue the provider confirmed exists, with no
         *  way to tell from the app that the load had failed at all. */
        val sectionErrors: Map<ContentKind, String> = emptyMap(),
    ) {
        /**
         * Kinds that get a tab: either they have content, or (Xtream only)
         * they were queried at all - so a failed or empty VOD/series fetch
         * still gets a home to explain itself in, instead of vanishing.
         */
        val availableKinds: List<ContentKind>
            get() = ContentKind.entries.filter {
                sections[it]?.items?.isNotEmpty() == true || it in attemptedKinds
            }

        fun section(kind: ContentKind): Section? = sections[kind]

        val byCountry: List<Group> get() = sections[ContentKind.LIVE]?.byCountry.orEmpty()
        val byCategory: List<Group> get() = sections[ContentKind.LIVE]?.byCategory.orEmpty()
    }

    /**
     * [onUpdate] fires with an already-usable partial catalogue as soon as
     * each Xtream fetch phase finishes, tagged with the label of whatever is
     * loading next ("movies" once live is ready, "series" once movies are
     * ready). Live TV alone is typically the fast part of a 13,000+ channel
     * account; movies and series are the ones that can take minutes, so the
     * caller can put the user into a usable, already-navigable screen the
     * moment live lands instead of blocking on all three sequentially -
     * background sync, the same shape TiviMate and similar players use.
     * M3U sources never call this: a playlist is one parse, not three
     * fetches, so there is no intermediate state worth surfacing.
     */
    suspend fun load(
        source: TvSource,
        forceRefresh: Boolean = false,
        onUpdate: (label: String, partial: Catalogue) -> Unit = { _, _ -> },
    ): Result<Catalogue> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (source.kind) {
                    TvSource.Kind.M3U -> loadM3u(source, forceRefresh)
                    TvSource.Kind.XTREAM -> loadXtream(source, forceRefresh, onUpdate)
                }
            }
        }

    private fun cacheFile(sourceId: String) = File(cacheDir, "$sourceId.m3u")

    private fun loadM3u(source: TvSource, forceRefresh: Boolean): Catalogue {
        val cache = cacheFile(source.id)
        val fresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

        var fromCache = false
        val text = if (!forceRefresh && fresh) {
            fromCache = true; cache.readText()
        } else {
            try {
                download(source.location).also { cache.writeText(it) }
            } catch (t: Throwable) {
                // Network down but a stale copy exists - yesterday's channel list
                // beats an error screen.
                if (cache.exists()) { fromCache = true; cache.readText() } else throw t
            }
        }
        val parsed = M3uParser.parse(text, source.id)
        return index(source.id, parsed.channels, parsed.declaredEpgUrls, fromCache)
    }

    private fun loadXtream(source: TvSource, forceRefresh: Boolean, onUpdate: (String, Catalogue) -> Unit): Catalogue {
        // The one thing that made a large Xtream account feel unusable: every
        // single launch re-ran the full live+movies+series fetch from
        // scratch, the same multi-minute wait every time even though nothing
        // had changed since an hour ago. M3U sources already had a cache for
        // exactly this reason - Xtream never did. Same 30-hour window as M3U,
        // so "how fresh" isn't a second number to reason about.
        if (!forceRefresh) {
            freshXtreamCache(source.id)?.let { return it }
        }

        val creds = vault.get(source.id)
            ?: error("No saved credentials for ${source.name}. Remove and re-add it.")
        val client = XtreamClient(source.location, creds.first, creds.second)

        // Live is the one that must succeed. Plenty of accounts carry no VOD at
        // all, and a panel that 404s on get_vod_streams should not take the
        // whole source down with it - but the failure is captured rather than
        // just discarded, so an account whose panel genuinely does offer
        // movies/series but errored on this fetch can say so instead of
        // looking identical to an account with none.
        val account = try {
            client.authenticate()
        } catch (t: Throwable) {
            // Network or panel trouble reaching Xtream at all - yesterday's
            // catalogue (if one was cached) beats an error screen, the same
            // fallback the M3U path already uses for a dead playlist URL.
            // An account genuinely reported as inactive below is a real
            // status, not a network blip, so that one is deliberately not
            // covered by this fallback.
            parseXtreamCache(source.id)?.let { return it }
            throw t
        }
        if (!account.isActive) error("Provider reports this account as ${account.status}.")
        val live = try {
            client.liveChannels(source.id, onWarning = { onWarning("${source.name}: $it") })
        } catch (t: Throwable) {
            parseXtreamCache(source.id)?.let { return it }
            throw t
        }
        // Live is handed to the caller immediately - it is already a complete,
        // browsable catalogue on its own - rather than making the user wait
        // out movies and series too before anything appears.
        val liveCatalogue = index(source.id, live, emptyList(), fromCache = false)
        onUpdate("movies", liveCatalogue.copy(attemptedKinds = setOf(ContentKind.LIVE)))

        val moviesResult = runCatching {
            client.movies(source.id, onWarning = { onWarning("${source.name}: $it") })
        }
        val movies = moviesResult.getOrDefault(emptyList())
        // Only MOVIE is rebuilt here - LIVE's Section carries over by
        // reference from liveCatalogue instead of being re-walked and
        // re-grouped for data that has not changed since the update above.
        val moviesCatalogue = index(
            source.id, live + movies, emptyList(), fromCache = false,
            reuseSectionsFrom = liveCatalogue, recomputeOnly = setOf(ContentKind.MOVIE),
        )
        onUpdate(
            "series",
            moviesCatalogue.copy(
                attemptedKinds = setOf(ContentKind.LIVE, ContentKind.MOVIE),
                sectionErrors = buildMap {
                    moviesResult.exceptionOrNull()?.message?.let { put(ContentKind.MOVIE, it) }
                },
            ),
        )

        val seriesResult = runCatching {
            client.series(source.id, onWarning = { onWarning("${source.name}: $it") })
        }
        val series = seriesResult.getOrDefault(emptyList())
        // Same reuse here for LIVE and MOVIE - only SERIES is actually new.
        // This is also the step most likely to be memory-tight on a very
        // large account (the most channels to walk, right after two earlier
        // fetches are still warm in memory), so if it fails outright the
        // catalogue built two lines above - already fully indexed, not a
        // fresh rebuild - is used as the fallback rather than reindexing
        // live+movies a second time at the worst possible moment.
        val finalResult = runCatching {
            index(
                source.id, live + movies + series, emptyList(), fromCache = false,
                reuseSectionsFrom = moviesCatalogue, recomputeOnly = setOf(ContentKind.SERIES),
            )
        }
        val errors = buildMap {
            moviesResult.exceptionOrNull()?.message?.let { put(ContentKind.MOVIE, it) }
            seriesResult.exceptionOrNull()?.message?.let { put(ContentKind.SERIES, it) }
            finalResult.exceptionOrNull()?.let {
                put(ContentKind.SERIES, "Loaded ${series.size} shows but couldn't finish organizing them: ${it.message}")
            }
        }
        val result = finalResult.getOrElse { moviesCatalogue }.copy(
            attemptedKinds = setOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.SERIES),
            sectionErrors = errors,
        )
        writeXtreamCache(source.id, result)
        return result
    }

    private fun xtreamCacheFile(sourceId: String) = File(cacheDir, "$sourceId.xtream.json")

    /** Only a cache within CACHE_MAX_AGE_MS counts here - the normal "use it
     *  instead of a live fetch" path. See parseXtreamCache for the separate
     *  any-age fallback used when the live fetch itself fails. */
    private fun freshXtreamCache(sourceId: String): Catalogue? {
        val file = xtreamCacheFile(sourceId)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() >= CACHE_MAX_AGE_MS) return null
        return parseXtreamCache(sourceId)
    }

    /**
     * Streamed with android.util.JsonReader rather than read into a single
     * String and parsed as an org.json tree - the same reasoning as
     * XtreamClient's own streamArray: a large catalogue means tens of
     * thousands of channels, and materialising a full JSONObject/JSONArray
     * node per channel on top of the Channel list already being built is
     * exactly the kind of allocation burst that starves the garbage
     * collector. This was traced as the direct cause of a
     * FinalizerWatchdogDaemon crash (GC falling more than 10 seconds behind)
     * after the previous, tree-based version of this cache shipped.
     */
    private fun parseXtreamCache(sourceId: String): Catalogue? {
        val file = xtreamCacheFile(sourceId)
        if (!file.exists()) return null
        return runCatching {
            val channels = ArrayList<Channel>()
            var declaredEpgUrls: List<String> = emptyList()
            val sectionErrors = HashMap<ContentKind, String>()
            JsonReader(file.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "declaredEpgUrls" -> {
                            val list = ArrayList<String>()
                            r.beginArray()
                            while (r.hasNext()) list.add(r.nextString())
                            r.endArray()
                            declaredEpgUrls = list
                        }
                        "sectionErrors" -> {
                            r.beginObject()
                            while (r.hasNext()) {
                                val k = r.nextName()
                                val v = r.nextString()
                                runCatching { ContentKind.valueOf(k) }.getOrNull()?.let { sectionErrors[it] = v }
                            }
                            r.endObject()
                        }
                        "channels" -> {
                            r.beginArray()
                            while (r.hasNext()) readCachedChannel(r)?.let { channels.add(it) }
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            }
            index(sourceId, channels, declaredEpgUrls, fromCache = true).copy(
                attemptedKinds = setOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.SERIES),
                sectionErrors = sectionErrors,
            )
        }.getOrNull()
    }

    /** Best-effort: a failure here (disk full, an interrupted write) just
     *  means the next load pays the full network cost again, not a crash
     *  and not a discarded catalogue that already loaded fine in memory.
     *  Streamed straight to disk with JsonWriter for the same reason
     *  [parseXtreamCache] streams it back in - see that comment. */
    private fun writeXtreamCache(sourceId: String, catalogue: Catalogue) {
        val tmp = File(cacheDir, "$sourceId.xtream.json.tmp")
        val wrote = runCatching {
            JsonWriter(tmp.bufferedWriter()).use { w ->
                w.beginObject()
                w.name("written").value(System.currentTimeMillis())
                w.name("declaredEpgUrls")
                w.beginArray()
                catalogue.declaredEpgUrls.forEach { w.value(it) }
                w.endArray()
                w.name("sectionErrors")
                w.beginObject()
                catalogue.sectionErrors.forEach { (kind, msg) -> w.name(kind.name).value(msg) }
                w.endObject()
                w.name("channels")
                w.beginArray()
                catalogue.channels.forEach { writeCachedChannel(w, it) }
                w.endArray()
                w.endObject()
            }
        }.isSuccess
        // Renamed into place rather than written directly to the real cache
        // file - if the process dies mid-write (the exact failure mode this
        // rewrite exists to avoid, but belt and braces), the next launch
        // finds either the old complete file or nothing, never a
        // half-written one that fails to parse. A failed write leaves only
        // the .tmp file behind, cleaned up here rather than left to litter
        // the cache directory (clearCache() also sweeps for it separately).
        if (wrote && tmp.renameTo(xtreamCacheFile(sourceId))) return
        tmp.delete()
    }

    private fun writeCachedChannel(w: JsonWriter, c: Channel) {
        w.beginObject()
        w.name("id").value(c.id)
        w.name("sourceId").value(c.sourceId)
        w.name("name").value(c.name)
        w.name("number"); if (c.number == null) w.nullValue() else w.value(c.number)
        w.name("logoUrl"); if (c.logoUrl == null) w.nullValue() else w.value(c.logoUrl)
        w.name("group"); if (c.group == null) w.nullValue() else w.value(c.group)
        w.name("countryCode"); if (c.countryCode == null) w.nullValue() else w.value(c.countryCode)
        w.name("epgChannelId"); if (c.epgChannelId == null) w.nullValue() else w.value(c.epgChannelId)
        w.name("kind").value(c.kind.name)
        w.name("seriesId"); if (c.seriesId == null) w.nullValue() else w.value(c.seriesId)
        w.name("categories")
        w.beginArray()
        c.categories.forEach { w.value(it) }
        w.endArray()
        w.name("streams")
        w.beginArray()
        c.streams.forEach { s ->
            w.beginObject()
            w.name("url").value(s.url)
            w.name("priority").value(s.priority)
            w.name("headers")
            w.beginObject()
            s.headers.forEach { (k, v) -> w.name(k).value(v) }
            w.endObject()
            w.endObject()
        }
        w.endArray()
        w.endObject()
    }

    /** Mirror of [writeCachedChannel]. Any one malformed channel object
     *  returns null and is skipped rather than aborting the whole cache
     *  read - a lone bad entry shouldn't force a full network reload. */
    private fun readCachedChannel(r: JsonReader): Channel? = runCatching {
        var id: String? = null
        var srcId: String? = null
        var name: String? = null
        var number: Int? = null
        var logoUrl: String? = null
        var group: String? = null
        var countryCode: String? = null
        var epgChannelId: String? = null
        var kind = ContentKind.LIVE
        var seriesId: String? = null
        val categories = ArrayList<String>()
        val streams = ArrayList<StreamRef>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextString()
                "sourceId" -> srcId = r.nextString()
                "name" -> name = r.nextString()
                "number" -> number = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextInt()
                "logoUrl" -> logoUrl = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                "group" -> group = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                "countryCode" -> countryCode = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                "epgChannelId" -> epgChannelId = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                "kind" -> kind = runCatching { ContentKind.valueOf(r.nextString()) }.getOrDefault(ContentKind.LIVE)
                "seriesId" -> seriesId = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextString()
                "categories" -> {
                    r.beginArray()
                    while (r.hasNext()) categories.add(r.nextString())
                    r.endArray()
                }
                "streams" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var url: String? = null
                        var priority = 0
                        val headers = HashMap<String, String>()
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "url" -> url = r.nextString()
                                "priority" -> priority = r.nextInt()
                                "headers" -> {
                                    r.beginObject()
                                    while (r.hasNext()) headers[r.nextName()] = r.nextString()
                                    r.endObject()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (url != null) streams.add(StreamRef(url, priority, headers))
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        Channel(
            id = id ?: return@runCatching null,
            sourceId = srcId ?: return@runCatching null,
            name = name ?: return@runCatching null,
            number = number,
            logoUrl = logoUrl,
            group = group,
            countryCode = countryCode,
            epgChannelId = epgChannelId,
            streams = streams,
            kind = kind,
            categories = categories,
            seriesId = seriesId,
        )
    }.getOrNull()

    /** Episodes for a series, fetched on demand rather than up front. */
    suspend fun episodes(source: TvSource, seriesId: String): Result<List<Channel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val creds = vault.get(source.id) ?: error("No saved credentials.")
                XtreamClient(source.location, creds.first, creds.second)
                    .episodes(source.id, seriesId)
            }
        }

    /**
     * [reuseSectionsFrom] + [recomputeOnly] let an Xtream load, which calls
     * this once per phase (live, then live+movies, then live+movies+series)
     * over an ever-growing combined list, avoid rebuilding every kind's
     * Section from scratch on every single phase. Building a Section walks
     * and re-groups every channel of that kind into fresh HashMap/ArrayList
     * scaffolding - on a large account that is real, repeated allocation
     * pressure at exactly the moment memory is already most full from the
     * fetch that just landed, and it used to happen 3-4x per load for kinds
     * (LIVE especially) that were not what actually changed that phase. When
     * set, only the kinds in [recomputeOnly] are rebuilt; every other kind's
     * Section is carried over by reference from [reuseSectionsFrom] instead
     * of being reallocated for data that has not changed.
     */
    private fun index(
        sourceId: String,
        channels: List<Channel>,
        declaredEpgUrls: List<String>,
        fromCache: Boolean,
        reuseSectionsFrom: Catalogue? = null,
        recomputeOnly: Set<ContentKind>? = null,
    ): Catalogue {
        // Rails are ordered by channel count, not alphabetically. The
        // distribution is steeply long-tailed and on a remote the rail you want
        // must be reachable without paging.
        fun build(by: Map<String?, List<Channel>>, label: (String?) -> String) =
            by.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String?, List<Channel>>> { it.key != null }
                        .thenByDescending { it.value.size }
                )
                .map { Group(it.key, label(it.key), it.value) }

        fun sectionFor(kind: ContentKind): Section {
            // Distinct by id: LazyColumn keys on it, and a repeated key is a
            // hard crash rather than a visual glitch. Large providers do ship
            // the same stream under two categories.
            val items = channels.filter { it.kind == kind }.distinctBy { it.id }
            // An item with several categories appears under each of them; a
            // "Documentary;Series" entry belongs in both lists, not one.
            val byCat = HashMap<String?, MutableList<Channel>>()
            for (c in items) {
                val keys = c.categories.ifEmpty { listOfNotNull(c.group) }.ifEmpty { listOf(null) }
                for (k in keys.distinct()) byCat.getOrPut(k) { ArrayList() }.add(c)
            }
            return Section(
                kind = kind,
                items = items,
                byCountry = build(items.groupBy { it.countryCode }.mapValues { e -> e.value.distinctBy { it.id } }) {
                    if (it == null) "Ungrouped" else Country.label(it)
                },
                byCategory = build(byCat.mapValues { e -> e.value.distinctBy { it.id } }) {
                    it ?: "Uncategorised"
                },
            )
        }

        val sections = ContentKind.entries.associateWith { kind ->
            val reused = reuseSectionsFrom?.sections?.get(kind)
            if (reused != null && recomputeOnly != null && kind !in recomputeOnly) reused else sectionFor(kind)
        }
        return Catalogue(sourceId, channels, sections, declaredEpgUrls, fromCache)
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "TapperIPTV/0.3")
        }
        try {
            if (conn.responseCode !in 200..299) error("Playlist fetch failed: HTTP ${conn.responseCode}")
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true)
                GZIPInputStream(conn.inputStream) else conn.inputStream
            return stream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Forces a fresh download on next load; saved sources are untouched. */
    fun clearCache() {
        runCatching {
            cacheDir.listFiles()?.forEach {
                if (it.name.endsWith(".m3u") || it.name.endsWith(".xtream.json") ||
                    it.name.endsWith(".xtream.json.tmp")
                ) it.delete()
            }
        }
    }

    fun countryLabel(code: String?): String =
        if (code == null) "Ungrouped" else Country.label(code)

    /** Categories present within one country, for the secondary filter row. */
    fun categoriesIn(channels: List<Channel>): List<String> =
        channels.mapNotNull { it.group?.takeIf { g -> g.isNotBlank() } }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
}
