package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Episode list for one series.
 *
 * Episodes are fetched when the series is opened rather than up front: a panel
 * with a few thousand series would otherwise need a few thousand calls before
 * the app could show anything.
 */
@Composable
fun EpisodesScreen(
    series: Channel,
    episodes: List<Channel>,
    loading: Boolean,
    error: String?,
    watchedIds: Set<String>,
    nextUpId: String?,
    onPlay: (List<Channel>, Int) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onExit() }

    // Season derived from the group each episode was tagged with on fetch.
    val seasons = remember(episodes) { episodes.mapNotNull { it.group }.distinct() }
    var season by remember(episodes) { mutableStateOf<String?>(null) }
    val shown = remember(episodes, season) {
        if (season == null) episodes else episodes.filter { it.group == season }
    }

    // Holding Left jumps back to the first episode. A held key sends KeyDown
    // again with repeatCount >= 1 once the repeat delay has passed; the first
    // of those scrolls to the top and puts focus on that row. A short press is
    // left alone (nothing lies to the left of this list).
    val listState = rememberLazyListState()
    val topFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val holdLeftToTop = Modifier.onKeyEvent { e ->
        if (e.key != Key.DirectionLeft) return@onKeyEvent false
        val repeats = e.nativeKeyEvent.repeatCount
        if (e.type == KeyEventType.KeyDown && repeats >= 1) {
            if (repeats == 1) {
                scope.launch {
                    runCatching { listState.scrollToItem(0) }
                    // The first row has to be composed before it can take
                    // focus, so retry for a few frames.
                    repeat(5) {
                        if (runCatching { topFocus.requestFocus() }.isSuccess) return@launch
                        delay(16)
                    }
                }
            }
            true
        } else {
            false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Backdrop).padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (series.logoUrl != null) {
                AsyncImage(
                    model = series.logoUrl, contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(series.name, style = MaterialTheme.typography.headlineLarge, color = Ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        loading -> "Loading episodes..."
                        error != null -> error
                        else -> "${episodes.size} episodes"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) Color(0xFFE08A7A) else Dim,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (seasons.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip("All", season == null) { season = null } }
                items(seasons, key = { it }) { s ->
                    Chip(s, season == s) { season = if (season == s) null else s }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        if (!loading && error == null && episodes.isEmpty()) {
            Text(
                "This provider returned no episodes for this series.",
                style = MaterialTheme.typography.bodyLarge, color = Dim,
            )
        }

        LazyColumn(
            state = listState,
            modifier = holdLeftToTop,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Index folded into the key: a handful of Xtream panels are known
            // to report the same episode id twice across seasons, and a plain
            // `e.id` key crashes this list exactly the way a duplicate guide
            // row crashes ProgrammePanel's.
            itemsIndexed(shown, key = { i, e -> e.id + "#" + i }) { i, ep ->
                EpisodeRow(
                    episode = ep,
                    watched = ep.id in watchedIds,
                    nextUp = ep.id == nextUpId,
                    modifier = if (i == 0) Modifier.focusRequester(topFocus) else Modifier,
                ) { onPlay(shown, i) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Channel,
    watched: Boolean,
    nextUp: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (episode.logoUrl != null) {
            AsyncImage(model = episode.logoUrl, contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(16.dp))
        }
        Text(
            episode.name,
            style = MaterialTheme.typography.bodyLarge,
            // Watched episodes dim rather than disappear: hiding them makes a
            // rewatch impossible and the list confusing.
            color = if (watched) Dim else Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (nextUp) {
            Text("NEXT UP", style = MaterialTheme.typography.bodyMedium, color = Focus)
            Spacer(Modifier.width(12.dp))
        }
        if (watched) Text("watched", style = MaterialTheme.typography.bodyMedium, color = Dim)
    }
}
