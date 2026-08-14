package io.tapper.firetv.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.tapper.core.model.Channel
import io.tapper.firetv.data.EpgDatabase
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What is on the focused channel. Occupies the third column of the browse
 * layout and grows as the columns to its left collapse.
 */
@Composable
fun ProgrammePanel(
    channel: Channel?,
    schedule: List<EpgDatabase.Programme>,
    expanded: Boolean,
    onFocused: () -> Unit,
    hasReminder: (EpgDatabase.Programme) -> Boolean,
    onSetReminder: (EpgDatabase.Programme) -> Unit,
    onCancelReminder: (EpgDatabase.Programme) -> Unit,
    onRecord: (EpgDatabase.Programme) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Focus arriving here is what widens the column, mirroring how focus
    // entering the channel list collapses the rail. Nothing expands on a key
    // press, so moving between columns never overshoots a level.
    LaunchedEffect(focused) { if (focused) onFocused() }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Focus.copy(alpha = 0.10f) else Color.Transparent)
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) Focus.copy(alpha = 0.7f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onFocused)
            .padding(10.dp)
    ) {
        if (channel == null) {
            Text("Select a channel", style = MaterialTheme.typography.bodyMedium, color = Dim)
            return@Column
        }
        val ch = channel  // non-null from here on; see the guard above

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ch.logoUrl != null) {
                AsyncImage(
                    model = ch.logoUrl, contentDescription = null,
                    modifier = Modifier.size(if (expanded) 56.dp else 36.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(
                ch.name,
                style = if (expanded) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.bodyLarge,
                color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(12.dp))

        if (schedule.isEmpty()) {
            Text(
                "No guide data for this channel.",
                style = MaterialTheme.typography.bodyMedium, color = Dim,
            )
            return@Column
        }

        val now = System.currentTimeMillis()
        // The first entry is what is on air; the rest is what follows.
        //
        // Keyed on start time plus position, not start time alone: the crash
        // this app actually hit in the field was
        // "IllegalArgumentException: Key '<epoch millis>' was already used",
        // because a source's guide repeated a <programme> entry for this
        // channel at the same start time. The database now rejects that kind
        // of duplicate at the source (see EpgDatabase), but the index suffix
        // here means a stray duplicate can never crash this list either way.
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(schedule, key = { i, p -> "${p.startUtc}#$i" }) { _, p ->
                val live = now in p.startUtc until p.endUtc
                ScheduleRow(
                    programme = p, live = live, expanded = expanded, fmt = fmt,
                    onClick = {
                        menu = {
                            ItemMenu(
                                title = p.title,
                                subtitle = ch.name + "  ·  " + fmt.format(Date(p.startUtc)),
                                actions = buildList {
                                    if (live) {
                                        // Already airing: nothing to remind
                                        // about, but recording from this
                                        // moment to the end still makes sense.
                                        add(MenuAction("Record now (until it ends)") {
                                            onRecord(p); menu = null
                                        })
                                    } else {
                                        if (hasReminder(p)) {
                                            add(MenuAction("Cancel reminder") {
                                                onCancelReminder(p); menu = null
                                            })
                                        } else {
                                            add(MenuAction("Remind me") {
                                                onSetReminder(p); menu = null
                                            })
                                        }
                                        add(MenuAction("Record this") { onRecord(p); menu = null })
                                    }
                                },
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
private fun ScheduleRow(
    programme: EpgDatabase.Programme,
    live: Boolean,
    expanded: Boolean,
    fmt: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Focus.copy(alpha = 0.22f)
                    live -> Focus.copy(alpha = 0.14f)
                    else -> Color.White.copy(alpha = 0.03f)
                }
            )
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) Focus else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            fmt.format(Date(programme.startUtc)) + (if (live) "   ON NOW" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = if (live) Focus else Dim,
        )
        Text(
            programme.title, style = MaterialTheme.typography.bodyLarge, color = Ink,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
        // Descriptions only earn their space once the panel is wide. No line
        // cap here any more - the enclosing LazyColumn in ProgrammePanel
        // already scrolls the whole schedule, so a long synopsis just makes
        // this row taller instead of being cut off mid-sentence.
        if (expanded) {
            programme.description?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim)
            }
        }
    }
}
