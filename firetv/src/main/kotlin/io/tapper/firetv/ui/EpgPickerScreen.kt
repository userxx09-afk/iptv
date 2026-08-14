package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tapper.core.model.Channel
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

/**
 * Manually points one channel at a guide id.
 *
 * Automatic matching needs the playlist's tvg-id and the guide's own channel
 * id to agree, and plenty of real playlists ship a channel with no tvg-id at
 * all, or one that simply doesn't match anything the guide declares - the
 * channel plays fine and never shows a schedule. Every id currently loaded
 * for this source is listed here, searchable by the id itself or by whatever
 * programme is airing on it right now, since a raw guide id ("I279.6244.
 * schedulesdirect.org") rarely says which real channel it belongs to on its
 * own.
 */
@Composable
fun EpgPickerScreen(
    channel: Channel,
    candidates: List<String>,
    sampleTitle: (String) -> String?,
    currentOverride: String?,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onExit() }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Titles are looked up per candidate, so the query is matched against ids
    // first (cheap, always available) and only falls back to a title scan
    // when there's a manageable number of ids left to check.
    val shown = remember(query, candidates) {
        val q = query.trim()
        if (q.isEmpty()) return@remember candidates.take(300)
        val byId = candidates.filter { it.contains(q, ignoreCase = true) }
        if (byId.size >= 300) return@remember byId.take(300)
        val rest = candidates - byId.toSet()
        val byTitle = rest.filter { sampleTitle(it)?.contains(q, ignoreCase = true) == true }
        (byId + byTitle).take(300)
    }

    Column(
        Modifier.fillMaxSize().background(Backdrop).padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text("Guide for " + channel.name, style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                currentOverride != null -> "Manually set to: $currentOverride"
                !channel.epgChannelId.isNullOrBlank() -> "Automatic id: " + channel.epgChannelId
                else -> "The playlist declares no guide id for this channel."
            },
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Focus.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    "Search guide ids or program titles",
                    style = MaterialTheme.typography.bodyLarge, color = Dim.copy(alpha = 0.6f),
                )
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

        Spacer(Modifier.height(16.dp))

        if (currentOverride != null) {
            Chip("Clear override, use automatic matching", false, onClear)
            Spacer(Modifier.height(16.dp))
        }

        when {
            candidates.isEmpty() -> Text(
                "No guide is loaded for this source yet. Refresh the guide from " +
                    "Settings, then come back here.",
                style = MaterialTheme.typography.bodyLarge, color = Dim,
            )
            shown.isEmpty() -> Text(
                "Nothing matches \"$query\".",
                style = MaterialTheme.typography.bodyLarge, color = Dim,
            )
            else -> {
                Text(
                    "${shown.size} of ${candidates.size} guide channels" +
                        if (shown.size >= 300) " (narrow the search to see more)" else "",
                    style = MaterialTheme.typography.bodyMedium, color = Dim,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(shown, key = { i, id -> id + "#" + i }) { _, id ->
                        GuideRow(
                            id = id,
                            title = sampleTitle(id),
                            active = id == currentOverride,
                            onClick = { onPick(id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(id: String, title: String?, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Focus.copy(alpha = 0.18f)
                    active -> Focus.copy(alpha = 0.10f)
                    else -> Color.White.copy(alpha = 0.04f)
                }
            )
            .border(
                if (focused || active) 2.dp else 0.dp,
                if (focused || active) Focus else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title ?: "No program data right now",
                style = MaterialTheme.typography.bodyLarge,
                color = if (title != null) Ink else Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee() else Modifier,
            )
            Text(id, style = MaterialTheme.typography.bodyMedium, color = Dim,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (focused) Modifier.basicMarquee() else Modifier)
        }
        if (active) {
            Spacer(Modifier.width(12.dp))
            Text("IN USE", style = MaterialTheme.typography.bodyMedium, color = Focus)
        }
    }
}
