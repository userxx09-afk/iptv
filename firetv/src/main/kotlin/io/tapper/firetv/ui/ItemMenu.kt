package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink

data class MenuAction(val label: String, val onSelect: () -> Unit)

/**
 * Long-press menu. A centred dialog rather than a DropdownMenu: dropdowns
 * anchor to the pressed item and land off-screen near the edges, and their
 * focus handling on a D-pad is unreliable.
 */
@Composable
fun ItemMenu(
    title: String,
    subtitle: String? = null,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(min = 380.dp, max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Backdrop)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(vertical = 20.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            // Scrolls rather than growing unbounded - a country/category
            // picker can run to dozens of entries, and without a cap those
            // just ran off the bottom of the screen with no way to reach the
            // rest via D-pad. Short menus (a handful of actions) never hit
            // the cap and look exactly as before.
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                actions.forEach { action ->
                    MenuRow(action.label) { action.onSelect(); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (focused) Focus.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (focused) Ink else Dim)
    }
}
