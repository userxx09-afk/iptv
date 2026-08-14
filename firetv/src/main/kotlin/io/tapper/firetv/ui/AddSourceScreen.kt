package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

/**
 * Add an Xtream account or an M3U playlist.
 *
 * Typing a 12-character random password on a D-pad is the worst experience in
 * every app of this kind. This screen is the fallback; the intended path is
 * pairing from a phone, which is a later increment.
 */
@Composable
fun AddSourceScreen(
    busy: Boolean,
    error: String?,
    onSubmitXtream: (name: String, host: String, user: String, pass: String) -> Unit,
    onSubmitM3u: (name: String, url: String, epg: String?) -> Unit,
    onCancel: () -> Unit,
) {
    var xtream by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().background(Backdrop)
            .padding(horizontal = 64.dp, vertical = 40.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Add an IPTV service", style = MaterialTheme.typography.headlineLarge, color = Ink)
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeChip("Xtream login", xtream) { xtream = true }
            ModeChip("M3U playlist URL", !xtream) { xtream = false }
        }

        Spacer(Modifier.height(24.dp))
        Field("Name", name, "Living room provider") { name = it }

        if (xtream) {
            Field("Server address", host, "http://provider.tv:8080") { host = it }
            Field("Username", user, "") { user = it }
            Field("Password", pass, "", password = true) { pass = it }
        } else {
            Field("Playlist URL", url, "https://…/playlist.m3u") { url = it }
            // The default playlist declares two guides and the first one 404s,
            // so an override is a normal requirement, not an edge case.
            Field("EPG URL (optional)", epg, "https://…/guide.xml.gz") { epg = it }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE08A7A))
        }

        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(if (busy) "Checking…" else "Save and connect", enabled = !busy) {
                if (xtream) onSubmitXtream(name.ifBlank { host }, host, user, pass)
                else onSubmitM3u(name.ifBlank { "Playlist" }, url, epg.ifBlank { null })
            }
            Button("Cancel", enabled = !busy, onClick = onCancel)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Credentials are stored in the device keystore, not in the app database.",
            style = MaterialTheme.typography.bodyMedium, color = Dim,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    hint: String,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) {
            Text(hint, style = MaterialTheme.typography.bodyLarge, color = Dim.copy(alpha = 0.6f))
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
            cursorBrush = SolidColor(Focus),
            visualTransformation = if (password) PasswordVisualTransformation() else
                androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Mirrors [BrowseScreen]'s `Chip`: D-pad focus must look distinct from mere
 * selection, otherwise a remote user has no way to tell which chip they're
 * about to press.
 */
@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(
                if (active || focused) Focus.copy(alpha = if (active) 0.28f else 0.16f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(1.dp, if (focused || active) Focus else Color.Transparent, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (active || focused) Ink else Dim)
    }
}

/**
 * "Save and connect" and "Cancel" used to render identically whenever both
 * were enabled, which on a D-pad meant no way to tell which one had focus
 * before pressing select. Same focus treatment as [ModeChip] and
 * [BrowseScreen]'s `Chip` fixes that.
 */
@Composable
private fun Button(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    focused -> Focus.copy(alpha = 0.38f)
                    else -> Focus.copy(alpha = 0.16f)
                }
            )
            .border(
                if (focused && enabled) 2.dp else 1.dp,
                if (enabled) Focus.copy(alpha = if (focused) 1f else 0.45f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) Ink else Dim)
    }
}
