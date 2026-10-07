package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.core.model.ContentKind
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Per content kind, so a flood of movie matches cannot push the live channels
// off the screen - and bounds how much is ever composed, however common the
// search term is.
private const val PER_KIND_CAP = 40

/** Lower-cased names built once per catalogue, off the main thread. */
private class SearchIndex(val lower: Array<String>, val kinds: IntArray, val channels: List<Channel>)

private class ChannelHits(val byKind: List<List<Channel>>, val totals: IntArray) {
    companion object {
        val EMPTY = ChannelHits(
            List(ContentKind.entries.size) { emptyList() },
            IntArray(ContentKind.entries.size),
        )
    }
}

private class ProgrammeHit(val programme: EpgDatabase.Programme, val channel: Channel?)

private fun buildIndex(channels: List<Channel>): SearchIndex {
    val n = channels.size
    return SearchIndex(
        Array(n) { channels[it].name.lowercase() },
        IntArray(n) { channels[it].kind.ordinal },
        channels,
    )
}

/**
 * One pass over the pre-lowercased names. Every word typed must appear
 * (so "nfl network" finds "NFL Network HD"); names that start with the first
 * word rank first, then names with it at the start of a later word, then any
 * other match. Cancels promptly when the next keystroke supersedes it.
 */
private suspend fun runChannelSearch(idx: SearchIndex, query: String): ChannelHits {
    val tokens = query.split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return ChannelHits.EMPTY
    val first = tokens[0]
    val spaced = " $first"
    val nKinds = ContentKind.entries.size
    val buckets = Array(nKinds) { Array(3) { ArrayList<Channel>() } }
    val totals = IntArray(nKinds)
    val ctx = currentCoroutineContext()
    val names = idx.lower
    for (i in names.indices) {
        if ((i and 0xFFF) == 0) ctx.ensureActive()
        val name = names[i]
        var ok = true
        for (t in tokens) if (!name.contains(t)) { ok = false; break }
        if (!ok) continue
        val k = idx.kinds[i]
        totals[k]++
        val score = when {
            name.startsWith(first) -> 0
            name.contains(spaced) -> 1
            else -> 2
        }
        val bucket = buckets[k][score]
        if (bucket.size < PER_KIND_CAP) bucket.add(idx.channels[i])
    }
    val byKind = List(nKinds) { k -> (buckets[k][0] + buckets[k][1] + buckets[k][2]).take(PER_KIND_CAP) }
    return ChannelHits(byKind, totals)
}

/**
 * Search across channels and live programmes.
 *
 * Everything heavy runs off the main thread and only after typing pauses:
 * the name scan (a single pass over a pre-lowercased index, rather than
 * a case-insensitive contains() over the whole catalogue on every keystroke),
 * the guide database query, and resolving each guide hit to its channel. The
 * main thread only ever updates the text field and draws at most a few dozen
 * rows.
 *
 * Programme results only exist for sources with guide data. Movies and series
 * are not searchable yet - they need Xtream's VOD and series endpoints, which
 * this build does not implement.
 */
@Composable
fun SearchScreen(
    channels: List<Channel>,
    searchProgrammes: (String) -> List<EpgDatabase.Programme>,
    channelForEpgId: (String) -> Channel?,
    onPlay: (Channel) -> Unit,
    isFavorite: (Channel) -> Boolean,
    onToggleFavorite: (Channel) -> Unit,
    /** Same "Set program guide..." action BrowseScreen offers from a live
     *  channel's own menu - LIVE only, wired through here so a channel found
     *  by search doesn't need a trip back to the browse list just to fix a
     *  missing or wrong guide id. */
    onSetGuideChannel: (Channel) -> Unit,
    onExit: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var programmes by remember { mutableStateOf<List<EpgDatabase.Programme>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Without this the search screen is a dead end: Back would finish the
    // Activity and drop the user out of the app entirely.
    BackHandler { onExit() }

    // Built once per catalogue, off the main thread. Null for the moment it
    // takes (typing is already accepted; the search runs when it arrives).
    val index by produceState<SearchIndex?>(initialValue = null, channels) {
        value = withContext(Dispatchers.Default) { buildIndex(channels) }
    }
    var hits by remember { mutableStateOf(ChannelHits.EMPTY) }
    var programmes by remember { mutableStateOf<List<ProgrammeHit>>(emptyList()) }
    // The (normalised) query the results on screen belong to - "Searching..."
    // shows while this lags behind what has been typed, so a slow search
    // never reads as "Nothing found".
    var settled by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()

    // Debounced: restarts (cancelling the previous run) on every keystroke, so
    // a burst of typing costs one search, not one per letter.
    LaunchedEffect(normalized, index) {
        if (normalized.length < 2) {
            hits = ChannelHits.EMPTY; programmes = emptyList(); settled = normalized
            return@LaunchedEffect
        }
        delay(300)
        val idx = index ?: return@LaunchedEffect
        hits = withContext(Dispatchers.Default) { runChannelSearch(idx, normalized) }
        programmes = withContext(Dispatchers.IO) {
            runCatching {
                searchProgrammes(normalized).map { ProgrammeHit(it, channelForEpgId(it.channelId)) }
            }.getOrDefault(emptyList())
        }
        settled = normalized
    }
    val searching = normalized.length >= 2 && settled != normalized
    val anyChannelHits = hits.totals.any { it > 0 }

    val timeFmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }

    Column(
        Modifier.fillMaxSize().background(Backdrop).padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text("Search", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Focus.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (query.isEmpty()) {
                Text("Channel or program name", style = MaterialTheme.typography.bodyLarge,
                    color = Dim.copy(alpha = 0.6f))
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                cursorBrush = SolidColor(Focus),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }

        Spacer(Modifier.height(20.dp))

        if (normalized.length < 2) {
            Text("Type at least two characters.", style = MaterialTheme.typography.bodyMedium, color = Dim)
        } else if (searching) {
            Text("Searching...", style = MaterialTheme.typography.bodyMedium, color = Dim)
        } else if (!anyChannelHits && programmes.isEmpty()) {
            Text("Nothing found for \"$query\".", style = MaterialTheme.typography.bodyLarge, color = Dim)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Grouped by kind so a search for "matrix" separates the live
            // channel from the film of the same name.
            for (k in ContentKind.entries) {
                val kindHits = hits.byKind[k.ordinal]
                if (kindHits.isEmpty()) continue
                val total = hits.totals[k.ordinal]
                item(key = "hdr:" + k.name) {
                    SectionHeader(
                        kindLabel(k) +
                            if (total > kindHits.size) " (${kindHits.size} of $total - type more to narrow)"
                            else " ($total)"
                    )
                }
                // Keyed by kind and position as well as id: ids are only
                // unique within a kind (a live channel and a movie can share
                // one), and a duplicate LazyColumn key throws.
                itemsIndexed(kindHits, key = { i, ch -> "ch:" + k.name + ":" + i + ":" + ch.id }) { _, ch ->
                    val fav = remember(revision, ch.id) { isFavorite(ch) }
                    ResultRow(
                        title = ch.name,
                        subtitle = ch.group,
                        logoUrl = ch.logoUrl,
                        favorite = fav,
                        onClick = { onPlay(ch) },
                        onLongPress = {
                            menu = {
                                ItemMenu(
                                    title = ch.name,
                                    subtitle = ch.group,
                                    actions = listOf(
                                        MenuAction("Play") { onPlay(ch) },
                                        MenuAction(
                                            if (fav) "Remove from My List" else "Add to My List"
                                        ) { onToggleFavorite(ch); revision++ },
                                    ) + if (ch.kind == ContentKind.LIVE) listOf(
                                        MenuAction("Set program guide...") { onSetGuideChannel(ch) },
                                    ) else emptyList(),
                                    onDismiss = { menu = null },
                                )
                            }
                        },
                    )
                }
            }
            if (programmes.isNotEmpty()) {
                item(key = "hdr:programmes") { SectionHeader("On now and next (${programmes.size})") }
            }
            // Same duplicate-guide-row hazard as ProgrammePanel: two providers'
            // entries can share (channelId, startUtc), so the index is folded
            // into the key rather than trusted to already be unique.
            itemsIndexed(programmes, key = { i, h -> "pg:" + h.programme.channelId + h.programme.startUtc + "#" + i }) { _, h ->
                val p = h.programme
                // Already resolved off the main thread (see the search effect).
                val ch = h.channel
                val fav = if (ch != null) remember(revision, ch.id) { isFavorite(ch) } else false
                ResultRow(
                    title = p.title,
                    subtitle = listOfNotNull(ch?.name, timeFmt.format(Date(p.startUtc)))
                        .joinToString("  ·  "),
                    logoUrl = ch?.logoUrl,
                    favorite = fav,
                    // A programme with no matching channel cannot be tuned to;
                    // it stays listed but does nothing rather than crashing.
                    onClick = { ch?.let(onPlay) },
                    onLongPress = {
                        val c = ch ?: return@ResultRow
                        menu = {
                            ItemMenu(
                                title = p.title,
                                subtitle = c.name,
                                actions = listOf(
                                    MenuAction("Play") { onPlay(c) },
                                    MenuAction(
                                        if (fav) "Remove from My List" else "Add to My List"
                                    ) { onToggleFavorite(c); revision++ },
                                ) + if (c.kind == ContentKind.LIVE) listOf(
                                    MenuAction("Set program guide...") { onSetGuideChannel(c) },
                                ) else emptyList(),
                                onDismiss = { menu = null },
                            )
                        }
                    },
                )
            }
        }
    }

    menu?.invoke()
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Dim)
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    title: String,
    subtitle: String?,
    logoUrl: String?,
    favorite: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var downAt by remember { mutableLongStateOf(0L) }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp))
            // Same explicit handling as the browse rows: a held D-pad centre on
            // a remote does not reach combinedClickable's onLongClick.
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
            }
            .onKeyEvent { e ->
                val isSelect = e.key == Key.DirectionCenter || e.key == Key.Enter ||
                    e.key == Key.NumPadEnter
                when {
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logoUrl != null) {
            AsyncImage(model = logoUrl, contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(16.dp))
        } else {
            Spacer(Modifier.width(60.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee() else Modifier)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim, maxLines = 1)
            }
        }
        if (favorite) Text("*", style = MaterialTheme.typography.titleMedium, color = Focus)
    }
}
