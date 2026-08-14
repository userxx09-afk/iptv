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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Search across channels and live programmes.
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

    val matchedChannels = remember(query, channels) {
        if (query.length < 2) emptyList()
        else channels.filter { it.name.contains(query, ignoreCase = true) }.take(60)
    }

    // Debounced: the programme table is queried with LIKE, and re-running it on
    // every keystroke makes typing feel sticky on a stick.
    LaunchedEffect(query) {
        if (query.length < 2) { programmes = emptyList(); return@LaunchedEffect }
        delay(250)
        programmes = runCatching { searchProgrammes(query) }.getOrDefault(emptyList())
    }

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

        if (query.length < 2) {
            Text("Type at least two characters.", style = MaterialTheme.typography.bodyMedium, color = Dim)
        } else if (matchedChannels.isEmpty() && programmes.isEmpty()) {
            Text("Nothing found for \"$query\".", style = MaterialTheme.typography.bodyLarge, color = Dim)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Grouped by kind so a search for "matrix" separates the live
            // channel from the film of the same name.
            for (k in ContentKind.entries) {
                val hits = matchedChannels.filter { it.kind == k }
                if (hits.isEmpty()) continue
                item(key = "hdr:" + k.name) { SectionHeader(kindLabel(k) + " (" + hits.size + ")") }
                items(hits, key = { "ch:" + it.id }) { ch ->
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
                item { SectionHeader("On now and next (${programmes.size})") }
            }
            // Same duplicate-guide-row hazard as ProgrammePanel: two providers'
            // entries can share (channelId, startUtc), so the index is folded
            // into the key rather than trusted to already be unique.
            itemsIndexed(programmes, key = { i, p -> "pg:" + p.channelId + p.startUtc + "#" + i }) { _, p ->
                val ch = channelForEpgId(p.channelId)
                val fav = ch != null && remember(revision, ch.id) { isFavorite(ch) }
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
