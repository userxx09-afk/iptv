package io.tapper.firetv.data

import android.content.Context
import io.tapper.core.sports.League
import io.tapper.core.sports.Team
import org.json.JSONArray
import org.json.JSONObject

/**
 * The teams picked under My Teams (see TeamsScreen and io.tapper.core.sports). Kept on this device
 * only, like favourites; at most [MAX] so the schedule stays a handful of quick requests.
 */
class TeamsStore(context: Context) {
    companion object { const val MAX = 12 }

    private val prefs = context.getSharedPreferences("tapper_teams", Context.MODE_PRIVATE)

    fun all(): List<Team> = runCatching {
        val arr = JSONArray(prefs.getString("teams", "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val league = runCatching { League.valueOf(o.getString("league")) }.getOrNull() ?: return@mapNotNull null
            Team(league, o.getString("id"), o.getString("name"), o.optString("short", o.getString("name")),
                o.optString("abbr"), o.optString("logo").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    /** Sets a reminder for every upcoming game by itself, on the best channel. */
    var autoRemind: Boolean
        get() = prefs.getBoolean("autoRemind", false)
        set(v) { prefs.edit().putBoolean("autoRemind", v).apply() }

    fun has(team: Team) = all().any { it.league == team.league && it.id == team.id }

    /** Adds or removes [team]. Returns false when adding would go past [MAX]. */
    fun toggle(team: Team): Boolean {
        val now = all()
        val next = if (now.any { it.league == team.league && it.id == team.id }) {
            now.filterNot { it.league == team.league && it.id == team.id }
        } else {
            if (now.size >= MAX) return false
            now + team
        }
        save(next)
        return true
    }

    private fun save(list: List<Team>) {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().put("league", t.league.name).put("id", t.id).put("name", t.name)
                .put("short", t.shortName).put("abbr", t.abbr).put("logo", t.logoUrl ?: ""))
        }
        prefs.edit().putString("teams", arr.toString()).apply()
    }
}
