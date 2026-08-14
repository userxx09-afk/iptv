package io.tapper.firetv.data

import android.content.Context

/**
 * Manual channel -> guide id overrides.
 *
 * Automatic matching only works when the id a provider tags a channel with
 * happens to line up with the id its own XMLTV guide uses for that same
 * channel - two independently chosen strings that frequently don't agree, or
 * that the provider never set at all. When that happens the channel plays
 * fine but never carries a schedule, and until now there was no way to fix it
 * short of editing the playlist by hand. This lets one channel be pointed at
 * whichever guide id actually carries its listings, chosen once from the
 * channel's own menu.
 *
 * Keyed "sourceId|channelId", same convention as FavoritesStore, so an
 * override survives a playlist refresh (channel ids are stable across a
 * reload) but is scoped to the source it was set on.
 */
class EpgOverrideStore(context: Context) {

    private val prefs = context.getSharedPreferences("tapper_epg_overrides", Context.MODE_PRIVATE)

    private fun key(sourceId: String, channelId: String) = "$sourceId|$channelId"

    /** The guide id to use instead of the one the playlist declared, if any. */
    fun get(sourceId: String, channelId: String): String? =
        prefs.getString(key(sourceId, channelId), null)?.takeIf { it.isNotBlank() }

    fun set(sourceId: String, channelId: String, guideChannelId: String) {
        prefs.edit().putString(key(sourceId, channelId), guideChannelId).apply()
    }

    fun clear(sourceId: String, channelId: String) {
        prefs.edit().remove(key(sourceId, channelId)).apply()
    }

    /** Snapshot of every override, for Compose state that needs to react to changes. */
    fun all(): Map<String, String> =
        prefs.all.entries.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
}
