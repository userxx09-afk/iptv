package io.tapper.firetv.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput

/**
 * OK selects, holding OK (or the remote's menu button) opens options. Used
 * in place of clickable on rows that now have a second action, such as
 * Download beside Watch. Same timing approach as the Browse lists: a held
 * D-pad centre on a Fire TV remote doesn't reliably reach onLongClick, so the
 * key is timed here.
 */
internal fun Modifier.okOrHold(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onHold: () -> Unit,
): Modifier = composed {
    var downAt by remember { mutableLongStateOf(0L) }
    val click by rememberUpdatedState(onClick)
    val hold by rememberUpdatedState(onHold)
    this
        .focusable(interactionSource = interaction)
        .pointerInput(Unit) {
            detectTapGestures(onTap = { click() }, onLongPress = { hold() })
        }
        .onKeyEvent { e ->
            val isSelect = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            when {
                e.key == Key.Menu && e.type == KeyEventType.KeyUp -> { hold(); true }
                !isSelect -> false
                e.type == KeyEventType.KeyDown -> {
                    if (downAt == 0L) downAt = System.currentTimeMillis()
                    true
                }
                e.type == KeyEventType.KeyUp -> {
                    val held = System.currentTimeMillis() - downAt
                    downAt = 0L
                    if (held >= 450) hold() else click()
                    true
                }
                else -> false
            }
        }
}
