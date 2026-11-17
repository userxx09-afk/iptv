package io.tapper.firetv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tapper.firetv.data.PlayerSettingsStore
import io.tapper.firetv.data.SeriesRuleStore
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Ink

/** Settings > Recordings: padding and the "record every episode" list. */
@Composable
fun RecordingSettings(settings: PlayerSettingsStore, rules: SeriesRuleStore) {
    var before by remember { mutableIntStateOf(settings.recordPadBefore) }
    var after by remember { mutableIntStateOf(settings.recordPadAfter) }
    var list by remember { mutableStateOf(rules.all()) }

    Spacer(Modifier.height(28.dp))
    Text("RECORDINGS", style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(8.dp))
    Text("Start early", style = MaterialTheme.typography.bodyLarge, color = Ink)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerSettingsStore.PAD_BEFORE_CHOICES.forEach { m ->
            Chip(if (m == 0) "On time" else "$m min", before == m) { before = m; settings.recordPadBefore = m }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("Keep going after", style = MaterialTheme.typography.bodyLarge, color = Ink)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlayerSettingsStore.PAD_AFTER_CHOICES.forEach { m ->
            Chip(if (m == 0) "Stop on time" else "$m min", after == m) { after = m; settings.recordPadAfter = m }
        }
    }
    Spacer(Modifier.height(10.dp))
    if (list.isEmpty()) {
        Text(
            "Not recording any series. Long-press a guide entry and pick Record every episode.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
    } else {
        Text("Recording every episode of: tap one to stop.", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(6.dp))
        list.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { r ->
                    Chip("${r.title} · ${r.channelName}", false) { rules.remove(r.id); list = rules.all() }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
