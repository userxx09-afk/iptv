package io.tapper.core.xtream.multiplatform

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.tapper.core.model.CategoryName
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef
import io.tapper.core.net.tapperHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Xtream Codes panel client - the commonMain/Ktor counterpart to Fire TV's
 * original androidMain version (HttpURLConnection + org.json/android.util
 * JsonReader, both Android-only). Ported for live channels, movies, series
 * and episodes, matching that version's behaviour and field-leniency rules.
 *
 * Two deliberate differences from the Fire TV original, both worth knowing
 * about rather than discovering by surprise:
 *
 * 1. Not streaming. The Fire TV version reads get_live_streams/
 *    get_vod_streams/get_series with a hand-rolled streaming JsonReader
 *    specifically because one real account's get_series response measured
 *    over 100MB, and slurping that into one String plus a full JSON tree on
 *    top of it is two simultaneous in-memory copies of a 100MB+ document - on
 *    a Fire TV Stick, that is what an OutOfMemoryError on "Couldn't load
 *    shows" turned out to be. kotlinx.serialization's multiplatform streaming
 *    story (decodeToSequence) is JVM/InputStream-only, not available on
 *    iOS/Native, so this version uses the same whole-body-then-JsonElement-
 *    tree approach [TmdbClient] uses instead. An iPad has far more memory
 *    headroom than a Fire TV Stick, so this is a reasonable trade for now -
 *    but a 100MB+ catalogue on this path is still a real risk worth
 *    revisiting with a genuine multiplatform streaming parser if it turns
 *    out to matter in practice.
 *
 * 2. Simpler network-error messages. The original distinguishes DNS
 *    failures, timeouts, refused connections and TLS problems using
 *    java.net/javax.net.ssl exception types that only exist on the JVM.
 *    Those don't exist on Kotlin/Native, and there's no compiler available
 *    in this sandbox to verify which Darwin-side exception types Ktor
 *    actually surfaces for each case - so this version reports one generic
 *    "couldn't reach the server" message with whatever detail the
 *    underlying exception provides, rather than guessing at a platform-
 *    specific exception hierarchy that can't be checked. Worth tightening
 *    once this has run against real failures on a device.
 */
class XtreamClient(
    private val host: String,
    private val username: String,
    private val password: String,
) {
    class XtreamException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Normalises what people actually paste. Providers hand out addresses in
     * every shape: with a trailing slash, with /c or /player_api.php already
     * appended, occasionally with the whole get.php playlist query. Stripping
     * that back to scheme+host+port avoids a 404 that looks like a bad password.
     */
    private val base = host.trim().trimEnd('/')
        .let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        .let { raw ->
            // Strip only the known API entry points a provider might have
            // appended. A blanket strip to scheme+host+port would break panels
            // genuinely hosted under a path prefix, which do exist.
            var r = raw.substringBefore('?').trimEnd('/')
            for (suffix in listOf("/player_api.php", "/panel_api.php", "/get.php", "/xmltv.php", "/c", "/index.php")) {
                if (r.endsWith(suffix, ignoreCase = true)) { r = r.dropLast(suffix.length); break }
            }
            r.trimEnd('/')
        }

    // spaceToPlus=true matches java.net.URLEncoder.encode()'s behaviour,
    // which the Fire TV original used verbatim for this same enc() helper -
    // keeping the same encoding here (even in the liveUrl/vodUrl/episodeUrl
    // path segments below, not just the query string) means a username or
    // password already in use on Fire TV encodes identically on iPad.
    private fun enc(s: String) = s.encodeURLParameter(spaceToPlus = true)

    private fun api(action: String?) = buildString {
        append("$base/player_api.php?username=${enc(username)}&password=${enc(password)}")
        if (action != null) append("&action=$action")
    }

    /** Full guide for this account, matched to exactly the channels it carries. */
    fun epgUrl() = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    fun liveUrl(streamId: String, ext: String = "ts") =
        "$base/live/${enc(username)}/${enc(password)}/$streamId.$ext"

    fun vodUrl(streamId: String, ext: String) =
        "$base/movie/${enc(username)}/${enc(password)}/$streamId.$ext"

    fun episodeUrl(episodeId: String, ext: String) =
        "$base/series/${enc(username)}/${enc(password)}/$episodeId.$ext"

    private companion object {
        // Shared, long-lived client - see TmdbClient's companion object doc
        // for why this must not be constructed fresh per instance/call.
        val http = tapperHttpClient().config {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                // Catalogue endpoints can be large and slow on an overloaded
                // panel - matches the Fire TV original's 60s read timeout.
                socketTimeoutMillis = 60_000
            }
        }
    }

    /**
     * Validates the account and returns what the panel says about it.
     * Surfacing this is worth the extra call - "your subscription expired"
     * is the single most common cause of an app that looks broken.
     */
    suspend fun authenticate(): XtreamAccount {
        val body = fetch(api(null))
        if (body.trimStart().startsWith("<")) {
            throw XtreamException("The server returned a web page, not account data. Check the host address.")
        }
        val root = parseObject(body)
        val info = root["user_info"] as? JsonObject
            ?: throw XtreamException("No account information returned.")

        if (info.int("auth") == 0) throw XtreamException("Username or password rejected.")

        return XtreamAccount(
            username = info.str("username") ?: username,
            status = info.str("status") ?: "Unknown",
            expiresUtc = info.long("exp_date")?.takeIf { it > 0 }?.times(1000L),
            maxConnections = info.int("max_connections") ?: 1,
            activeConnections = info.int("active_cons") ?: 0,
            trial = info.int("is_trial") == 1,
        )
    }

    /**
     * [onWarning] fires (rather than throws) if fetching category names
     * fails - the stream list itself still comes back and is still usable,
     * just with every item falling into a single fallback group instead of
     * the panel's real category/country breakdown.
     */
    suspend fun liveChannels(
        sourceId: String,
        preferHls: Boolean = false,
        onWarning: (String) -> Unit = {},
    ): List<Channel> {
        val cats = loadCategories("get_live_categories", onWarning, "live categories")
        val ext = if (preferHls) "m3u8" else "ts"
        val arr = parseArray(fetch(api("get_live_streams")))
        val out = ArrayList<Channel>(arr.size)
        arr.forEachIndexed { i, el ->
            val o = el as? JsonObject ?: return@forEachIndexed
            val id = o.str("stream_id") ?: return@forEachIndexed
            val name = o.str("name")?.trim() ?: return@forEachIndexed
            val parsed = CategoryName.parse(o.categoryKey()?.let { cats[it] })
            out.add(
                Channel(
                    id = id,
                    sourceId = sourceId,
                    name = name,
                    number = o.int("num") ?: (i + 1),
                    logoUrl = o.str("stream_icon")?.takeIf { it.isNotBlank() },
                    group = parsed.category,
                    countryCode = parsed.countryCode,
                    epgChannelId = o.str("epg_channel_id")?.takeIf { it.isNotBlank() },
                    streams = listOf(StreamRef(liveUrl(id, ext), 0)),
                    kind = ContentKind.LIVE,
                    categories = listOfNotNull(parsed.category),
                )
            )
        }
        return out
    }

    /**
     * Films. Same panel, different endpoint. The container extension the
     * panel reports is used verbatim - guessing .mp4 for an .mkv gives a
     * 404 on most panels.
     */
    suspend fun movies(sourceId: String, onWarning: (String) -> Unit = {}): List<Channel> {
        val cats = loadCategories("get_vod_categories", onWarning, "movie categories")
        val arr = parseArray(fetch(api("get_vod_streams")))
        val out = ArrayList<Channel>(arr.size)
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val id = o.str("stream_id") ?: continue
            val name = o.str("name")?.trim() ?: continue
            val parsed = CategoryName.parse(o.categoryKey()?.let { cats[it] })
            val ext = o.str("container_extension") ?: "mp4"
            out.add(
                Channel(
                    id = "vod-$id",
                    sourceId = sourceId,
                    name = name,
                    number = null,
                    logoUrl = o.str("stream_icon")?.takeIf { it.isNotBlank() },
                    group = parsed.category,
                    countryCode = parsed.countryCode,
                    epgChannelId = null,
                    streams = listOf(StreamRef(vodUrl(id, ext), 0)),
                    kind = ContentKind.MOVIE,
                    categories = listOfNotNull(parsed.category),
                )
            )
        }
        return out
    }

    /**
     * Series listings. These carry no stream of their own - episodes are
     * fetched per series via [episodes], because a panel with thousands of
     * series would otherwise need thousands of calls up front.
     */
    suspend fun series(sourceId: String, onWarning: (String) -> Unit = {}): List<Channel> {
        val cats = loadCategories("get_series_categories", onWarning, "show categories")
        val arr = parseArray(fetch(api("get_series")))
        val out = ArrayList<Channel>(arr.size)
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val id = o.str("series_id") ?: continue
            val name = o.str("name")?.trim() ?: continue
            val parsed = CategoryName.parse(o.categoryKey()?.let { cats[it] })
            out.add(
                Channel(
                    id = "series-$id",
                    sourceId = sourceId,
                    name = name,
                    number = null,
                    logoUrl = o.str("cover")?.takeIf { it.isNotBlank() },
                    group = parsed.category,
                    countryCode = parsed.countryCode,
                    epgChannelId = null,
                    streams = emptyList(),
                    kind = ContentKind.SERIES,
                    categories = listOfNotNull(parsed.category),
                    seriesId = id,
                )
            )
        }
        return out
    }

    /** Episodes for one series, flattened across seasons and sorted. */
    suspend fun episodes(sourceId: String, seriesId: String): List<Channel> {
        val body = fetch(api("get_series_info") + "&series_id=" + enc(seriesId))
        val root = parseObject(body)
        val seasons = root["episodes"] as? JsonObject ?: return emptyList()

        data class Ordered(val season: Int, val number: Int, val channel: Channel)
        val out = ArrayList<Ordered>()
        for ((seasonKey, value) in seasons) {
            val arr = value as? JsonArray ?: continue
            arr.forEachIndexed { i, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val epId = o.str("id") ?: return@forEachIndexed
                val season = seasonKey.toIntOrNull() ?: o.int("season") ?: 0
                val number = o.int("episode_num") ?: (i + 1)
                val ext = o.str("container_extension") ?: "mp4"
                val title = o.str("title")?.trim().orEmpty().ifEmpty { "Episode $number" }
                val image = (o["info"] as? JsonObject)?.str("movie_image")
                out.add(
                    Ordered(
                        season, number,
                        Channel(
                            id = "ep-$epId",
                            sourceId = sourceId,
                            name = "S${season}E$number  $title",
                            number = number,
                            logoUrl = image,
                            group = "Season $season",
                            countryCode = null,
                            epgChannelId = null,
                            streams = listOf(StreamRef(episodeUrl(epId, ext), 0)),
                            kind = ContentKind.MOVIE,
                            categories = listOf("Season $season"),
                        )
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.number })).map { it.channel }
    }

    private suspend fun loadCategories(
        action: String,
        onWarning: (String) -> Unit,
        label: String,
    ): Map<String, String> =
        try {
            val arr = parseArray(fetch(api(action)))
            val map = HashMap<String, String>(arr.size)
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val id = o.str("category_id") ?: continue
                map[id] = o.str("category_name") ?: "Unnamed"
            }
            map
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            onWarning("Couldn't load $label: ${t.message}")
            emptyMap()
        }

    private fun parseArray(body: String): JsonArray {
        if (body.trimStart().startsWith("<")) {
            throw XtreamException("The server returned a web page, not data. Check the host address.")
        }
        return try {
            Json.parseToJsonElement(body) as? JsonArray
                ?: throw XtreamException("Unexpected response from the server.")
        } catch (e: XtreamException) {
            throw e
        } catch (t: Throwable) {
            throw XtreamException("Unexpected response from the server.", t)
        }
    }

    private fun parseObject(body: String): JsonObject =
        try {
            Json.parseToJsonElement(body) as? JsonObject
                ?: throw XtreamException("Unexpected response from the server.")
        } catch (e: XtreamException) {
            throw e
        } catch (t: Throwable) {
            throw XtreamException("Unexpected response from the server.", t)
        }

    private suspend fun fetch(url: String): String {
        val response: HttpResponse = try {
            http.get(url) { header("User-Agent", "TapperIPTV/0.5") }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw XtreamException("Couldn't reach the server: ${t.message}", t)
        }
        if (!response.status.isSuccess()) {
            throw XtreamException(
                when (response.status.value) {
                    401, 403 -> "Server refused the request (HTTP ${response.status.value}). Check the username and password."
                    404 -> "Server has no Xtream API at this address (HTTP 404). Check the port and path."
                    else -> "Server returned HTTP ${response.status.value}."
                }
            )
        }
        return try {
            response.bodyAsText()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw XtreamException("Couldn't read the server's response: ${t.message}", t)
        }
    }
}

data class XtreamAccount(
    val username: String,
    val status: String,
    val expiresUtc: Long?,
    val maxConnections: Int,
    val activeConnections: Int,
    val trial: Boolean,
) {
    val isActive get() = status.equals("Active", ignoreCase = true)

    fun daysRemaining(nowUtc: Long): Long? =
        expiresUtc?.let { (it - nowUtc) / 86_400_000L }

    fun summary(nowUtc: Long): String = buildString {
        append(status)
        daysRemaining(nowUtc)?.let {
            append(if (it < 0) " — expired" else " — $it days left")
        }
        append(" · $maxConnections stream")
        if (maxConnections != 1) append("s")
    }
}

// Panels vary field types between endpoints (a number on one, a quoted
// string on the next) - JsonPrimitive.contentOrNull reads either shape as a
// plain string regardless of which one the panel actually sent, which is
// the same leniency the Fire TV original hand-rolled via lenientString().
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()
private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

/**
 * Some panels send a VOD/series item's category as "category_id" (a single
 * value); others send only "category_ids" - a JSON array - instead. The
 * first scalar element of that array is kept; the Fire TV original does the
 * same, since nothing here needs more than one id per item.
 */
private fun JsonObject.categoryKey(): String? {
    str("category_id")?.let { return it }
    val ids = this["category_ids"] as? JsonArray ?: return null
    for (el in ids) {
        (el as? JsonPrimitive)?.contentOrNull?.let { return it }
    }
    return null
}
