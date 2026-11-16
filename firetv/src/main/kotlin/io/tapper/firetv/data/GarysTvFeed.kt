package io.tapper.firetv.data

import android.content.res.AssetManager
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Free TV lineup and the extra guide files, shared with GarysTV.
 *
 * GarysTV keeps them in its public repo (src/core/sources/free-channels.json,
 * src/core/guide/free-guides.json and extra-guides.json) and re-checks them
 * there, so they are read from GitHub rather than copied into this app: a
 * channel GarysTV adds or a guide it re-matches shows up here at the next
 * refresh, with no new build. A copy ships in assets/garystv for a first
 * launch with no internet.
 */
class GarysTvFeed(private val cacheDir: File, private val assets: AssetManager) {

    companion object {
        private const val BASE = "https://raw.githubusercontent.com/userxx09-afk/Garystv/main/src/core/"
        private const val LINEUP = "sources/free-channels.json"
        private const val FREE_GUIDES = "guide/free-guides.json"
        private const val EXTRA_GUIDES = "guide/extra-guides.json"
        private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        private fun normWords(s: String?): String =
            s.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** One channel a guide file covers. A Free TV channel is matched by its
     *  [tvgId]; any other by words in its name ([match], minus [except]). */
    data class GuideChannel(
        val name: String,
        val guideId: String,
        val tvgId: String?,
        val match: List<String>,
        val except: List<String>,
    )

    data class GuideFile(val id: String, val name: String, val url: String, val channels: List<GuideChannel>)

    private fun cacheFile(path: String) = File(cacheDir, "garystv-" + path.substringAfterLast('/'))

    /** A fresh cached copy, else GitHub, else any cached copy, else the
     *  bundled one. */
    private fun text(path: String, allowNetwork: Boolean, force: Boolean = false): String {
        val cache = cacheFile(path)
        val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        if (fresh && !force) {
            val cached = runCatching { cache.readText() }.getOrNull()
            if (cached != null) return cached
        }
        if (allowNetwork) {
            val body = runCatching {
                download(BASE + path).also {
                    JSONObject(it) // only keep a copy that parses
                    cache.writeText(it)
                }
            }.getOrNull()
            if (body != null) return body
        }
        if (cache.exists()) {
            val cached = runCatching { cache.readText() }.getOrNull()
            if (cached != null) return cached
        }
        return assets.open("garystv/" + path.substringAfterLast('/')).bufferedReader().use { it.readText() }
    }

    private fun download(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "TapperIPTV/0.3")
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** The Free TV channels, as a source's live lineup. Blocking; call off
     *  the main thread. */
    fun lineup(sourceId: String, force: Boolean): List<Channel> {
        val arr = JSONObject(text(LINEUP, allowNetwork = true, force = force)).optJSONArray("channels") ?: JSONArray()
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
            val tvgId = o.optString("tvgId").takeIf { it.isNotBlank() }
            val category = o.optString("category").takeIf { it.isNotBlank() }
            out.add(
                Channel(
                    id = "$sourceId:" + (tvgId ?: name),
                    sourceId = sourceId,
                    name = name,
                    number = o.optString("number").toIntOrNull(),
                    logoUrl = o.optString("logo").takeIf { it.isNotBlank() },
                    group = category,
                    countryCode = "us",
                    epgChannelId = tvgId,
                    streams = listOf(StreamRef(url, 0)),
                    kind = ContentKind.LIVE,
                    categories = listOfNotNull(category),
                )
            )
        }
        return out.distinctBy { it.id }
    }

    /**
     * Both guide files merged the way GarysTV merges them: a file named in
     * both is downloaded once, with both lists of channels. With
     * [allowNetwork] false only cached or bundled copies are read, so it is
     * safe on the main thread.
     */
    fun guides(allowNetwork: Boolean): List<GuideFile> {
        val out = LinkedHashMap<String, GuideFile>()
        for (path in listOf(EXTRA_GUIDES, FREE_GUIDES)) {
            val arr = runCatching { JSONObject(text(path, allowNetwork)).optJSONArray("guides") }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i) ?: continue
                val id = g.optString("id").takeIf { it.isNotBlank() } ?: continue
                val url = g.optString("url").takeIf { it.isNotBlank() } ?: continue
                val chans = ArrayList<GuideChannel>()
                val ca = g.optJSONArray("channels") ?: JSONArray()
                for (j in 0 until ca.length()) {
                    val c = ca.optJSONObject(j) ?: continue
                    val guideId = c.optString("guideId").takeIf { it.isNotBlank() } ?: continue
                    chans.add(
                        GuideChannel(
                            name = c.optString("name"),
                            guideId = guideId,
                            tvgId = c.optString("tvgId").takeIf { it.isNotBlank() },
                            match = c.optJSONArray("match").strings(),
                            except = c.optJSONArray("except").strings(),
                        )
                    )
                }
                val have = out[id]
                out[id] = have?.copy(channels = have.channels + chans)
                    ?: GuideFile(id, g.optString("name", id), url, chans)
            }
        }
        return out.values.toList()
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    /**
     * Guide ids for channels matched by name (Marquee Sports Network from an
     * IPTV plan, and whatever else extra-guides.json gains), keyed
     * "sourceId|channelId" like EpgOverrideStore. Free TV channels need no
     * entry: their listings are stored under their own tvgId.
     */
    fun nameMatches(channels: List<Channel>, guides: List<GuideFile>): Map<String, String> {
        val rules = guides.flatMap { it.channels }.filter { it.tvgId == null && it.match.isNotEmpty() }
        if (rules.isEmpty()) return emptyMap()
        fun has(name: String, words: List<String>) = words.any { " $name ".contains(" " + normWords(it) + " ") }
        val out = HashMap<String, String>()
        for (ch in channels) {
            if (ch.kind != ContentKind.LIVE) continue
            val name = normWords(ch.name)
            val rule = rules.firstOrNull { has(name, it.match) && !has(name, it.except) } ?: continue
            out["${ch.sourceId}|${ch.id}"] = EpgDatabase.normalizeId(rule.guideId)
        }
        return out
    }
}
