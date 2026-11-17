package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.firetv.R
import io.tapper.firetv.data.EpgDatabase.Programme
import io.tapper.firetv.ui.theme.GarysColors as G
import io.tapper.firetv.ui.theme.skinBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private const val SLOT_MS = 30 * 60 * 1000L
/** How much time the grid shows across at once: four half-hour columns. */
private const val SPAN_MS = 4 * SLOT_MS
private val ROW_H = 58.dp
private val CHANNEL_W = 250.dp
private val GAP = 2.dp

private fun alignToSlot(t: Long) = t / SLOT_MS * SLOT_MS

/**
 * The Gary's TV skin's guide: a satellite-box style grid, channels down the
 * left, half-hour columns across, the highlighted show described in a pane
 * on top. Modelled on GarysTV's guide (src/ui/guide.js and styles.css):
 * navy cells, the focused cell solid white with a red underline, a red line
 * at the current time and the day in yellow.
 *
 * One focusable box takes every key and moves a (row, time) cursor, rather
 * than one focusable per cell: hundreds of cells each holding focus state
 * would be slow on a Fire TV Stick, and moving by time like a satellite box
 * (Up/Down keeps the same time) is easier this way.
 */
@Composable
fun GridGuide(
    channels: List<Channel>,
    title: String,
    scheduleFor: (Channel) -> List<Programme>,
    isFavorite: (Channel) -> Boolean,
    onPlay: (List<Channel>, Int) -> Unit,
    /** Long-press / Menu on a cell: show actions for this show on this channel. */
    onOptions: (Channel, Programme?) -> Unit,
    onExit: () -> Unit,
    initialChannelId: String? = null,
    modifier: Modifier = Modifier,
) {
    BackHandler { onExit() }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    val baseStart = alignToSlot(now)
    var windowStart by remember { mutableLongStateOf(baseStart) }
    // The window never sits in the past once time moves on.
    if (windowStart < baseStart) windowStart = baseStart

    var row by remember {
        mutableIntStateOf(channels.indexOfFirst { it.id == initialChannelId }.coerceAtLeast(0))
    }
    if (channels.isNotEmpty() && row > channels.lastIndex) row = channels.lastIndex
    // The time the cursor sits at. Up/Down keep it, so moving between
    // channels stays in the same column like a satellite guide.
    var cursor by remember { mutableLongStateOf(now) }
    if (cursor < now && cursor < windowStart) cursor = maxOf(now, windowStart)

    // Schedules are database reads: loaded off the main thread, one per row
    // as it comes on screen, and kept for the life of the grid.
    val schedules = remember { mutableStateMapOf<String, List<Programme>>() }

    fun programmesOf(i: Int): List<Programme> =
        channels.getOrNull(i)?.let { schedules[it.id] }.orEmpty()

    fun focusedProgramme(): Programme? =
        programmesOf(row).firstOrNull { cursor >= it.startUtc && cursor < it.endUtc }

    val listState = rememberLazyListState()
    LaunchedEffect(row) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (row < first || row > last - 1) {
            listState.animateScrollToItem((row - 1).coerceAtLeast(0))
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    fun moveRight() {
        val p = focusedProgramme()
        val next = if (p != null) p.endUtc else alignToSlot(cursor) + SLOT_MS
        cursor = next
        while (cursor >= windowStart + SPAN_MS) windowStart += SLOT_MS
    }

    fun moveLeft() {
        val p = focusedProgramme()
        val start = p?.startUtc ?: alignToSlot(cursor)
        val target = start - 1
        if (target < now) {
            // Already on what's on now: nothing earlier to go to.
            if (cursor > now) cursor = now else return
        } else {
            val prog = programmesOf(row).firstOrNull { target >= it.startUtc && target < it.endUtc }
            cursor = maxOf(prog?.startUtc ?: alignToSlot(target), now)
        }
        while (cursor < windowStart && windowStart > baseStart) windowStart -= SLOT_MS
    }

    fun moveRows(by: Int) {
        if (channels.isEmpty()) return
        row = (row + by).coerceIn(0, channels.lastIndex)
    }

    val playable = remember(channels) { channels.filter { it.isPlayable } }

    Column(
        modifier.fillMaxSize().skinBackground()
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                val up = e.type == KeyEventType.KeyUp
                when (e.key) {
                    Key.DirectionUp -> { if (down) moveRows(-1); true }
                    Key.DirectionDown -> { if (down) moveRows(1); true }
                    Key.DirectionRight -> { if (down) moveRight(); true }
                    Key.DirectionLeft -> { if (down) moveLeft(); true }
                    Key.ChannelUp, Key.PageUp, Key.MediaRewind -> { if (down) moveRows(-5); true }
                    Key.ChannelDown, Key.PageDown, Key.MediaFastForward -> { if (down) moveRows(5); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (up) {
                            val ch = channels.getOrNull(row)
                            val p = focusedProgramme()
                            when {
                                ch == null -> {}
                                // Something later on: OK offers remind/record,
                                // like a satellite box. On now: watch it.
                                p != null && p.startUtc > now -> onOptions(ch, p)
                                ch.isPlayable -> onPlay(playable, playable.indexOf(ch).coerceAtLeast(0))
                            }
                        }
                        true
                    }
                    Key.Menu -> {
                        if (up) channels.getOrNull(row)?.let { onOptions(it, focusedProgramme()) }
                        true
                    }
                    else -> false
                }
            }
            // After the key handler, so the handler sits above the focus
            // target and sees its keys.
            .focusRequester(focus)
            .focusable()
            .padding(horizontal = 40.dp, vertical = 20.dp),
    ) {
        InfoPane(
            channel = channels.getOrNull(row),
            programme = focusedProgramme(),
            now = now,
            title = title,
        )
        Spacer(Modifier.height(10.dp))
        if (channels.isEmpty()) {
            Text("No live channels to show.", style = MaterialTheme.typography.bodyLarge, color = G.Muted)
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val gridW = maxWidth - CHANNEL_W
            fun xOf(t: Long): Dp = gridW * ((t - windowStart).toFloat() / SPAN_MS)

            Column(Modifier.fillMaxSize()) {
                TimeHeader(windowStart, gridW)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(channels, key = { _, c -> "${c.sourceId}|${c.id}" }) { i, ch ->
                            LaunchedEffect(ch.id) {
                                if (schedules[ch.id] == null) {
                                    schedules[ch.id] = withContext(Dispatchers.IO) {
                                        runCatching { scheduleFor(ch) }.getOrDefault(emptyList())
                                    }
                                }
                            }
                            GuideRow(
                                channel = ch,
                                selected = i == row,
                                favorite = remember(ch.id) { isFavorite(ch) },
                                programmes = schedules[ch.id],
                                windowStart = windowStart,
                                gridW = gridW,
                                focusedAt = if (i == row) cursor else null,
                                now = now,
                            )
                        }
                    }
                    // The current time, drawn over every row.
                    if (now in windowStart until windowStart + SPAN_MS) {
                        Box(
                            Modifier.offset(x = CHANNEL_W + xOf(now) - 1.dp).width(2.dp).fillMaxHeight()
                                .background(G.Red)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Hints(
            listOf(
                "OK" to "Watch / options", "MENU" to "Options", "◀ ▶" to "Time",
                "▲ ▼" to "Channel", "BACK" to "Close guide",
            )
        )
    }
}

private val timeFmt: DateFormat get() = DateFormat.getTimeInstance(DateFormat.SHORT)

@Composable
private fun InfoPane(channel: Channel?, programme: Programme?, now: Long, title: String) {
    var clock by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); clock = clockText() } }
    Row(Modifier.fillMaxWidth().height(150.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                programme?.title ?: channel?.name ?: "",
                style = MaterialTheme.typography.headlineLarge, color = G.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val live = programme != null && now in programme.startUtc until programme.endUtc
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (live) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(G.Red))
                    Spacer(Modifier.width(8.dp))
                }
                val parts = listOfNotNull(
                    channel?.let { listOfNotNull(it.number?.toString(), it.name).joinToString("  ") },
                    programme?.let {
                        dayPrefix(it.startUtc) + timeFmt.format(Date(it.startUtc)) + " – " + timeFmt.format(Date(it.endUtc))
                    },
                    if (live) "ON NOW" else null,
                )
                Text(parts.joinToString("   ·   "), style = MaterialTheme.typography.bodyLarge,
                    color = G.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (live && programme != null) {
                val f = programme.progressAt(now)
                Box(Modifier.width(320.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.11f))) {
                    Box(Modifier.fillMaxHeight().width(320.dp * f).background(G.Red))
                }
            }
            Text(
                programme?.description ?: if (programme == null) "No guide information for this time." else "",
                style = MaterialTheme.typography.bodyMedium, color = G.Desc,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(24.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.garys_logo), contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(CircleShape).border(2.dp, G.Red, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text("GARY'S", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = G.Text)
                Text(" TV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = G.Red)
            }
            Spacer(Modifier.height(8.dp))
            Text(clock, style = MaterialTheme.typography.titleMedium, color = G.Text)
            Spacer(Modifier.height(4.dp))
            Text(title.uppercase(), style = MaterialTheme.typography.bodyMedium, color = G.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TimeHeader(windowStart: Long, gridW: Dp) {
    Row(Modifier.fillMaxWidth().height(36.dp).drawBehind {
        drawRect(G.Line, topLeft = Offset.Zero, size = Size(size.width, 1.dp.toPx()))
    }) {
        Box(Modifier.width(CHANNEL_W).fillMaxHeight().padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(dayWord(windowStart), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold, color = G.Yellow)
        }
        Box(Modifier.width(gridW).fillMaxHeight()) {
            for (k in 0 until (SPAN_MS / SLOT_MS).toInt()) {
                val t = windowStart + k * SLOT_MS
                Box(
                    Modifier.offset(x = gridW * (k.toFloat() / (SPAN_MS / SLOT_MS))).width(gridW / 4).fillMaxHeight()
                        .drawBehind { drawRect(G.Line, size = Size(1.dp.toPx(), size.height)) }
                        .padding(start = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(timeFmt.format(Date(t)), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, color = G.Muted, maxLines = 1)
                }
            }
        }
    }
}

private fun dayWord(t: Long): String = dayPrefix(t).trim().ifEmpty { "Today" }.uppercase()

@Composable
private fun GuideRow(
    channel: Channel,
    selected: Boolean,
    favorite: Boolean,
    programmes: List<Programme>?,
    windowStart: Long,
    gridW: Dp,
    focusedAt: Long?,
    now: Long,
) {
    val windowEnd = windowStart + SPAN_MS
    Row(Modifier.fillMaxWidth().height(ROW_H + GAP).padding(bottom = GAP)) {
        // Channel cell: number, logo, name. The selected row gets GarysTV's
        // lighter panel and red edge (.g-row.sel .g-ch).
        Row(
            Modifier.width(CHANNEL_W - GAP).fillMaxHeight()
                .background(if (selected) G.Panel2 else G.Panel)
                .drawBehind { if (selected) drawRect(G.Red, size = Size(4.dp.toPx(), size.height)) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(channel.number?.toString() ?: "", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = G.Text, modifier = Modifier.width(52.dp), maxLines = 1)
            if (channel.logoUrl != null) {
                AsyncImage(model = channel.logoUrl, contentDescription = null,
                    modifier = Modifier.size(width = 48.dp, height = 32.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(channel.name, style = MaterialTheme.typography.bodyMedium, color = G.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (favorite) Text(" ★", style = MaterialTheme.typography.bodyMedium, color = G.Yellow)
        }
        Spacer(Modifier.width(GAP))
        Box(Modifier.width(gridW).fillMaxHeight()) {
            val inWindow = programmes.orEmpty().filter { it.endUtc > windowStart && it.startUtc < windowEnd }
            if (inWindow.isEmpty()) {
                Cell(
                    title = if (programmes == null) "" else channel.name,
                    sub = if (programmes == null) "" else "No guide information",
                    x = 0.dp, w = gridW, focused = focusedAt != null, alt = false, filler = true,
                    continuesLeft = false, continuesRight = false,
                )
            } else {
                // Gaps in the guide become filler cells so the row stays
                // solid and the cursor always has something to land on.
                var t = windowStart
                var n = 0
                fun x(ms: Long) = gridW * ((ms - windowStart).toFloat() / SPAN_MS)
                for (p in inWindow) {
                    if (p.startUtc > t) {
                        Cell("", "", x(t), x(p.startUtc) - x(t), focusedAt != null && focusedAt in t until p.startUtc,
                            alt = false, filler = true, continuesLeft = false, continuesRight = false)
                    }
                    val s = maxOf(p.startUtc, windowStart)
                    val e = minOf(p.endUtc, windowEnd)
                    Cell(
                        title = p.title,
                        sub = if (now in p.startUtc until p.endUtc) "" else timeFmt.format(Date(p.startUtc)),
                        x = x(s), w = x(e) - x(s),
                        focused = focusedAt != null && focusedAt >= p.startUtc && focusedAt < p.endUtc,
                        alt = n % 2 == 1, filler = false,
                        continuesLeft = p.startUtc < windowStart, continuesRight = p.endUtc > windowEnd,
                    )
                    n++
                    t = maxOf(t, p.endUtc)
                }
                if (t < windowEnd) {
                    Cell("", "", x(t), gridW - x(t), focusedAt != null && focusedAt >= t,
                        alt = false, filler = true, continuesLeft = false, continuesRight = false)
                }
            }
        }
    }
}

@Composable
private fun Cell(
    title: String,
    sub: String,
    x: Dp,
    w: Dp,
    focused: Boolean,
    alt: Boolean,
    filler: Boolean,
    continuesLeft: Boolean,
    continuesRight: Boolean,
) {
    if (w <= 0.dp) return
    Box(
        Modifier.offset(x = x).width(w).fillMaxHeight()
            .padding(start = GAP)
            .background(if (focused) G.FocusBg else if (alt) G.CellAlt else G.Cell)
            .drawBehind {
                if (focused) {
                    val h = 4.dp.toPx()
                    drawRect(G.Red, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            val t = (if (continuesLeft) "‹ " else "") + title + (if (continuesRight) " ›" else "")
            Text(
                t, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (filler) FontWeight.Normal else FontWeight.SemiBold,
                color = when {
                    focused -> G.FocusText
                    filler -> G.DimText
                    else -> G.Text
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotEmpty() && w > 140.dp) {
                Text(sub, style = MaterialTheme.typography.bodyMedium,
                    color = if (focused) G.FocusSub else G.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Hints(hints: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { (k, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(5.dp)).background(G.Panel2)
                        .border(1.dp, G.Line, RoundedCornerShape(5.dp))
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                ) { Text(k, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = G.Text) }
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = G.Muted)
            }
        }
    }
}
