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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.tapper.firetv.data.Recording
import io.tapper.firetv.data.RecordingStatus
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scheduled, in-progress, and completed recordings.
 *
 * Recording capture itself is a plain byte copy of the live stream (see
 * RecordingService) - this screen is just the durable record of what that
 * has produced: what's still to come, what's currently being written, and
 * what's on disk and playable now.
 */
@Composable
fun RecordingsScreen(
    recordings: List<Recording>,
    onPlay: (Recording) -> Unit,
    onStop: (Recording) -> Unit,
    onCancel: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    onExit: () -> Unit,
) {
    BackHandler { onExit() }
    var menuFor by remember { mutableStateOf<Recording?>(null) }
    val fmt = remember { SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()) }
    val sorted = remember(recordings) { recordings.sortedByDescending { it.startUtc } }

    Column(
        Modifier.fillMaxSize().background(Backdrop)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text("Recordings", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Recording works cleanly for plain stream sources (Xtream \"ts\" links, " +
                "most M3U providers). A source that serves fragmented or encrypted " +
                "HLS may still produce a file that doesn't play back.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(20.dp))

        if (sorted.isEmpty()) {
            Text(
                "Nothing scheduled yet. Long-press a channel or a guide entry " +
                    "and choose Record to add one.",
                style = MaterialTheme.typography.bodyLarge, color = Dim,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(sorted, key = { i, r -> "${r.id}#$i" }) { _, r ->
                    RecordingRow(
                        recording = r, fmt = fmt,
                        onClick = { menuFor = r },
                    )
                }
            }
        }
    }

    menuFor?.let { r ->
        ItemMenu(
            title = r.channelName,
            subtitle = fmt.format(Date(r.startUtc)) + "  ·  " + statusLabel(r.status),
            actions = buildList {
                when (r.status) {
                    RecordingStatus.DONE -> {
                        add(MenuAction("Play") { onPlay(r) })
                        add(MenuAction("Delete") { onDelete(r) })
                    }
                    RecordingStatus.RECORDING -> {
                        add(MenuAction("Stop recording") { onStop(r) })
                    }
                    RecordingStatus.SCHEDULED -> {
                        add(MenuAction("Cancel") { onCancel(r) })
                    }
                    RecordingStatus.FAILED -> {
                        add(MenuAction("Delete") { onDelete(r) })
                    }
                }
            },
            onDismiss = { menuFor = null },
        )
    }
}

private fun statusLabel(status: RecordingStatus): String = when (status) {
    RecordingStatus.SCHEDULED -> "Scheduled"
    RecordingStatus.RECORDING -> "Recording now"
    RecordingStatus.DONE -> "Ready to play"
    RecordingStatus.FAILED -> "Failed"
}

@Composable
private fun RecordingRow(
    recording: Recording,
    fmt: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
            .border(if (focused) 2.dp else 0.dp, if (focused) Focus else Color.Transparent,
                RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(recording.channelName, style = MaterialTheme.typography.titleMedium, color = Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            fmt.format(Date(recording.startUtc)) + "  ·  " + statusLabel(recording.status),
            style = MaterialTheme.typography.bodyMedium,
            color = when (recording.status) {
                RecordingStatus.RECORDING -> Focus
                RecordingStatus.FAILED -> Color(0xFFE08A7A)
                else -> Dim
            },
        )
    }
}
