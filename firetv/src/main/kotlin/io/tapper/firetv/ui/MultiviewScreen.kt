package io.tapper.firetv.ui

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import io.tapper.core.model.Channel
import io.tapper.firetv.player.TapperPlayer
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

/**
 * How many channels this device can show at once: 4 where the video decoder
 * allows four instances and there is the memory for them (Fire TV Cube, 4K
 * Max), otherwise 2 (Fire TV Stick 4K and older sticks).
 */
fun multiviewTiles(context: Context): Int {
    val decoders = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", ignoreCase = true) } }
            .maxOf { it.getCapabilitiesForType("video/avc").maxSupportedInstances }
    }.getOrDefault(2)
    val mem = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(it)
    }.totalMem
    return if (decoders >= 4 && mem >= 1_800_000_000L) 4 else 2
}

/**
 * Multiview: two or four live channels on one screen, for watching several
 * games at once. One tile has the sound (outlined); the D-pad moves it.
 * Select opens a menu to watch that tile full screen, swap its channel for
 * another from the same category, or remove it. Back leaves.
 */
@Composable
fun MultiviewScreen(
    initial: List<Channel>,
    /** Channels to swap a tile to - the live lineup. */
    allChannels: List<Channel>,
    onFullScreen: (Channel) -> Unit,
    onChanged: (List<Channel>) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val max = remember { multiviewTiles(context) }
    var channels by remember { mutableStateOf(initial.take(max)) }
    var selected by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var swapping by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // One player per tile, kept by position so swapping a channel reuses it.
    val players = remember { mutableStateMapOf<Int, TapperPlayer>() }
    DisposableEffect(Unit) {
        onDispose { players.values.forEach { it.release() }; players.clear() }
    }
    LaunchedEffect(channels) {
        // Tiles that went away.
        players.keys.filter { it >= channels.size }.forEach { players.remove(it)?.release() }
        onChanged(channels)
    }
    LaunchedEffect(selected, channels.size) {
        players.forEach { (i, p) -> p.setAudible(i == selected) }
    }

    BackHandler {
        when {
            swapping -> swapping = false
            menuOpen -> menuOpen = false
            else -> onExit()
        }
    }

    val columns = if (channels.size <= 1) 1 else 2
    val rows = (channels.size + columns - 1) / columns
    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp || menuOpen || swapping) return@onKeyEvent false
                val n = channels.size
                when (e.key) {
                    Key.DirectionRight -> { if (selected % columns < columns - 1 && selected + 1 < n) selected++; true }
                    Key.DirectionLeft -> { if (selected % columns > 0) selected--; true }
                    Key.DirectionDown -> { if (selected + columns < n) selected += columns; true }
                    Key.DirectionUp -> { if (selected - columns >= 0) selected -= columns; true }
                    Key.DirectionCenter, Key.Enter -> { menuOpen = true; true }
                    else -> false
                }
            }
    ) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until columns) {
                    val i = r * columns + c
                    val ch = channels.getOrNull(i)
                    Box(
                        Modifier.weight(1f).fillMaxHeight().padding(3.dp)
                            .border(if (i == selected) 3.dp else 0.dp, if (i == selected) Focus else Color.Transparent)
                    ) {
                        if (ch != null) {
                            key(i, ch.id) {
                                val player = remember(i) {
                                    players[i] ?: TapperPlayer(context, scope, onDiagnosis = {}, onPlaying = {})
                                        .also { players[i] = it }
                                }
                                LaunchedEffect(ch.id) {
                                    player.setAudible(i == selected)
                                    player.play(ch)
                                }
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            isFocusable = false
                                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                            )
                                            player.attach(this)
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Text(
                                (if (i == selected) "🔊 " else "") + ch.name,
                                style = MaterialTheme.typography.bodyMedium, color = Ink,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (menuOpen) {
        val ch = channels.getOrNull(selected)
        ItemMenu(
            title = ch?.name ?: "Multiview",
            subtitle = "Multiview · ${channels.size} of $max",
            actions = buildList {
                if (ch != null) {
                    add(MenuAction("Watch full screen") { menuOpen = false; onFullScreen(ch) })
                    add(MenuAction("Swap channel...") { menuOpen = false; swapping = true })
                    if (channels.size > 1) add(MenuAction("Remove from multiview") {
                        menuOpen = false
                        channels = channels.filterIndexed { j, _ -> j != selected }
                        players.remove(selected)?.release()
                        // Players are keyed by position: renumber the ones after it.
                        val rest = players.toSortedMap()
                        players.clear()
                        rest.forEach { (j, p) -> players[if (j > selected) j - 1 else j] = p }
                        selected = selected.coerceAtMost(channels.size - 1)
                    })
                }
                if (channels.size < max) add(MenuAction("Add a channel...") {
                    menuOpen = false
                    selected = channels.size
                    swapping = true
                })
                add(MenuAction("Leave multiview") { menuOpen = false; onExit() })
            },
            onDismiss = { menuOpen = false },
        )
    }

    if (swapping) {
        val current = channels.getOrNull(selected)
        val group = current?.group
        val choices = remember(group, allChannels) {
            val same = allChannels.filter { it.group == group && it.isPlayable }
            (same.ifEmpty { allChannels.filter { it.isPlayable } }).take(400)
        }
        ChannelPickerOverlay(
            title = if (current == null) "Add a channel" else "Swap ${current.name} for...",
            channels = choices,
            onPick = { picked ->
                swapping = false
                channels = if (selected < channels.size) {
                    channels.mapIndexed { j, c -> if (j == selected) picked else c }
                } else channels + picked
            },
        )
    }
}

@Composable
private fun ChannelPickerOverlay(title: String, channels: List<Channel>, onPick: (Channel) -> Unit) {
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).fillMaxHeight(0.85f)
                .background(Backdrop, RoundedCornerShape(12.dp)).padding(20.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(channels, key = { it.sourceId + "|" + it.id }) { ch ->
                    val idx = channels.indexOf(ch)
                    MenuRow(
                        label = ch.name, sub = ch.group,
                        modifier = if (idx == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { onPick(ch) },
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun MenuRow(label: String, sub: String?, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier.fillMaxWidth()
            .background(if (focused) Focus.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(8.dp))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink)
        sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim) }
    }
}
