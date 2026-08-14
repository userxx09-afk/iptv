package io.tapper.firetv.data

import android.content.Context
import io.tapper.core.model.ContentKind

/**
 * Optional fixed starting point per kind - "always open Live TV on United
 * States, Sports" instead of wherever an auto-picked default (or leftover
 * position from last time) happens to land. Set from Settings, one per kind.
 *
 * Deliberately not tied to whatever the user was last browsing: that was the
 * previous behaviour (see BrowseScreen's old initialRailKey), and it meant
 * opening a kind could land somewhere unpredictable depending on where it was
 * last left, weeks ago. With nothing configured here, BrowseScreen always
 * starts at the top of the list instead - see it for exactly where "top"
 * means for each column. A home is the one deliberate exception to that.
 */
class NavHomeStore(context: Context) {

    /** [countryKey] is null for a kind with no real per-country split (Movies
     *  and Shows, normally) - it always means "the one Ungrouped bucket" in
     *  that case, never "unset". [category] is null to mean "All", not "no
     *  category chosen" - a home can deliberately point at the unfiltered
     *  list for a country. */
    data class Home(val countryKey: String?, val category: String?)

    private val prefs = context.getSharedPreferences("tapper_nav_home", Context.MODE_PRIVATE)

    private fun setFlag(kind: ContentKind) = "set_${kind.name}"
    private fun countryPref(kind: ContentKind) = "country_${kind.name}"
    private fun categoryPref(kind: ContentKind) = "category_${kind.name}"

    fun get(kind: ContentKind): Home? {
        if (!prefs.getBoolean(setFlag(kind), false)) return null
        // Empty string is the stored stand-in for "null" in both fields -
        // SharedPreferences has no native null, and a real country code or
        // category name is never empty, so this never collides with one.
        val country = prefs.getString(countryPref(kind), "")?.ifEmpty { null }
        val category = prefs.getString(categoryPref(kind), "")?.ifEmpty { null }
        return Home(country, category)
    }

    fun set(kind: ContentKind, countryKey: String?, category: String?) {
        prefs.edit()
            .putBoolean(setFlag(kind), true)
            .putString(countryPref(kind), countryKey.orEmpty())
            .putString(categoryPref(kind), category.orEmpty())
            .apply()
    }

    fun clear(kind: ContentKind) {
        prefs.edit()
            .remove(setFlag(kind))
            .remove(countryPref(kind))
            .remove(categoryPref(kind))
            .apply()
    }
}
