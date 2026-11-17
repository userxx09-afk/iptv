package io.tapper.core.freetv

import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The Free TV lineup and guide files kept in the GarysTV public repo
 * (free-channels.json, free-guides.json, extra-guides.json), parsed the same
 * way Fire TV's GarysTvFeed reads them. Text in, values out: the iPad
 * downloads and caches the files itself (URLSession) and hands the text here,
 * so this needs no networking and no suspend functions across the Swift
 * boundary.
 */
object GarysTvFiles {
    const val BASE = "https://raw.githubusercontent.com/userxx09-afk/Garystv/main/src/core/"
    const val LINEUP = "sources/free-channels.json"
    const val FREE_GUIDES = "guide/free-guides.json"
    const val EXTRA_GUIDES = "guide/extra-guides.json"

    /** Category prefix for Free TV channels shown alongside another source. */
    const val ALONGSIDE_PREFIX = "Free TV · "

    data class GuideChannel(
        val name: String,
        val guideId: String,
        val tvgId: String?,
        val match: List<String>,
        val except: List<String>,
    )

    data class GuideFile(val id: String, val name: String, val url: String, val channels: List<GuideChannel>)

    /** The lineup as live channels of [sourceId]. Empty when [json] doesn't parse. */
    fun lineup(json: String, sourceId: String): List<Channel> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["channels"] as? JsonArray ?: return emptyList()
        val out = ArrayList<Channel>(arr.size)
        for (el in arr) {
            val o = el as? JsonObject ?: continue
            val name = o.str("name") ?: continue
            val url = o.str("url") ?: continue
            val tvgId = o.str("tvgId")
            val category = o.str("category")
            out.add(
                Channel(
                    id = "$sourceId:" + (tvgId ?: name),
                    sourceId = sourceId,
                    name = name,
                    number = o.str("number")?.toIntOrNull(),
                    logoUrl = o.str("logo"),
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
     * The same channels relabelled for showing alongside another source:
     * every category gains the "Free TV · " prefix, so they group together
     * (and the apps sort them to the bottom of the category list).
     */
    fun alongside(channels: List<Channel>): List<Channel> = channels.map { c ->
        val cat = ALONGSIDE_PREFIX + (c.group ?: "Other")
        c.copy(group = cat, categories = listOf(cat))
    }

    /** Both guide files merged: a file named in both is downloaded once, with both lists of channels. */
    fun guides(jsons: List<String>): List<GuideFile> {
        val out = LinkedHashMap<String, GuideFile>()
        for (json in jsons) {
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: continue
            val arr = root["guides"] as? JsonArray ?: continue
            for (el in arr) {
                val g = el as? JsonObject ?: continue
                val id = g.str("id") ?: continue
                val url = g.str("url") ?: continue
                val chans = (g["channels"] as? JsonArray).orEmpty().mapNotNull { ce ->
                    val c = ce as? JsonObject ?: return@mapNotNull null
                    val guideId = c.str("guideId") ?: return@mapNotNull null
                    GuideChannel(
                        name = c.str("name").orEmpty(),
                        guideId = guideId,
                        tvgId = c.str("tvgId"),
                        match = c.strings("match"),
                        except = c.strings("except"),
                    )
                }
                val have = out[id]
                out[id] = have?.copy(channels = have.channels + chans)
                    ?: GuideFile(id, g.str("name") ?: id, url, chans)
            }
        }
        return out.values.toList()
    }

    /**
     * The guide id a channel without its own listings should use, matched by
     * words in its name (Marquee Sports Network from an IPTV plan, say).
     * Null when no rule matches.
     */
    fun guideIdForName(channelName: String, guides: List<GuideFile>): String? {
        val name = normWords(channelName)
        fun has(words: List<String>) = words.any { " $name ".contains(" " + normWords(it) + " ") }
        return guides.asSequence().flatMap { it.channels.asSequence() }
            .firstOrNull { it.tvgId == null && it.match.isNotEmpty() && has(it.match) && !has(it.except) }
            ?.guideId
    }

    private fun normWords(s: String?): String =
        s.orEmpty().lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
}
