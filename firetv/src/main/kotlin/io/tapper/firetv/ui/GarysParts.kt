package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.RecordingStatus
import io.tapper.firetv.ui.theme.LocalOnLight

/*
 * GarysTV pieces used outside the main browse screen: the frame (top bar +
 * key hints) every other screen sits in, GarysTV's menu dialog, and its
 * My Recordings layout. See GarysScreens.kt for the browse screen itself.
 */

/** The navy page background with GarysTV's soft glow from the top left. */
internal fun Modifier.garysBackground(): Modifier = drawBehind {
    drawRect(GBg)
    drawRect(
        Brush.radialGradient(
            colors = listOf(GGlow, GBg2, GBg),
            center = Offset(size.width * 0.2f, -size.height * 0.1f),
            radius = size.width * 0.9f,
        )
    )
}

/**
 * Puts a screen inside GarysTV's frame: the top bar (Back, Home, Guide,
 * Search, Menu, clock) above it and the key hints below. With [enabled]
 * false it shows the content alone, so callers can wrap unconditionally.
 */
@Composable
fun GarysFrame(
    enabled: Boolean,
    crumb: String,
    hints: List<Pair<String, String>>,
    recording: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onGuide: () -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) { content(); return }
    val rem = rememberRem()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15_000); now = System.currentTimeMillis() } }
    CompositionLocalProvider(LocalRem provides rem) {
        Column(Modifier.fillMaxSize().garysBackground()) {
            GTopBar(
                showBack = true, crumb = crumb, recording = recording, now = now,
                onBack = onBack, onHome = onHome, onGuide = onGuide, onSearch = onSearch, onMenu = onMenu,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            GHints(hints)
        }
    }
}

/** GarysTV's `.modal` menu: a navy card of full-width buttons, white when focused. */
@Composable
fun GarysMenuDialog(
    title: String,
    subtitle: String?,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val rem = rememberRem()
    val first = remember { FocusRequester() }
    CompositionLocalProvider(LocalRem provides rem) {
        Dialog(onDismissRequest = onDismiss) {
            val shape = RoundedCornerShape(rem.dp(1f))
            Column(
                Modifier.widthIn(min = rem.dp(24f), max = rem.dp(46f))
                    .clip(shape)
                    .background(Brush.linearGradient(listOf(Color(0xFF142744), Color(0xFF0B1628))))
                    .border(1.dp, Color(0xFF2C4774), shape)
                    .padding(horizontal = rem.dp(1.8f), vertical = rem.dp(1.6f)),
                verticalArrangement = Arrangement.spacedBy(rem.dp(0.8f)),
            ) {
                Text(title, style = gStyle(1.7f, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = gStyle(1f, color = GMuted)) }
                Column(
                    Modifier.heightIn(max = rem.dp(30f)).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(rem.dp(0.45f)),
                ) {
                    actions.forEachIndexed { i, a ->
                        GBtn(a.label, Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it }) {
                            a.onSelect(); onDismiss()
                        }
                    }
                }
            }
            FocusOnEnter(first, Unit)
        }
    }
}

/** GarysTV's `.btn`: a navy rounded button, white with dark text when focused. */
@Composable
internal fun GBtn(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val rem = LocalRem.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(rem.dp(0.5f))
    val bg = when { focused -> GFocusBg; primary -> Color(0xFF1D4F9C); else -> GPanel2 }
    val edge = when { focused -> GFocusBg; primary -> Color(0xFF2F6FD1); else -> GLine }
    Box(
        modifier.clip(shape).background(bg).border(2.dp, edge, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = rem.dp(1.1f), vertical = rem.dp(0.8f)),
    ) {
        Text(label, style = gStyle(1.1f, FontWeight.SemiBold, if (focused) GFocusText else GText),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * My Recordings, GarysTV-style: every recording down the left, the
 * highlighted one's details and buttons in a panel on the right.
 */
@Composable
fun GarysRecordingsScreen(
    recordings: List<Recording>,
    onPlay: (Recording) -> Unit,
    onStop: (Recording) -> Unit,
    onCancel: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onExit() }
    val rem = rememberRem()
    val ctx = LocalContext.current
    val sorted = remember(recordings) { recordings.sortedByDescending { it.startUtc } }
    var sel by remember(sorted) { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    FocusOnEnter(first, sorted.size)
    fun whenText(t: Long): String =
        java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(t) + " " +
            android.text.format.DateFormat.getTimeFormat(ctx).format(t)
    fun status(r: Recording) = when (r.status) {
        RecordingStatus.SCHEDULED -> "Scheduled"
        RecordingStatus.RECORDING -> "Recording now"
        RecordingStatus.DONE -> "Ready to play"
        RecordingStatus.FAILED -> "Failed"
    }

    CompositionLocalProvider(LocalRem provides rem) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = rem.dp(1.6f)).padding(bottom = rem.dp(0.4f)),
            horizontalArrangement = Arrangement.spacedBy(rem.dp(1.6f)),
        ) {
            Column(Modifier.weight(1.25f).fillMaxHeight()) {
                Row(Modifier.padding(top = rem.dp(0.2f), bottom = rem.dp(0.8f)), verticalAlignment = Alignment.CenterVertically) {
                    Text("ALL RECORDINGS", style = gStyle(1.15f, FontWeight.SemiBold, GMuted, spacing = 0.1f))
                    Spacer(Modifier.weight(1f))
                    Text("${sorted.size} recording${if (sorted.size == 1) "" else "s"}", style = gStyle(0.95f, color = GMuted))
                }
                if (sorted.isEmpty()) {
                    Text(
                        "No recordings yet.\nIn the Guide, hold OK on a show and choose Record.",
                        style = gStyle(1f, color = GMuted), modifier = Modifier.padding(top = rem.dp(1f)),
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(rem.dp(0.35f)), modifier = Modifier.weight(1f)) {
                    itemsIndexed(sorted, key = { i, r -> "${r.id}#$i" }) { i, r ->
                        GRecordingRow(
                            title = r.channelName,
                            sub = whenText(r.startUtc) + "  ·  " + status(r),
                            live = r.status == RecordingStatus.RECORDING,
                            failed = r.status == RecordingStatus.FAILED,
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                            onFocus = { sel = i },
                        ) {
                            when (r.status) {
                                RecordingStatus.DONE -> onPlay(r)
                                RecordingStatus.RECORDING -> onStop(r)
                                RecordingStatus.SCHEDULED -> onCancel(r)
                                RecordingStatus.FAILED -> onDelete(r)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("DETAILS", style = gStyle(1.15f, FontWeight.SemiBold, GMuted, spacing = 0.1f),
                    modifier = Modifier.padding(top = rem.dp(0.2f), bottom = rem.dp(0.8f)))
                val r = sorted.getOrNull(sel)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(rem.dp(0.8f))).background(GPanel)
                        .border(1.dp, GLine, RoundedCornerShape(rem.dp(0.8f)))
                        .padding(horizontal = rem.dp(1.6f), vertical = rem.dp(1.4f)),
                    verticalArrangement = Arrangement.spacedBy(rem.dp(0.7f)),
                ) {
                    if (r == null) {
                        Text("Highlight a recording to see details.", style = gStyle(1f, color = GMuted))
                    } else {
                        Text(r.channelName, style = gStyle(1.8f, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(whenText(r.startUtc) + " – " + android.text.format.DateFormat.getTimeFormat(ctx).format(r.endUtc),
                            style = gStyle(1.15f, color = GSub))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem.dp(0.5f))) {
                            if (r.status == RecordingStatus.RECORDING) GDot()
                            Text(status(r), style = gStyle(1f, FontWeight.SemiBold,
                                when (r.status) { RecordingStatus.FAILED -> Color(0xFFE08A7A); RecordingStatus.RECORDING -> GRed2; else -> GMuted }))
                        }
                        Text(
                            "Recording works best with plain stream sources (Xtream \"ts\" links and most M3U providers).",
                            style = gStyle(0.95f, color = GDesc),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(rem.dp(0.7f)), modifier = Modifier.padding(top = rem.dp(0.4f))) {
                            when (r.status) {
                                RecordingStatus.DONE -> {
                                    GBtn("▶ Play", primary = true) { onPlay(r) }
                                    GBtn("Delete") { onDelete(r) }
                                }
                                RecordingStatus.RECORDING -> GBtn("■ Stop recording", primary = true) { onStop(r) }
                                RecordingStatus.SCHEDULED -> GBtn("Cancel", primary = true) { onCancel(r) }
                                RecordingStatus.FAILED -> GBtn("Delete") { onDelete(r) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GRecordingRow(
    title: String, sub: String, live: Boolean, failed: Boolean,
    modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit,
) {
    val rem = LocalRem.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    val shape = RoundedCornerShape(rem.dp(0.5f))
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (focused) GFocusBg else GPanel)
            .drawBehind { if (focused) drawRect(GRed2, size = Size(5.dp.toPx(), size.height)) }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = rem.dp(1f), vertical = rem.dp(0.75f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rem.dp(0.9f)),
    ) {
        if (live) GDot()
        Column(Modifier.weight(1f)) {
            Text(title, style = gStyle(1.15f, FontWeight.SemiBold, if (focused) GFocusText else GText),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = gStyle(0.92f, color = when {
                focused -> GFocusSub; failed -> Color(0xFFE08A7A); else -> GMuted
            }), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Used by rows that predate the GarysTV kit, to know whether they sit on the white highlight. */
@Composable
internal fun onLight(): Boolean = LocalOnLight.current

/**
 * GarysTV's on-screen display while watching: a full-width band along the
 * bottom that fades up from the picture, with what's on, a red progress bar
 * and its times.
 */
@Composable
fun GarysPlayerOsd(
    channel: io.tapper.core.model.Channel,
    position: String,
    programme: io.tapper.firetv.data.EpgDatabase.Programme?,
    modifier: Modifier = Modifier,
) {
    val rem = rememberRem()
    val ctx = LocalContext.current
    val tf = remember(ctx) { android.text.format.DateFormat.getTimeFormat(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(programme) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) } }
    CompositionLocalProvider(LocalRem provides rem) {
        Column(
            modifier.background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0xDD000000), 1f to Color(0xEE000000)))
                .padding(start = rem.dp(3f), end = rem.dp(3f), top = rem.dp(2f), bottom = rem.dp(2.2f)),
            verticalArrangement = Arrangement.spacedBy(rem.dp(0.5f)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(rem.dp(0.8f))) {
                if (channel.logoUrl != null) {
                    coil.compose.AsyncImage(
                        model = channel.logoUrl, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.size(width = rem.dp(3.2f), height = rem.dp(2.2f)),
                    )
                }
                Text(
                    listOfNotNull(channel.number?.toString(), channel.name).joinToString("  "),
                    style = gStyle(1.1f, FontWeight.SemiBold, GSub), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (position.isNotEmpty()) Text(position, style = gStyle(0.95f, color = GMuted))
            }
            Text(programme?.title ?: channel.name, style = gStyle(1.8f, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (programme != null) {
                Box(Modifier.fillMaxWidth().height(rem.dp(0.45f)).clip(RoundedCornerShape(rem.dp(0.3f))).background(Color(0x30FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(programme.progressAt(now)).background(GRed2))
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(tf.format(programme.startUtc), style = gStyle(1f, color = GMuted))
                    Spacer(Modifier.weight(1f))
                    Text(tf.format(programme.endUtc), style = gStyle(1f, color = GMuted))
                }
                programme.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = gStyle(1.05f, color = GDesc), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The panel colour behind the player's overlays: GarysTV navy, or the original near-black. */
internal fun overlayPanel(): Color =
    if (io.tapper.firetv.ui.theme.isGarysSkin) Color(0xF00E1A2E) else Color.Black.copy(alpha = 0.80f)
