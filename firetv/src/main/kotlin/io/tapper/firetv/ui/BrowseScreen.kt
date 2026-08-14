package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.firetv.R
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.EpgDatabase.Programme
import io.tapper.firetv.data.FavoritesStore
import io.tapper.firetv.data.MovieMetadataStore
import io.tapper.firetv.data.NavHomeStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.PlaylistRepository.Group
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

internal fun kindLabel(kind: ContentKind): String = when (kind) {
    ContentKind.LIVE -> "Live TV"
    ContentKind.MOVIE -> "Movies"
    ContentKind.SERIES -> "Shows"
}


/**
 * Select and long-press handling that works on both a remote and a touchscreen.
 *
 * combinedClickable's onLongClick is driven by pointer input; a held D-pad
 * centre on a Fire TV remote does not reliably reach it, which is why the
 * context menu opened on a tablet but not on the TV. Key events are therefore
 * timed explicitly here, and consumed so nothing handles them twice. Touch
 * still goes through detectTapGestures.
 */
private fun Modifier.selectable(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier = composed {
    var downAt by remember { mutableLongStateOf(0L) }
    this
        .focusable(interactionSource = interaction)
        .pointerInput(Unit) {
            detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
        }
        .onKeyEvent { e ->
            val isSelect = e.key == Key.DirectionCenter || e.key == Key.Enter ||
                e.key == Key.NumPadEnter
            when {
                // Some remotes have a dedicated menu button; treat it as long-press.
                e.key == Key.Menu && e.type == KeyEventType.KeyUp -> { onLongPress(); true }
                !isSelect -> false
                e.type == KeyEventType.KeyDown -> {
                    if (downAt == 0L) downAt = System.currentTimeMillis()
                    true
                }
                e.type == KeyEventType.KeyUp -> {
                    val held = System.currentTimeMillis() - downAt
                    downAt = 0L
                    if (held >= 450) onLongPress() else onClick()
                    true
                }
                else -> false
            }
        }
}

/**
 * Five Miller-column levels: a persistent nav (search / kind switch /
 * recordings / my list), then country, then category, then the channel list,
 * then its guide. COUNTRY is always skipped for My List, which spans every
 * kind and has no country breakdown of its own; CATEGORY is repurposed for
 * it instead, as a Live TV / Movies / Shows breakdown - see myListActive and
 * myListKindFilter below.
 */
private enum class Depth { NAV, COUNTRY, CATEGORY, CHANNELS, GUIDE }

/**
 * What the COUNTRY/CATEGORY column area shows while the NAV list itself has
 * D-pad focus (depth == NAV) - kept live purely by each NAV row's onFocused,
 * the same "highlighting is enough to act on it" pattern a kind row already
 * used on its own before this existed. KIND previews the currently
 * highlighted kind's countries, same as always; MY_LIST previews My List's
 * own Live TV/Movies/Shows breakdown the same way. Search, Recordings and
 * Settings all leave this screen entirely and have no column of their own to
 * preview, so highlighting one of them is NONE - blanking the area rather
 * than leaving whatever kind or My List was last highlighted looking like it
 * still belongs to the row now highlighted.
 */
private enum class NavPreview { KIND, MY_LIST, NONE }

/**
 * Most accounts are watched overwhelmingly in one language, and re-picking
 * "United States" (or the nearest English-speaking equivalent) every single
 * time a kind is opened wastes a column most viewers never actually want.
 * Used only when there is no prior selection for this kind to restore - see
 * selectedCountry below - so a country picked on purpose is never overridden.
 */
private val PREFERRED_COUNTRIES = listOf("us", "gb", "ca", "au")

/** Sentinel key for the synthetic "Recently Watched" rail prepended to the
 *  country list - deliberately not a valid country token (see CategoryName's
 *  TOKENS map) so it can never collide with a real one. */
private const val RECENT_KEY = "__recent__"

private fun preferredCountry(groups: List<PlaylistRepository.Group>): PlaylistRepository.Group? =
    PREFERRED_COUNTRIES.firstNotNullOfOrNull { pref -> groups.firstOrNull { it.key == pref } }
        ?: groups.firstOrNull()

/** Matches a short leading tag regardless of how the provider chose to wrap
 *  it - "[EN] ...", "( EN ) ...", "|EN|...", "EN - ...", "EN: ..." are all
 *  the same tag underneath, and different endpoints on the same Xtream
 *  panel (get_vod_categories vs get_series_categories, here) turned out to
 *  use different conventions for it despite being the same provider. The
 *  tag must still be followed by a real delimiter, not just any letter -
 *  that's what stops this from misreading the first two letters of
 *  "English Movies" or "Entertainment" as the tag.
 */
private val LEADING_TAG = Regex("""^[\[(|\s]*([A-Za-z]{2,6})[\]) |:/~>_-]""")

private fun leadingTag(category: String): String? =
    LEADING_TAG.find(category.trim())?.groupValues?.get(1)?.uppercase()

/** Sort tier for a Movies/Shows category row - see the categoryCounts
 *  comment at its call site for why English and (for Shows) Multi are
 *  pinned ahead of the alphabetical order rather than left to fall wherever
 *  their name happens to sort. internal rather than private: SettingsScreen
 *  reuses this for the starting-point category picker, so the order shown
 *  there matches the order it'll actually appear in once picked. */
internal fun categoryRank(category: String, kind: ContentKind): Int {
    val tag = leadingTag(category)
    return when {
        tag == "EN" -> 0
        kind == ContentKind.SERIES && tag == "MULTI" -> 1
        else -> 2
    }
}

@Composable
fun BrowseScreen(
    catalogue: PlaylistRepository.Catalogue,
    repo: PlaylistRepository,
    favorites: FavoritesStore,
    sources: List<TvSource>,
    activeSource: TvSource,
    nowPlaying: Map<String, EpgDatabase.Programme>,
    epgStatus: String?,
    onPlay: (List<Channel>, Int) -> Unit,
    onSwitchSource: (TvSource) -> Unit,
    onAddSource: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRefreshEpg: () -> Unit,
    onOpenSeries: (Channel) -> Unit,
    scheduleFor: (Channel) -> List<Programme>,
    /** Resolves the guide id to use for a channel - a manual override first,
     *  falling back to whatever the playlist declared. */
    epgIdFor: (Channel) -> String,
    onSetGuideChannel: (Channel) -> Unit,
    /** Reminder/recording actions for a specific guide entry on the currently
     *  focused channel. ProgrammePanel only knows the Programme, not which
     *  channel it belongs to, so these are wrapped around focusedChannel at
     *  the call site below rather than threaded through ProgrammePanel. */
    hasReminder: (Channel, Programme) -> Boolean,
    onSetReminder: (Channel, Programme) -> Unit,
    onCancelReminder: (Channel, Programme) -> Unit,
    onRecord: (Channel, Programme) -> Unit,
    /** Ad-hoc "record now" for a channel with no specific guide entry chosen. */
    onRecordChannel: (Channel) -> Unit,
    onOpenRecordings: () -> Unit,
    /** Bumped whenever a manual override changes. The focused channel's
     *  schedule is loaded once per focus change (see the LaunchedEffect
     *  below), so without this an override set from this same channel's menu
     *  would not show up until the user refocused it. */
    epgRevision: Int = 0,
    initialChannelId: String?,
    onSelectionChanged: (channelId: String?) -> Unit,
    /** Optional fixed starting point per kind, set from Settings - see
     *  NavHomeStore. Consulted only when a kind is first entered (a NAV
     *  click, or arrowing right off a NAV row); it never overrides a
     *  selection already made by hand within the current visit. */
    navHome: NavHomeStore,
    /** Most-recently-watched item ids across the whole catalogue, newest
     *  first - drives the "Recently Watched" rail below. Live TV and movies
     *  are recorded under their own catalogue id, so they match directly;
     *  a watched series episode is recorded under the episode's id, which
     *  never matches a SERIES-kind catalogue entry (those are the series
     *  containers, not individual episodes) - so the rail simply has nothing
     *  to show there, rather than showing something wrong. */
    recentItemIds: List<String> = emptyList(),
    /** Poster/synopsis/rating lookup for a Movies or Shows item, backed by
     *  MovieMetadataStore - suspend because a cache miss means a real
     *  network call, run off the main thread by the caller. Returns null
     *  for a Live TV channel, same as MovieMetadataStore.lookup itself. */
    movieInfoFor: suspend (Channel) -> MovieMetadataStore.Metadata?,
    /** The reason the lookup just performed by [movieInfoFor] returned null,
     *  if it failed (bad key, network) rather than genuinely finding
     *  nothing - read right after [movieInfoFor] returns. See
     *  MovieMetadataStore.lastError for why this needs to be separate from
     *  the Metadata? result itself. */
    movieInfoLastError: () -> String?,
    /** Whether a TMDb API key is configured yet - drives MovieInfoPanel's
     *  "add a key in Settings" message versus actually attempting a lookup. */
    tmdbKeyConfigured: Boolean,
) {
    /**
     * Depth follows focus rather than clicks wherever there is a natural
     * "next column" to focus into (country -> category -> channel -> guide),
     * exactly the way the channel list used to pick up focus transferring in
     * from the rail. The nav column is the one exception: it has no single
     * natural next column (search and recordings leave the screen entirely,
     * and even My List - which does land on CATEGORY, like any other kind -
     * has no single one of its rows to force focus onto), so its rows commit
     * on click instead.
     *
     * Only the nav column minimizes once left behind - it stays on screen as
     * a compact icon strip, since a short glyph can stand in for "which
     * section" well enough to remain a useful shortcut back. Country,
     * category and the channel list have no such glyph for "which country" /
     * "which category" / "which channel", so each instead steps fully aside
     * and reclaims its width for whatever is next, reappearing the moment
     * focus backs out past it.
     */
    var depth by remember(catalogue.sourceId) { mutableStateOf(Depth.NAV) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    var schedule by remember { mutableStateOf<List<Programme>>(emptyList()) }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var revision by remember { mutableIntStateOf(0) }

    val kinds = catalogue.availableKinds
    var kind by remember(catalogue.sourceId) { mutableStateOf(kinds.firstOrNull() ?: ContentKind.LIVE) }
    // My List spans every kind - favourites are already sourceId|channelId
    // keyed with no kind baked in (see FavoritesStore), so there is nothing
    // to filter by here beyond "is it favourited at all".
    var myListActive by remember(catalogue.sourceId) { mutableStateOf(false) }
    // See NavPreview above. Starts at KIND to match the pre-existing default
    // (the country column already always previewed the initial kind).
    var navPreview by remember(catalogue.sourceId) { mutableStateOf(NavPreview.KIND) }

    val section = catalogue.section(kind)
    val pinned = remember(revision) { favorites.pinnedCountries() }
    // Capped at 15 per the request that started this - a "Recently Watched"
    // rail is meant as a shortcut back to what was on, not a second full
    // history browser.
    val recentChannels = remember(section, recentItemIds) {
        if (recentItemIds.isEmpty()) emptyList() else {
            val byId = section?.items?.associateBy { it.id } ?: emptyMap()
            recentItemIds.mapNotNull { byId[it] }.distinctBy { it.id }.take(15)
        }
    }
    val countryGroups = remember(catalogue, kind, pinned, recentChannels) {
        val groups = section?.byCountry.orEmpty()
        val (pin, rest) = groups.partition { it.key != null && it.key in pinned }
        val recentGroup = if (recentChannels.isEmpty()) emptyList()
            else listOf(Group(RECENT_KEY, "Recently Watched", recentChannels))
        recentGroup + pin + rest
    }
    // Always the top of the list - a fixed, explicit Home (see NavHomeStore)
    // if one is configured for this kind in Settings, otherwise the same
    // United-States-or-nearest default preferredCountry always used for a
    // kind with no Home set. This deliberately does NOT restore wherever the
    // user last happened to leave off: that was the previous behaviour, and
    // it meant opening a kind could land somewhere unpredictable depending on
    // whatever was last focused, possibly weeks ago, with no visible reason
    // why. A Home is the one deliberate exception to "always the top."
    //
    // Recently Watched is deliberately excluded from the preferredCountry
    // fallback (though it stays fully selectable from the list itself).
    // Movies and Shows almost always have no real per-country split -
    // byCountry is just one "Ungrouped" bucket - so falling through to
    // "whichever group is first" would have picked Recently Watched itself
    // whenever there was any watch history at all, quietly narrowing the
    // category breakdown down to only the 15 most recent titles instead of
    // the whole catalogue - the opposite of what a default is supposed to do.
    var selectedCountry by remember(countryGroups) {
        mutableStateOf(
            navHome.get(kind)?.countryKey?.let { hk -> countryGroups.firstOrNull { it.key == hk } }
                ?: preferredCountry(countryGroups.filterNot { it.key == RECENT_KEY })
        )
    }
    // A country change invalidates whatever category was chosen in the
    // previous one - carrying it over would silently narrow the new country
    // to a category it might not even have. Re-derived from Home rather than
    // always reset to null: this is what makes a Home's category apply
    // whenever selectedCountry lands on the Home's own country, regardless of
    // whether that happened because the Home just jumped it there or because
    // the newly-selected country simply happens to match it - either way, the
    // Home's category is exactly the category most likely wanted there. Any
    // other country resets to "All" the same as always.
    var categoryFilter by remember(selectedCountry) {
        mutableStateOf(navHome.get(kind)?.takeIf { it.countryKey == selectedCountry?.key }?.category)
    }
    // Same idea as Recently Watched in the country column, but for the
    // category column - the one Movies and Shows actually land on by
    // default, since they have no real country split for Recently Watched to
    // sit in front of otherwise. Mutually exclusive with categoryFilter. A
    // Home never targets History, so this always starts false.
    var historySelected by remember(selectedCountry) { mutableStateOf(false) }
    val pinnedCategories = remember(revision, kind) { favorites.pinnedCategories(kind) }

    val myListChannels = remember(revision, catalogue) {
        val ids = favorites.favorites()
        catalogue.channels.filter { "${it.sourceId}|${it.id}" in ids }.distinctBy { it.id }
    }
    // Which kind My List's CATEGORY-equivalent column is filtered to - null
    // means "All". Reset every time My List is freshly entered (rather than
    // carried over from a previous visit), the same way categoryFilter
    // resets on a fresh country - keyed on myListActive itself so it clears
    // going false->true, not on every recomposition while already inside it.
    var myListKindFilter by remember(myListActive) { mutableStateOf<ContentKind?>(null) }
    // One row per kind actually represented in My List, in ContentKind's own
    // declared order (Live TV, Movies, Shows) - a kind with zero favourites
    // gets no row at all rather than an always-present but perpetually empty
    // one.
    val myListKindCounts = remember(myListChannels) {
        ContentKind.entries.mapNotNull { k ->
            val count = myListChannels.count { it.kind == k }
            if (count > 0) k to count else null
        }
    }
    // Self-heals a filter pointed at a kind that has just emptied out (the
    // last item of that kind removed while still filtered to it) - without
    // this, shown's filter{it.kind==myListKindFilter} below matches nothing
    // forever, and neither the vanished kind's own row nor a visible reason
    // why remains on screen to get back to "All" from. Removing the very
    // last item of whichever kind is currently selected is exactly what
    // "remove everything, one at a time" naturally does, so this is not a
    // rare edge case for My List specifically.
    LaunchedEffect(myListKindCounts) {
        if (myListKindFilter != null && myListKindCounts.none { it.first == myListKindFilter }) {
            myListKindFilter = null
        }
    }
    val channelsInCountry = selectedCountry?.channels.orEmpty()
    // Movies/Shows (and anything else with no real per-country split) always
    // land selectedCountry on the single "Ungrouped" bucket that holds every
    // item of that kind - channelsInCountry above is then the WHOLE section,
    // not a per-country slice of it.
    val hasCountrySplit = section?.byCountry.orEmpty().any { it.key != null }
    // Alphabetical rather than by-size: with the count-sort gone, a category
    // stays put as the account's catalogue changes size over time instead of
    // hopping around the list on every refresh. English content is pinned to
    // the very top regardless, since it's what the large majority of viewers
    // on this app actually want and an alphabetical sort would otherwise
    // scatter "[EN] ..." wherever "E" happens to fall relative to a hundred
    // other-language categories; Shows additionally pins "[MULTI]" right
    // behind it, since a dubbed/multi-audio show is the next thing most
    // people scanning a show list specifically look for. Movies has no
    // equivalent second tier - nothing in the account's own categories
    // singled it out the way it did for Shows. A category pinned by hand
    // (long-press, same gesture as pinning a country) outranks even that -
    // it's the one signal stronger than an automatic guess about what people
    // generally want.
    val categoryCounts = remember(channelsInCountry, kind, pinnedCategories, hasCountrySplit) {
        // When there's no country split, channelsInCountry is the entire
        // section - identical in content to what index() already grouped by
        // category once, off the main thread, while loading. Re-scanning
        // every item again here on the main thread (mapNotNull + group +
        // count over a VOD catalogue that can run into the tens of
        // thousands) is what froze the UI for several seconds the first time
        // Movies or Shows was opened; reusing Section.byCategory instead
        // skips that rescan entirely for exactly the case large enough for
        // it to matter. Live TV, which does have a real per-country split,
        // is unaffected - each country's own channel count is small enough
        // that scanning it fresh here was never the expensive part.
        val counts: Map<String, Int> =
            if (!hasCountrySplit && section != null) {
                section.byCategory.filter { it.key != null }.associate { it.key!! to it.channels.size }
            } else {
                // Matches Section.byCategory's own grouping key exactly (see
                // PlaylistRepository.sectionFor) rather than the singular
                // group field, so a multi-category item ("Documentary;Series")
                // counts under both rows here the same way it already does
                // in the reused-section branch above and in the channel
                // filter below - a mismatch here used to mean a category row
                // could show a real count and then filter to nothing.
                channelsInCountry.flatMap { c -> c.categories.ifEmpty { listOfNotNull(c.group) }.distinct() }
                    .groupingBy { it }.eachCount()
            }
        counts.entries.sortedWith(
            compareBy<Map.Entry<String, Int>> { if (it.key in pinnedCategories) -1 else categoryRank(it.key, kind) }
                .thenBy { it.key.lowercase() }
        )
    }
    val baseChannels = if (myListActive) myListChannels else channelsInCountry
    val shown = remember(baseChannels, categoryFilter, myListActive, myListKindFilter, historySelected, recentChannels) {
        val filtered = when {
            myListActive -> if (myListKindFilter == null) baseChannels
                else baseChannels.filter { it.kind == myListKindFilter }
            historySelected -> recentChannels
            categoryFilter == null -> baseChannels
            // categories.ifEmpty{listOfNotNull(group)} mirrors exactly how
            // categoryCounts above (and Section.byCategory during indexing)
            // decide which category row(s) a channel belongs to - matching
            // it here against just the singular group field used to mean a
            // multi-category item ("Documentary;Series") could show a real
            // count in the category rail above and then filter to nothing
            // when actually selected.
            else -> baseChannels.filter {
                categoryFilter in it.categories.ifEmpty { listOfNotNull(it.group) }
            }
        }
        // Last line of defence. A duplicate key is not a glitch in LazyColumn,
        // it throws - and only once both copies are composed at the same time,
        // which is why it surfaced when scrolling back to the top rather than
        // on first display.
        filtered.distinctBy { it.id }
    }
    // "Ungrouped" is the byCountry bucket's own label for "no country token
    // found" - correct as an internal name for that bucket, but it reads as
    // if the category breakdown itself had failed when shown as a heading,
    // which for Movies/Shows (no real per-country split at all) it always
    // would be. The kind name ("Movies", "Shows") is what's actually true
    // there instead. Live TV countries are unaffected - a real one always
    // has a real label.
    val headingRoot = selectedCountry?.let { if (it.key == null) kindLabel(kind) else it.label }
        ?: kindLabel(kind)
    val heading =
        if (myListActive) "My List" + (myListKindFilter?.let { "  ·  ${kindLabel(it)}" } ?: "")
        else headingRoot + when {
            historySelected -> "  ·  History"
            categoryFilter != null -> "  ·  $categoryFilter"
            else -> ""
        }

    // Guide lookup is a database read; doing it per focus change on the main
    // thread would stutter the list, so it is keyed and debounced by focus.
    LaunchedEffect(focusedChannel?.id, epgRevision) {
        val ch = focusedChannel
        schedule = if (ch == null) emptyList() else runCatching { scheduleFor(ch) }.getOrDefault(emptyList())
    }

    // Movies/Shows equivalent of the schedule lookup above - see
    // MovieInfoPanel. Keyed on the channel id alone (not epgRevision, which
    // is a guide-only concept): re-focusing the same title while scrolling
    // cancels the previous in-flight lookup for free, the same way the
    // schedule fetch above already does, since LaunchedEffect restarts (and
    // cancels its prior coroutine) whenever its key changes.
    var movieInfo by remember { mutableStateOf<MovieMetadataStore.Metadata?>(null) }
    var movieInfoLoading by remember { mutableStateOf(false) }
    var movieInfoError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedChannel?.id) {
        val ch = focusedChannel
        if (ch == null || ch.kind == ContentKind.LIVE) {
            movieInfo = null
            movieInfoLoading = false
            movieInfoError = null
            return@LaunchedEffect
        }
        movieInfoLoading = true
        movieInfo = runCatching { movieInfoFor(ch) }.getOrNull()
        movieInfoError = movieInfoLastError()
        movieInfoLoading = false
    }

    // Set only when a column becomes active by stepping *back* into it (via
    // goBack below) or by an explicit click from the nav column - never by
    // arrowing right, which already lands focus correctly on its own via
    // Compose's normal focus search. Country, category and the channel list
    // are each reachable both ways, so blindly forcing focus on every depth
    // change would clobber the correct in-progress focus from a rightward
    // arrow-press; this flag scopes the forced re-focus to only the two
    // paths that actually need it.
    var pendingFocus by remember { mutableStateOf<Depth?>(null) }

    // Shared by the physical Back button and the D-pad LEFT arrow (wired onto
    // each column below) - both should step out exactly one level the same
    // way.
    // Movies/Shows normally have no real per-country split - byCountry is
    // just one "Ungrouped" bucket - so the COUNTRY column has nothing in it
    // worth stopping at for those kinds. Live TV (and any source that really
    // does group by country) is unaffected.
    fun hasRealCountries(k: ContentKind) = catalogue.section(k)?.byCountry.orEmpty().any { it.key != null }

    fun goBack() {
        val next = when (depth) {
            Depth.GUIDE -> Depth.CHANNELS
            // My List now has a real CATEGORY-equivalent column (the kind
            // breakdown - see categoryListBody below), so it steps back into
            // that exactly like every other kind, rather than jumping
            // straight past it to NAV.
            Depth.CHANNELS -> Depth.CATEGORY
            // My List has no COUNTRY column at all (it spans every kind), so
            // it always steps from CATEGORY straight to NAV, same as a kind
            // with no real per-country split.
            Depth.CATEGORY -> if (!myListActive && hasRealCountries(kind)) Depth.COUNTRY else Depth.NAV
            Depth.COUNTRY -> Depth.NAV
            Depth.NAV -> Depth.NAV
        }
        depth = next
        if (next != Depth.NAV) pendingFocus = next
    }

    BackHandler(enabled = depth != Depth.NAV) { goBack() }

    // Shared by the NAV row's onClick and by arrowing DirectionRight off the
    // NAV column (see rightArrowEntersKind below) - both should land on
    // exactly the same starting point for a kind, whether reached by click or
    // by arrow key. That starting point is, in priority order: a Settings
    // Home if one is configured (jump straight to the channel list - country
    // and category are both already pinned down by selectedCountry's and
    // categoryFilter's own remember blocks above, which read navHome
    // themselves); otherwise the category rail directly for a kind with no
    // real per-country split (skipping the empty COUNTRY column); otherwise
    // the channel list, with the country rail one LEFT-arrow away for anyone
    // who wants a different one.
    fun enterKind(k: ContentKind) {
        myListActive = false
        kind = k
        when {
            navHome.get(k) != null -> { depth = Depth.CHANNELS; pendingFocus = Depth.CHANNELS }
            !hasRealCountries(k) -> { depth = Depth.CATEGORY; pendingFocus = Depth.CATEGORY }
            else -> { depth = Depth.CHANNELS; pendingFocus = Depth.CHANNELS }
        }
    }

    val navWidth by animateDpAsState(if (depth == Depth.NAV) 300.dp else 64.dp, label = "nav")
    // Each of these is only ever rendered at its 0.dp target - the full/peek
    // states below use weight(1f) or a fixed dp directly instead. The
    // non-zero branch exists purely so the shrink has a real width to
    // animate from the instant its column is left behind.
    val countryHiddenWidth by animateDpAsState(
        if ((depth == Depth.NAV && navPreview == NavPreview.KIND) || depth == Depth.COUNTRY) 340.dp else 0.dp,
        label = "country",
    )
    val categoryWidth by animateDpAsState(
        when (depth) { Depth.COUNTRY -> 260.dp; else -> 0.dp }, label = "category"
    )
    val channelPeekWidth by animateDpAsState(
        when (depth) { Depth.CATEGORY -> 300.dp; else -> 0.dp }, label = "channelPeek"
    )
    val channelFullWidth by animateDpAsState(if (depth == Depth.GUIDE) 0.dp else 320.dp, label = "channelFull")
    val guideWidth by animateDpAsState(
        when (depth) { Depth.CHANNELS -> 300.dp; Depth.GUIDE -> 460.dp; else -> 0.dp }, label = "guide"
    )

    // requestFocus() throws if its target hasn't attached to a composed node
    // on this frame yet - a known Compose race, and more likely the more a
    // column's content just changed shape (My List's breakdown gaining a
    // second kind row, for instance). A single unguarded attempt used to
    // swallow that failure via runCatching and simply give up, which could
    // leave the column with nothing explicitly focused at all: Up/Down still
    // work (that's Compose's own spatial search, not app code), but Left,
    // long-press and Back all bubble up from a focused row that no longer
    // exists, so they go silently dead. A few retries a frame apart gives
    // the target node a real chance to attach before giving up for good.
    suspend fun requestFocusRetrying(target: FocusRequester) {
        repeat(5) { attempt ->
            if (runCatching { target.requestFocus() }.isSuccess) return
            if (attempt < 4) delay(16)
        }
    }

    val firstNavFocus = remember { FocusRequester() }
    // Re-requested whenever the nav re-expands: the collapsed icon strip that
    // had focus is removed from the tree at that moment, and without this
    // the D-pad would have nothing focused and appear dead.
    LaunchedEffect(depth) {
        if (depth == Depth.NAV) requestFocusRetrying(firstNavFocus)
    }

    // One per column that can be stepped *back* into (see pendingFocus
    // above). GUIDE has none - goBack() only ever moves to a shallower
    // depth, so GUIDE is never a target of it.
    val firstCountryFocus = remember { FocusRequester() }
    val firstCategoryFocus = remember { FocusRequester() }
    val firstChannelFocus = remember { FocusRequester() }
    LaunchedEffect(pendingFocus) {
        when (pendingFocus) {
            Depth.COUNTRY -> requestFocusRetrying(firstCountryFocus)
            Depth.CATEGORY -> requestFocusRetrying(firstCategoryFocus)
            Depth.CHANNELS -> requestFocusRetrying(firstChannelFocus)
            else -> {}
        }
        pendingFocus = null
    }

    // Consumes DirectionLeft bubbling up from a focused row and steps back
    // one level, the mirror image of arrowing right to drill in. The shared
    // Modifier.selectable() extension never consumes non-select/menu keys, so
    // LEFT always bubbles up from whichever row has focus to reach this.
    //
    // Both KeyDown and KeyUp for DirectionLeft are consumed here, not just
    // KeyUp. Consuming only KeyUp (the original version of this) left every
    // KeyDown for LEFT unconsumed, and Compose's own built-in D-pad focus
    // search reacts to that unconsumed KeyDown by moving focus on its own,
    // spatially, before goBack() below ever runs. With the columns to the
    // left collapsed to 0dp at most depths, the nearest real focusable target
    // for that search is the collapsed nav icon - not whatever goBack() would
    // have stepped back to - which is exactly the overshoot-to-"TV" jump this
    // was reported as. Swallowing KeyDown too means the framework's own
    // search never sees the key at all, and goBack() is the only thing that
    // acts on it.
    val leftArrowGoesBack = Modifier.onKeyEvent { e ->
        if (e.key != Key.DirectionLeft) return@onKeyEvent false
        if (e.type == KeyEventType.KeyUp) goBack()
        true
    }

    // Mirror image of leftArrowGoesBack, on the NAV column: arrowing RIGHT
    // off a highlighted kind row should land exactly where clicking it would
    // (see enterKind above), not wherever Compose's default spatial focus
    // search happens to find - which, before this, was always the COUNTRY
    // column even for Movies/Shows, landing on a column with nothing grouped
    // in it. Gated on navPreview rather than myListActive: only a kind row
    // actually being highlighted (navPreview == KIND) means enterKind(kind)
    // is the right thing to run. My List being highlighted instead
    // (navPreview == MY_LIST) already has real, focusable content to its
    // right by then - its own kind breakdown, see the CATEGORY column's
    // NavPreview.MY_LIST case below - so it's left to Compose's own spatial
    // search, which finds it correctly on its own. Search/Recordings/
    // Settings (navPreview == NONE) have no column at all - every column has
    // collapsed to 0dp for them - so that same default search simply finds
    // nothing and does nothing, rather than the wrong thing.
    val rightArrowEntersKind = Modifier.onKeyEvent { e ->
        if (e.key != Key.DirectionRight || navPreview != NavPreview.KIND) return@onKeyEvent false
        if (e.type == KeyEventType.KeyUp) enterKind(kind)
        true
    }

    // Scrolls the channel list so the channel that was playing is on screen.
    val listState = rememberLazyListState()
    LaunchedEffect(shown, initialChannelId) {
        val idx = shown.indexOfFirst { it.id == initialChannelId }
        if (idx >= 0) runCatching { listState.scrollToItem(idx) }
    }

    @Composable
    fun channelListBody() {
        if (shown.isEmpty()) {
            // A kind with no items at all (as opposed to just this
            // country/category having none) gets a specific reason when one
            // is known, rather than the same generic line regardless of
            // whether the account has nothing or the fetch actually failed -
            // the two look identical otherwise, and a real failure on an
            // account that does have VOD was exactly what went unnoticed
            // before this tab existed for an empty result at all.
            val kindEmpty = section?.items.isNullOrEmpty()
            val kindError = catalogue.sectionErrors[kind]
            Text(
                when {
                    // myListKindFilter is a delegated var (by remember), not
                    // smart-castable from the null check above - hence the
                    // explicit !! rather than relying on that check alone.
                    myListActive && myListKindFilter != null ->
                        "No ${kindLabel(myListKindFilter!!).lowercase()} in My List yet."
                    myListActive -> "Nothing in My List yet. Long-press a channel to add one."
                    kindEmpty && kindError != null ->
                        "Couldn't load ${kindLabel(kind).lowercase()}: $kindError"
                    kindEmpty ->
                        "This account has no ${kindLabel(kind).lowercase()} available. " +
                            "If your provider says it should, try Refresh guide from the source menu."
                    else -> "No channels here."
                },
                style = MaterialTheme.typography.bodyLarge, color = Dim,
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(shown, key = { _, it -> "${it.sourceId}|${it.id}" }) { i, ch ->
                val isFav = remember(revision, ch.id) { favorites.isFavorite(ch.sourceId, ch.id) }
                // A series is a container, not a stream: opening it lists
                // episodes rather than handing an empty URL to the player.
                val activate: () -> Unit =
                    if (ch.kind == ContentKind.SERIES) ({ onOpenSeries(ch) })
                    else ({ onPlay(shown.filter { it.isPlayable }, i) })
                ChannelRow(
                    channel = ch,
                    favorite = isFav,
                    modifier = if (i == 0) Modifier.focusRequester(firstChannelFocus) else Modifier,
                    onFocused = {
                        focusedChannel = ch
                        onSelectionChanged(ch.id)
                        if (depth == Depth.CATEGORY) depth = Depth.CHANNELS
                    },
                    programme = nowPlaying[epgIdFor(ch)],
                    onClick = activate,
                    onLongPress = {
                        menu = {
                            ItemMenu(
                                title = ch.name,
                                subtitle = nowPlaying[epgIdFor(ch)]?.title ?: ch.group,
                                actions = listOf(
                                    MenuAction(
                                        if (ch.kind == ContentKind.SERIES) "Open" else "Play"
                                    ) { activate() },
                                    MenuAction(
                                        if (isFav) "Remove from My List" else "Add to My List"
                                    ) { favorites.toggle(ch.sourceId, ch.id); revision++ },
                                ) + if (ch.kind == ContentKind.LIVE) listOf(
                                    // The fix for a channel whose guide never
                                    // loads: point it at whichever guide id
                                    // actually carries its listings.
                                    MenuAction("Set program guide...") { onSetGuideChannel(ch) },
                                    // No specific guide entry chosen here -
                                    // just capture from now for a default
                                    // window; see RecordingsScreen to stop
                                    // it early.
                                    MenuAction("Record now") { onRecordChannel(ch) },
                                ) else emptyList(),
                                onDismiss = { menu = null },
                            )
                        }
                    },
                )
            }
        }
    }

    @Composable
    fun categoryListBody() {
        // My List spans every kind, so this column is repurposed here as a
        // Live TV / Movies / Shows breakdown instead of the usual
        // History/All/category rail - there is no country selected to derive
        // any of that from. A plain early return rather than folding this
        // into the branches below: nothing past this point (channelsInCountry,
        // categoryCounts, historySelected, pinned categories) means anything
        // for My List.
        if (myListActive) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    RailRow(
                        title = "All",
                        subtitle = "${myListChannels.size}",
                        selected = myListKindFilter == null,
                        modifier = Modifier.focusRequester(firstCategoryFocus),
                        // Promotes depth the same way every other branch of
                        // this function does (History/All/category rows
                        // below) when focus actually lands in this column -
                        // this one was missing it, which is exactly why
                        // arrowing RIGHT into My List's breakdown left depth
                        // stuck on NAV: goBack()'s Depth.NAV branch is a
                        // no-op and BackHandler is disabled at depth==NAV, so
                        // both LEFT and Back did nothing once focus was here.
                        // Selecting worked because onClick on the NAV row
                        // (see "My List" below) sets depth explicitly -
                        // arrowing right never went through that path.
                        onFocused = {
                            myListKindFilter = null
                            if (depth == Depth.NAV) depth = Depth.CATEGORY
                        },
                        onClick = { myListKindFilter = null },
                        onLongPress = {},
                    )
                }
                items(myListKindCounts, key = { (k, _) -> k.name }) { (k, count) ->
                    RailRow(
                        title = kindLabel(k),
                        subtitle = "$count",
                        selected = myListKindFilter == k,
                        onFocused = {
                            myListKindFilter = k
                            if (depth == Depth.NAV) depth = Depth.CATEGORY
                        },
                        onClick = { myListKindFilter = k },
                        onLongPress = {},
                    )
                }
            }
            return
        }
        // First real-world player this was compared against puts a "History"
        // rail at the very top of exactly this column - Movies and Shows
        // land here directly (see the NAV onClick logic above), since
        // neither has a real per-country split, so this is where a shortcut
        // back to what was recently watched actually needs to live to be
        // useful; the country column's own Recently Watched entry is one
        // LEFT-arrow further away and effectively hidden for those two kinds.
        val showHistory = recentChannels.isNotEmpty()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (showHistory) {
                item {
                    RailRow(
                        title = "History",
                        subtitle = "${recentChannels.size}",
                        selected = historySelected,
                        modifier = Modifier.focusRequester(firstCategoryFocus),
                        onFocused = {
                            historySelected = true; categoryFilter = null
                            if (depth == Depth.COUNTRY) depth = Depth.CATEGORY
                        },
                        onClick = { historySelected = true; categoryFilter = null },
                        onLongPress = {},
                    )
                }
            }
            item {
                RailRow(
                    title = "All",
                    subtitle = "${channelsInCountry.size}",
                    selected = !historySelected && categoryFilter == null,
                    modifier = if (showHistory) Modifier else Modifier.focusRequester(firstCategoryFocus),
                    onFocused = {
                        historySelected = false; categoryFilter = null
                        if (depth == Depth.COUNTRY) depth = Depth.CATEGORY
                    },
                    onClick = { historySelected = false; categoryFilter = null },
                    onLongPress = {},
                )
            }
            items(categoryCounts, key = { it.key }) { entry ->
                val isPinned = entry.key in pinnedCategories
                RailRow(
                    title = if (isPinned) "[pin] ${entry.key}" else entry.key,
                    subtitle = "${entry.value}",
                    selected = !historySelected && categoryFilter == entry.key,
                    onFocused = {
                        historySelected = false; categoryFilter = entry.key
                        if (depth == Depth.COUNTRY) depth = Depth.CATEGORY
                    },
                    onClick = { historySelected = false; categoryFilter = entry.key },
                    onLongPress = {
                        val category = entry.key
                        menu = {
                            ItemMenu(
                                title = entry.key,
                                subtitle = "${entry.value} titles",
                                actions = listOf(
                                    MenuAction(if (isPinned) "Unpin from top" else "Pin to top") {
                                        favorites.togglePinnedCategory(kind, category); revision++
                                    }
                                ),
                                onDismiss = { menu = null },
                            )
                        }
                    },
                )
            }
        }
    }

    Row(
        Modifier.fillMaxSize().background(Backdrop)
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        // NAV
        if (depth == Depth.NAV) {
            Column(Modifier.width(navWidth).fillMaxHeight().then(rightArrowEntersKind)) {
                SourceHeader(activeSource, epgStatus) {
                    menu = {
                        ItemMenu(
                            title = "Sources",
                            subtitle = "Currently: ${activeSource.name}",
                            actions = sources.filter { it.id != activeSource.id }
                                .map { s -> MenuAction("Switch to ${s.name}") { onSwitchSource(s) } }
                                + MenuAction("Add IPTV service...") { onAddSource() }
                                + MenuAction("Refresh guide") { onRefreshEpg() },
                            onDismiss = { menu = null },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        RailRow(
                            title = "Search", subtitle = "", selected = false,
                            modifier = Modifier.focusRequester(firstNavFocus),
                            // Leaves the screen entirely, same as Recordings/
                            // Settings below - see NavPreview.
                            onFocused = { navPreview = NavPreview.NONE },
                            onClick = onSearch, onLongPress = {},
                        )
                    }
                    items(kinds, key = { it.name }) { k ->
                        val count = catalogue.section(k)?.items?.size ?: 0
                        RailRow(
                            title = kindLabel(k), subtitle = "$count",
                            selected = !myListActive && kind == k,
                            // Highlighting a row is enough to act on it, same
                            // as country/category/channel rows already do -
                            // arrowing down to Shows (or pressing right once
                            // it's highlighted) gets there without a separate
                            // select press. Only the state updates here, not
                            // depth: this fires while still sitting in NAV, so
                            // changing depth would collapse the list out from
                            // under an arrow key that's still moving through
                            // it. The country rail to the right (already
                            // visible at this depth) updates live instead,
                            // exactly like arrowing through countries already
                            // updates the category rail next to it.
                            onFocused = { myListActive = false; kind = k; navPreview = NavPreview.KIND },
                            // See enterKind above for exactly where this
                            // lands - a Home if one's configured, otherwise
                            // straight to the category rail for a kind with
                            // no real per-country split, otherwise the
                            // channel list. Arrowing DirectionRight off this
                            // same row (rightArrowEntersKind, on the Column
                            // above) lands identically, so a click and an
                            // arrow-press never disagree.
                            onClick = { enterKind(k) },
                            onLongPress = {},
                        )
                    }
                    item {
                        RailRow(
                            title = "Recordings", subtitle = "", selected = false,
                            onFocused = { navPreview = NavPreview.NONE },
                            onClick = onOpenRecordings, onLongPress = {},
                        )
                    }
                    item {
                        RailRow(
                            title = "My List", subtitle = "${myListChannels.size}",
                            selected = myListActive,
                            // Same "highlighting is enough" live preview as a
                            // kind row: arrowing over My List (no click
                            // needed) previews its Live TV/Movies/Shows
                            // breakdown in the CATEGORY column right away -
                            // see the myListActive branch of
                            // categoryListBody, and its NavPreview.MY_LIST
                            // render condition below.
                            onFocused = { myListActive = true; navPreview = NavPreview.MY_LIST },
                            // Lands on the kind breakdown (categoryListBody's
                            // myListActive branch above), same as any other
                            // kind with no real country split - not straight
                            // on the channel list, now that My List has a
                            // real column to land on instead.
                            onClick = { myListActive = true; depth = Depth.CATEGORY; pendingFocus = Depth.CATEGORY },
                            onLongPress = {},
                        )
                    }
                    item {
                        RailRow(
                            title = "Settings", subtitle = "", selected = false,
                            onFocused = { navPreview = NavPreview.NONE },
                            onClick = onSettings, onLongPress = {},
                        )
                    }
                }
                // Small brand mark anchored under Settings at the bottom of
                // the nav column - the app icon rather than a new asset, so
                // there is nothing extra to ship. Only ever visible at the
                // full 300.dp nav width (this whole branch only composes
                // there), so it never needs to shrink or hide itself.
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .alpha(0.55f),
                )
            }
        } else {
            CollapsedNavIcon(
                width = navWidth,
                glyph = navGlyph(myListActive, kind),
                onClick = { depth = Depth.NAV },
            )
        }
        Spacer(Modifier.width(if (depth == Depth.NAV) 32.dp else 20.dp))

        // COUNTRY
        if ((depth == Depth.NAV && navPreview == NavPreview.KIND) || depth == Depth.COUNTRY) {
            Column(Modifier.weight(1f).fillMaxHeight().then(leftArrowGoesBack)) {
                Text(kindLabel(kind), style = MaterialTheme.typography.headlineLarge, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(countryGroups, key = { _, g -> g.key ?: " " }) { i, g ->
                        val isPinned = g.key != null && g.key in pinned
                        RailRow(
                            title = if (g.key == RECENT_KEY) g.label
                                else if (isPinned) "[pin] ${g.label}" else g.label,
                            subtitle = "${g.channels.size}",
                            selected = g == selectedCountry,
                            modifier = if (i == 0) Modifier.focusRequester(firstCountryFocus) else Modifier,
                            onFocused = {
                                if (selectedCountry != g) selectedCountry = g
                                if (depth == Depth.NAV) depth = Depth.COUNTRY
                            },
                            onClick = { selectedCountry = g },
                            onLongPress = {
                                val key = g.key
                                // Pinning is a real-country concept; the
                                // synthetic Recently Watched entry always sits
                                // first on its own and has nothing to pin.
                                if (key != null && key != RECENT_KEY) {
                                    menu = {
                                        ItemMenu(
                                            title = g.label,
                                            subtitle = "${g.channels.size} channels",
                                            actions = listOf(
                                                MenuAction(if (isPinned) "Unpin from top" else "Pin to top") {
                                                    favorites.togglePinned(key); revision++
                                                }
                                            ),
                                            onDismiss = { menu = null },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
        } else {
            Spacer(Modifier.width(countryHiddenWidth))
        }

        // CATEGORY
        // A single call site for categoryListBody() at all times it is on
        // screen, full-width or peeking, with only the Modifier varying - not
        // two separate call sites picked by a `when`. Two call sites are two
        // distinct slot-table positions to Compose, so switching between them
        // (exactly when a row's onFocused advances depth) unmounted and
        // remounted the list, destroying its focus; that was the cause of
        // arrowing right landing back on column 1 instead of this column.
        // The third condition is My List's own live NAV preview (see
        // NavPreview) - full-width here too, the same way the COUNTRY column
        // above goes full-width to preview a highlighted kind at depth==NAV,
        // since there is no COUNTRY column shown alongside it to share space
        // with in that case.
        if (depth == Depth.COUNTRY || depth == Depth.CATEGORY || (depth == Depth.NAV && navPreview == NavPreview.MY_LIST)) {
            val full = depth == Depth.CATEGORY || (depth == Depth.NAV && navPreview == NavPreview.MY_LIST)
            Column(
                Modifier
                    .then(if (full) Modifier.weight(1f) else Modifier.width(260.dp))
                    .fillMaxHeight()
                    .then(leftArrowGoesBack)
            ) {
                if (full) {
                    Text(heading, style = MaterialTheme.typography.headlineLarge, color = Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
                categoryListBody()
            }
            Spacer(Modifier.width(20.dp))
        } else {
            Spacer(Modifier.width(categoryWidth))
        }

        // CHANNEL
        // Same single-call-site fix as CATEGORY above. My List now reaches
        // this the same way every other kind does - via CATEGORY - so it no
        // longer needs its own (depth == Depth.NAV && myListActive) case.
        if (depth == Depth.CATEGORY || depth == Depth.CHANNELS) {
            val full = depth == Depth.CHANNELS
            Column(
                Modifier
                    .then(if (full) Modifier.weight(1f) else Modifier.width(300.dp))
                    .fillMaxHeight()
                    .then(leftArrowGoesBack)
            ) {
                if (full) {
                    Text(heading, style = MaterialTheme.typography.headlineLarge, color = Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
                channelListBody()
            }
            Spacer(Modifier.width(20.dp))
        } else if (depth == Depth.GUIDE) {
            Spacer(Modifier.width(channelFullWidth))
        } else {
            Spacer(Modifier.width(channelPeekWidth))
        }

        // GUIDE
        // Live TV gets its schedule (ProgrammePanel); Movies/Shows have no
        // schedule to show and get their TMDb-backed poster/synopsis/rating
        // instead (MovieInfoPanel) - branched on the focused channel's own
        // kind, not the currently browsed kind, so it's always correct even
        // in the instant focus is transferring between columns. This is a
        // genuinely different composable per branch, unlike categoryListBody
        // elsewhere in this file which deliberately keeps one call site -
        // but this column is never mid-focus at the moment its content would
        // switch (focusedChannel only changes via the CHANNELS list, which
        // is a different column), so there is no focus to lose here.
        if (guideWidth > 0.dp) {
            Spacer(Modifier.width(20.dp))
            val guideModifier = Modifier
                .then(if (depth == Depth.GUIDE) Modifier.weight(1f) else Modifier.width(guideWidth))
                .fillMaxHeight()
                .then(leftArrowGoesBack)
            if (focusedChannel?.kind == ContentKind.LIVE || focusedChannel == null) {
                ProgrammePanel(
                    channel = focusedChannel,
                    schedule = schedule,
                    expanded = depth == Depth.GUIDE,
                    onFocused = { depth = Depth.GUIDE },
                    // Wrapped around whichever channel is currently focused:
                    // read at call time (not captured once), so these stay
                    // correct as focus moves between channels without
                    // ProgrammePanel itself needing to know about channels
                    // at all.
                    hasReminder = { p -> focusedChannel?.let { hasReminder(it, p) } ?: false },
                    onSetReminder = { p -> focusedChannel?.let { onSetReminder(it, p) } },
                    onCancelReminder = { p -> focusedChannel?.let { onCancelReminder(it, p) } },
                    onRecord = { p -> focusedChannel?.let { onRecord(it, p) } },
                    modifier = guideModifier,
                )
            } else {
                MovieInfoPanel(
                    channel = focusedChannel,
                    info = movieInfo,
                    loading = movieInfoLoading,
                    error = movieInfoError,
                    apiKeyConfigured = tmdbKeyConfigured,
                    expanded = depth == Depth.GUIDE,
                    onFocused = { depth = Depth.GUIDE },
                    modifier = guideModifier,
                )
            }
        }
    }

    menu?.invoke()
}

@Composable
private fun SourceHeader(source: TvSource, epgStatus: String?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("SOURCE", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Text(source.name, style = MaterialTheme.typography.titleMedium, color = Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        epgStatus?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(
                if (active || focused) Focus.copy(alpha = if (active) 0.28f else 0.16f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(1.dp, if (focused || active) Focus else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = if (active || focused) Ink else Dim, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }

    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp))
            .selectable(
                interaction = interaction,
                onClick = { onFocused(); onClick() },
                onLongPress = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (focused || selected) Ink else Dim,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            // Scrolls only the focused row, and only once, rather than
            // resorting to an ellipsis for a country/category name too long
            // to fit - a raw, un-split category string ("US[Global
            // Sports]|PGA Tour Main Camera...") is exactly the case that
            // needed this: without it, everything past the ellipsis was
            // simply invisible unless you drilled in.
            modifier = Modifier.weight(1f).then(if (focused) Modifier.basicMarquee() else Modifier))
        if (subtitle.isNotEmpty()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Dim)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    favorite: Boolean,
    programme: EpgDatabase.Programme?,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocused() }

    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp))
            .selectable(
                interaction = interaction,
                onClick = onClick,
                onLongPress = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(model = channel.logoUrl, contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(64.dp))
        }
        Column(Modifier.weight(1f)) {
            // Scrolls only while this specific row is focused - a full name
            // like "US[Global Sports]|PGA Tour Main Camera Global Cast
            // (2026-08-13:09:00:20)" previously just vanished past the
            // ellipsis with no way to read the rest without opening it.
            Text(channel.name, style = MaterialTheme.typography.bodyLarge, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee() else Modifier)
            // Guide line when it exists, otherwise the category - never blank,
            // so rows keep a consistent height whether or not EPG has loaded.
            Text(
                programme?.title ?: channel.group ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (programme != null) Focus else Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee() else Modifier,
            )
        }
        if (favorite) {
            Text("*", style = MaterialTheme.typography.titleMedium, color = Focus)
            Spacer(Modifier.width(12.dp))
        }
        if (channel.streams.size > 1) {
            Text("${channel.streams.size} feeds",
                style = MaterialTheme.typography.bodyMedium, color = Dim)
        }
    }
}

/**
 * The nav minimized to a small icon strip. Stays fully focusable and
 * clickable so the way back up is always one press away - country, category
 * and the channel list step fully aside as focus moves past them (see
 * BrowseScreen above) instead, because none of them has a glyph that stands
 * in for its selection the way this one does for "which section".
 */
@Composable
private fun CollapsedNavIcon(
    width: Dp,
    glyph: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("<", style = MaterialTheme.typography.titleMedium, color = if (focused) Ink else Dim)
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .width(48.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (focused) Focus.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = MaterialTheme.typography.bodyMedium,
                color = if (focused) Ink else Dim, maxLines = 1)
        }
    }
}

/** A short stand-in for whichever nav section is active, once the nav has
 *  minimized to an icon: a star for My List, matching the "*" marker
 *  already used for a favourited channel, otherwise a short abbreviation of
 *  the current kind. */
private fun navGlyph(myListActive: Boolean, kind: ContentKind): String =
    if (myListActive) "*" else when (kind) {
        ContentKind.LIVE -> "TV"
        ContentKind.MOVIE -> "MOV"
        ContentKind.SERIES -> "SHW"
    }
