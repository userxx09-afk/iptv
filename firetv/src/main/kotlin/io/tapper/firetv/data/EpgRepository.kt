package io.tapper.firetv.data

import android.util.Xml
import io.tapper.core.epg.XmltvTime
import io.tapper.core.xtream.XtreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Downloads and stores XMLTV guides.
 *
 * The guide a playlist declares is not trustworthy. The default iptv-org
 * playlist names two XMLTV URLs; the first returns 404 and the second is a
 * free-tier worker. iptv-org deliberately does not host a guide at all. An
 * Xtream account, by contrast, serves its own guide matched to exactly the
 * channels it carries - which is why guide data only became worth building
 * once user-supplied sources existed.
 */
class EpgRepository(
    private val db: EpgDatabase,
    private val vault: CredentialVault,
    /** User-configured guides layered on top of whatever a source's own EPG
     *  provides - Marquee Sports Network ships as the built-in default (see
     *  SupplementalEpgStore), with room for the next channel in the same
     *  situation to be added from Settings instead of needing a rebuild. */
    private val supplemental: SupplementalEpgStore,
) {

    companion object {
        private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
        /** Keep a little history and three days ahead; the rest is dead weight. */
        private const val KEEP_BEFORE_MS = 6 * 60 * 60 * 1000L
        private const val KEEP_AFTER_MS = 72 * 60 * 60 * 1000L
        private const val MAX_ROWS = 400_000
    }

    data class Result(val programmes: Int, val channels: Int, val source: String)

    /** Reported while a large guide is still downloading and inserting. */
    fun interface Progress { fun onCount(rows: Int) }

    fun isStale(sourceId: String): Boolean =
        System.currentTimeMillis() - db.fetchedAt(sourceId) > STALE_AFTER_MS

    fun hasData(sourceId: String): Boolean = db.countFor(sourceId) > 0

    /**
     * Resolves which URL to use, in priority order: an explicit override, then
     * the provider's own guide, then whatever the playlist declared.
     */
    fun guideUrls(source: TvSource, declared: List<String>): List<String> = buildList {
        source.epgUrlOverride?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (source.kind == TvSource.Kind.XTREAM) {
            vault.get(source.id)?.let { (u, p) ->
                add(XtreamClient(source.location, u, p).epgUrl())
            }
        }
        addAll(declared)
    }

    suspend fun refresh(
        source: TvSource,
        declared: List<String>,
        progress: Progress = Progress {},
    ): kotlin.Result<Result> =
        withContext(Dispatchers.IO) {
            // Background priority: the import is long and must never out-compete
            // playback or the UI for CPU on a low-powered stick.
            runCatching {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            }
            val urls = guideUrls(source, declared)
            val primaryResult: kotlin.Result<Result> = if (urls.isEmpty()) {
                kotlin.Result.failure(
                    IllegalStateException("No guide URL for ${source.name}. Add one in the source settings.")
                )
            } else {
                var lastError: Throwable? = null
                var success: Result? = null
                // Try each candidate: dead guide URLs are the norm, not the exception.
                for (url in urls) {
                    try {
                        // Channel ids are counted in a set as rows stream past, so
                        // coverage can still be reported without keeping the rows.
                        val channelIds = HashSet<String>()
                        val written = open(url).use { input ->
                            db.replaceStreaming(
                                sourceId = source.id,
                                onProgress = { progress.onCount(it) },
                            ) { emit ->
                                parseStreaming(input) { p ->
                                    channelIds.add(p.channelId)
                                    emit(p)
                                }
                            }
                        }
                        if (written == 0) {
                            lastError = IllegalStateException("Guide at $url contained no programmes.")
                            continue
                        }
                        db.prune(System.currentTimeMillis() - KEEP_BEFORE_MS)
                        success = Result(written, channelIds.size, url)
                        break
                    } catch (t: Throwable) {
                        lastError = t
                    }
                }
                success?.let { kotlin.Result.success(it) }
                    ?: kotlin.Result.failure(lastError ?: IllegalStateException("Guide download failed."))
            }

            // Always attempted, success or failure above: this source's own
            // guide (or lack of one right now) says nothing about whether
            // Marquee Sports Network's supplemental listings are fresh. Never
            // allowed to turn a working refresh into a failed one, or a failed
            // one into a misleadingly successful one - it only ever adds rows
            // under ids nothing else writes to.
            runCatching { mergeSupplemental(source.id) }

            primaryResult
        }

    /**
     * Fetches every configured supplemental guide and writes in only the
     * channel ids each one declares, via [EpgDatabase.upsert] rather than a
     * replace, so a source's primary guide (fetched above) is never touched
     * by this.
     */
    private fun mergeSupplemental(sourceId: String) {
        val entries = supplemental.all()
        if (entries.isEmpty()) return
        // Grouped by guide URL: two entries can point at the same guide file
        // for different channel ids, and that guide should only be
        // downloaded once for this refresh, not once per entry.
        for ((url, group) in entries.groupBy { it.guideUrl }) {
            val wanted = group.flatMap { it.channelIds }.map(EpgDatabase::normalizeId).toSet()
            if (url.isBlank() || wanted.isEmpty()) continue
            val rows = ArrayList<EpgDatabase.Programme>()
            runCatching {
                open(url).use { input ->
                    parseStreaming(input) { p ->
                        if (EpgDatabase.normalizeId(p.channelId) in wanted) rows.add(p)
                    }
                }
            }
            db.upsert(sourceId, rows)
        }
    }

    private fun open(url: String): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TapperIPTV/0.3")
        }
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            error("HTTP ${conn.responseCode}")
        }
        // Sniff for gzip rather than trusting the extension or Content-Encoding.
        // Guides are commonly served as .xml.gz with no encoding header, and
        // equally often as plain .xml behind a .gz filename.
        val push = PushbackInputStream(BufferedInputStream(conn.inputStream), 2)
        val b1 = push.read()
        val b2 = push.read()
        if (b2 != -1) push.unread(b2)
        if (b1 != -1) push.unread(b1)
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(push) else push
    }

    /**
     * Streaming pull parse. Never materialises the document: a real guide is
     * 100-200MB of XML and several hundred thousand programme elements.
     */
    private fun parseStreaming(input: InputStream, emit: (EpgDatabase.Programme) -> Unit) {
        val now = System.currentTimeMillis()
        val floor = now - KEEP_BEFORE_MS
        val ceiling = now + KEEP_AFTER_MS

        var emitted = 0
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channelId: String? = null
        var start: Long? = null
        var stop: Long? = null
        var title: String? = null
        var descr: String? = null
        var inTitle = false
        var inDesc = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && emitted < MAX_ROWS) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channelId = parser.getAttributeValue(null, "channel")
                        start = parser.getAttributeValue(null, "start")?.let(XmltvTime::parse)
                        stop = parser.getAttributeValue(null, "stop")?.let(XmltvTime::parse)
                        title = null; descr = null
                    }
                    "title" -> inTitle = true
                    "desc" -> inDesc = true
                }

                XmlPullParser.TEXT -> {
                    // Multi-language guides repeat title/desc per language; keep the first.
                    if (inTitle && title == null) title = parser.text?.trim()
                    if (inDesc && descr == null) descr = parser.text?.trim()
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "title" -> inTitle = false
                    "desc" -> inDesc = false
                    "programme" -> {
                        val ch = channelId; val s = start; val e = stop
                        // Drop anything that cannot be placed on a timeline. A
                        // wrong guide slot is worse than a gap.
                        if (ch != null && s != null && e != null && e > s && e > floor && s < ceiling) {
                            emit(
                                EpgDatabase.Programme(
                                    channelId = ch,
                                    startUtc = s,
                                    endUtc = e,
                                    title = title?.takeIf { it.isNotBlank() } ?: "No information",
                                    description = descr?.takeIf { it.isNotBlank() },
                                )
                            )
                            emitted++
                        }
                        channelId = null; start = null; stop = null
                    }
                }
            }
            event = parser.next()
        }
    }
}
