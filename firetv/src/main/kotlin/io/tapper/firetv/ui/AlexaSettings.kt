package io.tapper.firetv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.tapper.firetv.data.AlexaLink
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.launch

/** Settings > Alexa: the relay address, and linking this TV to an Alexa account. */
@Composable
fun AlexaSettings(alexa: AlexaLink, tvName: String, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(alexa.serverUrl) }
    var key by remember { mutableStateOf(alexa.registerKey) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<PromptRequest?>(null) }

    LaunchedEffect(url) {
        if (alexa.configured && alexa.registered) {
            status = runCatching { if (alexa.isLinked()) "Linked. Say \"Alexa, ask Tapper to play ESPN\"." else "Not linked yet." }
                .getOrElse { "Can't reach the relay: ${it.message}" }
        }
    }

    Spacer(Modifier.height(28.dp))
    Text("ALEXA", style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(8.dp))
    Text(
        when {
            !alexa.configured -> "Off. Enter your Tapper Alexa relay's address to use voice control."
            else -> status ?: "Relay: $url"
        },
        style = MaterialTheme.typography.bodyLarge, color = Ink,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(if (alexa.configured) "Change relay address" else "Set relay address", false) {
            prompt = PromptRequest("Relay address", hint = "https://alexa.example.com", initial = url) { v ->
                alexa.serverUrl = v; url = alexa.serverUrl; status = null; onChanged()
            }
        }
        Chip(if (key.isBlank()) "Relay key (optional)" else "Change relay key", false) {
            prompt = PromptRequest("Relay key", hint = "The REGISTER_KEY set on the relay", initial = key, secret = true) { v ->
                alexa.registerKey = v; key = v
            }
        }
        if (alexa.configured) {
            Chip(if (busy) "Working..." else "Link to Alexa", false) {
                if (busy) return@Chip
                busy = true
                scope.launch {
                    status = runCatching { alexa.pairCode(tvName) }.fold(
                        { code -> "Say: \"Alexa, ask Tapper to link code ${code.chunked(3).joinToString(" ")}\" (10 minutes)" },
                        { "Couldn't reach the relay: ${it.message}" },
                    )
                    busy = false
                    onChanged()
                }
            }
            if (alexa.registered) {
                Chip("Unlink", false) { scope.launch { alexa.unlink(); status = "Unlinked." } }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "Works while Tapper is open: play a channel by name or number, next and previous " +
            "channel, pause, resume and stop. The relay runs on your own server (see the alexa " +
            "folder in the repository).",
        style = MaterialTheme.typography.bodyMedium, color = Dim,
    )
    prompt?.let { req -> TextPrompt(req, onDismiss = { if (prompt === req) prompt = null }) }
}
