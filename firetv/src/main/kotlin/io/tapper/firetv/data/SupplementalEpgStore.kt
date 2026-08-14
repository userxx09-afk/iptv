package io.tapper.firetv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * A guide layered on top of whatever a source's own EPG provides, scoped to
 * just the channel ids it declares (see EpgRepository.mergeSupplemental)
 * rather than replacing that source's guide wholesale.
 *
 * Marquee Sports Network is the reason this exists: it carries no schedule in
 * the iptv-org playlist's own declared guides, and most Xtream panels'
 * xmltv.php doesn't carry it either. epgshare01.online's "US2" bundle does,
 * under two ids for the main feed and the overflow feed used during
 * scheduling conflicts.
 */
data class SupplementalEpgSource(
    val id: String,
    val name: String,
    val guideUrl: String,
    val channelIds: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("guideUrl", guideUrl)
        .put("channelIds", JSONArray(channelIds))

    companion object {
        val MARQUEE = SupplementalEpgSource(
            id = "marquee",
            name = "Marquee Sports Network",
            guideUrl = "https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz",
            channelIds = listOf(
                "marquee.sports.network.hd.us2",
                "marquee.sports.network.overflow.us2",
            ),
        )

        fun fromJson(o: JSONObject): SupplementalEpgSource {
            val ids = ArrayList<String>()
            val arr = o.optJSONArray("channelIds")
            if (arr != null) for (i in 0 until arr.length()) {
                arr.optString(i)?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }
            return SupplementalEpgSource(
                id = o.getString("id"),
                name = o.getString("name"),
                guideUrl = o.getString("guideUrl"),
                channelIds = ids,
            )
        }
    }
}

/**
 * Same "JSON array in ordinary preferences" pattern as [SourceStore] - these
 * are guide URLs and channel ids, nothing sensitive enough to need the
 * encrypted vault.
 */
class SupplementalEpgStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tapper_supplemental_epg", Context.MODE_PRIVATE)

    fun all(): List<SupplementalEpgSource> {
        val raw = prefs.getString("sources", null) ?: return listOf(SupplementalEpgSource.MARQUEE)
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return listOf(SupplementalEpgSource.MARQUEE)
        val list = ArrayList<SupplementalEpgSource>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let {
                runCatching { SupplementalEpgSource.fromJson(it) }.getOrNull()?.let(list::add)
            }
        }
        // Marquee ships as the default every install starts with - re-added if
        // a corrupt or hand-edited preferences value ever drops it, the same
        // way SourceStore never lets the built-in playlist disappear.
        if (list.none { it.id == SupplementalEpgSource.MARQUEE.id }) list.add(0, SupplementalEpgSource.MARQUEE)
        return list
    }

    private fun save(list: List<SupplementalEpgSource>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("sources", arr.toString()).apply()
    }

    fun add(source: SupplementalEpgSource) = save(all().filterNot { it.id == source.id } + source)

    fun remove(id: String) = save(all().filterNot { it.id == id })
}
