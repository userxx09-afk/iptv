package io.tapper.firetv

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef
import io.tapper.core.xtream.XtreamClient
import io.tapper.firetv.data.BufferSize
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.LogoFolderResolver
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.Reminder
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.data.withResolvedLogos
import io.tapper.firetv.recordings.RecordingAlarmReceiver
import io.tapper.firetv.recordings.RecordingService
import io.tapper.firetv.ui.AddSourceScreen
import io.tapper.firetv.ui.BrowseScreen
import io.tapper.firetv.ui.EpgPickerScreen
import io.tapper.firetv.ui.EpisodesScreen
import io.tapper.firetv.ui.FailureScreen
import io.tapper.firetv.ui.RecordingsScreen
import io.tapper.firetv.ui.SettingsScreen
import io.tapper.firetv.ui.LoadingScreen
import io.tapper.firetv.ui.PlayerScreen
import io.tapper.firetv.ui.SearchScreen
import io.tapper.firetv.ui.theme.TapperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private sealed interface Screen {
        data object Loading : Screen
        data class Failed(val message: String) : Screen
        data class Browse(val catalogue: PlaylistRepository.Catalogue) : Screen
        data object AddSource : Screen
        data object Search : Screen
        data object Settings : Screen
        data class Series(val series: Channel) : Screen
        data class EpgPick(val channel: Channel) : Screen
        data object Recordings : Screen
    }

    // allLiveChannels feeds the in-player guide's category browsing
    // (PlayerScreen's expanded overlay) - deliberately the WHOLE Live TV
    // catalogue, not `channels` above, which is only ever whatever narrower
    // list (a single category, a country, My List, ...) the user happened to
    // be looking at when they pressed play. Without this the guide's
    // category picker had nothing broader to offer than the one category
    // already on screen, defeating the point of being able to switch.
    // Empty for on-demand playback, where the guide never opens anyway.
    private data class Playing(
        val channels: List<Channel>,
        val index: Int,
        val allLiveChannels: List<Channel> = emptyList(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TapperApp
        // A big account can sit on the loading screen for a while even with
        // background sync - the Fire TV screensaver kicking in mid-load (or
        // mid-browse) looked like the app had frozen. Keeping the screen on
        // for the life of the activity matches what a video app is expected
        // to do anyway.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // A real stack, so Back always goes up one level from any screen
            // rather than each screen inventing its own idea of "back".
            var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Loading)) }
            val screen = stack.last()
            fun push(s: Screen) { stack = stack + s }
            fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
            fun replaceAll(s: Screen) { stack = listOf(s) }
            var playing by remember { mutableStateOf<Playing?>(null) }
            var sources by remember { mutableStateOf(app.sourceStore.all()) }
            var active by remember { mutableStateOf(app.sourceStore.active()) }
            var busy by remember { mutableStateOf(false) }
            var addError by remember { mutableStateOf<String?>(null) }

            // Pre-configured Xtream source, added on request without its
            // credentials ever being checked against the panel - they go
            // straight into the vault and the source list. Reachable two
            // ways: an explicit button in Settings (the reliable one), and
            // this holdover ten-tap gesture on "Settings" itself for anyone
            // who already knew about it. Idempotent either way.
            val hiddenSourceId = "xtream-prime4tv-hidden"
            fun addHiddenXtreamSource() {
                if (sources.none { it.id == hiddenSourceId }) {
                    app.vault.put(hiddenSourceId, "70405ae64f", "ae1752cd23")
                    app.sourceStore.add(
                        TvSource(hiddenSourceId, "Prime4TV", TvSource.Kind.XTREAM, "http://line.prime4tv.com")
                    )
                    sources = app.sourceStore.all()
                }
            }
            // Counted here in the screen-stack root rather than inside
            // BrowseScreen, since navigating to Settings unmounts BrowseScreen
            // and would reset a counter kept there back to zero on every tap.
            var settingsTapCount by remember { mutableStateOf(0) }
            fun onSettingsTapped() {
                settingsTapCount++
                if (settingsTapCount >= 10) { settingsTapCount = 0; addHiddenXtreamSource() }
                push(Screen.Settings)
            }

            // Same "mirror preferences into Compose state" pattern as sources
            // above, for the guides layered on top of every source's own EPG.
            var supplementalEpgSources by remember { mutableStateOf(app.supplementalEpg.all()) }
            var nowPlaying by remember { mutableStateOf<Map<String, EpgDatabase.Programme>>(emptyMap()) }
            var epgStatus by remember { mutableStateOf<String?>(null) }
            // Non-null only while movies/series are still catching up in the
            // background after live has already made the screen usable - see
            // loadActive() below. Shown as a small status line rather than a
            // blocking screen, since the catalogue on screen is already real.
            var loadStatus by remember { mutableStateOf<String?>(null) }
            // Bumped every time app.logEvent(...) is called, purely so the
            // Settings screen's error-log read (keyed off this) knows to
            // refresh - the log itself lives in a plain file, which Compose
            // has no way to observe on its own.
            var logRevision by remember { mutableIntStateOf(0) }
            fun logEvent(tag: String, message: String) {
                app.logEvent(tag, message)
                logRevision++
            }
            // Bumped on every progress record, so the "Recently Watched" rail
            // in BrowseScreen (keyed off this) reflects what was just on
            // shortly after backing out of the player, not only after the
            // next cross-device sync.
            var watchRevision by remember { mutableIntStateOf(0) }
            var bufferSize by remember { mutableStateOf(app.playerSettings.bufferSize) }
            var episodes by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var episodesLoading by remember { mutableStateOf(false) }
            var episodesError by remember { mutableStateOf<String?>(null) }
            // Remembered so episode progress is filed against its series.
            var seriesContext by remember { mutableStateOf<Channel?>(null) }
            var syncBusy by remember { mutableStateOf(false) }
            var lastChannelId by remember { mutableStateOf(app.sourceStore.lastChannelId) }
            var resumeLast by remember { mutableStateOf(app.sourceStore.resumeLastChannel) }
            // Mirrored into state the same way resumeLast/bufferSize above
            // are - SettingsScreen displays and edits it, BrowseScreen reads
            // it live to decide whether MovieInfoPanel can actually attempt
            // a lookup or should just point at Settings.
            var tmdbApiKey by remember { mutableStateOf(app.movieMetadata.apiKey) }
            var autoResumed by remember { mutableStateOf(false) }
            var syncSummary by remember { mutableStateOf(app.sync.describe()) }
            // A plain SharedPreferences read (via epgOverrides.get) is not
            // observed by Compose, so overrides are mirrored into state here.
            // Reassigning this map is what makes every screen that reads
            // epgIdFor() below recompose the moment an override changes.
            var epgOverrideMap by remember { mutableStateOf(app.epgOverrides.all()) }
            var epgOverrideRevision by remember { mutableIntStateOf(0) }
            // Same "mirror preferences into Compose state" pattern as
            // epgOverrideMap above: ReminderStore/RecordingStore are plain
            // SharedPreferences, so nothing here recomposes on its own when
            // they change - this is what does that.
            var reminderIds by remember { mutableStateOf(app.reminders.all().map { it.id }.toSet()) }
            var recordingsList by remember { mutableStateOf(app.recordings.all()) }
            var logoFolderIndex by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var logoFolderPickingFor by remember { mutableStateOf<String?>(null) }

            fun refreshReminders() { reminderIds = app.reminders.all().map { it.id }.toSet() }
            fun refreshRecordings() { recordingsList = app.recordings.all() }

            // Resolves a channel to a stream URL suitable for recording. Only
            // LIVE channels are supported: MOVIE/SERIES items are on-demand
            // already and recording them would just be a slower re-download.
            fun recordableUrl(ch: Channel): String? =
                if (ch.kind == ContentKind.LIVE) ch.primaryUrl else null

            fun runSync() {
                syncBusy = true
                lifecycleScope.launch {
                    val r = app.sync.sync()
                    syncBusy = false
                    syncSummary = r.fold(
                        onSuccess = {
                            app.sync.describe() + "  ·  merged " + it.changed +
                                " from " + it.pulledFrom + " other device(s)"
                        },
                        onFailure = { "Sync failed: " + (it.message ?: "unknown error") },
                    )
                }
            }

            // The system folder picker: whatever provider the user has installed
            // (Drive, OneDrive, Dropbox, a NAS client) appears as a choice.
            val folderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    // Without persisting, the grant dies with the process and
                    // sync silently stops working after a reboot.
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    app.sync.saveFolder(uri)
                    syncSummary = app.sync.describe()
                    runSync()
                }
            }

            // A separate picker from folderPicker above: that one grants a
            // sync folder onto WatchSync, this one grants a per-source logo
            // folder onto whichever TvSource is currently being edited in
            // Settings (tracked in logoFolderPickingFor, since the picker
            // itself has no way to carry that context through the system UI).
            val logoFolderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                val forId = logoFolderPickingFor
                logoFolderPickingFor = null
                if (uri != null && forId != null) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    val target = sources.firstOrNull { it.id == forId }
                    if (target != null) {
                        app.sourceStore.update(target.copy(logoFolderUri = uri.toString()))
                        sources = app.sourceStore.all()
                        if (active.id == forId) active = app.sourceStore.active()
                    }
                }
            }

            // Reminder/recording notifications are silently dropped without
            // this on API 33+ - asked once, up front, rather than waiting for
            // the first reminder to fire and discovering then that nothing
            // showed up.
            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, android.Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    runCatching { notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                }
            }

            fun reloadNowPlaying() {
                lifecycleScope.launch {
                    val map = withContext(Dispatchers.IO) {
                        runCatching { app.epgDb.nowPlaying(active.id, System.currentTimeMillis()) }
                            .getOrDefault(emptyMap())
                    }
                    nowPlaying = map
                }
            }

            fun refreshEpg(catalogue: PlaylistRepository.Catalogue?) {
                epgStatus = "Guide: updating..."
                lifecycleScope.launch {
                    val result = app.epg.refresh(
                        active,
                        catalogue?.declaredEpgUrls.orEmpty(),
                        // Progress ticks so a large guide shows movement rather
                        // than looking frozen for several minutes.
                        { rows ->
                            // Emitted from the IO thread mid-transaction, so the
                            // UI update is posted rather than applied inline.
                            lifecycleScope.launch(Dispatchers.Main) {
                                epgStatus = "Guide: loading, " + rows + " entries..."
                            }
                        },
                    )
                    epgStatus = result.fold(
                        onSuccess = { "Guide: ${it.programmes} entries, ${it.channels} channels" },
                        // Dead guide URLs are routine, so this is informational
                        // rather than an error screen - the app still works.
                        onFailure = { "Guide unavailable: ${it.message?.take(60)}" },
                    )
                    result.exceptionOrNull()?.let {
                        logEvent("EPG", "Guide unavailable for ${active.name}: ${it.message}")
                    }
                    reloadNowPlaying()
                }
            }

            // Swaps in a freshly loaded catalogue without disturbing whatever
            // else is on the stack (Settings, Search, a still-open guide
            // panel...) - a plain replaceAll() would drop the user back to
            // Browse every time movies or series finish loading behind them.
            fun updateBrowseCatalogue(cat: PlaylistRepository.Catalogue) {
                stack = if (stack.any { it is Screen.Browse }) {
                    stack.map { if (it is Screen.Browse) Screen.Browse(cat) else it }
                } else {
                    listOf(Screen.Browse(cat))
                }
            }

            fun loadActive(force: Boolean = false) {
                replaceAll(Screen.Loading)
                loadStatus = null
                lifecycleScope.launch {
                    var reachedBrowse = false
                    val res = app.repository.load(active, force) { label, partial ->
                        reachedBrowse = true
                        loadStatus = when (label) {
                            "movies" -> "Loading movies in the background..."
                            "series" -> "Loading shows in the background..."
                            else -> "Loading more in the background..."
                        }
                        updateBrowseCatalogue(partial)
                    }
                    loadStatus = null
                    res.fold(
                        onSuccess = { updateBrowseCatalogue(it) },
                        onFailure = {
                            logEvent("LOAD", "${active.name}: ${it.message}")
                            // A source that failed outright but never got as far
                            // as showing live TV still needs the error screen;
                            // one that already put live channels on screen
                            // before something later went wrong keeps showing
                            // what it already loaded instead of yanking it away.
                            if (!reachedBrowse) replaceAll(Screen.Failed(it.message ?: "Couldn't load this source."))
                        },
                    )
                    res.getOrNull()?.let { cat ->
                        // Movies/series fetch failures are captured rather than
                        // thrown (see PlaylistRepository), so they never reach
                        // the onFailure branch above - logged here instead, once
                        // per kind, so a panel that silently 404s on get_series
                        // still leaves a trace.
                        for ((sectionKind, error) in cat.sectionErrors) {
                            logEvent("LOAD", "${active.name} ${sectionKind.name.lowercase()}: $error")
                        }
                        // Resume playback only once per launch, and only after
                        // the catalogue exists to resolve the id against.
                        if (resumeLast && !autoResumed) {
                            autoResumed = true
                            val ch = cat.channels.firstOrNull { it.id == lastChannelId }
                            if (ch != null && ch.isPlayable) {
                                val list = cat.channels.filter { it.kind == ch.kind }
                                playing = Playing(list, list.indexOf(ch).coerceAtLeast(0))
                            }
                        }
                        epgStatus = if (app.epg.hasData(active.id)) null else "Guide: none yet"
                        reloadNowPlaying()
                        // Refresh in the background; the channel list is already
                        // usable and must not wait on a 100MB guide download.
                        if (app.epg.isStale(active.id)) refreshEpg(cat)
                    }
                }
            }

            LaunchedEffect(active.id) { loadActive() }

            // Reads the granted folder's contents once per source/folder
            // change - a real SAF directory listing, so kept off the main
            // thread and cached in state rather than done inline in the
            // catalogue derivation below.
            LaunchedEffect(active.id, active.logoFolderUri) {
                val folder = active.logoFolderUri
                logoFolderIndex = if (folder == null) emptyMap() else withContext(Dispatchers.IO) {
                    runCatching { LogoFolderResolver.index(this@MainActivity, folder) }.getOrDefault(emptyMap())
                }
            }

            // Pull on launch so a device that was off overnight catches up
            // before the user starts browsing.
            LaunchedEffect(Unit) {
                if (app.sync.config().kind != io.tapper.firetv.data.WatchSync.Config.Kind.NONE) {
                    runSync()
                }
            }

            // The catalogue survives navigation: it lives on the Browse entry
            // at the bottom of the stack, so Settings and Search do not lose it.
            val catalogue = stack.filterIsInstance<Screen.Browse>().lastOrNull()?.catalogue

            fun openSeries(series: Channel) {
                seriesContext = series
                push(Screen.Series(series))
                episodes = emptyList(); episodesError = null; episodesLoading = true
                lifecycleScope.launch {
                    val id = series.seriesId
                    if (id == null) {
                        episodesLoading = false
                        episodesError = "This item has no series id."
                        return@launch
                    }
                    val res = app.repository.episodes(active, id)
                    episodesLoading = false
                    res.fold(
                        onSuccess = { episodes = it },
                        onFailure = { episodesError = it.message ?: "Couldn't load episodes." },
                    )
                }
            }

            // Guide id actually in effect for a channel: a manual override, if
            // one was set from the channel's own menu, otherwise whatever the
            // playlist or Xtream panel declared. Every guide lookup in this
            // app goes through this rather than reading epgChannelId directly.
            fun epgIdFor(ch: Channel): String {
                val override = epgOverrideMap["${ch.sourceId}|${ch.id}"]
                return EpgDatabase.normalizeId(if (!override.isNullOrBlank()) override else ch.epgChannelId)
            }

            fun channelForEpgId(epgId: String): Channel? =
                catalogue?.channels?.firstOrNull { epgIdFor(it) == epgId }

            TapperTheme {
                val p = playing
                val current = screen

                // True root of the back stack: every screen above (Search,
                // Settings, Episodes, Recordings, the guide picker, and
                // BrowseScreen itself once its own internal columns are
                // backed out to the leftmost one) has its own BackHandler
                // that consumes Back before it gets here - see each
                // screen's own "BackHandler { onExit() }". Compose's back
                // dispatcher only ever reaches this one when NONE of those
                // are currently enabled, i.e. this is a deliberate "quit the
                // app" press.
                //
                // Returning here from PlayerScreen (see the Box below) used
                // to also momentarily rebuild BrowseScreen from scratch,
                // which was slow enough that an impatient second Back press
                // - fired only because the first one *looked* like it hadn't
                // registered yet - landed right here and closed the app with
                // no warning. Composing BrowseScreen underneath the player
                // instead of tearing it down (below) removes that rebuild,
                // but a stray extra press landing here by simple bad timing
                // is still possible, so this stays as a second line of
                // defence: only a SECOND press within the grace window
                // actually exits, and a lone press just arms the warning.
                var exitArmedAt by remember { mutableStateOf(0L) }
                BackHandler {
                    val now = System.currentTimeMillis()
                    if (now - exitArmedAt < 2000) {
                        finish()
                    } else {
                        exitArmedAt = now
                        Toast.makeText(this@MainActivity, "Press Back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }

                // PlayerScreen is layered ON TOP of whichever screen was
                // current when playback started, rather than replacing it in
                // the `when` below - that screen (almost always BrowseScreen)
                // stays fully composed and keeps every bit of its own state
                // (which column is focused, which country/category is
                // selected, ...) the whole time something is playing, so
                // Back out of the player lands exactly back where the user
                // left off, instantly, instead of that screen rebuilding
                // itself from nothing. BrowseScreen's own state used to reset
                // to its defaults on literally every return from the player,
                // which is what made "watching something under Sports, then
                // Back" land back on a default country/category instead of
                // Sports - it looked like the app forgot, but there was
                // nothing to remember from: it had already been torn down.
                Box(Modifier.fillMaxSize()) {
                when {
                    current is Screen.Series -> EpisodesScreen(
                        series = current.series,
                        episodes = episodes,
                        loading = episodesLoading,
                        error = episodesError,
                        watchedIds = remember(episodes, syncSummary) {
                            app.watch.all().filter { it.watched }.map { it.itemId }.toSet()
                        },
                        nextUpId = remember(episodes, syncSummary) {
                            current.series.seriesId?.let { sid ->
                                app.sync.nextEpisode(
                                    sid,
                                    episodes.map {
                                        io.tapper.firetv.data.WatchSync.EpisodeRef(
                                            it.id, it.group?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0,
                                            it.number ?: 0,
                                        )
                                    },
                                )?.itemId
                            }
                        },
                        onPlay = { list, i -> playing = Playing(list, i) },
                        onExit = { pop() },
                    )

                    current is Screen.EpgPick -> EpgPickerScreen(
                        channel = current.channel,
                        candidates = remember(active.id, epgStatus) {
                            runCatching { app.epgDb.guideChannelIds(active.id) }
                                .getOrDefault(emptySet()).sorted()
                        },
                        sampleTitle = { id ->
                            runCatching {
                                app.epgDb.sampleTitle(active.id, id, System.currentTimeMillis())
                            }.getOrNull()
                        },
                        currentOverride = app.epgOverrides.get(current.channel.sourceId, current.channel.id),
                        onPick = { id ->
                            app.epgOverrides.set(current.channel.sourceId, current.channel.id, id)
                            epgOverrideMap = app.epgOverrides.all()
                            epgOverrideRevision++
                            reloadNowPlaying()
                            pop()
                        },
                        onClear = {
                            app.epgOverrides.clear(current.channel.sourceId, current.channel.id)
                            epgOverrideMap = app.epgOverrides.all()
                            epgOverrideRevision++
                            reloadNowPlaying()
                            pop()
                        },
                        onExit = { pop() },
                    )

                    current is Screen.Search && catalogue != null -> SearchScreen(
                        isFavorite = { app.favorites.isFavorite(it.sourceId, it.id) },
                        onToggleFavorite = { app.favorites.toggle(it.sourceId, it.id) },
                        channels = catalogue.channels,
                        searchProgrammes = { q ->
                            app.epgDb.search(active.id, q, System.currentTimeMillis())
                        },
                        channelForEpgId = ::channelForEpgId,
                        onPlay = { ch ->
                            // A series has no stream; selecting one from search
                            // must open its episode list, not the player.
                            if (ch.kind == ContentKind.SERIES) openSeries(ch)
                            else { playing = Playing(listOf(ch), 0); pop() }
                        },
                        onSetGuideChannel = { push(Screen.EpgPick(it)) },
                        onExit = { pop() },
                    )

                    current is Screen.AddSource -> AddSourceScreen(
                        busy = busy,
                        error = addError,
                        onCancel = { addError = null; loadActive() },
                        onSubmitXtream = { name, host, user, pass ->
                            busy = true; addError = null
                            lifecycleScope.launch {
                                val id = "xtream-" + UUID.randomUUID().toString().take(8)
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { XtreamClient(host, user, pass).authenticate() }
                                }
                                busy = false
                                result.fold(
                                    onSuccess = {
                                        app.vault.put(id, user, pass)
                                        app.sourceStore.add(TvSource(id, name, TvSource.Kind.XTREAM, host))
                                        app.sourceStore.activeId = id
                                        sources = app.sourceStore.all()
                                        active = app.sourceStore.active()
                                        addError = null
                                    },
                                    onFailure = { addError = it.message ?: "Couldn't connect." },
                                )
                            }
                        },
                        onSubmitM3u = { name, url, epgUrl ->
                            val id = "m3u-" + UUID.randomUUID().toString().take(8)
                            app.sourceStore.add(TvSource(id, name, TvSource.Kind.M3U, url, epgUrl))
                            app.sourceStore.activeId = id
                            sources = app.sourceStore.all()
                            active = app.sourceStore.active()
                            addError = null
                        },
                    )

                    current is Screen.Loading -> LoadingScreen("Loading " + active.name + "...")

                    current is Screen.Failed -> FailureScreen(
                        sourceName = active.name,
                        message = current.message,
                        canFallBack = active.id != TvSource.BUILTIN.id,
                        onRetry = { loadActive(force = true) },
                        onSettings = { onSettingsTapped() },
                        onUseBuiltIn = {
                            app.sourceStore.activeId = TvSource.BUILTIN.id
                            active = TvSource.BUILTIN
                        },
                    )

                    current is Screen.Settings -> SettingsScreen(
                        sources = sources,
                        activeId = active.id,
                        guideSummary = buildString {
                            append(epgStatus ?: if (app.epg.hasData(active.id)) "Guide loaded." else "No guide data yet.")
                            // Coverage is the number that actually explains a
                            // blank guide: rows can be stored yet match nothing
                            // if the panel's ids differ from its XMLTV ids.
                            val cat = catalogue
                            if (cat != null && app.epg.hasData(active.id)) {
                                val guideIds = runCatching { app.epgDb.guideChannelIds(active.id) }
                                    .getOrDefault(emptySet())
                                val live = cat.channels.filter { it.kind == ContentKind.LIVE }
                                val matched = live.count {
                                    epgIdFor(it).isNotEmpty() && epgIdFor(it) in guideIds
                                }
                                append("\n" + matched + " of " + live.size + " channels matched to the guide")
                                if (matched == 0 && guideIds.isNotEmpty()) {
                                    append("\nGuide has " + guideIds.size +
                                        " channel ids but none match this playlist. The guide is for a different source.")
                                }
                            }
                        },
                        onSwitchSource = {
                            app.sourceStore.activeId = it.id
                            active = it
                            pop()
                        },
                        onAddSource = { addError = null; push(Screen.AddSource) },
                        onRemoveSource = { s2 ->
                            app.vault.delete(s2.id)
                            app.sourceStore.remove(s2.id)
                            sources = app.sourceStore.all()
                            if (active.id == s2.id) {
                                app.sourceStore.activeId = TvSource.BUILTIN.id
                                active = app.sourceStore.active()
                            }
                        },
                        onSetEpgUrl = { s2, url ->
                            app.sourceStore.update(s2.copy(epgUrlOverride = url))
                            sources = app.sourceStore.all()
                            if (active.id == s2.id) active = app.sourceStore.active()
                        },
                        onPickLogoFolder = { s2 ->
                            logoFolderPickingFor = s2.id
                            runCatching { logoFolderPicker.launch(null) }
                        },
                        onClearLogoFolder = { s2 ->
                            app.sourceStore.update(s2.copy(logoFolderUri = null))
                            sources = app.sourceStore.all()
                            if (active.id == s2.id) active = app.sourceStore.active()
                        },
                        onRefreshGuide = { refreshEpg(catalogue) },
                        onClearCache = { app.repository.clearCache() },
                        hiddenSourceAdded = sources.any { it.id == hiddenSourceId },
                        onAddHiddenSource = { addHiddenXtreamSource() },
                        supplementalEpgSources = supplementalEpgSources,
                        onAddSupplementalEpg = { name, url, channelIdsCsv ->
                            val ids = channelIdsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (ids.isNotEmpty()) {
                                app.supplementalEpg.add(
                                    io.tapper.firetv.data.SupplementalEpgSource(
                                        id = "supp-" + UUID.randomUUID().toString().take(8),
                                        name = name,
                                        guideUrl = url,
                                        channelIds = ids,
                                    )
                                )
                                supplementalEpgSources = app.supplementalEpg.all()
                            }
                        },
                        onRemoveSupplementalEpg = { id ->
                            app.supplementalEpg.remove(id)
                            supplementalEpgSources = app.supplementalEpg.all()
                        },
                        appVersion = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        lastCrash = remember(syncSummary, logRevision) { app.lastCrash() },
                        // lastCrash above is keyed off logRevision - without
                        // bumping it here, clearLastCrash() deletes the file
                        // on disk but the text already on screen never
                        // refreshes, so the button looks like it does
                        // nothing (it only appeared to work when paired with
                        // clearing the error log right after, which does
                        // bump this).
                        onClearCrash = { app.clearLastCrash(); logRevision++ },
                        onCopyCrash = {
                            // A Fire TV stick has no clipboard UI to paste into,
                            // but this at least gets the trace off the device
                            // into whatever the launcher's paste target is
                            // (a connected keyboard app, a remote-desktop tool)
                            // without falling back to photographing the screen.
                            val crash = app.lastCrash()
                            if (crash != null) {
                                val cm = getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Tapper crash report", crash))
                            }
                        },
                        // Separate from lastCrash above: this covers load and
                        // guide failures that were caught and recovered from,
                        // not just the fatal ones that took the app down - and
                        // it always shows a section in Settings, even before
                        // anything has ever gone wrong, rather than only
                        // appearing the first time something does.
                        errorLog = remember(logRevision) { app.eventLog() },
                        onClearErrorLog = { app.clearEventLog(); logRevision++ },
                        onCopyErrorLog = {
                            val log = app.eventLog()
                            if (log != null) {
                                val cm = getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Tapper error log", log))
                            }
                        },
                        resumeLastChannel = resumeLast,
                        onSetResumeLast = {
                            resumeLast = it; app.sourceStore.resumeLastChannel = it
                        },
                        bufferSize = bufferSize,
                        onSetBufferSize = {
                            bufferSize = it; app.playerSettings.bufferSize = it
                        },
                        syncSummary = syncSummary,
                        syncBusy = syncBusy,
                        onPickFolder = { runCatching { folderPicker.launch(null) } },
                        onSaveWebDav = { url, user, pass ->
                            app.sync.saveWebDav(url, user.ifBlank { null }, pass.ifBlank { null })
                            syncSummary = app.sync.describe()
                            runSync()
                        },
                        onSyncNow = { runSync() },
                        onDisableSync = { app.sync.disable(); syncSummary = app.sync.describe() },
                        onExit = { pop() },
                        catalogue = catalogue,
                        repo = app.repository,
                        navHomeFor = { k -> app.navHome.get(k) },
                        onSetNavHome = { k, country, cat -> app.navHome.set(k, country, cat) },
                        onClearNavHome = { k -> app.navHome.clear(k) },
                        tmdbApiKey = tmdbApiKey,
                        onSetTmdbApiKey = { k -> app.movieMetadata.apiKey = k; tmdbApiKey = k },
                        onClearMovieInfoCache = { app.movieMetadata.clearCache() },
                    )

                    current is Screen.Browse -> {
                    // Hoisted out of the catalogue = ... argument below so
                    // onPlay can also reach it, for allLiveChannels - see the
                    // Playing data class above.
                    val resolvedCatalogue = remember(current.catalogue, logoFolderIndex) {
                        current.catalogue.withResolvedLogos(logoFolderIndex)
                    }
                    BrowseScreen(
                        // A no-op when no folder is configured for this
                        // source (see withResolvedLogos) - cheap in the
                        // common case of not using the feature.
                        catalogue = resolvedCatalogue,
                        repo = app.repository,
                        favorites = app.favorites,
                        sources = sources,
                        activeSource = active,
                        nowPlaying = nowPlaying,
                        // Background-sync status (movies/series still loading)
                        // takes priority over the guide line while it's active -
                        // both share the one status slot under the source name.
                        epgStatus = loadStatus ?: epgStatus,
                        onPlay = { list, i ->
                            playing = Playing(list, i, resolvedCatalogue.section(ContentKind.LIVE)?.items.orEmpty())
                        },
                        onSwitchSource = { app.sourceStore.activeId = it.id; active = it },
                        onAddSource = { addError = null; push(Screen.AddSource) },
                        onSearch = { push(Screen.Search) },
                        onSettings = { onSettingsTapped() },
                        onRefreshEpg = { refreshEpg(current.catalogue) },
                        onOpenSeries = { openSeries(it) },
                        initialChannelId = lastChannelId,
                        onSelectionChanged = { chId ->
                            lastChannelId = chId
                            app.sourceStore.lastChannelId = chId
                        },
                        navHome = app.navHome,
                        recentItemIds = remember(watchRevision, syncSummary) {
                            runCatching {
                                app.watch.all().sortedByDescending { it.clock }
                                    .map { it.itemId }.distinct().take(50)
                            }.getOrDefault(emptyList())
                        },
                        // A cache miss here is a real network call - kept off
                        // the main thread, the same way a catalogue load
                        // already is.
                        movieInfoFor = { ch -> withContext(Dispatchers.IO) { app.movieMetadata.lookup(ch) } },
                        movieInfoLastError = { app.movieMetadata.lastError },
                        tmdbKeyConfigured = tmdbApiKey.isNotBlank(),
                        scheduleFor = { ch ->
                            app.epgDb.upcoming(
                                active.id,
                                epgIdFor(ch),
                                System.currentTimeMillis(),
                            )
                        },
                        epgIdFor = ::epgIdFor,
                        onSetGuideChannel = { push(Screen.EpgPick(it)) },
                        epgRevision = epgOverrideRevision,
                        hasReminder = { ch, p ->
                            Reminder.idFor(ch.sourceId, ch.id, p.startUtc) in reminderIds
                        },
                        onSetReminder = { ch, p ->
                            app.reminders.add(
                                Reminder(
                                    id = Reminder.idFor(ch.sourceId, ch.id, p.startUtc),
                                    sourceId = ch.sourceId, channelId = ch.id, channelName = ch.name,
                                    programmeTitle = p.title, startUtc = p.startUtc,
                                )
                            )
                            refreshReminders()
                        },
                        onCancelReminder = { ch, p ->
                            app.reminders.remove(Reminder.idFor(ch.sourceId, ch.id, p.startUtc))
                            refreshReminders()
                        },
                        onRecord = { ch, p ->
                            val url = recordableUrl(ch)
                            if (url != null) {
                                // "Record now" from a currently-airing entry
                                // starts from this moment rather than the
                                // programme's own start, which may already be
                                // in the past.
                                val start = if (System.currentTimeMillis() in p.startUtc until p.endUtc)
                                    System.currentTimeMillis() else p.startUtc
                                app.recordings.schedule(
                                    Recording(
                                        id = Recording.newId(), sourceId = ch.sourceId, channelId = ch.id,
                                        channelName = ch.name, streamUrl = url,
                                        startUtc = start, endUtc = p.endUtc,
                                    )
                                )
                                refreshRecordings()
                            }
                        },
                        onRecordChannel = { ch ->
                            val url = recordableUrl(ch)
                            if (url != null) {
                                val now = System.currentTimeMillis()
                                app.recordings.schedule(
                                    Recording(
                                        id = Recording.newId(), sourceId = ch.sourceId, channelId = ch.id,
                                        // No guide entry chosen, so there is no
                                        // natural end time - three hours is a
                                        // generous default; RecordingsScreen
                                        // can stop it early at any point.
                                        channelName = ch.name, streamUrl = url,
                                        startUtc = now, endUtc = now + 3 * 60 * 60 * 1000L,
                                    )
                                )
                                refreshRecordings()
                            }
                        },
                        onOpenRecordings = { refreshRecordings(); push(Screen.Recordings) },
                    )
                    }

                    current is Screen.Recordings -> RecordingsScreen(
                        recordings = recordingsList,
                        onPlay = { r ->
                            val path = r.filePath
                            if (path != null) {
                                val synthetic = Channel(
                                    id = "recording-" + r.id, sourceId = r.sourceId,
                                    name = r.channelName, number = null, logoUrl = null,
                                    group = null, countryCode = null, epgChannelId = null,
                                    streams = listOf(StreamRef(url = "file://" + path, priority = 0)),
                                    kind = ContentKind.MOVIE,
                                )
                                playing = Playing(listOf(synthetic), 0)
                            }
                        },
                        onStop = { r ->
                            // Mirrors what the end-of-window alarm would send -
                            // RecordingService itself decides start/RECORDING vs
                            // stop/DONE based on this action, not on the caller.
                            startService(
                                Intent(this@MainActivity, RecordingService::class.java)
                                    .putExtra("recording_id", r.id)
                                    .setAction(RecordingAlarmReceiver.ACTION_STOP)
                            )
                            refreshRecordings()
                        },
                        onCancel = { r -> app.recordings.remove(r.id); refreshRecordings() },
                        onDelete = { r -> app.recordings.remove(r.id); refreshRecordings() },
                        onExit = { pop() },
                    )
                }
                if (p != null) {
                    PlayerScreen(
                        channels = p.channels,
                        startIndex = p.index,
                        allLiveChannels = p.allLiveChannels,
                        nowPlaying = { ch -> nowPlaying[epgIdFor(ch)] },
                        scheduleFor = { ch ->
                            app.epgDb.upcoming(active.id, epgIdFor(ch), System.currentTimeMillis())
                        },
                        resumeAt = { ch -> app.sync.resumeAt(ch.id) },
                        onChannelChanged = { ch ->
                            lastChannelId = ch.id
                            app.sourceStore.lastChannelId = ch.id
                        },
                        onProgress = { ch, pos, dur, finished ->
                            app.sync.record(
                                itemId = ch.id,
                                sourceId = ch.sourceId,
                                seriesId = seriesContext?.seriesId,
                                season = seriesContext?.let { ch.number ?: 0 } ?: 0,
                                number = ch.number ?: 0,
                                title = ch.name,
                                positionMs = pos,
                                durationMs = dur,
                                finished = finished,
                            )
                            watchRevision++
                        },
                        onExit = {
                            playing = null
                            // Publish as soon as viewing stops, so the other
                            // device sees it without waiting for a launch.
                            lifecycleScope.launch { app.sync.sync() }
                        },
                        bufferSize = bufferSize,
                    )
                }
                }
            }
        }
    }
}
