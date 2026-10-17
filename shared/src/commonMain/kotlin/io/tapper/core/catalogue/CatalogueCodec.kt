package io.tapper.core.catalogue

import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef
import io.tapper.core.xtream.multiplatform.XtreamAccount

/**
 * One channel (or the account) as a single line of text, and back.
 *
 * Used by the iPad to keep a copy of a provider's whole library on disk, so
 * opening the app doesn't download and parse tens of thousands of items again.
 * The Swift side only moves strings: it hands each Channel here to be turned
 * into a line, writes the lines to a file, and later hands the lines back to
 * get Channels. It never builds a Channel itself, which keeps the fiddly
 * Kotlin/Swift type mapping out of the picture.
 *
 * A plain delimiter format rather than JSON: reading a hundred thousand of
 * these is a handful of string splits each, with no tree built per item, and
 * a line can be read and dropped one at a time so the whole file is never in
 * memory at once.
 *
 * Fields are separated by control characters that don't occur in names or
 * addresses; any that do are replaced with a space on the way out. An empty
 * optional field reads back as null.
 */
object CatalogueCodec {
    private const val FIELD = '\u001F'   // between the fields of a channel
    private const val ITEM = '\u001E'    // between categories, or between streams
    private const val PART = '\u001D'    // between the parts of one stream
    private const val PAIR = '\u001C'    // between a header's name and value
    private const val HDR = '\u001B'     // between headers of one stream

    private const val FIELD_COUNT = 12

    private fun isSeparator(c: Char): Boolean =
        c == FIELD || c == ITEM || c == PART || c == PAIR || c == HDR || c == '\n' || c == '\r'

    private fun clean(s: String): String {
        var dirty = false
        for (c in s) {
            if (isSeparator(c)) { dirty = true; break }
        }
        if (!dirty) return s
        val out = StringBuilder(s.length)
        for (c in s) out.append(if (isSeparator(c)) ' ' else c)
        return out.toString()
    }

    fun encode(channel: Channel): String {
        val sb = StringBuilder(192)
        sb.append(clean(channel.id)).append(FIELD)
        sb.append(clean(channel.sourceId)).append(FIELD)
        sb.append(clean(channel.name)).append(FIELD)
        sb.append(channel.number?.toString() ?: "").append(FIELD)
        sb.append(clean(channel.logoUrl ?: "")).append(FIELD)
        sb.append(clean(channel.group ?: "")).append(FIELD)
        sb.append(clean(channel.countryCode ?: "")).append(FIELD)
        sb.append(clean(channel.epgChannelId ?: "")).append(FIELD)
        sb.append(channel.kind.name).append(FIELD)
        sb.append(clean(channel.seriesId ?: "")).append(FIELD)
        var first = true
        for (category in channel.categories) {
            if (!first) sb.append(ITEM)
            sb.append(clean(category))
            first = false
        }
        sb.append(FIELD)
        first = true
        for (stream in channel.streams) {
            if (!first) sb.append(ITEM)
            sb.append(clean(stream.url)).append(PART).append(stream.priority).append(PART)
            var firstHeader = true
            for ((name, value) in stream.headers) {
                if (!firstHeader) sb.append(HDR)
                sb.append(clean(name)).append(PAIR).append(clean(value))
                firstHeader = false
            }
            first = false
        }
        return sb.toString()
    }

    /** Null when the line isn't one this encoder wrote. */
    fun decode(line: String): Channel? {
        val f = line.split(FIELD)
        if (f.size < FIELD_COUNT) return null
        val id = f[0]
        val name = f[2]
        if (id.isEmpty()) return null

        val kind = runCatching { ContentKind.valueOf(f[8]) }.getOrDefault(ContentKind.LIVE)
        val categories = if (f[10].isEmpty()) emptyList() else f[10].split(ITEM)

        val streams = ArrayList<StreamRef>()
        if (f[11].isNotEmpty()) {
            for (item in f[11].split(ITEM)) {
                val parts = item.split(PART)
                val url = parts[0]
                if (url.isEmpty()) continue
                val priority = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val headers = HashMap<String, String>()
                val rawHeaders = parts.getOrNull(2).orEmpty()
                if (rawHeaders.isNotEmpty()) {
                    for (pair in rawHeaders.split(HDR)) {
                        val kv = pair.split(PAIR)
                        if (kv.size >= 2 && kv[0].isNotEmpty()) headers[kv[0]] = kv[1]
                    }
                }
                streams.add(StreamRef(url = url, priority = priority, headers = headers))
            }
        }

        return Channel(
            id = id,
            sourceId = f[1],
            name = name,
            number = f[3].toIntOrNull(),
            logoUrl = f[4].ifEmpty { null },
            group = f[5].ifEmpty { null },
            countryCode = f[6].ifEmpty { null },
            epgChannelId = f[7].ifEmpty { null },
            streams = streams,
            kind = kind,
            categories = categories,
            seriesId = f[9].ifEmpty { null },
        )
    }

    fun encodeAccount(account: XtreamAccount): String =
        listOf(
            clean(account.username),
            clean(account.status),
            account.expiresUtc?.toString() ?: "",
            account.maxConnections.toString(),
            account.activeConnections.toString(),
            if (account.trial) "1" else "0",
        ).joinToString(FIELD.toString())

    fun decodeAccount(text: String): XtreamAccount? {
        val f = text.split(FIELD)
        if (f.size < 6) return null
        return XtreamAccount(
            username = f[0],
            status = f[1],
            expiresUtc = f[2].toLongOrNull(),
            maxConnections = f[3].toIntOrNull() ?: 1,
            activeConnections = f[4].toIntOrNull() ?: 0,
            trial = f[5] == "1",
        )
    }
}
