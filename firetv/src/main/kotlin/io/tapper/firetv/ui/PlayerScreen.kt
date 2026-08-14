package io.tapper.firetv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.core.playback.Diagnosis
import io.tapper.firetv.data.BufferSize
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.player.TapperPlayer
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * Full-screen playback with up/down channel changing.
 *
 * The channel list handed in is the one the user was browsing, so zapping walks
 * the same order they were just looking at rather than some global index.
 */
@Composable
fun PlayerScreen(
    channels: List<Channel>,
    startIndex: Int,
    nowPlaying: (Channel) -> EpgDatabase.Programme?,
    // Upcoming programmes for one channel, current show first - same shape
    // BrowseScreen's own ProgrammePanel already uses (EpgDatabase.upcoming).
    // Defaulted so nothing else calling this needs to change.
    scheduleFor: (Channel) -> List<EpgDatabase.Programme> = { emptyList() },
    resumeAt: (Channel) -> Long?,
    onProgress: (Channel, Long, Long, Boolean) -> Unit,
    onChannelChanged: (Channel) -> Unit,
    onExit: () -> Unit,
    bufferSize: BufferSize = BufferSize.MEDIUM,
    // The WHOLE Live TV catalogue, not just `channels` above (which is only
    // ever whatever narrower list - a single category, a country, My List,
    // ... - the user happened to be browsing when they pressed play). Feeds
    // the expanded guide's category sidebar, so switching categories there
    // has something broader to offer than the one category already on
    // screen. Defaulted to empty (and falls back to `channels` below) so
    // on-demand playback, which never opens the guide anyway, needs nothing.
    allLiveChannels: List<Channel> = emptyList(),
) {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val focus = remember { FocusRequester() }

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    // Set when the expanded guide's category sidebar is used to select a
    // channel that isn't a member of `channels` - see allLiveChannels above.
    // `index` can only ever point INTO `channels`, so a channel from outside
    // it has nowhere to live there; this is tracked separately and takes
    // priority over `index` whenever it's set. Cleared the moment playback
    // moves via anything that IS `channels`/`index`-based (plain zap, or a
    // guide selection that resolves back into `channels`).
    var foreignChannel by remember { mutableStateOf<Channel?>(null) }
    val channel = foreignChannel ?: channels.getOrNull(index) ?: return
    var status by remember(channel.id) { mutableStateOf<String?>("Tuning ${channel.name}...") }
    // Shown briefly after a channel change, then fades out of the way.
    var overlayVisible by remember { mutableStateOf(true) }
    // Transport controls (play/pause, seek) - on-demand only. A live stream
    // has no position to show a bar for and nothing to pause into.
    var showControls by remember(channel.id) { mutableStateOf(false) }
    // Bumped by every control interaction so the auto-hide timer below
    // restarts instead of closing mid-scrub - see the LaunchedEffect for it.
    var controlsRevision by remember { mutableIntStateOf(0) }
    var livePosition by remember(channel.id) { mutableStateOf(0L) }
    var liveDuration by remember(channel.id) { mutableStateOf(0L) }
    var isPlaying by remember(channel.id) { mutableStateOf(true) }

    // In-player channel browsing, live only - there is no "next channel" to
    // browse on an on-demand item. 0 = plain playback. 1 = the compact guide
    // (Down): a channel list overlaid on the still-full-screen picture. 2 =
    // the expanded guide (Left, from either plain playback or the compact
    // guide): the picture shrinks to a preview so a category sidebar and a
    // longer channel list fit.
    var overlayLevel by remember { mutableIntStateOf(0) }
    // The channel currently highlighted while a guide is open - separate
    // from `index` (what is actually playing) so Up/Down only previews.
    // Nothing changes what's on screen until Select/Enter copies this into
    // `index`; Back with nothing selected leaves playback exactly where it
    // was. Reset to whatever is actually playing every time a guide is
    // freshly opened - see openGuide() below.
    var previewChannelId by remember { mutableStateOf(channel.id) }
    // Index into categoryOptions below, not into any channel list.
    var guideCategoryIndex by remember { mutableIntStateOf(0) }

    // The broader pool the expanded guide's category sidebar draws from -
    // the full Live TV catalogue when it was supplied, falling back to
    // `channels` for on-demand playback (which never opens the guide) or any
    // older caller that hasn't started passing allLiveChannels yet.
    val guideChannels = remember(channels, allLiveChannels) { allLiveChannels.ifEmpty { channels } }

    // Every category any channel in that pool belongs to, flattened the same
    // way BrowseScreen's own category rail does - group falls back to the
    // single legacy group when a channel has no multi-category list at all.
    val categories = remember(guideChannels) {
        guideChannels.flatMap { it.categories.ifEmpty { listOfNotNull(it.group) } }
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    // null stands for "All channels", always first.
    val categoryOptions = remember(categories) { listOf<String?>(null) + categories }
    val selectedCategory = categoryOptions.getOrNull(guideCategoryIndex)
    val filteredChannels = remember(guideChannels, selectedCategory) {
        if (selectedCategory == null) guideChannels
        else guideChannels.filter { selectedCategory in it.categories.ifEmpty { listOfNotNull(it.group) } }
    }
    // The compact guide's own channel list - almost always just `channels`,
    // but with whatever foreign channel is currently playing (see
    // foreignChannel above) prepended when it isn't already a member. Without
    // this, opening the compact guide right after picking something from the
    // expanded guide's broader category list couldn't find the actually-
    // playing channel in `channels` at all, and fell back to highlighting row
    // 0 as if IT were what's playing.
    val compactGuideChannels = remember(channels, foreignChannel) {
        val fc = foreignChannel
        if (fc != null && channels.none { it.id == fc.id }) listOf(fc) + channels else channels
    }
    // Where the preview currently sits in each list, falling back to the top
    // rather than crashing or showing nothing when previewChannelId isn't a
    // member - e.g. it was set from the full list but a category filter was
    // then applied that excludes it. Plain computed values, not state: nothing
    // needs to remember these across recompositions, only read them fresh.
    val previewIndexInChannels = compactGuideChannels.indexOfFirst { it.id == previewChannelId }.let { if (it >= 0) it else index }
    val previewIndexInFiltered = filteredChannels.indexOfFirst { it.id == previewChannelId }.let { if (it >= 0) it else 0 }

    val player = remember {
        TapperPlayer(
            context = context,
            scope = scope,
            onDiagnosis = { d: Diagnosis -> status = d.message() },
            onPlaying = { status = null },
            bufferSize = bufferSize,
        )
    }

    // Re-tunes whenever the index changes; also covers the initial channel.
    LaunchedEffect(channel.id) {
        // The guide overlays are a live-browsing feature; step() can only
        // ever land on another LIVE entry today, but this is cheap insurance
        // against a future mixed-kind list leaving the picture shrunk with
        // no way to grow it back for something that isn't zappable.
        if (channel.kind != ContentKind.LIVE) overlayLevel = 0
        overlayVisible = true
        // Reported on every change so backing out lands on the channel actually
        // being watched, not the one originally chosen.
        onChannelChanged(channel)
        player.play(channel, resumeAt(channel))
    }

    LaunchedEffect(channel.id, status) {
        if (status == null) { delay(4000); overlayVisible = false }
    }

    // Closes itself after a few seconds of no interaction, same as the
    // channel badge above - a transport bar left permanently on screen over
    // a movie is more obstruction than help. controlsRevision restarts this
    // on every seek/play-pause press instead of the close racing an
    // in-progress scrub.
    LaunchedEffect(showControls, controlsRevision) {
        if (showControls) { delay(6000); showControls = false }
    }

    // Keeps the seek bar and play/pause label live while the controls are
    // open. Not worth polling while they're hidden - nothing on screen would
    // show it.
    LaunchedEffect(channel.id, showControls) {
        if (!showControls) return@LaunchedEffect
        while (true) {
            livePosition = player.positionMs()
            liveDuration = player.durationMs()
            isPlaying = player.isPlaying()
            delay(500)
        }
    }

    // Progress is sampled on a timer and again on exit. Live channels have no
    // meaningful position, so only on-demand items are recorded.
    LaunchedEffect(channel.id) {
        if (channel.kind == ContentKind.LIVE) return@LaunchedEffect
        while (true) {
            delay(10_000)
            val pos = player.positionMs()
            val dur = player.durationMs()
            if (dur > 0) onProgress(channel, pos, dur, false)
        }
    }

    DisposableEffect(channel.id) {
        onDispose {
            if (channel.kind != ContentKind.LIVE) {
                val pos = player.positionMs(); val dur = player.durationMs()
                // "Only on exit" would lose everything when Fire OS kills the
                // app for memory, which it does often; this is the backstop.
                if (dur > 0) onProgress(channel, pos, dur, false)
            }
        }
    }

    DisposableEffect(Unit) { onDispose { player.release(); scope.cancel() } }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The controls closing (auto-hide or Back) removes whichever button was
    // focused from composition entirely, and Compose doesn't automatically
    // hand focus back to anything when that happens - without this the D-pad
    // would go dead until something is tapped, same failure BrowseScreen's
    // own nav-collapse comment already describes.
    LaunchedEffect(showControls) {
        if (!showControls) runCatching { focus.requestFocus() }
    }

    // Closes the transport controls or an open guide overlay first, same as
    // any TV player - only a Back with nothing open actually leaves the
    // screen. Without this the very first thing anyone who opened the guide
    // would learn is that Back skips straight past it and quits playback.
    BackHandler {
        when {
            showControls -> showControls = false
            overlayLevel != 0 -> overlayLevel = 0
            else -> onExit()
        }
    }

    // Direct zap - the dedicated remote shortcuts (plain Up, ChannelUp/Down,
    // PageUp/Down) still work this way outside any guide, same as always.
    fun step(delta: Int) {
        if (channels.size < 2) return
        // A direct zap is always `channels`/`index`-based, so any foreign
        // channel a guide selection had switched to (see foreignChannel
        // above) is no longer what's being navigated from - drop it rather
        // than leaving it playing while index silently drifts underneath it.
        foreignChannel = null
        // Wraps at both ends: hitting up on the first channel should land on the
        // last, not sit there doing nothing.
        index = ((index + delta) % channels.size + channels.size) % channels.size
    }

    // Opens a guide fresh, always starting the preview on whatever is
    // actually playing right now - not wherever it was last left, which
    // would otherwise be a stale channel from a browse that was cancelled
    // with Back instead of committed with Select.
    fun openGuide(level: Int) {
        previewChannelId = channel.id
        overlayLevel = level
    }

    // Moves the compact guide's preview within the full channel list. Does
    // not touch `index` - see previewChannelId above.
    fun previewStep(delta: Int) {
        if (compactGuideChannels.isEmpty()) return
        val next = ((previewIndexInChannels + delta) % compactGuideChannels.size + compactGuideChannels.size) % compactGuideChannels.size
        previewChannelId = compactGuideChannels[next].id
    }

    // Same idea, scoped to the expanded guide's current category.
    fun previewStepExpanded(delta: Int) {
        if (filteredChannels.isEmpty()) return
        val next = ((previewIndexInFiltered + delta) % filteredChannels.size + filteredChannels.size) % filteredChannels.size
        previewChannelId = filteredChannels[next].id
    }

    // Select/Enter: the only thing that actually changes what's playing
    // while a guide is open. Closes the guide too, same as picking a channel
    // in any TV guide returns to watching it.
    fun commitPreview() {
        val target = channels.indexOfFirst { it.id == previewChannelId }
        if (target >= 0) {
            foreignChannel = null
            index = target
        } else {
            // Selected a channel from a category the expanded guide's
            // sidebar pulled in from allLiveChannels that isn't part of the
            // original `channels` list - there's no slot for it in
            // `index`/`channels` to represent, so it's tracked separately
            // instead (see foreignChannel above). Falls back to leaving
            // playback alone if it's somehow not in guideChannels either.
            foreignChannel = guideChannels.firstOrNull { it.id == previewChannelId } ?: foreignChannel
        }
        overlayLevel = 0
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Backdrop)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                // Once the controls are open, they and their own children own
                // every key - this handler backing off is what lets D-pad
                // focus move between the seek bar and the buttons instead of
                // Down re-triggering itself or Up doing nothing forever.
                if (showControls) return@onKeyEvent false
                // Once a guide overlay is open it owns Up/Down/Left/Right -
                // same reasoning as showControls backing off above, so the
                // plain-playback zap shortcuts below don't fire twice for one
                // press.
                if (overlayLevel == 2) {
                    return@onKeyEvent when (e.key) {
                        // The list on screen runs top-to-bottom in ascending
                        // order (guideWindow never reorders it), so Down has
                        // to step toward a HIGHER index to match moving down
                        // the visible rows, and Up toward a lower one - the
                        // opposite of plain zapping's step() below, which has
                        // no visible list to match and follows the classic
                        // Channel Up/Down convention instead.
                        Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { previewStepExpanded(-1); true }
                        Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { previewStepExpanded(1); true }
                        Key.DirectionLeft ->
                            { guideCategoryIndex = (guideCategoryIndex - 1 + categoryOptions.size) % categoryOptions.size; true }
                        Key.DirectionRight ->
                            { guideCategoryIndex = (guideCategoryIndex + 1) % categoryOptions.size; true }
                        Key.DirectionCenter, Key.Enter -> { commitPreview(); true }
                        else -> false
                    }
                }
                if (overlayLevel == 1) {
                    return@onKeyEvent when (e.key) {
                        // Same top-to-bottom-ascending reasoning as the
                        // expanded guide above.
                        Key.DirectionUp, Key.ChannelUp, Key.PageUp -> { previewStep(-1); true }
                        Key.DirectionDown, Key.ChannelDown, Key.PageDown -> { previewStep(1); true }
                        // Steps out to the fuller category browser, carrying
                        // the same preview forward rather than resetting it.
                        Key.DirectionLeft -> { overlayLevel = 2; true }
                        Key.DirectionCenter, Key.Enter -> { commitPreview(); true }
                        else -> false
                    }
                }
                when (e.key) {
                    // Live: Up zaps directly, the same shortcut as
                    // ChannelUp/PageUp on a real remote. Down instead opens
                    // the compact guide, which only *previews* as you move
                    // through it - Select is what actually tunes to the
                    // highlighted channel. On-demand has no "next channel" in
                    // that sense - Down opens the transport controls, which
                    // is where seeking and pause actually live for it.
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp ->
                        if (channel.kind == ContentKind.LIVE) { step(1); true } else false
                    Key.ChannelDown, Key.PageDown ->
                        if (channel.kind == ContentKind.LIVE) { step(-1); true }
                        else { showControls = true; true }
                    Key.DirectionDown ->
                        if (channel.kind == ContentKind.LIVE) { openGuide(1); true }
                        else { showControls = true; true }
                    Key.DirectionLeft ->
                        if (channel.kind == ContentKind.LIVE) { openGuide(2); true } else false
                    Key.DirectionCenter, Key.Enter ->
                        if (channel.kind == ContentKind.LIVE) { overlayVisible = !overlayVisible; true }
                        else { showControls = true; true }
                    else -> false
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    player.attach(this)
                }
            },
            // The expanded guide (level 2) shrinks playback to a preview so
            // the category sidebar and channel list have room - everything
            // else (including the compact guide) keeps the picture full
            // screen behind it.
            modifier = if (overlayLevel == 2)
                Modifier.padding(24.dp).size(360.dp, 203.dp).align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(10.dp))
            else Modifier.fillMaxSize(),
        )

        val msg = status
        if (msg != null) {
            Column(
                Modifier.align(Alignment.Center).padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(channel.name, style = MaterialTheme.typography.headlineLarge, color = Ink)
                Spacer(Modifier.height(12.dp))
                Text(msg, style = MaterialTheme.typography.bodyLarge, color = Dim)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (channel.kind == ContentKind.LIVE)
                        "Up / Down to change channel  ·  Back to return"
                    else "Down for playback controls  ·  Back to return",
                    style = MaterialTheme.typography.bodyMedium, color = Dim,
                )
            }
        } else if (showControls) {
            PlayerControls(
                title = channel.name,
                playing = isPlaying,
                positionMs = livePosition,
                durationMs = liveDuration,
                onPlayPause = {
                    val next = !isPlaying
                    player.setPlaying(next); isPlaying = next; controlsRevision++
                },
                onSeekBy = { delta ->
                    player.seekBy(delta)
                    livePosition = player.positionMs()
                    controlsRevision++
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(48.dp).fillMaxWidth(),
            )
        } else if (overlayLevel == 0 && overlayVisible) {
            ChannelBadge(
                channel = channel,
                // `index` only means something inside `channels` - a channel
                // picked from outside it (see foreignChannel above) has no
                // honest position to show there, so it's left blank rather
                // than printing a stale/misleading number.
                position = if (foreignChannel != null) "" else "${index + 1} / ${channels.size}",
                programme = nowPlaying(channel),
                modifier = Modifier.align(Alignment.BottomStart).padding(48.dp),
            )
        }

        if (overlayLevel == 1) {
            GuideOverlay(
                channels = compactGuideChannels,
                previewChannelId = previewChannelId,
                nowPlaying = nowPlaying,
                scheduleFor = scheduleFor,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 48.dp),
            )
        } else if (overlayLevel == 2) {
            ExpandedGuideOverlay(
                categoryOptions = categoryOptions,
                selectedCategoryIndex = guideCategoryIndex,
                filteredChannels = filteredChannels,
                previewChannelId = previewChannelId,
                nowPlaying = nowPlaying,
                scheduleFor = scheduleFor,
                modifier = Modifier.fillMaxSize().padding(24.dp),
            )
        }
    }
}

/**
 * Transport controls for on-demand playback, opened by pressing Down over a
 * movie or show (see the key handler above) - live channels never reach
 * this, they have Up/Down zapping instead.
 *
 * Seeking is step-based rather than drag-based: there is no pointer on a
 * Fire TV remote to drag a slider with, so Left/Right on the bar jump by a
 * fixed increment instead, same as every other D-pad-driven player. The
 * increment scales with the title's own length - 10 seconds is fine for a
 * 20-minute episode but would take over a hundred presses to cross a
 * two-hour film, so it's a percentage of duration instead, clamped to a
 * sane range for both ends.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerControls(
    title: String,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playPauseFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playPauseFocus.requestFocus() } }
    val step = if (durationMs > 0) (durationMs / 50).coerceIn(5_000L, 60_000L) else 10_000L

    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 28.dp, vertical = 20.dp),
    ) {
        Text(
            title, style = MaterialTheme.typography.titleMedium, color = Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.basicMarquee(),
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(positionMs), style = MaterialTheme.typography.bodyMedium, color = Dim)
            Text(
                if (durationMs > 0) formatPlaybackTime(durationMs) else "--:--",
                style = MaterialTheme.typography.bodyMedium, color = Dim,
            )
        }
        Spacer(Modifier.height(8.dp))
        SeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeekBy = onSeekBy,
        )
        Spacer(Modifier.height(20.dp))
        // The label reflects the same step used to seek, not a fixed "10s" -
        // it scales with the title's length (see step above), so a two-hour
        // film and a twenty-minute episode each show the jump size they'll
        // actually get.
        val stepLabel = "${step / 1000}s"
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ControlButton("< $stepLabel") { onSeekBy(-step) }
            ControlButton(if (playing) "Pause" else "Play", modifier = Modifier.focusRequester(playPauseFocus)) {
                onPlayPause()
            }
            ControlButton("$stepLabel >") { onSeekBy(step) }
        }
    }
}

@Composable
private fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val step = if (durationMs > 0) (durationMs / 50).coerceIn(5_000L, 60_000L) else 10_000L
    val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent, RoundedCornerShape(5.dp))
            .focusable(interactionSource = interaction)
            // Left/Right must be consumed here, not left to bubble - the
            // parent's own key handler backs off entirely while controls are
            // open (see PlayerScreen above) specifically so this bar and the
            // buttons next to it are the only things arrow keys can reach.
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onSeekBy(-step); true }
                    Key.DirectionRight -> { onSeekBy(step); true }
                    else -> false
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .clip(RoundedCornerShape(5.dp))
                .background(Focus)
        )
    }
}

@Composable
private fun ControlButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Focus.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.10f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            color = if (focused) Ink else Dim, maxLines = 1)
    }
}

private fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelBadge(
    channel: Channel,
    position: String,
    programme: EpgDatabase.Programme?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(
                model = channel.logoUrl, contentDescription = null,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.widthIn(max = 620.dp)) {
            // Always on, not focus-gated like the browse list rows - only one
            // badge is ever on screen here, so there's no risk of several
            // rows scrolling at once.
            Text(
                channel.name, style = MaterialTheme.typography.titleMedium, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            if (programme != null) {
                Text(
                    programme.title, style = MaterialTheme.typography.bodyLarge, color = Focus,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                programme.description?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium, color = Dim,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(position, style = MaterialTheme.typography.bodyMedium, color = Dim)
            }
        }
    }
}

/**
 * Which channel-list rows a guide should draw: every channel, in order, if
 * they all fit within the window - never padding a short list out to a fixed
 * row count by wrapping back over channels already shown, which is what a
 * plain sliding window did with only a couple of channels to show (the same
 * one or two entries repeated to fill five rows). Only once there are more
 * channels than the window holds does it become a `radius`-either-side slice
 * centred on `center`, wrapping at the ends.
 */
private fun guideWindow(size: Int, center: Int, radius: Int): List<Int> {
    if (size <= 0) return emptyList()
    val windowSize = radius * 2 + 1
    if (size <= windowSize) return (0 until size).toList()
    return (-radius..radius).map { offset -> ((center + offset) % size + size) % size }
}

/**
 * The compact guide (Down): a small window of channels around the one
 * currently highlighted, laid over the still-full-screen picture behind it,
 * plus what's coming up next on whichever one is highlighted. Up/Down only
 * moves the highlight (`previewChannelId`, tracked by the caller) - nothing
 * actually changes what's playing until Select/Enter.
 *
 * There is deliberately no LazyColumn/scroll-to-item or per-row
 * FocusRequester here: the window is a fixed handful of rows recomputed on
 * every keypress, driven entirely by the root Box's own key handler above.
 * BrowseScreen's own history this app has had with requestFocus() races on a
 * real TV remote is exactly the failure mode this sidesteps by not asking
 * Compose focus to track anything here at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideOverlay(
    channels: List<Channel>,
    previewChannelId: String,
    nowPlaying: (Channel) -> EpgDatabase.Programme?,
    scheduleFor: (Channel) -> List<EpgDatabase.Programme>,
    modifier: Modifier = Modifier,
) {
    val previewIndex = channels.indexOfFirst { it.id == previewChannelId }.let { if (it >= 0) it else 0 }
    val windowIndices = guideWindow(channels.size, previewIndex, 2)
    val previewChannel = channels.getOrNull(previewIndex)

    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.80f))
            .padding(vertical = 12.dp),
    ) {
        Text(
            "Guide", style = MaterialTheme.typography.labelLarge, color = Dim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Row {
            Column(Modifier.width(320.dp)) {
                windowIndices.forEach { i ->
                    val ch = channels[i]
                    GuideRow(channel = ch, programme = nowPlaying(ch), active = i == previewIndex)
                }
            }
            if (previewChannel != null) {
                Column(Modifier.width(320.dp).padding(start = 16.dp, end = 20.dp)) {
                    Text(
                        "On ${previewChannel.name}",
                        style = MaterialTheme.typography.labelLarge, color = Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    SchedulePreview(schedule = scheduleFor(previewChannel))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Up / Down to browse  ·  Select to tune in  ·  Left for more channels  ·  Back to close",
            style = MaterialTheme.typography.bodySmall, color = Dim,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

/**
 * The expanded guide (Left, from either plain playback or the compact
 * guide): the picture shrinks to a preview (handled by the caller), a
 * category sidebar lets Left/Right narrow the channel list without touching
 * playback, and the highlighted channel's upcoming schedule sits alongside
 * it - same browse-then-Select model as the compact guide above.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandedGuideOverlay(
    categoryOptions: List<String?>,
    selectedCategoryIndex: Int,
    filteredChannels: List<Channel>,
    previewChannelId: String,
    nowPlaying: (Channel) -> EpgDatabase.Programme?,
    scheduleFor: (Channel) -> List<EpgDatabase.Programme>,
    modifier: Modifier = Modifier,
) {
    val previewIndex = filteredChannels.indexOfFirst { it.id == previewChannelId }.let { if (it >= 0) it else 0 }
    val windowIndices = guideWindow(filteredChannels.size, previewIndex, 4)
    val previewChannel = filteredChannels.getOrNull(previewIndex)

    Row(modifier) {
        Column(
            Modifier
                .width(220.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.80f))
                .padding(vertical = 16.dp),
        ) {
            Text(
                "Categories", style = MaterialTheme.typography.labelLarge, color = Dim,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
            )
            categoryOptions.forEachIndexed { i, cat ->
                val active = i == selectedCategoryIndex
                Text(
                    cat ?: "All channels",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (active) Ink else Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (active) Focus.copy(alpha = 0.22f) else Color.Transparent)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.80f)),
        ) {
            if (filteredChannels.isEmpty()) {
                Text(
                    "No channels in this category.",
                    style = MaterialTheme.typography.bodyMedium, color = Dim,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                windowIndices.forEach { i ->
                    val ch = filteredChannels[i]
                    GuideRow(channel = ch, programme = nowPlaying(ch), active = i == previewIndex)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Up / Down to browse  ·  Select to tune in  ·  Left / Right for category  ·  Back to close",
                style = MaterialTheme.typography.bodySmall, color = Dim,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.80f))
                .padding(20.dp),
        ) {
            if (previewChannel != null) {
                Text(
                    previewChannel.name, style = MaterialTheme.typography.titleMedium, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                Spacer(Modifier.height(12.dp))
                SchedulePreview(schedule = scheduleFor(previewChannel))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(channel: Channel, programme: EpgDatabase.Programme?, active: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (active) Focus.copy(alpha = 0.22f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel.logoUrl != null) {
            AsyncImage(
                model = channel.logoUrl, contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                channel.name, style = MaterialTheme.typography.bodyLarge,
                color = if (active) Ink else Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (active) Modifier.basicMarquee() else Modifier,
            )
            // Just the title here, no time - the highlighted row's full
            // upcoming schedule (with times) is its own panel next to this
            // list, see SchedulePreview. Repeating times on every row was
            // both noisy and, combined with the old wraparound duplicate-row
            // bug this replaces, what made the list read as garbled.
            if (programme != null) {
                Text(
                    programme.title, style = MaterialTheme.typography.bodySmall,
                    color = if (active) Focus else Dim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A short forward-looking schedule for one channel - current show first,
 *  same data BrowseScreen's ProgrammePanel uses, just read-only and without
 *  its reminder/record menu (there is no long-press here, only a highlight
 *  moving as the guide is browsed). */
@Composable
private fun SchedulePreview(schedule: List<EpgDatabase.Programme>, modifier: Modifier = Modifier) {
    if (schedule.isEmpty()) {
        Text(
            "No guide data for this channel.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
            modifier = modifier,
        )
        return
    }
    val now = System.currentTimeMillis()
    Column(modifier) {
        schedule.take(4).forEach { p ->
            val live = now in p.startUtc until p.endUtc
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(
                    "${formatClockTime(p.startUtc)}–${formatClockTime(p.endUtc)}" + if (live) "  ·  On now" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (live) Focus else Dim,
                )
                Text(
                    p.title, style = MaterialTheme.typography.bodyLarge, color = Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** h:mm am/pm in the device's local time - the same clock a live guide's
 *  audience actually reads their remote's own clock in, not UTC. */
private fun formatClockTime(utcMs: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = utcMs
    val hour24 = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val hour = (hour24 % 12).let { if (it == 0) 12 else it }
    val minute = cal.get(java.util.Calendar.MINUTE)
    val amPm = if (hour24 < 12) "AM" else "PM"
    return "%d:%02d %s".format(hour, minute, amPm)
}
