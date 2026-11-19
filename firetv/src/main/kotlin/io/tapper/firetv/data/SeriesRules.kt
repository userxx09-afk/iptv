package io.tapper.firetv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * "Record every episode": a programme title on one channel. Whenever the
 * guide has a new airing of it, a recording is scheduled, with the padding
 * from Settings. Checked when a rule is added, when the app starts and after
 * each background guide refresh.
 */
class SeriesRuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapper_series_rules", Context.MODE_PRIVATE)

    data class Rule(
        val id: String,
        val sourceId: String,
        val channelId: String,
        val channelName: String,
        val streamUrl: String,
        val guideId: String,
        val guideSourceIds: List<String>,
        val title: String,
    )

    fun all(): List<Rule> = runCatching {
        val a = JSONArray(prefs.getString("rules", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val ids = o.getJSONArray("guideSourceIds")
            Rule(
                o.getString("id"), o.getString("sourceId"), o.getString("channelId"), o.getString("channelName"),
                o.getString("streamUrl"), o.getString("guideId"),
                (0 until ids.length()).map { ids.getString(it) }, o.getString("title"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Rule>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(
                JSONObject().put("id", r.id).put("sourceId", r.sourceId).put("channelId", r.channelId)
                    .put("channelName", r.channelName).put("streamUrl", r.streamUrl).put("guideId", r.guideId)
                    .put("guideSourceIds", JSONArray(r.guideSourceIds)).put("title", r.title)
            )
        }
        prefs.edit().putString("rules", a.toString()).apply()
    }

    fun find(sourceId: String, channelId: String, title: String): Rule? =
        all().firstOrNull { it.sourceId == sourceId && it.channelId == channelId && it.title.equals(title, ignoreCase = true) }

    fun add(rule: Rule) { save(all().filterNot { it.id == rule.id } + rule) }

    fun remove(id: String) { save(all().filterNot { it.id == id }) }

    fun newRuleId(): String = "series-" + UUID.randomUUID().toString().take(8)

    /**
     * Schedules every guide airing of every rule that isn't scheduled yet.
     * Returns how many recordings were added.
     */
    fun scheduleUpcoming(epg: EpgDatabase, recordings: RecordingStore, settings: PlayerSettingsStore,
                         now: Long = System.currentTimeMillis()): Int {
        val rules = all()
        if (rules.isEmpty()) return 0
        val existing = recordings.all().filterNot { it.download }
        val before = settings.recordPadBefore * 60_000L
        val after = settings.recordPadAfter * 60_000L
        var added = 0
        for (r in rules) {
            val airings = runCatching { epg.upcoming(r.guideSourceIds, r.guideId, now, limit = 60) }.getOrDefault(emptyList())
                .filter { it.title.equals(r.title, ignoreCase = true) && it.startUtc > now }
            for (p in airings) {
                val start = p.startUtc - before
                val end = p.endUtc + after
                // Same channel overlapping an existing recording: already covered.
                if (existing.any { it.channelId == r.channelId && it.sourceId == r.sourceId && it.startUtc < end && it.endUtc > start }) continue
                recordings.schedule(
                    Recording(
                        id = Recording.newId(), sourceId = r.sourceId, channelId = r.channelId,
                        channelName = r.channelName, streamUrl = r.streamUrl,
                        startUtc = start, endUtc = end,
                    )
                )
                added++
            }
        }
        return added
    }
}
