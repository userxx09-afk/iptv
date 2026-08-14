package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tapper.core.model.ContentKind
import io.tapper.firetv.data.BufferSize
import io.tapper.firetv.data.NavHomeStore
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.SupplementalEpgSource
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings, and the recovery route out of a broken source.
 *
 * This screen exists because the previous build could strand the app: if the
 * active source failed to load, the error screen offered nothing but the error.
 * Source configuration lives in preferences, not in an editable file, so there
 * was no way back short of clearing app storage. Settings is therefore
 * reachable from the failure screen, not only from a working one.
 */
@Composable
fun SettingsScreen(
    sources: List<TvSource>,
    activeId: String,
    guideSummary: String,
    onSwitchSource: (TvSource) -> Unit,
    onAddSource: () -> Unit,
    onRemoveSource: (TvSource) -> Unit,
    onSetEpgUrl: (TvSource, String?) -> Unit,
    onPickLogoFolder: (TvSource) -> Unit,
    onClearLogoFolder: (TvSource) -> Unit,
    onRefreshGuide: () -> Unit,
    onClearCache: () -> Unit,
    /** One tap, not the ten-tap gesture this replaces as the reliable way to
     *  do the same thing - see MainActivity's addHiddenXtreamSource. */
    hiddenSourceAdded: Boolean,
    onAddHiddenSource: () -> Unit,
    supplementalEpgSources: List<SupplementalEpgSource>,
    onAddSupplementalEpg: (name: String, guideUrl: String, channelIdsCsv: String) -> Unit,
    onRemoveSupplementalEpg: (id: String) -> Unit,
    appVersion: String,
    lastCrash: String?,
    onClearCrash: () -> Unit,
    onCopyCrash: () -> Unit,
    /** Load/guide failures that were caught and recovered from - a running
     *  log, always shown (with a "nothing logged yet" placeholder when
     *  empty), unlike lastCrash above which only appears once something
     *  fatal has actually happened. */
    errorLog: String?,
    onClearErrorLog: () -> Unit,
    onCopyErrorLog: () -> Unit,
    resumeLastChannel: Boolean,
    onSetResumeLast: (Boolean) -> Unit,
    bufferSize: BufferSize,
    onSetBufferSize: (BufferSize) -> Unit,
    syncSummary: String,
    syncBusy: Boolean,
    onPickFolder: () -> Unit,
    onSaveWebDav: (String, String, String) -> Unit,
    onSyncNow: () -> Unit,
    onDisableSync: () -> Unit,
    onExit: () -> Unit,
    /** Null when Settings is reached without ever having loaded a source
     *  successfully (see the failure-screen route this screen also serves) -
     *  the STARTING POINT section below has nothing to offer in that case. */
    catalogue: PlaylistRepository.Catalogue?,
    /** Only used for its countryLabel() helper, to turn a saved Home's raw
     *  country code back into a display label. */
    repo: PlaylistRepository,
    navHomeFor: (ContentKind) -> NavHomeStore.Home?,
    onSetNavHome: (ContentKind, countryKey: String?, category: String?) -> Unit,
    onClearNavHome: (ContentKind) -> Unit,
    /** The saved TMDb API key, or empty if none is configured yet - see
     *  MovieMetadataStore. */
    tmdbApiKey: String,
    onSetTmdbApiKey: (String) -> Unit,
    onClearMovieInfoCache: () -> Unit,
) {
    BackHandler { onExit() }
    var editing by remember { mutableStateOf<String?>(null) }
    var epgDraft by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<TvSource?>(null) }
    var davOpen by remember { mutableStateOf(false) }
    var davUrl by remember { mutableStateOf("") }
    var davUser by remember { mutableStateOf("") }
    var davPass by remember { mutableStateOf("") }
    var supplementalEpgOpen by remember { mutableStateOf(false) }
    var epgSourceName by remember { mutableStateOf("") }
    var epgSourceUrl by remember { mutableStateOf("") }
    var epgSourceChannelIds by remember { mutableStateOf("") }
    // The button gave no indication anything happened when tapped - it does
    // (deletes the cache files on disk), it just did it silently.
    var cacheJustCleared by remember { mutableStateOf(false) }
    LaunchedEffect(cacheJustCleared) {
        if (cacheJustCleared) { delay(4000); cacheJustCleared = false }
    }
    // Re-derived from tmdbApiKey whenever it changes underneath this screen
    // (e.g. after Save writes through and the caller's mirrored state
    // updates) rather than only set once - the same reasoning as
    // homeSummary reading navHomeFor live rather than snapshotting it.
    var tmdbKeyDraft by remember(tmdbApiKey) { mutableStateOf(tmdbApiKey) }
    var movieInfoCacheJustCleared by remember { mutableStateOf(false) }
    LaunchedEffect(movieInfoCacheJustCleared) {
        if (movieInfoCacheJustCleared) { delay(4000); movieInfoCacheJustCleared = false }
    }

    // Two-step Home picker: which kind is choosing its country, and which
    // kind+country is choosing its category. Deliberately two SEPARATE state
    // slots rather than one shared "menu" state reused for both steps -
    // ItemMenu's MenuRow always calls onDismiss() right after onSelect(), so
    // chaining a second ItemMenu through the same slot the first one's
    // onDismiss also clears would wipe the second step the instant it opened.
    var homeKindPicking by remember { mutableStateOf<ContentKind?>(null) }
    var homeCountryPicking by remember { mutableStateOf<Pair<ContentKind, PlaylistRepository.Group>?>(null) }
    // navHomeFor(k) reads straight through to NavHomeStore's SharedPreferences,
    // not a Compose State - Set already happens to force a recompose for free
    // (picking a category also dismisses the ItemMenu, which changes
    // homeCountryPicking, a real State), but Clear has no such side effect,
    // so the row kept showing the old Home until this screen was next entered
    // fresh. Bumped alongside both Set and Clear below and read via
    // remember(navHomeRevision, k) on each row so the STARTING POINT rows
    // are forced to re-read navHomeFor/homeSummary right away either way,
    // not just for Set.
    var navHomeRevision by remember { mutableIntStateOf(0) }

    fun countryGroupsFor(k: ContentKind) = catalogue?.section(k)?.byCountry.orEmpty()
    // Movies/Shows normally have no real per-country split - byCountry is
    // just one "Ungrouped" bucket - so there is nothing worth asking about
    // there; picking a Home for one of those kinds skips straight to category.
    fun hasRealCountries(k: ContentKind) = countryGroupsFor(k).any { it.key != null }

    fun homeSummary(k: ContentKind): String {
        val home = navHomeFor(k) ?: return "Always opens at the top of the list."
        // "Ungrouped" is countryLabel's own label for a null code - correct
        // internally, but for a kind with no real country split it would read
        // as if something had failed, so it's left out entirely there.
        val countryPart = if (hasRealCountries(k)) repo.countryLabel(home.countryKey) else null
        val categoryPart = home.category ?: "All"
        return listOfNotNull(countryPart, categoryPart).joinToString("  ·  ")
    }

    Column(
        Modifier.fillMaxSize().background(Backdrop)
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("Back returns to the previous screen.",
            style = MaterialTheme.typography.bodyMedium, color = Dim)

        Spacer(Modifier.height(24.dp))
        Text("SOURCES", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))

        sources.forEach { s ->
            val isActive = s.id == activeId
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = if (isActive) 0.08f else 0.04f))
                    .border(1.dp, if (isActive) Focus.copy(alpha = 0.6f) else Color.Transparent,
                        RoundedCornerShape(10.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.name + if (isActive) "   (active)" else "",
                            style = MaterialTheme.typography.titleMedium, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        // Host only. The full Xtream URL carries the username and
                        // password, which must never be rendered on screen.
                        Text(
                            s.kind.name + "  ·  " + hostOnly(s.location),
                            style = MaterialTheme.typography.bodyMedium, color = Dim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        s.epgUrlOverride?.let {
                            Text("Guide: " + hostOnly(it),
                                style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1)
                        }
                        if (s.logoFolderUri != null) {
                            Text("Logo folder: set",
                                style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isActive) Chip("Use this", false) { onSwitchSource(s) }
                    Chip("Guide URL", editing == s.id) {
                        editing = if (editing == s.id) null else s.id
                        epgDraft = s.epgUrlOverride.orEmpty()
                    }
                    // Overrides the playlist's own (often missing or wrong)
                    // channel logos with local images matched by name - the
                    // same escape hatch TiviMate ships.
                    Chip(if (s.logoFolderUri != null) "Change logo folder" else "Logo folder", false) {
                        onPickLogoFolder(s)
                    }
                    if (s.logoFolderUri != null) {
                        Chip("Clear logo folder", false) { onClearLogoFolder(s) }
                    }
                    if (!s.builtIn) Chip("Remove", false) { confirmRemove = s }
                }

                if (editing == s.id) {
                    Spacer(Modifier.height(12.dp))
                    Text("XMLTV guide URL (leave blank to use the provider's own)",
                        style = MaterialTheme.typography.bodyMedium, color = Dim)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Focus.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (epgDraft.isEmpty()) {
                            Text("https://.../guide.xml.gz",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Dim.copy(alpha = 0.6f))
                        }
                        BasicTextField(
                            value = epgDraft, onValueChange = { epgDraft = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                            cursorBrush = SolidColor(Focus),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Save", false) {
                            onSetEpgUrl(s, epgDraft.trim().ifBlank { null }); editing = null
                        }
                        Chip("Cancel", false) { editing = null }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Add IPTV service", false, onAddSource)
            if (hiddenSourceAdded) {
                Text("Prime4TV source added — switch to it above.",
                    style = MaterialTheme.typography.bodyMedium, color = Dim)
            } else {
                Chip("Add Prime4TV source", false, onAddHiddenSource)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("GUIDE", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(guideSummary, style = MaterialTheme.typography.bodyLarge, color = Ink)
        Spacer(Modifier.height(10.dp))
        Chip("Refresh guide now", false, onRefreshGuide)

        Spacer(Modifier.height(28.dp))
        Text("MOVIE & SHOW INFO", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(
            "Posters, synopses and ratings for Movies and Shows, matched by " +
                "title and year and shown next to the list - the same place " +
                "Live TV shows what's on. Pulled from The Movie Database " +
                "(TMDb), free for personal use - get a key at themoviedb.org " +
                "(under Settings > API on their site) and paste it below. " +
                "TMDb's API page lists two credentials - either the short " +
                "\"API Key\" or the long \"API Read Access Token\" works here, " +
                "so paste whichever one you have.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(10.dp))
        SettingField("TMDb API key", tmdbKeyDraft, "", password = true) { tmdbKeyDraft = it }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Save", false) { onSetTmdbApiKey(tmdbKeyDraft.trim()) }
            if (tmdbApiKey.isNotBlank()) {
                Chip("Remove key", false) { tmdbKeyDraft = ""; onSetTmdbApiKey("") }
            }
            Chip("Clear cached info", false) {
                onClearMovieInfoCache()
                movieInfoCacheJustCleared = true
            }
        }
        if (movieInfoCacheJustCleared) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Cleared. Titles will be looked up again as you browse.",
                style = MaterialTheme.typography.bodyMedium, color = Focus,
            )
        }
        Spacer(Modifier.height(6.dp))
        // Required by TMDb's API terms of use for non-commercial use of
        // their data.
        Text(
            "This product uses the TMDb API but is not endorsed or certified by TMDb.",
            style = MaterialTheme.typography.bodySmall, color = Dim.copy(alpha = 0.6f),
        )

        Spacer(Modifier.height(28.dp))
        Text("SUPPLEMENTAL EPG", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(
            "Layered on top of every source's own guide - for a channel whose " +
                "provider never carries a schedule for it at all, rather than one " +
                "that just needs the manual \"Set program guide\" pointed at the " +
                "right id.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(10.dp))
        supplementalEpgSources.forEach { epgSrc ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(epgSrc.name, style = MaterialTheme.typography.titleMedium, color = Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        hostOnly(epgSrc.guideUrl) + "  ·  " + epgSrc.channelIds.size +
                            if (epgSrc.channelIds.size == 1) " channel id" else " channel ids",
                        style = MaterialTheme.typography.bodyMedium, color = Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Chip("Remove", false) { onRemoveSupplementalEpg(epgSrc.id) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Chip(if (supplementalEpgOpen) "Cancel" else "Add supplemental EPG source", supplementalEpgOpen) {
            supplementalEpgOpen = !supplementalEpgOpen
        }
        if (supplementalEpgOpen) {
            SettingField("Name", epgSourceName, "e.g. Some Regional Network") { epgSourceName = it }
            SettingField("Guide URL", epgSourceUrl, "https://.../guide.xml.gz") { epgSourceUrl = it }
            SettingField(
                "Channel id(s) in that guide, comma-separated", epgSourceChannelIds,
                "network.hd.us2, network.overflow.us2",
            ) { epgSourceChannelIds = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Save", false) {
                    if (epgSourceName.isNotBlank() && epgSourceUrl.isNotBlank() && epgSourceChannelIds.isNotBlank()) {
                        onAddSupplementalEpg(epgSourceName.trim(), epgSourceUrl.trim(), epgSourceChannelIds)
                        epgSourceName = ""; epgSourceUrl = ""; epgSourceChannelIds = ""
                        supplementalEpgOpen = false
                    }
                }
                Chip("Cancel", false) { supplementalEpgOpen = false }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("ERROR LOG", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(
            errorLog ?: "No errors logged yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (errorLog != null) Color(0xFFE08A7A) else Dim,
        )
        if (errorLog != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Copy error log", false, onCopyErrorLog)
                Chip("Clear error log", false, onClearErrorLog)
            }
        }

        lastCrash?.let { crash ->
            Spacer(Modifier.height(28.dp))
            Text("LAST CRASH", style = MaterialTheme.typography.bodyMedium, color = Dim)
            Spacer(Modifier.height(8.dp))
            // Full text, not a head-only preview: this screen was the only way
            // to get a stack trace off the device short of adb, and a 12-line
            // cap silently discarded whatever the exception actually was below
            // the top frames of the framework's own dispatch machinery. The
            // outer Column already scrolls, so length here costs nothing.
            Text(
                crash,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE08A7A),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Copy crash report", false, onCopyCrash)
                Chip("Clear crash report", false, onClearCrash)
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("ON STARTUP", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Open the channel list", !resumeLastChannel) { onSetResumeLast(false) }
            Chip("Resume last channel", resumeLastChannel) { onSetResumeLast(true) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (resumeLastChannel)
                "The app starts playing whatever was on when it last closed."
            else "The app opens on the channel list.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )

        Spacer(Modifier.height(28.dp))
        Text("STARTING POINT", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(
            "Where Live TV, Movies and Shows open to. Left unset, each always " +
                "starts at the top of its list - set one to jump straight to a " +
                "country and category instead, for example United States, Sports.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(10.dp))
        if (catalogue == null) {
            Text(
                "Load a source first to set a starting point.",
                style = MaterialTheme.typography.bodyMedium, color = Dim,
            )
        } else {
            ContentKind.entries.forEach { k ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // remember(navHomeRevision, k) rather than key() around the
                    // whole row - it forces these two reads to re-run (and
                    // Clear/Set to show up right away) without tearing down and
                    // rebuilding the Row/Chip nodes themselves, which would risk
                    // dropping D-pad focus off whichever chip was just pressed.
                    val summary = remember(navHomeRevision, k) { homeSummary(k) }
                    val hasHome = remember(navHomeRevision, k) { navHomeFor(k) != null }
                    Column(Modifier.weight(1f)) {
                        Text(kindLabel(k), style = MaterialTheme.typography.titleMedium, color = Ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = Dim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("Change", false) {
                            val real = countryGroupsFor(k).filter { it.key != null }
                            if (real.isEmpty()) {
                                // No real country split - skip straight to the
                                // category step, using whichever single group
                                // covers everything (or a synthetic stand-in
                                // if the section has no groups yet at all).
                                homeCountryPicking = k to (
                                    countryGroupsFor(k).firstOrNull()
                                        ?: PlaylistRepository.Group(null, kindLabel(k), catalogue.section(k)?.items.orEmpty())
                                    )
                            } else {
                                homeKindPicking = k
                            }
                        }
                        if (hasHome) {
                            Chip("Clear", false) { onClearNavHome(k); navHomeRevision++ }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("PLAYBACK BUFFER", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BufferSize.entries.forEach { size ->
                Chip(size.label, bufferSize == size) { onSetBufferSize(size) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            bufferSize.description + " Takes effect the next time something is played.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )

        Spacer(Modifier.height(28.dp))
        Text("SHARED WATCH HISTORY", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(syncSummary, style = MaterialTheme.typography.bodyLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Each device writes only its own file, so two devices can never " +
                "overwrite each other. Progress merges when they sync.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Drive, OneDrive and Dropbox all appear in the system folder
            // picker when their app is installed - no separate sign-in here.
            Chip("Choose folder", false, onPickFolder)
            Chip("WebDAV / NAS", davOpen) { davOpen = !davOpen }
            Chip(if (syncBusy) "Syncing..." else "Sync now", false) { if (!syncBusy) onSyncNow() }
            Chip("Turn off", false, onDisableSync)
        }

        if (davOpen) {
            Spacer(Modifier.height(12.dp))
            SettingField("Folder URL", davUrl, "https://nas.local/remote.php/dav/files/me/tapper") { davUrl = it }
            SettingField("Username (optional)", davUser, "") { davUser = it }
            SettingField("Password (optional)", davPass, "", password = true) { davPass = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Save", false) {
                    onSaveWebDav(davUrl.trim(), davUser.trim(), davPass); davOpen = false
                }
                Chip("Cancel", false) { davOpen = false }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("STORAGE", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(
            "Playlists and guide data are cached for 30 hours. Clearing forces a " +
                "fresh download on the next load; saved sources and credentials are kept.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(10.dp))
        Chip("Clear cached playlists", false) {
            onClearCache()
            cacheJustCleared = true
        }
        if (cacheJustCleared) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Cleared. The next load will fetch fresh from the provider.",
                style = MaterialTheme.typography.bodyMedium, color = Focus,
            )
        }

        Spacer(Modifier.height(28.dp))
        Text("ABOUT", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        // The one reliable way to tell, from the running app, which build is
        // actually installed - a sideloaded update can silently fail to
        // replace the old APK, and without this there was no way to tell
        // that short of adb.
        Text("Tapper IPTV $appVersion", style = MaterialTheme.typography.bodyLarge, color = Ink)
        Spacer(Modifier.height(40.dp))
    }

    confirmRemove?.let { s ->
        ItemMenu(
            title = "Remove " + s.name + "?",
            subtitle = "Saved credentials for this source are deleted too.",
            actions = listOf(
                MenuAction("Remove") { onRemoveSource(s); confirmRemove = null },
                MenuAction("Keep it") { confirmRemove = null },
            ),
            onDismiss = { confirmRemove = null },
        )
    }

    // Step 1: which country. Only ever shown for a kind that has real
    // countries to choose from - see the "Change" chip above.
    homeKindPicking?.let { k ->
        val groups = countryGroupsFor(k).filter { it.key != null }
        ItemMenu(
            title = "Starting country for ${kindLabel(k)}",
            subtitle = "Then pick a category.",
            actions = groups.map { g ->
                // Selecting a country here only opens the category step
                // (below) - it deliberately does not call onSetNavHome yet,
                // since a Home always has both a country and a category.
                // homeKindPicking is cleared by ItemMenu's own onDismiss
                // right after this runs, but homeCountryPicking is a
                // separate slot, so the category step it opens here survives
                // that.
                MenuAction(g.label) { homeCountryPicking = k to g }
            },
            onDismiss = { homeKindPicking = null },
        )
    }

    // Step 2: which category (or "All"), within the country chosen above -
    // or the kind's one and only group, for a kind with no real countries.
    homeCountryPicking?.let { (k, g) ->
        val categories = g.channels.mapNotNull { it.group?.takeIf { c -> c.isNotBlank() } }
            .distinct()
            .sortedWith(compareBy<String> { categoryRank(it, k) }.thenBy { it.lowercase() })
        ItemMenu(
            title = "Starting category for ${kindLabel(k)}",
            subtitle = "${g.label}  ·  ${g.channels.size} titles",
            actions = listOf(MenuAction("All") { onSetNavHome(k, g.key, null); navHomeRevision++ }) +
                categories.map { c -> MenuAction(c) { onSetNavHome(k, g.key, c); navHomeRevision++ } },
            onDismiss = { homeCountryPicking = null },
        )
    }
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    hint: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    // Was a bare BasicTextField at all times - which is focusable, so
    // scrolling this screen with the D-pad could land input focus on it
    // (same as landing on any other row) and pop the on-screen keyboard
    // straight up without ever being selected. Now it's a plain read-only
    // row until explicitly selected, and only then swaps in a real editable
    // field - the same click(Select)-to-edit pattern a TV settings screen
    // needs, since there's no way to "click into" a field with a pointer here.
    var editing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Where focus returns to on closing - see the BackHandler below. A
    // separate requester from `focus` above: that one targets the text
    // field, which doesn't exist in the tree once editing is false.
    val rowFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val rowFocused by interaction.collectIsFocusedAsState()

    // Composed deeper than the screen's own BackHandler (line ~103), so
    // Compose's back dispatcher gives this one first refusal while editing -
    // Back backs out of the field, not out of Settings entirely, the same
    // "close what's open before leaving" rule every other screen this
    // session touched already follows. Also re-focuses the read-only row on
    // the way out: closing the field removes the BasicTextField that
    // currently holds focus from the tree, and Compose doesn't hand focus to
    // anything on its own when that happens - same "D-pad goes dead" failure
    // PlayerScreen's own showControls-closing comment already describes,
    // just on the way OUT of a field instead of into one. Launched from
    // here, not a plain LaunchedEffect(editing) - that would also fire on
    // this composable's very first mount (editing starts false) and every
    // field on screen would try to steal focus at once.
    BackHandler(enabled = editing) {
        editing = false
        scope.launch {
            repeat(5) { attempt ->
                if (runCatching { rowFocus.requestFocus() }.isSuccess) return@launch
                if (attempt < 4) delay(16)
            }
        }
    }
    // Same retry as BrowseScreen's own requestFocusRetrying: the text field
    // is a freshly composed node the instant editing flips true, and
    // Compose doesn't guarantee it's attached in time for a same-frame
    // requestFocus() - a silently swallowed failure here would leave the
    // D-pad with nothing focused at all.
    LaunchedEffect(editing) {
        if (editing) {
            repeat(5) { attempt ->
                if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
                if (attempt < 4) delay(16)
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(4.dp))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(
                if (rowFocused && !editing) 2.dp else 1.dp,
                if (rowFocused && !editing) Focus else Focus.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .focusRequester(rowFocus)
            .then(
                if (editing) Modifier
                else Modifier.clickable(interactionSource = interaction, indication = null) { editing = true }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (editing) {
            if (value.isEmpty() && hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.bodyMedium, color = Dim.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Focus),
                visualTransformation = if (password)
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        } else {
            // A masked value still shows something was typed (length, at a
            // glance) without echoing a password back in plain text on a
            // screen someone else in the room can see.
            val display = when {
                value.isNotEmpty() && password -> "•".repeat(value.length.coerceAtMost(24))
                value.isNotEmpty() -> value
                else -> hint.ifEmpty { "Not set" }
            }
            Text(
                display, style = MaterialTheme.typography.bodyLarge,
                color = if (value.isEmpty()) Dim.copy(alpha = 0.6f) else Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Strips everything after the host: Xtream paths contain credentials. */
private fun hostOnly(url: String): String =
    Regex("""^(https?://)?([^/]+)""").find(url)?.groupValues?.get(2) ?: url
