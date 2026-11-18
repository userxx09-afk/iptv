package io.tapper.firetv.ui.theme

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tapper.firetv.R

/**
 * Which skin the app wears, picked in Settings > Look.
 *
 * TAPPER is the original near-black and sky-blue design and stays the
 * default. GARYSTV borrows the look of the GarysTV app (its src/ui/styles.css):
 * deep navy panels on a navy gradient, a pale focus glow, red accents and
 * plain sans-serif type. Only colors, type and a few accents change; every
 * screen keeps its Tapper layout and features.
 */
enum class Skin(val label: String, val description: String) {
    TAPPER("Tapper (default)", "The original dark design with a sky-blue highlight."),
    GARYSTV("Gary's TV", "Navy panels, red accents and the Gary's TV logo, like the GarysTV app."),
}

/**
 * The current skin. Compose state, so every screen recomposes the moment
 * Settings changes it, with no restart.
 */
object UiSkin {
    private const val PREFS = "tapper_look"
    private const val KEY = "skin"

    var current by mutableStateOf(Skin.TAPPER)
        private set

    /** Called once from TapperApp.onCreate, before the first frame. */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current = Skin.entries.firstOrNull { it.name == saved } ?: Skin.TAPPER
    }

    fun choose(context: Context, skin: Skin) {
        current = skin
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, skin.name).apply()
    }
}

val isGarysSkin: Boolean get() = UiSkin.current == Skin.GARYSTV

/** True for content drawn on GarysTV's white focus highlight, so its text turns dark. */
val LocalOnLight = androidx.compose.runtime.compositionLocalOf { false }

// Tapper palette (unchanged from before skins existed).
private val TapperInk = Color(0xFFF2EEE6)
private val TapperDim = Color(0xFF969EAC)
private val TapperBackdrop = Color(0xFF0B0E14)
// Sky blue. Reads clearly against the near-black backdrop and, unlike the
// previous warm cream, is not easily confused with channel-logo artwork.
private val TapperFocus = Color(0xFF5BB8F5)

// GarysTV palette, straight from its styles.css :root variables.
private val GarysText = Color(0xFFEEF3FB)      // --text
private val GarysMuted = Color(0xFF93A6C4)     // --muted
private val GarysBg = Color(0xFF04070D)        // --bg
private val GarysBg2 = Color(0xFF0A1424)       // --bg2
private val GarysGlow = Color(0xFF15284A)      // body gradient highlight
private val GarysPanel = Color(0xFF0E1A2E)     // --panel
private val GarysLine = Color(0xFF22385C)      // --line
private val GarysFocusBg = Color(0xFFE9F0FB)   // --focus-bg
private val GarysRed = Color(0xFFFF3355)       // --red2
val GarysYellow = Color(0xFFF5C518)            // --yellow (guide day label, favourites)
val GarysSub = Color(0xFFCFE0FF)               // .info .sub: what's on, under a title

/** GarysTV guide-grid colors (styles.css: --cell, --cell-alt, --panel2, --focus-text...). */
object GarysColors {
    val Panel = Color(0xFF0E1A2E)
    val Panel2 = Color(0xFF13233D)
    val Cell = Color(0xFF12213A)
    val CellAlt = Color(0xFF0F1C32)
    val Line = Color(0xFF22385C)
    val Gap = Color(0xFF04070D)
    val Text = Color(0xFFEEF3FB)
    val Muted = Color(0xFF93A6C4)
    val DimText = Color(0xFF5D7194)
    val Sub = Color(0xFFCFE0FF)
    val Desc = Color(0xFFC4D1E6)
    val FocusBg = Color(0xFFE9F0FB)
    val FocusText = Color(0xFF08111F)
    val FocusSub = Color(0xFF3B4A63)
    val Red = Color(0xFFFF3355)
    val Yellow = Color(0xFFF5C518)
}

// Getters rather than constants: reading UiSkin.current while composing is
// what makes a skin change recompose every screen that uses these.
val Ink: Color get() = if (isGarysSkin) GarysText else TapperInk
val Dim: Color get() = if (isGarysSkin) GarysMuted else TapperDim
val Backdrop: Color get() = if (isGarysSkin) GarysBg2 else TapperBackdrop
/** Focus ring and highlight tint. GarysTV highlights with near-white. */
val Focus: Color get() = if (isGarysSkin) GarysFocusBg else TapperFocus
/** Live / on-now / brand accent: red in GarysTV, the focus blue in Tapper. */
val Accent: Color get() = if (isGarysSkin) GarysRed else TapperFocus

/**
 * Idle fill for a row, card or field. Tapper draws these as a faint white
 * wash at [alpha]; GarysTV uses its solid navy panel (faint washes) or its
 * line color (the stronger ones, mostly borders).
 */
fun panelTint(alpha: Float): Color = when {
    !isGarysSkin -> Color.White.copy(alpha = alpha)
    alpha < 0.09f -> GarysPanel
    alpha < 0.2f -> GarysLine
    else -> Color.White.copy(alpha = alpha)
}

/** Full-screen background: flat near-black, or GarysTV's navy glow from the top left. */
fun Modifier.skinBackground(): Modifier =
    if (!isGarysSkin) {
        this.drawBehind { drawRect(TapperBackdrop) }
    } else {
        this.drawBehind {
            drawRect(GarysBg)
            drawRect(
                Brush.radialGradient(
                    0f to GarysGlow, 0.45f to GarysBg2, 1f to GarysBg,
                    center = Offset(size.width * 0.2f, -size.height * 0.1f),
                    radius = size.width * 0.9f,
                )
            )
        }
    }

/**
 * GarysTV marks the focused list row with a red bar down its left edge
 * (`.item.nav.focus` in its styles.css). No-op in the Tapper skin.
 */
fun Modifier.skinFocusBar(focused: Boolean): Modifier =
    if (!focused || !isGarysSkin) this
    else this.drawBehind {
        drawRect(GarysRed, topLeft = Offset.Zero, size = Size(5.dp.toPx(), size.height))
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
 * 14sp is readable at arm's length and invisible at ten feet. Both skins use
 * the same sizes; GarysTV swaps Archivo for the system sans-serif (it uses
 * Segoe UI / system-ui on the desktop) and its heavier title weight.
 */
private fun typographyFor(family: FontFamily, titleWeight: FontWeight) = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp),
    headlineLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    titleMedium = TextStyle(fontFamily = family, fontWeight = titleWeight, fontSize = 22.sp),
    bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 18.sp),
)

private val tapperTypography = typographyFor(Archivo, FontWeight.SemiBold)
private val garysTypography = typographyFor(FontFamily.SansSerif, FontWeight.Bold)

@Composable
fun TapperTheme(content: @Composable () -> Unit) {
    val skin = UiSkin.current
    val typography = remember(skin) { if (skin == Skin.GARYSTV) garysTypography else tapperTypography }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Focus, background = Backdrop, surface = Backdrop,
            onBackground = Ink, onSurface = Ink,
        ),
        typography = typography,
        content = content,
    )
}
