package io.tapper.firetv

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.tapper.core.catchup.CatchupUrl
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.model.StreamRef
import io.tapper.core.xtream.XtreamClient
import io.tapper.firetv.data.BufferSize
import io.tapper.firetv.data.PlayerSettingsStore
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.data.LogoFolderResolver
import io.tapper.firetv.data.PlaylistRepository
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.Reminder
import io.tapper.firetv.data.TvSource
import io.tapper.firetv.data.withResolvedLogos
import io.tapper.firetv.data.withEdits
import io.tapper.firetv.recordings.RecordingAlarmReceiver
import io.tapper.firetv.recordings.RecordingService
import io.tapper.firetv.ui.AddSourceScreen
import io.tapper.firetv.ui.BrowseScreen
import io.tapper.firetv.ui.MenuAction
import io.tapper.firetv.ui.EpgPickerScreen
import io.tapper.firetv.ui.EpisodesScreen
import io.tapper.firetv.ui.FailureScreen
import io.tapper.firetv.ui.RecordingsScreen
import io.tapper.firetv.ui.TeamsScreen
import io.tapper.firetv.ui.SettingsScreen
import io.tapper.firetv.ui.LoadingScreen
import io.tapper.firetv.ui.PlayerScreen
import io.tapper.firetv.ui.PresetSource
import io.tapper.firetv.ui.SearchScreen
import io.tapper.firetv.ui.theme.TapperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    private companion object {
        /** How long playback may continue after the app leaves the screen. */
        const val BACKGROUND_PLAYBACK_GRACE_MS = 30_000L
        /** No remote press for this long while playing => ask "Still watching?" */
        const val IDLE_PROMPT_AFTER_MS = 3L * 60L * 60L * 1000L
        /** How long the question waits for a press before playback stops. */
        const val IDLE_PROMPT_GRACE_MS = 2L * 60L * 1000L
    }

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
        data object Teams : Screen
        data object Multiview : Screen
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
        /** A virtual channel: films one after another. */
        val autoAdvance: Boolean = false,
    )

    // Counts returns to the foreground (not the first start), so the screen
    // can pull what the other devices watched while this one was away.
    private var foregroundTick by mutableIntStateOf(0)
    private var startedOnce = false

    /** True while the app is off screen (Home pressed, screensaver, another
     *  app on top). Playback must not carry on decoding video nobody sees. */
    private var inBackground by mutableStateOf(false)

    /** When the remote was last pressed, and whether "Still watching?" is up. */
    private var lastInputMs = SystemClock.elapsedRealtime()
    private var stillWatchingPrompt by mutableStateOf(false)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInputMs = SystemClock.elapsedRealtime()
        if (stillWatchingPrompt) {
            // The press that answers the question does only that, so it
            // can't also change channel or skip.
            if (event.action == KeyEvent.ACTION_DOWN) stillWatchingPrompt = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** True while something plays full screen; Home then shrinks it to
     *  picture in picture, on the Fire TV models that offer it. */
    private var watchingFullScreen = false
    var inPictureInPicture by mutableStateOf(false)
        private set

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!watchingFullScreen || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        inBackground = false
        if (startedOnce) foregroundTick++
        startedOnce = true
    }

    override fun onStop() {
        super.onStop()
        inBackground = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TapperApp
        // The screen is kept on only while something is loading or playing
        // (see keepAwake in setContent below). Holding it on for the whole
        // life of the activity meant the stick never dimmed or reached its
        // screensaver while the app sat open, and kept the TV pulling a
        // picture, and the processor busy, for as long as the app was left.

        setContent {
            // A real stack, so Back always goes up one level from any screen
            // rather than each screen inventing its own idea of "back".
            var stack by remember { mutableStateOf<List<Screen>>(listOf(Screen.Loading)) }
            val screen = stack.last()
            fun push(s: Screen) { stack = stack + s }
            fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
            fun replaceAll(s: Screen) { stack = listOf(s) }
            var playing by remember { mutableStateOf<Playing?>(null) }
            // false = the stream has dropped into the top-right corner picture
            // over BrowseScreen (live only) - see PlayerScreen's `minimized`.
            var playerFullscreen by remember { mutableStateOf(true) }
            // Bumped for every NEW playback request so PlayerScreen is rebuilt
            // for it (key() below). Needed now that a player can outlive the
            // screen that started it: without it a second Select while the
            // corner picture is up would hand the already-running player a
            // new channel list it ignores (its index state is remembered).
            var playToken by remember { mutableIntStateOf(0) }
            // What PlayerScreen is actually showing right now (it can zap away
            // from whatever list started it) - what Back-to-browse reveals.
            var playingChannel by remember { mutableStateOf<Channel?>(null) }
            // Bumped when the player drops to the corner; BrowseScreen then
            // navigates to playingChannel and focuses it.
            var revealToken by remember { mutableIntStateOf(0) }
            var revealChannel by remember { mutableStateOf<Channel?>(null) }
            // Mirrors the player state for onUserLeaveHint (picture in picture).
            SideEffect { watchingFullScreen = playing != null && playerFullscreen }
            fun startPlayback(p: Playing) {
                playToken++
                playing = p
                playerFullscreen = true
            }
            fun stopPlayback() {
                playing = null
                playingChannel = null
                playerFullscreen = true
                // Publish as soon as viewing stops, so the other
                // device sees it without waiting for a launch.
                lifecycleScope.launch { app.sync.sync() }
            }
            var sources by remember { mutableStateOf(app.sourceStore.all()) }
            var active by remember { mutableStateOf(app.sourceStore.active()) }
            // Sources whose live channels join the active one's lineup (Free
            // TV next to a paid plan) - see SourceStore.alongsideIds.
            var alongsideIds by remember { mutableStateOf(app.sourceStore.alongsideIds) }
            val alongside = remember(sources, active, alongsideIds) { app.sourceStore.alongside(active) }
            // Every source id whose guide rows belong to what's on screen: the
            // active source, each one shown alongside it, and the GarysTV
            // guide files. Every guide read goes through this.
            val guideSourceIds = remember(active, alongside) {
                listOf(active.id) + alongside.filter { it.kind != TvSource.Kind.FREE }.map { it.id } +
                    io.tapper.firetv.data.EpgRepository.garysGuidesId(
                        io.tapper.firetv.data.EpgRepository.usesFreeTvGuide(listOf(active) + alongside)
                    )
            }
            // The GarysTV guide files (cached or bundled copy; refreshed from
            // GitHub with the guide). Only their channel lists are read here.
            var garysGuides by remember { mutableStateOf(app.garysFeed.guides(allowNetwork = false)) }
            var busy by remember { mutableStateOf(false) }
            var addError by remember { mutableStateOf<String?>(null) }

            // Pre-configured Xtream source, added on request without its
            // credentials ever being checked against the panel - they go
            // straight into the vault and the source list. Reachable two
            // ways: an explicit button in Settings (the reliable one), and
            // this holdover ten-tap gesture on "Settings" itself for anyone
            // who already knew about it. Idempotent either way.
            val hiddenSourceId = "xtream-prime4tv-hidden"
            fun addPreset(id: String) {
                val def = PRESET_SOURCES.firstOrNull { it.id == id } ?: return
                if (sources.none { it.id == def.id }) {
                    app.vault.put(def.id, def.username, def.password)
                    app.sourceStore.add(TvSource(def.id, def.label, TvSource.Kind.XTREAM, def.host))
                    sources = app.sourceStore.all()
                }
            }
            fun addHiddenXtreamSource() = addPreset(hiddenSourceId)
            // The original panel was relabelled "Old Prime4TV" when the two
            // newer accounts were added. A copy already added under the old
            // label is renamed in place (id, credentials and watch history
            // are untouched).
            LaunchedEffect(Unit) {
                val old = app.sourceStore.all().firstOrNull { it.id == hiddenSourceId && it.name == "Prime4TV" }
                if (old != null) {
                    app.sourceStore.update(old.copy(name = "Old Prime4TV"))
                    sources = app.sourceStore.all()
                    if (active.id == hiddenSourceId) active = app.sourceStore.active()
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
            var captionsOn by remember { mutableStateOf(app.playerSettings.captions) }
            // Accessibility: every screen's text 25% larger.
            var largeText by remember { mutableStateOf(app.playerSettings.largeText) }
            // Channels picked for multiview (Browse > long-press > Add to multiview).
            var multiview by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var preferredLanguage by remember { mutableStateOf(app.playerSettings.preferredLanguage) }
            var muteMinutes by remember { mutableStateOf(app.playerSettings.muteMinutes) }
            var cacheHours by remember { mutableStateOf(app.playerSettings.cacheHours) }
            var episodes by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var episodesLoading by remember { mutableStateOf(false) }
            var episodesError by remember { mutableStateOf<String?>(null) }
            // Remembered so episode progress is filed against its series.
            var seriesContext by remember { mutableStateOf<Channel?>(null) }
            val traktSent = remember { mutableSetOf<String>() }
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
            var cloudSignIn by remember { mutableStateOf<io.tapper.firetv.data.CloudSignInState?>(null) }
            var cloudSignInJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
            // A plain SharedPreferences read (via epgOverrides.get) is not
            // observed by Compose, so overrides are mirrored into state here.
            // Reassigning this map is what makes every screen that reads
            // epgIdFor() below recompose the moment an override changes.
            var epgOverrideMap by remember { mutableStateOf(app.epgOverrides.all()) }
            var epgOverrideRevision by remember { mutableIntStateOf(0) }
            // Bumped when a channel edit or a parental setting changes, so the
            // filtered catalogue below is rebuilt.
            var editRevision by remember { mutableIntStateOf(0) }
            var prompt by remember { mutableStateOf<io.tapper.firetv.ui.PromptRequest?>(null) }
            // Actor filmography: the cast of a title, then that person's other
            // titles here. Two slots, since a menu's dismiss clears its own.
            var castMenu by remember { mutableStateOf<Pair<Channel, List<io.tapper.core.tmdb.TmdbClient.Person>>?>(null) }
            var starringMenu by remember { mutableStateOf<Pair<String, List<Channel>>?>(null) }
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

            // Google / Microsoft device-code sign-in: show the code, wait for the
            // user to approve on their phone, then sync into the folder the
            // sign-in created.
            fun startCloudSignIn(p: io.tapper.firetv.data.CloudProvider) {
                cloudSignInJob?.cancel()
                cloudSignIn = io.tapper.firetv.data.CloudSignInState(p)
                cloudSignInJob = lifecycleScope.launch {
                    try {
                        val code = withContext(Dispatchers.IO) { app.sync.startCloudSignIn(p) }
                        cloudSignIn = io.tapper.firetv.data.CloudSignInState(p, code.userCode, code.verificationUrl)
                        val account = app.sync.finishCloudSignIn(code)
                        app.logEvent("SYNC", "Signed in to ${p.label} as $account")
                        cloudSignIn = null
                        syncSummary = app.sync.describe()
                        runSync()
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        app.logEvent("SYNC", "${p.label} sign-in failed: ${t.message}")
                        cloudSignIn = io.tapper.firetv.data.CloudSignInState(
                            p, error = t.message ?: "Sign-in failed."
                        )
                    }
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
                        runCatching { app.epgDb.nowPlaying(guideSourceIds, System.currentTimeMillis()) }
                            .getOrDefault(emptyMap())
                    }
                    nowPlaying = map
                }
            }

            // Guide addresses each source shown alongside declared in its own
            // playlist, kept from its last load for refreshEpg.
            val alongsideDeclared = remember { HashMap<String, List<String>>() }

            // [onlyStale] refreshes just the guides that are due, for the
            // automatic refresh after a load; the Settings button does all.
            fun refreshEpg(catalogue: PlaylistRepository.Catalogue?, onlyStale: Boolean = false) {
                val primary = active
                val extra = alongside
                epgStatus = "Guide: updating..."
                lifecycleScope.launch {
                    // The GarysTV guide files first: small next to a provider's
                    // guide, and all the Free TV channels have.
                    val withFree = io.tapper.firetv.data.EpgRepository.usesFreeTvGuide(listOf(primary) + extra)
                    val garysId = io.tapper.firetv.data.EpgRepository.garysGuidesId(withFree)
                    if (!onlyStale || app.epg.isStale(garysId)) {
                        app.epg.refreshGarysGuides(app.garysFeed, withFree).exceptionOrNull()?.let {
                            logEvent("EPG", "GarysTV guides unavailable: ${it.message}")
                        }
                        garysGuides = withContext(Dispatchers.IO) { app.garysFeed.guides(allowNetwork = false) }
                        epgOverrideRevision++
                        reloadNowPlaying()
                    }
                    for (s2 in extra) {
                        if (s2.kind == TvSource.Kind.FREE) continue
                        if (onlyStale && !app.epg.isStale(s2.id)) continue
                        app.epg.refresh(s2, alongsideDeclared[s2.id].orEmpty()).exceptionOrNull()?.let {
                            logEvent("EPG", "Guide unavailable for ${s2.name}: ${it.message}")
                        }
                        reloadNowPlaying()
                    }
                    if (primary.kind == TvSource.Kind.FREE || (onlyStale && !app.epg.isStale(primary.id))) {
                        epgStatus = null
                        reloadNowPlaying()
                        return@launch
                    }
                    val result = app.epg.refresh(
                        primary,
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
                        logEvent("EPG", "Guide unavailable for ${primary.name}: ${it.message}")
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

            // [quiet]: reload behind whatever is on screen (Settings, after a
            // source is shown alongside or not), if a lineup is already there.
            fun loadActive(force: Boolean = false, quiet: Boolean = false) {
                if (!quiet || stack.none { it is Screen.Browse }) replaceAll(Screen.Loading)
                loadStatus = null
                val primary = active
                val extra = alongside
                // What's on now comes straight from the guide saved on this
                // device, so it can be ready while the channel list loads
                // rather than after it (and after every source alongside).
                reloadNowPlaying()
                lifecycleScope.launch {
                    var reachedBrowse = false
                    // The sources shown alongside load at the same time as the
                    // active one; whichever finishes later merges in, so a
                    // slow one never holds up the other.
                    var latest: PlaylistRepository.Catalogue? = null
                    var extraCats: List<Pair<TvSource, PlaylistRepository.Catalogue>> = emptyList()
                    // Always called on the main thread. The merge re-sorts the
                    // whole live lineup, so it runs off it; a result that is
                    // already out of date by the time it's ready is dropped.
                    fun show() {
                        val cat = latest ?: return
                        val more = extraCats
                        if (more.isEmpty()) { updateBrowseCatalogue(cat); return }
                        lifecycleScope.launch {
                            val merged = withContext(Dispatchers.Default) { app.repository.withAlongside(cat, more) }
                            if (latest === cat && extraCats === more) updateBrowseCatalogue(merged)
                        }
                    }
                    val extraJob = launch {
                        if (extra.isEmpty()) return@launch
                        val loaded = extra.map { s2 ->
                            async {
                                s2 to app.repository.load(s2, force).onFailure {
                                    logEvent("LOAD", "${s2.name} (shown alongside): ${it.message}")
                                }.getOrNull()
                            }
                        }.awaitAll().mapNotNull { (s2, c) -> c?.let { s2 to it } }
                        loaded.forEach { (s2, c) -> alongsideDeclared[s2.id] = c.declaredEpgUrls }
                        extraCats = loaded
                        show()
                    }
                    val res = app.repository.load(primary, force) { label, partial ->
                        reachedBrowse = true
                        loadStatus = when (label) {
                            "movies" -> "Loading movies in the background..."
                            "series" -> "Loading shows in the background..."
                            else -> "Loading more in the background..."
                        }
                        lifecycleScope.launch(Dispatchers.Main) { latest = partial; show() }
                    }
                    loadStatus = null
                    res.fold(
                        onSuccess = { latest = it; show() },
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
                        // The sources alongside too: the last channel may be one
                        // of theirs, and their guide addresses come from their
                        // playlists (refreshEpg below).
                        extraJob.join()
                        // Resume playback only once per launch, and only after
                        // the catalogue exists to resolve the id against.
                        if (resumeLast && !autoResumed) {
                            autoResumed = true
                            val pool = app.repository.withAlongside(cat, extraCats).channels
                            val ch = pool.firstOrNull { it.id == lastChannelId }
                            if (ch != null && ch.isPlayable) {
                                val list = pool.filter { it.kind == ch.kind }
                                startPlayback(Playing(list, list.indexOf(ch).coerceAtLeast(0)))
                            }
                        }
                        epgStatus = if (guideSourceIds.any { app.epg.hasData(it) }) null else "Guide: none yet"
                        reloadNowPlaying()
                        // Refresh in the background; the channel list is already
                        // usable and must not wait on a 100MB guide download.
                        refreshEpg(cat, onlyStale = true)

                        // A channel list read from a saved copy past the cache
                        // window was shown anyway (see Catalogue.stale); fetch
                        // a new one now, behind the screen, and swap it in.
                        // The guide is keyed by channel id, so the rows already
                        // on screen line up with the new list as it is.
                        val staleExtra = extraCats.filter { it.second.stale }.map { it.first }
                        if (cat.stale || staleExtra.isNotEmpty()) {
                            launch {
                                logEvent("LOAD", "Showing the saved channel list; updating it in the background")
                                loadStatus = "Updating channels in the background..."
                                val freshExtra = staleExtra.map { s2 ->
                                    async {
                                        s2 to app.repository.load(s2, forceRefresh = true).onFailure {
                                            logEvent("LOAD", "${s2.name} background update: ${it.message}")
                                        }.getOrNull()
                                    }
                                }.awaitAll()
                                // Partial (live-only) results are not shown: the
                                // saved copy on screen is already complete.
                                val freshPrimary = if (cat.stale) {
                                    app.repository.load(primary, forceRefresh = true).onFailure {
                                        logEvent("LOAD", "${primary.name} background update: ${it.message}")
                                    }.getOrNull()
                                } else null
                                // Dropped if the user switched source meanwhile.
                                if (active.id != primary.id || alongside.map { it.id } != extra.map { it.id }) {
                                    return@launch
                                }
                                loadStatus = null
                                if (freshPrimary == null && freshExtra.all { it.second == null }) return@launch
                                freshExtra.forEach { (s2, c) -> c?.let { alongsideDeclared[s2.id] = it.declaredEpgUrls } }
                                extraCats = extraCats.map { (s2, c) ->
                                    s2 to (freshExtra.firstOrNull { it.first.id == s2.id }?.second ?: c)
                                }
                                freshPrimary?.let { latest = it }
                                show()
                                reloadNowPlaying()
                            }
                        }
                    }
                }
            }

            LaunchedEffect(active.id) { loadActive() }
            var loadedAlongsideIds by remember { mutableStateOf(alongsideIds) }
            LaunchedEffect(alongsideIds) {
                if (alongsideIds != loadedAlongsideIds) {
                    loadedAlongsideIds = alongsideIds
                    loadActive(quiet = true)
                }
            }

            // Screen stays on while loading a big account (the screensaver
            // mid-load looked like a freeze) and while something plays; the
            // rest of the time the stick is free to dim and sleep.
            val keepAwake = playing != null || screen is Screen.Loading || loadStatus != null
            LaunchedEffect(keepAwake) {
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            // Sent to the background while playing (Home, the screensaver, a
            // call-to-action from another app): the picture is no longer
            // shown, but the player would keep downloading and decoding it
            // indefinitely. A short grace covers a quick glance away.
            // Hours of playback with no remote press usually means the TV was
            // switched off (the stick can't tell) and the stream is being
            // downloaded and decoded for nobody. After a long stretch it asks
            // "Still watching?" and stops if nobody answers.
            LaunchedEffect(playing != null) {
                if (playing == null) { stillWatchingPrompt = false; return@LaunchedEffect }
                lastInputMs = SystemClock.elapsedRealtime()
                var askedAt = 0L
                while (true) {
                    delay(15_000)
                    val now = SystemClock.elapsedRealtime()
                    if (!stillWatchingPrompt) {
                        if (now - lastInputMs >= IDLE_PROMPT_AFTER_MS) {
                            stillWatchingPrompt = true
                            askedAt = now
                        }
                    } else if (now - askedAt >= IDLE_PROMPT_GRACE_MS) {
                        stillWatchingPrompt = false
                        stopPlayback()
                        Toast.makeText(this@MainActivity, "Playback stopped - no one was watching", Toast.LENGTH_LONG).show()
                        return@LaunchedEffect
                    }
                }
            }

            // Auto-update: a short while after the app opens (so it doesn't
            // compete with loading the channel list), look for a newer build
            // and download it. Only when the setting is on, and at most
            // every few hours.
            LaunchedEffect(Unit) {
                delay(8_000)
                if (app.updater.autoUpdate && app.updater.dueForCheck()) app.updater.check()
            }

            // A downloaded update is offered once per build, and never over
            // fullscreen playback - it waits until the viewer is back at the
            // channel list or has stopped watching.
            var offeredBuild by remember { mutableIntStateOf(0) }
            val readyBuild = app.updater.readyBuild
            LaunchedEffect(readyBuild, playing != null && playerFullscreen, inBackground) {
                val b = readyBuild ?: return@LaunchedEffect
                if (b == offeredBuild || inBackground || (playing != null && playerFullscreen)) return@LaunchedEffect
                if (isFinishing) return@LaunchedEffect
                offeredBuild = b
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update ready")
                    .setMessage("Tapper IPTV build $b has been downloaded. Install it now? The app restarts when it finishes.")
                    .setPositiveButton("Install now") { _, _ -> lifecycleScope.launch { app.updater.install() } }
                    .setNegativeButton("Later", null)
                    .show()
            }

            LaunchedEffect(inBackground, playing != null) {
                if (inBackground && playing != null) {
                    delay(BACKGROUND_PLAYBACK_GRACE_MS)
                    if (inBackground && playing != null) stopPlayback()
                }
            }

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

            // And again whenever the app comes back to the foreground, so a
            // film started on the iPad can be resumed here without a restart.
            LaunchedEffect(foregroundTick) {
                if (foregroundTick > 0 &&
                    app.sync.config().kind != io.tapper.firetv.data.WatchSync.Config.Kind.NONE
                ) {
                    runSync()
                }
            }

            // The catalogue survives navigation: it lives on the Browse entry
            // at the bottom of the stack, so Settings and Search do not lose it.
            val rawCatalogue = stack.filterIsInstance<Screen.Browse>().lastOrNull()?.catalogue
            // Hidden, locked and renamed channels, applied once here so Browse,
            // Search, My Teams and Settings all see the same list.
            val catalogue = remember(rawCatalogue, editRevision) {
                rawCatalogue?.withEdits(app.channelEdits, app.parental, kids = app.profiles.active().kids)
            }

            /** Runs [action] after the right PIN, or at once when no PIN is set. */
            fun withPin(title: String, action: () -> Unit) {
                if (!app.parental.hasPin) { action(); return }
                fun ask(message: String?) {
                    prompt = io.tapper.firetv.ui.PromptRequest(title, hint = "PIN", numeric = true, secret = true, message = message) { pin ->
                        if (app.parental.check(pin)) action() else ask("Wrong PIN, try again.")
                    }
                }
                ask(null)
            }

            /** One reminder per upcoming game, on its best channel; existing ones stay. */
            fun autoRemindGames(list: List<io.tapper.firetv.ui.MatchedGame>) {
                val now = System.currentTimeMillis()
                var added = 0
                for (m in list) {
                    val (ch, match) = m.channels.firstOrNull() ?: continue
                    val start = match.listing?.startUtc ?: m.game.startUtc
                    if (start <= now) continue
                    val id = Reminder.idFor(ch.sourceId, ch.id, start)
                    if (id in reminderIds) continue
                    app.reminders.add(
                        Reminder(
                            id = id, sourceId = ch.sourceId, channelId = ch.id, channelName = ch.name,
                            programmeTitle = match.listing?.title ?: "${m.game.league.label}: ${m.game.title}",
                            startUtc = start,
                        )
                    )
                    added++
                }
                if (added > 0) refreshReminders()
            }

            fun channelActions(ch: Channel): List<MenuAction> {
                val edits = app.channelEdits
                val hidden = edits.isHidden(ch)
                val castAction = if (ch.kind != ContentKind.LIVE && app.movieMetadata.apiKey.isNotBlank()) listOf(
                    MenuAction("Cast, and more with them...") {
                        lifecycleScope.launch {
                            val cast = app.movieMetadata.castFor(ch)
                            if (cast.isEmpty()) {
                                Toast.makeText(this@MainActivity, "TMDb has no cast for ${ch.name}", Toast.LENGTH_LONG).show()
                            } else {
                                castMenu = ch to cast
                            }
                        }
                    },
                ) else emptyList()
                return castAction + listOf(
                    MenuAction("Rename...") {
                        withPin("Enter the PIN to edit channels") {
                            prompt = io.tapper.firetv.ui.PromptRequest(
                                "Rename ${ch.name}", hint = "Leave empty to use the provider's name",
                                initial = edits.nameFor(ch) ?: ch.name,
                            ) { name ->
                                // Saving the provider's own name clears the edit.
                                val raw = rawCatalogue?.channels?.firstOrNull { it.id == ch.id && it.sourceId == ch.sourceId }?.name
                                edits.rename(ch, name.takeUnless { it.isBlank() || it == raw })
                                editRevision++
                            }
                        }
                    },
                    MenuAction("Change number...") {
                        withPin("Enter the PIN to edit channels") {
                            prompt = io.tapper.firetv.ui.PromptRequest(
                                "Channel number for ${ch.name}", hint = "Leave empty to use the provider's number",
                                initial = (edits.numberFor(ch) ?: ch.number)?.toString().orEmpty(), numeric = true,
                            ) { n -> edits.renumber(ch, n.toIntOrNull()); editRevision++ }
                        }
                    },
                    MenuAction(if (hidden) "Unhide channel" else "Hide channel") {
                        withPin("Enter the PIN to edit channels") { edits.setHidden(ch, !hidden); editRevision++ }
                    },
                )
            }

            fun categoryActions(kind: ContentKind, category: String): List<MenuAction> {
                val hidden = category in app.channelEdits.hiddenCategories()
                val locked = category in app.parental.lockedCategories()
                // A virtual channel: the category's films back to back, shuffled.
                val virtual = if (kind == ContentKind.MOVIE) listOf(
                    MenuAction("Play as a channel (shuffled)") {
                        val films = catalogue?.section(ContentKind.MOVIE)?.byCategory
                            ?.firstOrNull { it.label == category || it.key == category }?.channels
                            ?.filter { it.isPlayable }.orEmpty()
                        if (films.size < 2) {
                            Toast.makeText(this@MainActivity, "Not enough films in $category", Toast.LENGTH_SHORT).show()
                        } else {
                            startPlayback(Playing(films.shuffled(), 0, autoAdvance = true))
                        }
                    },
                ) else emptyList()
                return virtual + listOf(
                    MenuAction(if (hidden) "Unhide category" else "Hide category") {
                        withPin("Enter the PIN to edit categories") {
                            app.channelEdits.setCategoryHidden(category, !hidden); editRevision++
                        }
                    },
                    MenuAction(if (locked) "Unlock category" else "Lock with PIN") {
                        if (!app.parental.hasPin) {
                            Toast.makeText(this@MainActivity, "Set a PIN first in Settings > Parental controls", Toast.LENGTH_LONG).show()
                        } else {
                            withPin(if (locked) "Enter the PIN to unlock $category" else "Enter the PIN to lock $category") {
                                app.parental.setLocked(category, !locked); editRevision++
                            }
                        }
                    },
                )
            }
            // Channels the GarysTV extra guides cover by name (Marquee Sports
            // Network from a paid plan, ...), keyed like the manual overrides.
            val guideNameMatches = remember(catalogue, garysGuides) {
                catalogue?.let { app.garysFeed.nameMatches(it.channels, garysGuides) }.orEmpty()
            }

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
                val key = "${ch.sourceId}|${ch.id}"
                val override = epgOverrideMap[key]
                if (!override.isNullOrBlank()) return EpgDatabase.normalizeId(override)
                guideNameMatches[key]?.let { return it }
                return EpgDatabase.normalizeId(ch.epgChannelId)
            }

            // One lookup table per catalogue/override change instead of a scan
            // over every channel (tens of thousands, each running epgIdFor)
            // for every single guide hit - search resolves up to 60 of those
            // at a time, which is what made it lock up. `lazy` so nothing is
            // built until a search actually asks, and so that first ask can
            // be made from a background thread. First channel wins on a
            // shared id, exactly as the firstOrNull scan did.
            val epgChannelIndex = remember(catalogue, epgOverrideRevision) {
                lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                    val m = HashMap<String, Channel>()
                    catalogue?.channels?.forEach { m.putIfAbsent(epgIdFor(it), it) }
                    m
                }
            }

            fun channelForEpgId(epgId: String): Channel? = epgChannelIndex.value[epgId]

            // Alexa (your own relay; see the alexa folder): while the app is
            // on screen, act on what was said. Restarts when the relay changes.
            var alexaRevision by remember { mutableIntStateOf(0) }
            LaunchedEffect(alexaRevision, inBackground) {
                if (inBackground || !app.alexa.configured || !app.alexa.registered) return@LaunchedEffect
                app.alexa.listen { cmd ->
                    fun key(code: Int) {
                        dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                        dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                    }
                    val live = catalogue?.section(ContentKind.LIVE)?.items.orEmpty()
                    when (cmd.type) {
                        "tune", "number" -> {
                            val target = if (cmd.type == "number") live.firstOrNull { it.number == cmd.number }
                            else io.tapper.firetv.data.AlexaLink.bestMatch(cmd.query.orEmpty(), live, app.favorites.favorites())
                            if (target == null) {
                                Toast.makeText(this@MainActivity, "Alexa: no channel matches \"${cmd.query ?: cmd.number}\"", Toast.LENGTH_LONG).show()
                            } else {
                                startPlayback(Playing(live, live.indexOf(target).coerceAtLeast(0), live))
                            }
                        }
                        "channelUp" -> key(android.view.KeyEvent.KEYCODE_CHANNEL_UP)
                        "channelDown" -> key(android.view.KeyEvent.KEYCODE_CHANNEL_DOWN)
                        "pause" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                        "resume" -> key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                        "stop" -> stopPlayback()
                        "linked" -> Toast.makeText(this@MainActivity, "Alexa is linked to this TV", Toast.LENGTH_LONG).show()
                    }
                }
            }

            // Series recordings: pick up airings the guide gained since the
            // last background refresh.
            LaunchedEffect(Unit) {
                val n = withContext(Dispatchers.IO) {
                    runCatching { app.seriesRules.scheduleUpcoming(app.epgDb, app.recordings, app.playerSettings) }.getOrDefault(0)
                }
                if (n > 0) refreshRecordings()
            }

            // My Teams' automatic reminders also run once per launch, so
            // games are covered without opening My Teams.
            LaunchedEffect(catalogue?.sourceId) {
                val cat = catalogue ?: return@LaunchedEffect
                val teams = app.teams.all()
                if (!app.teams.autoRemind || teams.isEmpty()) return@LaunchedEffect
                val matched = withContext(Dispatchers.IO) {
                    runCatching {
                        val schedule = app.sports.games(teams, System.currentTimeMillis())
                        schedule.games.map { g ->
                            io.tapper.firetv.ui.MatchedGame(
                                g,
                                io.tapper.firetv.ui.matchChannels(g, cat.channels, ::epgIdFor) { from, to ->
                                    app.epgDb.startingBetween(guideSourceIds, from, to)
                                },
                            )
                        }
                    }.getOrDefault(emptyList())
                }
                autoRemindGames(matched)
            }


            io.tapper.firetv.ui.TextSize(largeText) { TapperTheme {
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

                // The corner picture only makes sense over BrowseScreen: any
                // other screen taking over (Settings, Search, an episode
                // list...) ends it rather than leaving it floating over them.
                LaunchedEffect(current is Screen.Browse) {
                    if (playing != null && !playerFullscreen && current !is Screen.Browse) stopPlayback()
                }

                BackHandler {
                    // Back with nothing left to back out of inside Browse and a
                    // corner picture still playing: stop the picture first, so
                    // only a further press can arm the exit below.
                    if (playing != null && !playerFullscreen) {
                        stopPlayback()
                        return@BackHandler
                    }
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
                // Gary's TV skin: every screen but the main one sits in
                // GarysTV's frame (top bar with Home/Guide/Search/Menu and the
                // clock above, key hints below), like the GarysTV app.
                val garysFramed = io.tapper.firetv.ui.theme.isGarysSkin &&
                    current !is Screen.Browse && current !is Screen.Loading && current !is Screen.Multiview
                // Its Home and Guide buttons jump straight back to the main screen.
                fun backToBrowse(view: io.tapper.firetv.ui.GView) {
                    val at = stack.indexOfLast { it is Screen.Browse }
                    if (at >= 0) stack = stack.take(at + 1)
                    io.tapper.firetv.ui.GarysNav.view = view
                }
                io.tapper.firetv.ui.GarysFrame(
                    enabled = garysFramed,
                    crumb = when (current) {
                        is Screen.Series -> "Shows › " + current.series.name
                        is Screen.EpgPick -> "Guide › " + current.channel.name
                        Screen.Search -> "Search"
                        Screen.AddSource -> "Add a source"
                        Screen.Settings -> "Settings"
                        Screen.Recordings -> "My Recordings"
                        Screen.Teams -> "My Teams"
                        is Screen.Failed -> "Problem"
                        else -> ""
                    },
                    hints = when (current) {
                        Screen.Search -> listOf("Type" to "Search", "↓" to "Results", "OK" to "Open", "Hold OK" to "Options", "Back" to "Back")
                        Screen.Recordings -> listOf("↑↓" to "Move", "OK" to "Play / stop", "Back" to "Back")
                        is Screen.Series -> listOf("↑↓" to "Move", "OK" to "Play", "Back" to "Back")
                        else -> listOf("↑↓←→" to "Move", "OK" to "Select", "Back" to "Back")
                    },
                    recording = recordingsList.any { it.status == io.tapper.firetv.data.RecordingStatus.RECORDING },
                    onBack = { pop() },
                    onHome = { backToBrowse(io.tapper.firetv.ui.GView.HOME) },
                    onGuide = { backToBrowse(io.tapper.firetv.ui.GView.GUIDE) },
                    onSearch = { if (current !is Screen.Search) push(Screen.Search) },
                    onMenu = { if (current !is Screen.Settings) onSettingsTapped() },
                ) {
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
                        onPlay = { list, i -> startPlayback(Playing(list, i)) },
                        onExit = { pop() },
                    )

                    current is Screen.EpgPick -> EpgPickerScreen(
                        channel = current.channel,
                        candidates = remember(guideSourceIds, epgStatus) {
                            runCatching { app.epgDb.guideChannelIds(guideSourceIds) }
                                .getOrDefault(emptySet()).sorted()
                        },
                        sampleTitle = { id ->
                            runCatching {
                                app.epgDb.sampleTitle(guideSourceIds, id, System.currentTimeMillis())
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
                            app.epgDb.search(guideSourceIds, q, System.currentTimeMillis())
                        },
                        channelForEpgId = ::channelForEpgId,
                        onPlay = { ch ->
                            // A series has no stream; selecting one from search
                            // must open its episode list, not the player.
                            if (ch.kind == ContentKind.SERIES) openSeries(ch)
                            else { startPlayback(Playing(listOf(ch), 0)); pop() }
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
                        alongsideIds = alongsideIds,
                        onSetAlongside = { s2, on ->
                            app.sourceStore.setAlongside(s2.id, on)
                            alongsideIds = app.sourceStore.alongsideIds
                        },
                        guideSummary = buildString {
                            append(epgStatus ?: if (guideSourceIds.any { app.epg.hasData(it) }) "Guide loaded." else "No guide data yet.")
                            // Coverage is the number that actually explains a
                            // blank guide: rows can be stored yet match nothing
                            // if the panel's ids differ from its XMLTV ids.
                            val cat = catalogue
                            if (cat != null && guideSourceIds.any { app.epg.hasData(it) }) {
                                val guideIds = runCatching { app.epgDb.guideChannelIds(guideSourceIds) }
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
                            app.sourceStore.setAlongside(s2.id, false)
                            alongsideIds = app.sourceStore.alongsideIds
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
                        presets = PRESET_SOURCES.map { PresetSource(it.id, it.label, sources.any { s -> s.id == it.id }) },
                        onAddPreset = { addPreset(it) },
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
                        updater = app.updater,
                        onCheckUpdates = { lifecycleScope.launch { app.updater.check() } },
                        onInstallUpdate = { lifecycleScope.launch { app.updater.install() } },
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
                        captions = captionsOn,
                        onSetCaptions = { captionsOn = it; app.playerSettings.captions = it },
                        largeText = largeText,
                        onSetLargeText = { largeText = it; app.playerSettings.largeText = it },
                        preferredLanguage = preferredLanguage,
                        onSetPreferredLanguage = {
                            preferredLanguage = it; app.playerSettings.preferredLanguage = it
                        },
                        muteMinutes = muteMinutes,
                        onSetMuteMinutes = {
                            muteMinutes = it; app.playerSettings.muteMinutes = it
                        },
                        cacheHours = cacheHours,
                        onSetCacheHours = {
                            val h = it.coerceIn(PlayerSettingsStore.MIN_CACHE_HOURS, PlayerSettingsStore.MAX_CACHE_HOURS)
                            cacheHours = h
                            app.playerSettings.cacheHours = h
                            app.repository.cacheMaxAgeHours = h
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
                        cloudSignIn = cloudSignIn,
                        onCloudSignIn = { startCloudSignIn(it) },
                        onCancelCloudSignIn = {
                            cloudSignInJob?.cancel(); cloudSignInJob = null; cloudSignIn = null
                        },
                        onExit = { pop() },
                        catalogue = catalogue,
                        repo = app.repository,
                        navHomeFor = { k -> app.navHome.get(k) },
                        onSetNavHome = { k, country, cat -> app.navHome.set(k, country, cat) },
                        onClearNavHome = { k -> app.navHome.clear(k) },
                        tmdbApiKey = tmdbApiKey,
                        onSetTmdbApiKey = { k -> app.movieMetadata.apiKey = k; tmdbApiKey = k },
                        onClearMovieInfoCache = { app.movieMetadata.clearCache() },
                        household = {
                            io.tapper.firetv.ui.RecordingSettings(app.playerSettings, app.seriesRules, app.recordingLocation)
                            io.tapper.firetv.ui.TraktSettings(app.trakt, app.openSubtitles)
                            io.tapper.firetv.ui.AlexaSettings(
                                app.alexa,
                                tvName = android.os.Build.MODEL ?: "Fire TV",
                                onChanged = { alexaRevision++ },
                            )
                            io.tapper.firetv.ui.HouseholdSettings(
                                profiles = app.profiles,
                                edits = app.channelEdits,
                                parental = app.parental,
                                setup = app.setupSync,
                                syncConfigured = app.sync.backendOrNull() != null,
                                onSwitchProfile = { id -> app.switchProfile(id); recreate() },
                                onChanged = { editRevision++ },
                                onRestart = {
                                    // Every store re-reads its file on a fresh start.
                                    val i = packageManager.getLaunchIntentForPackage(packageName)
                                    if (i != null) {
                                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        startActivity(i)
                                    }
                                    Runtime.getRuntime().exit(0)
                                },
                            )
                        },
                    )

                    current is Screen.Browse -> {
                    // Hoisted out of the catalogue = ... argument below so
                    // onPlay can also reach it, for allLiveChannels - see the
                    // Playing data class above.
                    val resolvedCatalogue = remember(catalogue, current.catalogue, logoFolderIndex) {
                        (catalogue ?: current.catalogue).withResolvedLogos(logoFolderIndex)
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
                            val target = list.getOrNull(i)
                            if (playing != null && target != null && target.id == playingChannel?.id) {
                                // Already on in the corner: just enlarge it,
                                // rather than re-tuning the same stream.
                                playerFullscreen = true
                            } else {
                                startPlayback(
                                    Playing(list, i, resolvedCatalogue.section(ContentKind.LIVE)?.items.orEmpty())
                                )
                            }
                        },
                        miniPlayerActive = playing != null && !playerFullscreen,
                        revealChannel = revealChannel,
                        revealToken = revealToken,
                        onSwitchSource = { app.sourceStore.activeId = it.id; active = it },
                        onAddSource = { addError = null; push(Screen.AddSource) },
                        onSearch = { push(Screen.Search) },
                        onSettings = { onSettingsTapped() },
                        onRefreshEpg = { refreshEpg(current.catalogue) },
                        onOpenSeries = { openSeries(it) },
                        initialChannelId = lastChannelId,
                        recordings = recordingsList,
                        // Gary's TV guide grid: what airs on a channel in a time window.
                        guideWindowFor = { ch, from, to ->
                            app.epgDb.upcoming(guideSourceIds, epgIdFor(ch), from, limit = 40)
                                .filter { it.startUtc < to }
                        },
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
                            val now = System.currentTimeMillis()
                            // A channel with an archive also lists what
                            // already aired today, for catch-up.
                            if (ch.catchup != null) {
                                app.epgDb.upcoming(guideSourceIds, epgIdFor(ch), now - 24 * 60 * 60 * 1000L, limit = 40)
                            } else {
                                app.epgDb.upcoming(guideSourceIds, epgIdFor(ch), now)
                            }
                        },
                        canCatchUp = { ch, p ->
                            CatchupUrl.canPlay(ch, p.startUtc, System.currentTimeMillis())
                        },
                        onCatchUp = { ch, p ->
                            val archived = CatchupUrl.archiveChannel(
                                ch, p.title, p.startUtc, p.endUtc, System.currentTimeMillis(),
                            )
                            if (archived == null) {
                                Toast.makeText(this@MainActivity, "That programme is no longer in ${ch.name}'s archive", Toast.LENGTH_LONG).show()
                            } else {
                                startPlayback(Playing(listOf(archived), 0))
                            }
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
                                val now = System.currentTimeMillis()
                                val padBefore = app.playerSettings.recordPadBefore * 60_000L
                                val padAfter = app.playerSettings.recordPadAfter * 60_000L
                                val start = if (now >= p.startUtc - padBefore) now else p.startUtc - padBefore
                                app.recordings.schedule(
                                    Recording(
                                        id = Recording.newId(), sourceId = ch.sourceId, channelId = ch.id,
                                        channelName = ch.name, streamUrl = url,
                                        startUtc = start, endUtc = p.endUtc + padAfter,
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
                        onOpenTeams = { push(Screen.Teams) },
                        multiviewCount = multiview.size,
                        channelActions = ::channelActions,
                        isSeriesRecorded = { ch, p -> app.seriesRules.find(ch.sourceId, ch.id, p.title) != null },
                        onToggleSeries = { ch, p ->
                            val existing = app.seriesRules.find(ch.sourceId, ch.id, p.title)
                            val url = recordableUrl(ch)
                            when {
                                existing != null -> {
                                    app.seriesRules.remove(existing.id)
                                    Toast.makeText(this@MainActivity, "No longer recording every ${p.title}. Scheduled ones stay in Recordings.", Toast.LENGTH_LONG).show()
                                }
                                url == null -> Toast.makeText(this@MainActivity, "${ch.name} can't be recorded", Toast.LENGTH_LONG).show()
                                else -> {
                                    app.seriesRules.add(
                                        io.tapper.firetv.data.SeriesRuleStore.Rule(
                                            id = app.seriesRules.newRuleId(), sourceId = ch.sourceId, channelId = ch.id,
                                            channelName = ch.name, streamUrl = url, guideId = epgIdFor(ch),
                                            guideSourceIds = guideSourceIds, title = p.title,
                                        )
                                    )
                                    val n = app.seriesRules.scheduleUpcoming(app.epgDb, app.recordings, app.playerSettings)
                                    refreshRecordings()
                                    Toast.makeText(this@MainActivity, "Recording every ${p.title} on ${ch.name} ($n in the guide now)", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        categoryActions = ::categoryActions,
                        onAddToMultiview = { ch ->
                            // The provider's stream limit caps it too: past it,
                            // the extra tiles would just fail to open.
                            val limit = app.repository.maxConnections[ch.sourceId]?.takeIf { it > 0 }
                            val max = minOf(io.tapper.firetv.ui.multiviewTiles(this@MainActivity), limit ?: Int.MAX_VALUE)
                            when {
                                limit != null && multiview.size >= limit ->
                                    Toast.makeText(this@MainActivity, "Your plan allows $limit stream${if (limit == 1) "" else "s"} at once", Toast.LENGTH_LONG).show()
                                multiview.any { it.id == ch.id && it.sourceId == ch.sourceId } ->
                                    Toast.makeText(this@MainActivity, "${ch.name} is already in multiview", Toast.LENGTH_SHORT).show()
                                multiview.size >= max ->
                                    Toast.makeText(this@MainActivity, "Multiview holds $max channels on this device", Toast.LENGTH_LONG).show()
                                else -> {
                                    multiview = multiview + ch
                                    if (multiview.size >= 2) {
                                        stopPlayback()
                                        push(Screen.Multiview)
                                    } else {
                                        Toast.makeText(this@MainActivity, "Added ${ch.name}. Long-press another channel and choose Add to multiview.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onOpenMultiview = { stopPlayback(); push(Screen.Multiview) },
                    )
                    }

                    current is Screen.Multiview -> io.tapper.firetv.ui.MultiviewScreen(
                        initial = multiview,
                        allChannels = catalogue?.section(ContentKind.LIVE)?.items.orEmpty(),
                        onFullScreen = { ch ->
                            pop()
                            startPlayback(Playing(listOf(ch), 0))
                        },
                        onChanged = { multiview = it },
                        onExit = { pop() },
                    )

                    current is Screen.Teams && catalogue != null -> TeamsScreen(
                        store = app.teams,
                        sports = app.sports,
                        channels = catalogue.channels,
                        epgIdFor = ::epgIdFor,
                        listingsBetween = { from, to -> app.epgDb.startingBetween(guideSourceIds, from, to) },
                        onWatch = { ch -> startPlayback(Playing(listOf(ch), 0)); pop() },
                        onAutoRemind = { list -> autoRemindGames(list) },
                        onRemind = { ch, g, m ->
                            // The guide's listing when there is one (its start is what the
                            // reminder alarm fires on), otherwise the game's own start.
                            val start = m.listing?.startUtc ?: g.startUtc
                            app.reminders.add(
                                Reminder(
                                    id = Reminder.idFor(ch.sourceId, ch.id, start),
                                    sourceId = ch.sourceId, channelId = ch.id, channelName = ch.name,
                                    programmeTitle = m.listing?.title ?: "${g.league.label}: ${g.title}",
                                    startUtc = start,
                                )
                            )
                            refreshReminders()
                            Toast.makeText(this@MainActivity, "Reminder set for ${g.title} on ${ch.name}", Toast.LENGTH_LONG).show()
                        },
                        onRecord = { ch, g, m ->
                            val url = recordableUrl(ch)
                            if (url == null) {
                                Toast.makeText(this@MainActivity, "${ch.name} can't be recorded", Toast.LENGTH_LONG).show()
                            } else {
                                // Games run long: from a few minutes early until an hour
                                // after the listing (or the usual length) ends.
                                val now = System.currentTimeMillis()
                                val start = maxOf(now, (m.listing?.startUtc ?: g.startUtc) - 5 * 60_000)
                                val end = maxOf(m.listing?.endUtc ?: 0L, g.endGuessUtc) + 60 * 60_000
                                app.recordings.schedule(
                                    Recording(
                                        id = Recording.newId(), sourceId = ch.sourceId, channelId = ch.id,
                                        channelName = ch.name, streamUrl = url, startUtc = start, endUtc = end,
                                    )
                                )
                                refreshRecordings()
                                Toast.makeText(this@MainActivity, "Will record ${g.title} on ${ch.name}, with an hour extra", Toast.LENGTH_LONG).show()
                            }
                        },
                        onExit = { pop() },
                    )


                    current is Screen.Recordings -> RecordingsScreen(
                        recordings = recordingsList,
                        onPlay = { r ->
                            val path = r.filePath
                            if (path != null) {
                                val synthetic = Channel(
                                    id = "recording-" + r.id, sourceId = r.sourceId,
                                    name = r.channelName, number = null, logoUrl = null,
                                    group = null, countryCode = null, epgChannelId = null,
                                    // smb:// recordings are on a network share and
                                    // play through SmbDataSource; the rest are files here.
                                    streams = listOf(StreamRef(
                                        url = if (path.startsWith("smb://", ignoreCase = true)) path else "file://" + path,
                                        priority = 0,
                                    )),
                                    kind = ContentKind.MOVIE,
                                )
                                startPlayback(Playing(listOf(synthetic), 0))
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
                        savedTo = { app.recordingLocation.activeShare()?.label ?: "this Fire TV" },
                        settingsPanel = {
                            io.tapper.firetv.ui.RecordingSettings(app.playerSettings, app.seriesRules, app.recordingLocation)
                        },
                    )
                }
                }
                if (p != null) {
                    key(playToken) {
                    PlayerScreen(
                        minimized = !playerFullscreen,
                        onExpand = { playerFullscreen = true },
                        // Back from a LIVE stream started in Browse drops it
                        // into the corner instead of stopping it; anything
                        // else (movies, episodes, recordings, or a stream
                        // started from Search) keeps the old Back = stop.
                        onMinimize = if (current is Screen.Browse && playingChannel?.kind == ContentKind.LIVE) ({
                            revealChannel = playingChannel
                            revealToken++
                            playerFullscreen = false
                        }) else null,
                        channels = p.channels,
                        startIndex = p.index,
                        allLiveChannels = p.allLiveChannels,
                        nowPlaying = { ch -> nowPlaying[epgIdFor(ch)] },
                        scheduleFor = { ch ->
                            app.epgDb.upcoming(guideSourceIds, epgIdFor(ch), System.currentTimeMillis())
                        },
                        resumeAt = { ch -> app.sync.resumeAt(ch.id) },
                        onChannelChanged = { ch ->
                            playingChannel = ch
                            lastChannelId = ch.id
                            app.sourceStore.lastChannelId = ch.id
                        },
                        onProgress = { ch, pos, dur, finished ->
                            app.sync.record(
                                itemId = ch.id,
                                sourceId = ch.sourceId,
                                // Only episodes belong to a series. seriesContext
                                // lingers after leaving a show, so without this a
                                // film played afterwards was tagged with that show.
                                seriesId = if (ch.id.startsWith("ep-")) seriesContext?.seriesId else null,
                                season = if (ch.id.startsWith("ep-")) {
                                    ch.group?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0
                                } else 0,
                                number = ch.number ?: 0,
                                title = ch.name,
                                positionMs = pos,
                                durationMs = dur,
                                finished = finished,
                            )
                            watchRevision++
                            // Trakt history, once per title per session.
                            if (finished && app.trakt.signedIn && traktSent.add(ch.sourceId + "|" + ch.id)) {
                                val isEp = ch.id.startsWith("ep-")
                                lifecycleScope.launch {
                                    app.trakt.markWatched(
                                        ch,
                                        seriesName = if (isEp) seriesContext?.name else null,
                                        season = if (isEp) ch.group?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 else 0,
                                        episode = if (isEp) ch.number ?: 0 else 0,
                                    ).onFailure { t -> app.logEvent("TRAKT", "${ch.name}: ${t.message}") }
                                }
                            }
                        },
                        onExit = { stopPlayback() },
                        autoAdvance = p.autoAdvance,
                        bufferSize = bufferSize,
                    )
                    }
                }
                if (stillWatchingPrompt && p != null) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Still watching?",
                                style = MaterialTheme.typography.displaySmall,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Press any button to keep playing. Playback stops in a couple of minutes otherwise.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                            )
                        }
                    }
                }
                prompt?.let { req ->
                    io.tapper.firetv.ui.TextPrompt(req, onDismiss = { if (prompt === req) prompt = null })
                }
                castMenu?.let { (ch, cast) ->
                    io.tapper.firetv.ui.ItemMenu(
                        title = "Cast of ${ch.name}",
                        subtitle = "Pick someone to see what else they're in here.",
                        actions = cast.map { person ->
                            MenuAction(person.name + (person.character?.let { "  as $it" } ?: "")) {
                                val all = rawCatalogue?.channels.orEmpty()
                                lifecycleScope.launch {
                                    val found = withContext(Dispatchers.IO) { app.movieMetadata.alsoStarring(person, all) }
                                        .filterNot { it.id == ch.id && it.sourceId == ch.sourceId }
                                    if (found.isEmpty()) {
                                        Toast.makeText(this@MainActivity, "Nothing else with ${person.name} here", Toast.LENGTH_LONG).show()
                                    } else {
                                        starringMenu = person.name to found
                                    }
                                }
                            }
                        },
                        onDismiss = { castMenu = null },
                    )
                }
                starringMenu?.let { (name, found) ->
                    io.tapper.firetv.ui.ItemMenu(
                        title = "With $name",
                        subtitle = "${found.size} titles here",
                        actions = found.take(30).map { t ->
                            MenuAction(t.name + if (t.kind == ContentKind.SERIES) "  (series)" else "") {
                                if (t.kind == ContentKind.SERIES) openSeries(t) else startPlayback(Playing(listOf(t), 0))
                            }
                        },
                        onDismiss = { starringMenu = null },
                    )
                }
                }
            } }
        }
    }
}

/** A ready-made Xtream service that Settings can add in one tap. */
private data class PresetDef(
    val id: String,
    val label: String,
    val username: String,
    val password: String,
    val host: String,
)

private val PRESET_SOURCES = listOf(
    PresetDef("xtream-prime4tv-hidden", "Old Prime4TV", "70405ae64f", "ae1752cd23", "http://line.prime4tv.com"),
    PresetDef("xtream-prime-account-1", "Prime Account 1", "5da5827d33", "3901889afd39", "http://line.prime-43.com"),
    PresetDef("xtream-prime-account-2", "Prime Account 2", "3250e96279", "30f470082b39", "http://line.prime-43.com"),
)
