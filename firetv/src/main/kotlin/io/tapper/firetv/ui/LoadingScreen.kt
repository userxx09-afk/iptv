package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

/**
 * A large account can sit here for a while even with live TV loading first -
 * the message alone previously looked identical whether the app was working
 * or dead. The spinner is real, continuous motion rather than a canned
 * "please wait" so a long load reads as "still going" instead of "frozen",
 * which is what got mistaken for a hang and let the Fire TV screensaver take
 * over mid-load.
 */
@Composable
fun LoadingScreen(message: String) {
    Box(Modifier.fillMaxSize().background(Backdrop), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TAPPER IPTV", style = MaterialTheme.typography.displayLarge, color = Ink)
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(
                color = Focus,
                trackColor = Dim.copy(alpha = 0.25f),
                strokeWidth = 4.dp,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Dim)
        }
    }
}
