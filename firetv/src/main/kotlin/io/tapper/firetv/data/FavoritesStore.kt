package io.tapper.firetv.data

import android.content.Context
import io.tapper.core.model.ContentKind

/**
 * Favourites, pinned countries, and pinned categories.
 *
 * Favourites are keyed "sourceId|channelId" and deliberately span sources — a
 * favourites list organised by provider would be useless, since the whole point
 * is one place for the handful of channels actually watched.
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences("tapper_favorites", Context.MODE_PRIVATE)

    private fun key(sourceId: String, channelId: String) = "$sourceId|$channelId"

    fun favorites(): Set<String> = prefs.getStringSet("ids", emptySet()) ?: emptySet()

    fun isFavorite(sourceId: String, channelId: String) = key(sourceId, channelId) in favorites()

    fun toggle(sourceId: String, channelId: String): Boolean {
        val k = key(sourceId, channelId)
        val next = favorites().toMutableSet()
        val added = if (k in next) { next.remove(k); false } else { next.add(k); true }
        // A new Set instance is required: SharedPreferences does not copy the set
        // it is handed, so mutating the original in place silently does nothing.
        prefs.edit().putStringSet("ids", HashSet(next)).apply()
        return added
    }

    fun pinnedCountries(): Set<String> = prefs.getStringSet("pinned", emptySet()) ?: emptySet()

    fun togglePinned(code: String): Boolean {
        val next = pinnedCountries().toMutableSet()
        val added = if (code in next) { next.remove(code); false } else { next.add(code); true }
        prefs.edit().putStringSet("pinned", HashSet(next)).apply()
        return added
    }

    /** Same idea as pinned countries, one level down: a category pinned from
     *  Movies or Shows' own category column. Keyed by kind - Movies and Shows
     *  don't share a category namespace, and a provider could plausibly reuse
     *  the same category name for genuinely different things in each. */
    private fun categoryKey(kind: ContentKind, category: String) = "${kind.name}|$category"

    fun pinnedCategories(kind: ContentKind): Set<String> {
        val prefix = "${kind.name}|"
        return (prefs.getStringSet("pinned_categories", emptySet()) ?: emptySet())
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    fun isPinnedCategory(kind: ContentKind, category: String): Boolean =
        categoryKey(kind, category) in (prefs.getStringSet("pinned_categories", emptySet()) ?: emptySet())

    fun togglePinnedCategory(kind: ContentKind, category: String): Boolean {
        val k = categoryKey(kind, category)
        val next = (prefs.getStringSet("pinned_categories", emptySet()) ?: emptySet()).toMutableSet()
        val added = if (k in next) { next.remove(k); false } else { next.add(k); true }
        prefs.edit().putStringSet("pinned_categories", HashSet(next)).apply()
        return added
    }
}
