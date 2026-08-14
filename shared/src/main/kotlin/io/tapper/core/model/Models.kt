package io.tapper.core.model

data class StreamRef(
    val url: String,
    val priority: Int,
    /** ~880 channels in the default playlist 403 without these. */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * What kind of thing an item is.
 *
 * Detecting this from an M3U is subtle: iptv-org's playlist has 624 entries in
 * a "Movies" group-title and 398 in "Series", but every one is a *live channel
 * that broadcasts films*, not video on demand. Classifying by category name
 * would misfile over a thousand live channels. Only URL shape is reliable.
 */
enum class ContentKind { LIVE, MOVIE, SERIES }

data class Channel(
    val id: String,
    val sourceId: String,
    val name: String,
    val number: Int?,
    val logoUrl: String?,
    val group: String?,
    val countryCode: String?,
    val epgChannelId: String?,
    /** Empty only for SERIES, which are containers rather than playable items. */
    val streams: List<StreamRef> = emptyList(),
    val kind: ContentKind = ContentKind.LIVE,
    /**
     * Every category this item belongs to. group-title is often
     * semicolon-delimited ("Movies;Series", "Documentary;Series"); collapsing
     * that to a single value discards a grouping the provider intended.
     */
    val categories: List<String> = emptyList(),
    /** Set on SERIES entries; used to fetch episodes on demand. */
    val seriesId: String? = null,
) {
    val primaryUrl: String? get() = streams.firstOrNull()?.url
    val isPlayable: Boolean get() = streams.isNotEmpty()
}

data class M3uResult(
    val channels: List<Channel>,
    val declaredEpgUrls: List<String>,
)
