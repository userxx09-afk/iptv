package io.tapper.firetv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import io.tapper.firetv.ui.theme.skinBackground
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.tapper.firetv.data.PlayerSettingsStore
import io.tapper.firetv.data.RecordingLocationStore
import io.tapper.firetv.data.SeriesRuleStore
import io.tapper.firetv.data.SmbStorage
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Recording: where recordings are saved (this Fire TV or a
 * network share), padding, and the "record every episode" list.
 */
@Composable
fun RecordingSettings(
    settings: PlayerSettingsStore,
    rules: SeriesRuleStore,
    location: RecordingLocationStore,
) {
    var before by remember { mutableIntStateOf(settings.recordPadBefore) }
    var after by remember { mutableIntStateOf(settings.recordPadAfter) }
    var list by remember { mutableStateOf(rules.all()) }
    var kind by remember { mutableStateOf(location.kind) }
    var share by remember { mutableStateOf(location.share()) }
    var prompt by remember { mutableStateOf<PromptRequest?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    fun save(s: RecordingLocationStore.Share) {
        share = s
        location.saveShare(s)
        testResult = null
    }

    fun test() {
        if (testing) return
        testing = true
        testResult = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { SmbStorage.test(share) }
            testResult = r.fold({ true to it }, { false to (it.message ?: "Couldn't use the share.") })
            testing = false
        }
    }

    Spacer(Modifier.height(28.dp))
    Text("RECORDING", style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(8.dp))
    Text("Save recordings to", style = MaterialTheme.typography.bodyLarge, color = Ink)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("This Fire TV", kind == RecordingLocationStore.Kind.LOCAL) {
            kind = RecordingLocationStore.Kind.LOCAL; location.kind = kind
        }
        Chip("Network storage", kind == RecordingLocationStore.Kind.NETWORK) {
            kind = RecordingLocationStore.Kind.NETWORK; location.kind = kind
        }
    }
    Spacer(Modifier.height(6.dp))

    if (kind == RecordingLocationStore.Kind.LOCAL) {
        val freeGb = remember { location.localFreeBytes() / 1_000_000_000.0 }
        Text(
            "Saved on this Fire TV (%.1f GB free, roughly 2 to 3 GB an hour of HD). ".format(freeGb) +
                "Pick Network storage to save to a NAS or a shared folder on a PC instead.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
    } else {
        Text(
            "A NAS or a Windows shared folder on your home network (SMB). Recordings are " +
                "written straight to it. If it can't be reached when a recording starts, " +
                "that one is saved on this Fire TV instead and noted in the error log.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Server: " + share.host.ifBlank { "not set" }, false) {
                prompt = PromptRequest(
                    "Server name or IP address",
                    hint = "192.168.1.20, or paste \\\\nas\\TV\\Recordings",
                    initial = share.host,
                ) { text ->
                    val parsed = RecordingLocationStore.parseLocation(text)
                    save(
                        if (parsed != null) share.copy(host = parsed.first, share = parsed.second, folder = parsed.third)
                        else share.copy(host = text.trim().trim('\\', '/'))
                    )
                }
            }
            Chip("Share: " + share.share.ifBlank { "not set" }, false) {
                prompt = PromptRequest("Shared folder name", hint = "e.g. TV or Media", initial = share.share) {
                    save(share.copy(share = it.trim().trim('\\', '/')))
                }
            }
            Chip("Folder: " + share.folder.ifBlank { "top of share" }, false) {
                prompt = PromptRequest(
                    "Folder inside the share", hint = "e.g. Recordings (blank for the top)", initial = share.folder,
                ) { save(share.copy(folder = it.trim().trim('\\', '/'))) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("User: " + share.username.ifBlank { "guest" }, false) {
                prompt = PromptRequest(
                    "User name on the server", hint = "blank to sign in as guest", initial = share.username,
                ) { save(share.copy(username = it.trim())) }
            }
            Chip("Password: " + if (share.password.isEmpty()) "none" else "saved", false) {
                prompt = PromptRequest("Password", secret = true) { save(share.copy(password = it)) }
            }
            Chip("Domain: " + share.domain.ifBlank { "none" }, false) {
                prompt = PromptRequest(
                    "Windows domain or workgroup", hint = "almost always blank", initial = share.domain,
                ) { save(share.copy(domain = it.trim())) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(if (testing) "Testing…" else "Test connection", false) {
                if (share.isComplete) {
                    test()
                } else {
                    testResult = false to "Set the server and the share first."
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (share.isComplete) "Saving to " + share.label else "Not set up yet.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
        testResult?.let { (ok, msg) ->
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = if (ok) Focus else Color(0xFFE08A7A))
        }
    }

    Spacer(Modifier.height(12.dp))
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

    prompt?.let { req -> TextPrompt(req, onDismiss = { if (prompt === req) prompt = null }) }
}

/**
 * The record menu: the Recording settings above in a dialog, opened from the
 * Recordings screen (both skins) so the save location is one press away.
 */
@Composable
fun RecordingSettingsDialog(content: @Composable () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxWidth(0.82f).fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(16.dp))
                .skinBackground()
                .border(1.dp, Focus.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(horizontal = 40.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Recording settings", style = MaterialTheme.typography.headlineMedium, color = Ink)
            Spacer(Modifier.height(4.dp))
            Text("Back closes this menu.", style = MaterialTheme.typography.bodyMedium, color = Dim)
            content()
            Spacer(Modifier.height(16.dp))
            Chip("Done", false, onDismiss)
        }
    }
}
