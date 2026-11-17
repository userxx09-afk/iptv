package io.tapper.core.catchup

import io.tapper.core.model.Catchup
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef

/**
 * Catch-up (archive) playback: turning a live channel plus a guide entry into
 * an address that plays that programme from its start.
 *
 * Two ways a provider says a channel has an archive:
 *  - Xtream: `tv_archive: 1` and `tv_archive_duration: <days>` on the live
 *    stream, played from `/timeshift/USER/PASS/MINUTES/YYYY-MM-DD:HH-MM/ID.ts`.
 *    See [xtream].
 *  - M3U: `catchup="..."`, `catchup-source="..."`, `catchup-days="..."` on the
 *    #EXTINF line (or as defaults on #EXTM3U). See [fromM3u].
 *
 * Both end up as one [Catchup] template on the channel, so the apps only ever
 * call [url] / [archiveChannel] and never care which kind it was.
 */
object CatchupUrl {

    /** Room at the end of a programme, since guides and archives drift a little. */
    private const val END_PADDING_SECONDS = 5 * 60L

    /**
     * Xtream's timeshift address, for a channel whose live address is
     * `.../live/USER/PASS/ID.ext`. [serverOffsetMinutes] is how far the
     * panel's clock is ahead of UTC (see [serverOffsetMinutes]).
     */
    fun xtream(base: String, user: String, pass: String, streamId: String, days: Int, serverOffsetMinutes: Int): Catchup =
        Catchup(
            template = "$base/timeshift/$user/$pass/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$streamId.ts",
            days = days.coerceAtLeast(1),
            offsetMinutes = serverOffsetMinutes,
        )

    /**
     * The panel's clock offset from UTC, read from player_api.php's
     * server_info: `time_now` is its local wall clock ("2026-11-17 06:12:44")
     * and `timestamp_now` the same moment in Unix seconds. Rounded to the
     * nearest quarter hour so a few seconds of request latency never shift a
     * programme by a minute. 0 when either is missing.
     */
    fun serverOffsetMinutes(timeNow: String?, timestampNow: Long?): Int {
        if (timeNow == null || timestampNow == null) return 0
        val d = timeNow.filter { it.isDigit() }
        if (d.length < 12) return 0
        val local = runCatching {
            io.tapper.core.epg.XmltvTime.toEpochMillisUtc(
                d.substring(0, 4).toInt(), d.substring(4, 6).toInt(), d.substring(6, 8).toInt(),
                d.substring(8, 10).toInt(), d.substring(10, 12).toInt(),
                if (d.length >= 14) d.substring(12, 14).toInt() else 0,
            ) / 1000L
        }.getOrNull() ?: return 0
        val minutes = (local - timestampNow) / 60.0
        val quarter = kotlin.math.round(minutes / 15.0).toInt() * 15
        return quarter.coerceIn(-14 * 60, 14 * 60)
    }

    /**
     * Reads the M3U catch-up attributes for one channel. [attrs] holds the
     * entry's own attributes; [defaults] the #EXTM3U line's, which some
     * providers use to declare catch-up once for the whole playlist.
     */
    fun fromM3u(liveUrl: String, attrs: Map<String, String>, defaults: Map<String, String>): Catchup? {
        fun pick(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> attrs[k]?.takeIf { it.isNotBlank() } }
                ?: keys.firstNotNullOfOrNull { k -> defaults[k]?.takeIf { it.isNotBlank() } }

        val mode = pick("catchup", "catchup-type")?.trim()?.lowercase()
        val source = pick("catchup-source")
        val days = pick("catchup-days", "tvg-rec", "timeshift")?.trim()?.toIntOrNull()
        if (mode == null && source == null && (days == null || days <= 0)) return null
        val keep = (days ?: 7).coerceAtLeast(1)

        val template = when (mode) {
            "append" -> source?.let { liveUrl + it }
            "shift", "timeshift" -> withQuery(liveUrl, "utc={utc}&lutc={lutc}")
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl)
            "xc", "xtream", "xstream", "xstream-1" -> xtreamFromLiveUrl(liveUrl)
            "vod" -> source
            else -> when {
                // "default" (or no mode): the source is the whole address, or
                // a query/fragment to add to the live one.
                source == null -> xtreamFromLiveUrl(liveUrl) ?: withQuery(liveUrl, "utc={utc}&lutc={lutc}")
                source.startsWith("?") || source.startsWith("&") -> withQuery(liveUrl, source.drop(1))
                source.startsWith("http") -> source
                else -> liveUrl + source
            }
        } ?: return null
        return Catchup(template = template, days = keep)
    }

    /** True when [startUtcMs] is still within the channel's archive, and already started. */
    fun canPlay(channel: Channel, startUtcMs: Long, nowUtcMs: Long): Boolean {
        val c = channel.catchup ?: return false
        if (startUtcMs >= nowUtcMs) return false
        return nowUtcMs - startUtcMs <= c.days * 86_400_000L
    }

    /**
     * The archive address for the programme from [startUtcMs] to [endUtcMs],
     * or null when the channel has no archive or the programme is outside it.
     * A programme still airing is asked for up to now, which is as far as the
     * archive reaches.
     */
    fun url(channel: Channel, startUtcMs: Long, endUtcMs: Long, nowUtcMs: Long): String? {
        val c = channel.catchup ?: return null
        if (!canPlay(channel, startUtcMs, nowUtcMs)) return null
        val start = startUtcMs / 1000L
        val now = nowUtcMs / 1000L
        val end = (endUtcMs / 1000L + END_PADDING_SECONDS).coerceAtMost(maxOf(now, start + 60))
        return expand(c.template, start, end, now, c.offsetMinutes)
    }

    /**
     * A playable stand-in for the programme: an on-demand item (so the player
     * offers pause and seeking) that carries the channel's stream headers.
     * Swift calls this rather than building a Channel itself.
     */
    fun archiveChannel(channel: Channel, title: String, startUtcMs: Long, endUtcMs: Long, nowUtcMs: Long): Channel? {
        val address = url(channel, startUtcMs, endUtcMs, nowUtcMs) ?: return null
        val headers = channel.streams.firstOrNull()?.headers.orEmpty()
        return Channel(
            id = "catchup-${channel.sourceId}-${channel.id}-$startUtcMs",
            sourceId = channel.sourceId,
            name = if (title.isBlank()) channel.name else "$title · ${channel.name}",
            number = null,
            logoUrl = channel.logoUrl,
            group = channel.group,
            countryCode = channel.countryCode,
            epgChannelId = channel.epgChannelId,
            streams = listOf(StreamRef(address, 0, headers)),
            kind = ContentKind.MOVIE,
            categories = channel.categories,
        )
    }

    /** Fills a template's placeholders; public so it can be tested on its own. */
    fun expand(template: String, startSec: Long, endSec: Long, nowSec: Long, offsetMinutes: Int): String {
        val duration = (endSec - startSec).coerceAtLeast(60)
        val local = civil(startSec + offsetMinutes * 60L)
        var out = template
            .replace("\${start}", startSec.toString()).replace("{start}", startSec.toString())
            .replace("{utcend}", endSec.toString()).replace("\${end}", endSec.toString())
            .replace("{end}", endSec.toString())
            .replace("{utc}", startSec.toString())
            .replace("{lutc}", nowSec.toString()).replace("\${now}", nowSec.toString())
            .replace("\${timestamp}", nowSec.toString()).replace("{timestamp}", nowSec.toString())
            .replace("\${duration}", duration.toString())
            .replace("\${offset}", (nowSec - startSec).toString())
            .replace("{Y}", local[0].pad(4)).replace("{m}", local[1].pad(2))
            .replace("{d}", local[2].pad(2)).replace("{H}", local[3].pad(2))
            .replace("{M}", local[4].pad(2)).replace("{S}", local[5].pad(2))
        // {duration} and {duration:N}, {offset:N}
        out = Regex("""\{(duration|offset)(?::(\d+))?\}""").replace(out) { m ->
            val base = if (m.groupValues[1] == "duration") duration else (nowSec - startSec)
            val div = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: 1L
            (base / div).toString()
        }
        return out
    }

    private fun Int.pad(n: Int) = toString().padStart(n, '0')

    /** Unix seconds to [year, month, day, hour, minute, second] (UTC). */
    private fun civil(sec: Long): IntArray {
        // Floor division by hand: java.lang.Math isn't available on iOS.
        var days = sec / 86_400L
        var rem = sec % 86_400L
        if (rem < 0) { rem += 86_400L; days -= 1 }
        val z = days + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return intArrayOf(y, m, d, (rem / 3600).toInt(), ((rem % 3600) / 60).toInt(), (rem % 60).toInt())
    }

    private fun withQuery(url: String, query: String): String =
        url + (if ('?' in url) "&" else "?") + query

    /** `.../index.m3u8` -> `.../index-{utc}-{duration}.m3u8`; `.../mpegts` -> `.../timeshift_abs-{utc}.ts`. */
    private fun flussonic(url: String): String? {
        val q = url.substringAfter('?', "")
        val path = url.substringBefore('?')
        val dir = path.substringBeforeLast('/')
        val file = path.substringAfterLast('/')
        val rebuilt = when {
            file.endsWith(".m3u8") -> "$dir/${file.removeSuffix(".m3u8")}-{utc}-{duration}.m3u8"
            file == "mpegts" || file.endsWith(".ts") -> "$dir/timeshift_abs-{utc}.ts"
            else -> return null
        }
        return if (q.isEmpty()) rebuilt else "$rebuilt?$q"
    }

    /**
     * An Xtream-style live address, `http://host/live/USER/PASS/ID.ext` or
     * `http://host/USER/PASS/ID`, turned into that panel's timeshift address.
     * Null when the address isn't that shape.
     */
    private fun xtreamFromLiveUrl(url: String): String? {
        val m = Regex("""^(https?://[^/]+(?:/[^/]+)*?)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.(\w+))?$""")
            .find(url.substringBefore('?')) ?: return null
        val (base, user, pass, id) = m.destructured
        if (user == "timeshift" || base.endsWith("/movie") || base.endsWith("/series")) return null
        return "$base/timeshift/$user/$pass/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$id.ts"
    }
}
