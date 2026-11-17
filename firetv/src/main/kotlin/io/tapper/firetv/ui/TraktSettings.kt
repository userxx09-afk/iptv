package io.tapper.firetv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tapper.firetv.data.OpenSubtitlesClient
import io.tapper.firetv.data.TraktClient
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings > Trakt and subtitles. Each part appears only when this build
 * carries that service's key, so nothing half-working shows without them.
 */
@Composable
fun TraktSettings(trakt: TraktClient, subtitles: OpenSubtitlesClient) {
    if (!trakt.configured && !subtitles.configured) return
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(trakt.signedIn) }
    var code by remember { mutableStateOf<TraktClient.DeviceCode?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    if (trakt.configured) {
        Spacer(Modifier.height(28.dp))
        Text("TRAKT", style = MaterialTheme.typography.bodyMedium, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                signedIn -> "Signed in" + (trakt.userName?.let { " as $it" } ?: "") + ". Finished films and episodes go to your Trakt history."
                code != null -> "On your phone, go to ${code!!.url} and enter  ${code!!.userCode}"
                else -> status ?: "Not signed in."
            },
            style = MaterialTheme.typography.bodyLarge, color = Ink,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (signedIn) {
                Chip("Sign out", false) { trakt.signOut(); signedIn = false }
            } else if (code != null) {
                Chip("Cancel", false) { job?.cancel(); code = null }
            } else {
                Chip("Sign in to Trakt", false) {
                    job = scope.launch {
                        val c = runCatching { trakt.startSignIn() }
                            .getOrElse { status = "Couldn't reach Trakt: ${it.message}"; return@launch }
                        code = c
                        val ok = runCatching { trakt.finishSignIn(c) }.getOrDefault(false)
                        code = null
                        signedIn = ok
                        status = if (ok) null else "The code expired or was declined. Try again."
                    }
                }
            }
        }
    }

    if (subtitles.configured) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Subtitles: when a film or episode has none of its own, turning captions on (Menu) " +
                "fetches them from OpenSubtitles in your preferred language.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
    }
}
