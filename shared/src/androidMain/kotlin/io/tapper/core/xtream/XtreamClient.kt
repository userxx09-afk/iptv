package io.tapper.core.xtream

import android.util.JsonReader
import android.util.JsonToken
import io.tapper.core.model.CategoryName
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Xtream Codes panel client. Uses HttpURLConnection and org.json/android.util
 * JsonReader — both are in the platform, so this adds no dependencies.
 *
 * Panels are inconsistent in ways that matter: numeric fields arrive as JSON
 * numbers on one endpoint and quoted strings on the next, and a rejected login
 * is commonly an HTML page served with HTTP 200. Everything here assumes that.
 *
 * The three catalogue endpoints (get_live_streams, get_vod_streams, get_series)
 * are read with a streaming parser rather than org.json. One account's
 * get_series response measured over 100MB - slurping that into a single
 * String and then building a full org.json tree on top of it is two
 * simultaneous in-memory copies of a 100MB+ document, on a device (Fire TV
 * Stick) with a fraction of a phone's heap. That is what an
 * OutOfMemoryError on "Couldn't load shows" turned out to be. The account
 * endpoint and category lists stay on the simple slurp-a-String path below
 * (fetch/JSONArray) since those responses are small.
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
            // Query string first: "/get.php?username=..." only ends with the
            // known suffix once the query has been removed.
            var r = raw.substringBefore('?').trimEnd('/')
            for (suffix in listOf("/player_api.php", "/panel_api.php", "/get.php", "/xmltv.php", "/c", "/index.php")) {
                if (r.endsWith(suffix, ignoreCase = true)) { r = r.dropLast(suffix.length); break }
            }
            r.trimEnd('/')
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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

    /**
     * HttpURLConnection refuses cross-protocol redirects: an http:// panel that
     * 301s to https:// returns the redirect itself rather than following it.
     * Several panels do exactly that, so redirects are followed by hand.
     *
     * Returns the live response stream rather than a slurped String - the
     * caller decides whether this response is small enough to read in one go
     * ([fetch]) or large enough that it needs to be streamed and never fully
     * materialised ([liveChannels], [movies], [series]).
     */
    private fun open(url: String, hop: Int = 0): InputStream {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "TapperIPTV/0.5")
        }
        val code = try {
            conn.responseCode
        } catch (t: Throwable) {
            throw XtreamException(describe(t, url), t)
        }

        if (code in 300..399) {
            val location = conn.getHeaderField("Location")
                ?: throw XtreamException("Server sent a redirect with no address (HTTP $code).")
            if (hop >= 4) throw XtreamException("Too many redirects from this server.")
            conn.disconnect()
            return open(URL(URL(url), location).toString(), hop + 1)
        }

        if (code !in 200..299) {
            conn.disconnect()
            throw XtreamException(
                when (code) {
                    401, 403 -> "Server refused the request (HTTP $code). Check the username and password."
                    404 -> "Server has no Xtream API at this address (HTTP 404). Check the port and path."
                    else -> "Server returned HTTP $code."
                }
            )
        }
        return conn.inputStream
    }

    /** Small, bounded responses - account info, category lists - where
     *  reading the whole body into one String is fine. */
    private fun fetch(url: String): String =
        try {
            open(url).use { it.bufferedReader().readText() }
        } catch (e: XtreamException) {
            throw e
        } catch (t: Throwable) {
            throw XtreamException(describe(t, url), t)
        }

    /**
     * Turns the underlying failure into something actionable. The previous
     * version reported "Couldn't reach <host>" for every possible cause, which
     * made a DNS typo, a wrong port, a TLS problem and a dead server all look
     * identical.
     */
    private fun describe(t: Throwable, url: String): String {
        val host = runCatching { URL(url).host }.getOrNull() ?: "the server"
        val port = runCatching { URL(url).port }.getOrNull() ?: -1
        val where = if (port > 0) "$host:$port" else host
        return when (t) {
            is java.net.UnknownHostException ->
                "Can't find $host. Check the address for typos, or that this device has a working connection."
            is java.net.SocketTimeoutException ->
                "$where didn't respond in time. The port may be wrong, or the server may be blocking this network."
            is java.net.ConnectException ->
                "$where refused the connection. This usually means the wrong port."
            is javax.net.ssl.SSLHandshakeException ->
                "$where has an HTTPS certificate this device rejects. Try http:// instead of https://."
            is javax.net.ssl.SSLException ->
                "Secure connection to $where failed. Try http:// instead of https://."
            else ->
                "Couldn't reach $where (" + (t::class.simpleName ?: "error") +
                    (t.message?.let { ": " + it.take(90) } ?: "") + ")"
        }
    }

    /**
     * Validates the account and returns what the panel says about it. Surfacing
     * this is worth the extra call — "your subscription expired" is the single
     * most common cause of an app that looks broken.
     */
    fun authenticate(): XtreamAccount {
        val body = fetch(api(null))
        if (body.trimStart().startsWith("<")) {
            throw XtreamException("The server returned a web page, not account data. Check the host address.")
        }
        val root = try { JSONObject(body) } catch (t: Throwable) {
            throw XtreamException("Unexpected response from the server.", t)
        }
        val info = root.optJSONObject("user_info")
            ?: throw XtreamException("No account information returned.")

        if (info.lenientInt("auth") == 0) throw XtreamException("Username or password rejected.")

        return XtreamAccount(
            username = info.lenientString("username") ?: username,
            status = info.lenientString("status") ?: "Unknown",
            expiresUtc = info.lenientLong("exp_date")?.takeIf { it > 0 }?.times(1000L),
            maxConnections = info.lenientInt("max_connections") ?: 1,
            activeConnections = info.lenientInt("active_cons") ?: 0,
            trial = info.lenientInt("is_trial") == 1,
        )
    }

    fun liveChannels(
        sourceId: String,
        preferHls: Boolean = false,
        /** Fired (not thrown) if fetching category names fails - the stream
         *  list itself still comes back and is still usable, just with every
         *  item falling into a single fallback group instead of the panel's
         *  real category/country breakdown. Silently swallowing this used to
         *  make that look identical to an account that genuinely has no
         *  categorisation, with no way to tell the two apart. */
        onWarning: (String) -> Unit = {},
    ): List<Channel> {
        val cats = runCatching { categoryNames() }
            .onFailure { onWarning("Couldn't load live categories: ${it.message}") }
            .getOrDefault(emptyMap())
        val ext = if (preferHls) "m3u8" else "ts"
        val out = ArrayList<Channel>()
        var i = 0
        open(api("get_live_streams")).use { input ->
            streamArray(input) { o ->
                val id = o["stream_id"] ?: return@streamArray
                val name = o["name"]?.trim() ?: return@streamArray
                val parsed = CategoryName.parse((o["category_id"] ?: o["category_ids"])?.let { cats[it] })
                out.add(
                    Channel(
                        id = id,
                        sourceId = sourceId,
                        name = name,
                        number = o["num"]?.toIntOrNull() ?: (i + 1),
                        logoUrl = o["stream_icon"]?.takeIf { it.isNotBlank() },
                        // Xtream has no country field of its own. Providers encode it
                        // in the category name ("US | Sports"), which is the only place
                        // the information exists - splitting it gives both axes.
                        group = parsed.category,
                        countryCode = parsed.countryCode,
                        epgChannelId = o["epg_channel_id"]?.takeIf { it.isNotBlank() },
                        streams = listOf(StreamRef(liveUrl(id, ext), 0)),
                        kind = ContentKind.LIVE,
                        categories = listOfNotNull(parsed.category),
                    )
                )
                i++
            }
        }
        return out
    }

    /**
     * Films. Same panel, different endpoint - no extra dependency, and the
     * container extension the panel reports must be used verbatim: guessing
     * .mp4 for an .mkv gives a 404 on most panels.
     *
     * See [liveChannels]'s [onWarning] doc - same deal here: a failed
     * get_vod_categories call still returns every movie, just uncategorised.
     */
    fun movies(sourceId: String, onWarning: (String) -> Unit = {}): List<Channel> {
        val cats = runCatching { categoryNames("get_vod_categories") }
            .onFailure { onWarning("Couldn't load movie categories: ${it.message}") }
            .getOrDefault(emptyMap())
        val out = ArrayList<Channel>()
        open(api("get_vod_streams")).use { input ->
            streamArray(input) { o ->
                val id = o["stream_id"] ?: return@streamArray
                val name = o["name"]?.trim() ?: return@streamArray
                val parsed = CategoryName.parse((o["category_id"] ?: o["category_ids"])?.let { cats[it] })
                val ext = o["container_extension"] ?: "mp4"
                out.add(
                    Channel(
                        id = "vod-" + id,
                        sourceId = sourceId,
                        name = name,
                        number = null,
                        logoUrl = o["stream_icon"]?.takeIf { it.isNotBlank() },
                        group = parsed.category,
                        countryCode = parsed.countryCode,
                        epgChannelId = null,
                        streams = listOf(StreamRef(vodUrl(id, ext), 0)),
                        kind = ContentKind.MOVIE,
                        categories = listOfNotNull(parsed.category),
                    )
                )
            }
        }
        return out
    }

    /**
     * Series listings. These carry no stream of their own - episodes are
     * fetched per series, because a panel with thousands of series would
     * otherwise need thousands of calls up front. See [liveChannels]'s
     * [onWarning] doc.
     */
    fun series(sourceId: String, onWarning: (String) -> Unit = {}): List<Channel> {
        val cats = runCatching { categoryNames("get_series_categories") }
            .onFailure { onWarning("Couldn't load show categories: ${it.message}") }
            .getOrDefault(emptyMap())
        val out = ArrayList<Channel>()
        open(api("get_series")).use { input ->
            streamArray(input) { o ->
                val id = o["series_id"] ?: return@streamArray
                val name = o["name"]?.trim() ?: return@streamArray
                val parsed = CategoryName.parse((o["category_id"] ?: o["category_ids"])?.let { cats[it] })
                out.add(
                    Channel(
                        id = "series-" + id,
                        sourceId = sourceId,
                        name = name,
                        number = null,
                        logoUrl = o["cover"]?.takeIf { it.isNotBlank() },
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
        }
        return out
    }

    /** Episodes for one series, flattened across seasons and sorted. */
    fun episodes(sourceId: String, seriesId: String): List<Channel> {
        val root = JSONObject(fetch(api("get_series_info") + "&series_id=" + enc(seriesId)))
        val seasons = root.optJSONObject("episodes") ?: return emptyList()
        val out = ArrayList<Triple<Int, Int, Channel>>()
        val keys = seasons.keys()
        while (keys.hasNext()) {
            val seasonKey = keys.next()
            val arr = seasons.optJSONArray(seasonKey) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val epId = o.lenientString("id") ?: continue
                val season = seasonKey.toIntOrNull() ?: o.lenientInt("season") ?: 0
                val number = o.lenientInt("episode_num") ?: (i + 1)
                val ext = o.lenientString("container_extension") ?: "mp4"
                val title = o.lenientString("title")?.trim().orEmpty()
                    .ifEmpty { "Episode " + number }
                out.add(
                    Triple(
                        season, number,
                        Channel(
                            id = "ep-" + epId,
                            sourceId = sourceId,
                            name = "S" + season + "E" + number + "  " + title,
                            number = number,
                            logoUrl = o.optJSONObject("info")?.lenientString("movie_image"),
                            group = "Season " + season,
                            countryCode = null,
                            epgChannelId = null,
                            streams = listOf(StreamRef(episodeUrl(epId, ext), 0)),
                            kind = ContentKind.MOVIE,
                            categories = listOf("Season " + season),
                        )
                    )
                )
            }
        }
        return out.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }

    private fun categoryNames(action: String = "get_live_categories"): Map<String, String> {
        val arr = JSONArray(fetch(api(action)))
        val map = HashMap<String, String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.lenientString("category_id") ?: continue
            map[id] = o.lenientString("category_name") ?: "Unnamed"
        }
        return map
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

// Panels vary field types between endpoints, so never trust getInt/getString.
private fun JSONObject.lenientString(key: String): String? {
    if (isNull(key)) return null
    val v = opt(key) ?: return null
    val s = v.toString()
    return if (s == "null" || s.isEmpty()) null else s
}
private fun JSONObject.lenientInt(key: String): Int? = lenientString(key)?.toIntOrNull()
private fun JSONObject.lenientLong(key: String): Long? = lenientString(key)?.toLongOrNull()

/**
 * Streaming counterpart to org.json's JSONArray/JSONObject, used only for the
 * three catalogue endpoints that can be large enough to matter (see the class
 * doc comment above). Reads one element at a time straight from the response
 * stream - the whole array is never held in memory, only whichever single
 * element is currently being read plus whatever the caller decides to keep
 * (typically a small Channel, not the raw JSON with every field the panel
 * sent).
 */
private fun streamArray(input: InputStream, onObject: (Map<String, String?>) -> Unit) {
    val reader = JsonReader(BufferedReader(InputStreamReader(input, Charsets.UTF_8)))
    reader.isLenient = true
    reader.beginArray()
    while (reader.hasNext()) {
        // A provider mixing a stray scalar into what's declared an array of
        // objects is rare, but one bad element should skip, not kill the rest
        // of the catalogue.
        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            onObject(reader.readFlatObject())
        } else {
            reader.skipValue()
        }
    }
    reader.endArray()
}

/**
 * Reads the current object's scalar fields into a flat map - same "be
 * lenient about types" behaviour as [lenientString]/[lenientInt] above,
 * coercing numbers/booleans to strings rather than requiring a caller to
 * know which type a given panel used for a given field. Nested objects (an
 * "info" block, and similar) are skipped rather than read, since nothing
 * here needs them and reading them would mean holding more of the response
 * in memory than necessary.
 *
 * Arrays are a partial exception. Some panels send a VOD/series item's
 * category as "category_id" (a single value); others send only
 * "category_ids" - a JSON array - instead, sometimes alongside a genre or
 * cast array too. Unconditionally skipping every array, as this used to,
 * meant every item on a "category_ids"-only panel came back with no
 * category at all, which is indistinguishable from a genuinely uncategorised
 * title - every movie and show in the account fell into one lump instead of
 * the panel's real category breakdown. The first scalar element of any
 * array field is kept for exactly this reason; the rest is still discarded
 * unread; a small handful of ids is cheap, nothing here needs more than one.
 */
private fun JsonReader.readFlatObject(): Map<String, String?> {
    val out = HashMap<String, String?>()
    beginObject()
    while (hasNext()) {
        val name = nextName()
        when (peek()) {
            JsonToken.STRING, JsonToken.NUMBER -> {
                val s = nextString()
                out[name] = if (s.isEmpty() || s == "null") null else s
            }
            JsonToken.BOOLEAN -> out[name] = nextBoolean().toString()
            JsonToken.NULL -> { nextNull(); out[name] = null }
            JsonToken.BEGIN_ARRAY -> {
                beginArray()
                var first: String? = null
                while (hasNext()) {
                    when (peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> {
                            val v = nextString()
                            if (first == null && v.isNotEmpty() && v != "null") first = v
                        }
                        else -> skipValue()
                    }
                }
                endArray()
                out[name] = first
            }
            else -> skipValue()
        }
    }
    endObject()
    return out
}
