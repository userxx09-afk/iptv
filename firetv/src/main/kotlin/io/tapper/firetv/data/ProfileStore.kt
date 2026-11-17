package io.tapper.firetv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Viewer profiles on one TV: each has its own My List and pins, and a kids
 * profile always keeps locked and adult categories out of sight, whatever
 * was unlocked on another profile. Leaving a kids profile asks for the PIN
 * when one is set.
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("tapper_profiles", Context.MODE_PRIVATE)

    data class Profile(val id: String, val name: String, val kids: Boolean)

    companion object {
        val MAIN = Profile("main", "Main", kids = false)
    }

    fun all(): List<Profile> {
        val raw = prefs.getString("list", null) ?: return listOf(MAIN)
        val a = runCatching { JSONArray(raw) }.getOrNull() ?: return listOf(MAIN)
        val list = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Profile(o.getString("id"), o.getString("name"), o.optBoolean("kids"))
        }
        return if (list.none { it.id == MAIN.id }) listOf(MAIN) + list else list
    }

    private fun save(list: List<Profile>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("kids", it.kids)) }
        prefs.edit().putString("list", a.toString()).apply()
    }

    fun add(name: String, kids: Boolean): Profile {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "Profile" }, kids)
        save(all() + p)
        return p
    }

    fun remove(id: String) {
        if (id == MAIN.id) return
        save(all().filterNot { it.id == id })
        if (activeId == id) activeId = MAIN.id
    }

    var activeId: String
        get() = prefs.getString("active", MAIN.id) ?: MAIN.id
        set(v) { prefs.edit().putString("active", v).commit() }

    fun active(): Profile = all().firstOrNull { it.id == activeId } ?: MAIN

    /** The main profile keeps the original file, so nothing moves on upgrade. */
    fun favoritesPrefs(p: Profile = active()): String =
        if (p.id == MAIN.id) "tapper_favorites" else "tapper_favorites_${p.id}"
}
