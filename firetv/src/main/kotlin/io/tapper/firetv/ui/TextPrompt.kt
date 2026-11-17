package io.tapper.firetv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.tapper.firetv.ui.theme.Backdrop
import io.tapper.firetv.ui.theme.Dim
import io.tapper.firetv.ui.theme.Focus
import io.tapper.firetv.ui.theme.Ink
import kotlinx.coroutines.delay

/** What a [TextPrompt] asks for; built by whoever opens it. */
data class PromptRequest(
    val title: String,
    val hint: String = "",
    val initial: String = "",
    val numeric: Boolean = false,
    val secret: Boolean = false,
    /** Shown under the field, e.g. "Wrong PIN". */
    val message: String? = null,
    val onDone: (String) -> Unit,
)

/**
 * A small dialog with one text field, opened straight into editing so the
 * Fire TV keyboard comes up at once. Done on the keyboard (or the Save chip)
 * hands the text back; Back cancels.
 */
@Composable
fun TextPrompt(req: PromptRequest, onDismiss: () -> Unit) {
    var text by remember(req) { mutableStateOf(req.initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(req) {
        repeat(5) { attempt ->
            if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect
            if (attempt < 4) delay(16)
        }
    }
    fun done() { onDismiss(); req.onDone(text) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(min = 420.dp, max = 600.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Backdrop)
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
                .padding(24.dp),
        ) {
            Text(req.title, style = MaterialTheme.typography.titleMedium, color = Ink)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(2.dp, Focus, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (text.isEmpty() && req.hint.isNotEmpty()) {
                    Text(req.hint, style = MaterialTheme.typography.bodyLarge, color = Dim.copy(alpha = 0.6f))
                }
                BasicTextField(
                    value = text,
                    onValueChange = { v -> text = if (req.numeric) v.filter { it.isDigit() }.take(8) else v },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                    cursorBrush = SolidColor(Focus),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            req.numeric && req.secret -> KeyboardType.NumberPassword
                            req.numeric -> KeyboardType.Number
                            req.secret -> KeyboardType.Password
                            else -> KeyboardType.Text
                        },
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                    visualTransformation = if (req.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            req.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Dim)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Save", false) { done() }
                Chip("Cancel", false) { onDismiss() }
            }
        }
    }
}
