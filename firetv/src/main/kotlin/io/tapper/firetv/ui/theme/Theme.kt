package io.tapper.firetv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.tapper.firetv.R

val Ink = Color(0xFFF2EEE6)
val Dim = Color(0xFF969EAC)
val Backdrop = Color(0xFF0B0E14)
// Sky blue. Reads clearly against the near-black backdrop and, unlike the
// previous warm cream, is not easily confused with channel-logo artwork.
val Focus = Color(0xFF5BB8F5)

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
private val typography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.ExtraBold, fontSize = 56.sp),
    headlineLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    titleMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    bodyLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Medium, fontSize = 20.sp),
    bodyMedium = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.Normal, fontSize = 18.sp),
)

@Composable
fun TapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Focus, background = Backdrop, surface = Backdrop,
            onBackground = Ink, onSurface = Ink,
        ),
        typography = typography,
        content = content,
    )
}
