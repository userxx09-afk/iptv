package io.tapper.firetv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Scales all text by 25% when [large] is on (Settings > Captions and language). */
@Composable
fun TextSize(large: Boolean, content: @Composable () -> Unit) {
    if (!large) { content(); return }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * 1.25f)) { content() }
}
