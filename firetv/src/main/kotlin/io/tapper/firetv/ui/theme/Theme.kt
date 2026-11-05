package io.tapper.firetv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.tapper.firetv.R

/**
 * Which look the app uses, picked in Settings > Interface.
 *
 * TAPPER is the original near-black and sky-blue design. GARYSTV matches the
 * GarysTV DVR's screens: deep navy panels, bright red accent, plain sans-serif
 * type. Every screen colors itself through Ink / Dim / Backdrop / Focus below,
 * so switching the skin restyles the whole app at once, with no restart.
 */
enum class UiStyle(val label: String, val description: String) {
    TAPPER("Tapper (default)", "The original dark design with a blue highlight."),
    GARYSTV("GarysTV", "Navy panels with a red highlight, like the GarysTV DVR."),
}

object UiSkin {
    /** Compose state, so every screen recomposes when Settings changes it. */
    var style by mutableStateOf(UiStyle.TAPPER)
}

private val isGarys get() = UiSkin.style == UiStyle.GARYSTV

// GarysTV palette, from its styles.css (--text, --muted, --bg/--bg2, --red2).
private val GarysInk = Color(0xFFEEF3FB)
private val GarysDim = Color(0xFF93A6C4)
private val GarysBackdrop = Color(0xFF07101E)
private val GarysRed = Color(0xFFFF3355)

private val TapperInk = Color(0xFFF2EEE6)
private val TapperDim = Color(0xFF969EAC)
private val TapperBackdrop = Color(0xFF0B0E14)
// Sky blue. Reads clearly against the near-black backdrop and, unlike the
// previous warm cream, is not easily confused with channel-logo artwork.
private val TapperFocus = Color(0xFF5BB8F5)

// GarysTV's focus highlight is solid white, so text on a focused row turns dark.
private val GarysFocusBg = Color(0xFFE9F0FB)
private val GarysFocusText = Color(0xFF08111F)
private val GarysFocusSub = Color(0xFF3B4A63)
private val GarysPanel = Color(0xFF0E1A2E)
private val GarysLine = Color(0xFF22385C)

/** True for content drawn on GarysTV's white focus highlight (see [OnFill]). */
val LocalOnLight = compositionLocalOf { false }

// Getters rather than constants: reading UiSkin.style inside composition is
// what makes a skin change recompose every screen that uses these. Ink and Dim
// also follow LocalOnLight, so a focused GarysTV row's text turns dark.
val Ink: Color
    @Composable @ReadOnlyComposable
    get() = when {
        !isGarys -> TapperInk
        LocalOnLight.current -> GarysFocusText
        else -> GarysInk
    }
val Dim: Color
    @Composable @ReadOnlyComposable
    get() = when {
        !isGarys -> TapperDim
        LocalOnLight.current -> GarysFocusSub
        else -> GarysDim
    }
val Backdrop: Color get() = if (isGarys) GarysBackdrop else TapperBackdrop
val Focus: Color get() = if (isGarys) GarysRed else TapperFocus

val isGarysSkin: Boolean get() = isGarys

/**
 * Background for a focusable row or button. GarysTV: navy panel, solid white
 * when focused. Tapper: whatever tint the row has always used.
 */
fun rowFill(focused: Boolean, tapperFocused: Color, tapperIdle: Color): Color =
    if (isGarys) (if (focused) GarysFocusBg else GarysPanel)
    else if (focused) tapperFocused else tapperIdle

/** Outline for the same rows: GarysTV draws a thin line, white when focused. */
fun rowEdge(focused: Boolean, tapper: Color): Color =
    if (isGarys) (if (focused) GarysFocusBg else GarysLine) else tapper

/** Wrap a row's content in this so its Ink/Dim text turns dark on the GarysTV highlight. */
@Composable
fun OnFill(focused: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOnLight provides (isGarys && focused), content = content)
}

/**
 * Static instances rather than the variable Archivo TTF: minSdk is 25 to keep
 * the Fire TV Stick 4K supported, and FontVariation requires 26.
 */
val Archivo = FontFamily(
    Font(R.font.archivo_regular, FontWeight.Normal),
    Font(R.font.archivo_medium, FontWeight.Medium),
    Font(R.font.archivo_semibold, FontWeight.SemiBold),
    Font(R.font.archivo_bold, FontWeight.Bold),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
)

/**
 * Nothing below 18sp. The standard mistake on TV is porting a phone type scale:
 * 14sp is readable at arm's length and invisible at ten feet.
 */
private fun typographyFor(family: FontFamily) = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp),
    headlineLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 18.sp),
)

private val tapperTypography = typographyFor(Archivo)

/**
 * GarysTV's type scale, in its rem (1.08% of the screen width, as in its
 * styles.css), with the system UI font (Segoe UI on Windows; Roboto here).
 */
private fun garysTypography(rem: Float): androidx.compose.material3.Typography {
    val f = FontFamily.SansSerif
    return androidx.compose.material3.Typography(
        displayLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.ExtraBold, fontSize = (rem * 2.4f).sp),
        headlineLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Bold, fontSize = (rem * 1.8f).sp),
        titleMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.SemiBold, fontSize = (rem * 1.15f).sp),
        bodyLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = (rem * 1.1f).sp),
        bodyMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = (rem * 0.95f).sp),
    )
}

@Composable
fun TapperTheme(content: @Composable () -> Unit) {
    val width = LocalConfiguration.current.screenWidthDp
    val garys = isGarys
    val typography = remember(garys, width) {
        if (garys) garysTypography((width * 0.0108f).coerceIn(9.5f, 16f)) else tapperTypography
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Focus, background = Backdrop, surface = Backdrop,
            onBackground = Ink, onSurface = Ink,
        ),
        typography = typography,
        content = content,
    )
}
