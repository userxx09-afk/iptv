package io.tapper.firetv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Ink

/**
 * Shown when the active source will not load.
 *
 * Deliberately not a dead end. The previous build printed the error and stopped
 * there, which stranded the app: source settings live in preferences, so a
 * misconfigured source could only be fixed by clearing app storage. Every route
 * out is offered here.
 */
@Composable
fun FailureScreen(
    sourceName: String,
    message: String,
    canFallBack: Boolean,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    onUseBuiltIn: () -> Unit,
) {
    BackHandler { onSettings() }

    Box(Modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 760.dp).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Couldn't load $sourceName",
                style = MaterialTheme.typography.headlineLarge, color = Ink)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Dim)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip("Try again", false, onRetry)
                Chip("Settings", false, onSettings)
                if (canFallBack) Chip("Use iptv-org instead", false, onUseBuiltIn)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Back opens Settings, where sources can be changed or removed.",
                style = MaterialTheme.typography.bodyMedium, color = Dim,
            )
        }
    }
}
