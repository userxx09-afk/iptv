package io.tapper.firetv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

// Getters rather than constants: reading UiSkin.style inside composition is
// what makes a skin change recompose every screen that uses these.
val Ink: Color get() = if (isGarys) GarysInk else TapperInk
val Dim: Color get() = if (isGarys) GarysDim else TapperDim
val Backdrop: Color get() = if (isGarys) GarysBackdrop else TapperBackdrop
val Focus: Color get() = if (isGarys) GarysRed else TapperFocus

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
// GarysTV uses the system UI font (Segoe UI on Windows); Roboto is Android's.
private val garysTypography = typographyFor(FontFamily.SansSerif)

@Composable
fun TapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Focus, background = Backdrop, surface = Backdrop,
            onBackground = Ink, onSurface = Ink,
        ),
        typography = if (isGarys) garysTypography else tapperTypography,
        content = content,
    )
}
