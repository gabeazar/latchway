package app.latchway.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// brand/README.md palette.
object Brand {
    val paper = Color(0xFFF7F8F6)
    val paperDark = Color(0xFF14202D)
    val ink = Color(0xFF1D2A3A)
    val inkLight = Color(0xFFE9EEF2)
    val inkSoft = Color(0xFF55606D)
    val inkSoftDark = Color(0xFFA7B1BC)
    val line = Color(0xFFD9DDE1)
    val lineDark = Color(0xFF2B3A4A)
    val brass = Color(0xFFB8862B)
    val brassDark = Color(0xFFD2A24A)
    val brassDeep = Color(0xFF96691C)
    val brassSoft = Color(0xFFF3E9D3)
    val brassSoftDark = Color(0xFF2F2A1C)
    val moss = Color(0xFF4F7A5B)
    val mossDark = Color(0xFF7FB08C)
    val rust = Color(0xFFA6472F)
    val rustDark = Color(0xFFD9785F)
    val wash = Color(0xFFEDEFF1)
    val washDark = Color(0xFF1B2A39)
    val card = Color(0xFFFFFFFF)
    val cardDark = Color(0xFF192636)
}

private val light = lightColorScheme(
    primary = Brand.brass,
    onPrimary = Color(0xFF15120A),
    primaryContainer = Brand.brassSoft,
    onPrimaryContainer = Brand.brassDeep,
    secondary = Brand.inkSoft,
    onSecondary = Color.White,
    tertiary = Brand.moss,
    error = Brand.rust,
    onError = Color.White,
    background = Brand.paper,
    onBackground = Brand.ink,
    surface = Brand.card,
    onSurface = Brand.ink,
    surfaceVariant = Brand.wash,
    onSurfaceVariant = Brand.inkSoft,
    outline = Brand.line,
    outlineVariant = Brand.line,
)

private val dark = darkColorScheme(
    primary = Brand.brassDark,
    onPrimary = Color(0xFF15120A),
    primaryContainer = Brand.brassSoftDark,
    onPrimaryContainer = Brand.brassDark,
    secondary = Brand.inkSoftDark,
    onSecondary = Brand.paperDark,
    tertiary = Brand.mossDark,
    error = Brand.rustDark,
    onError = Brand.paperDark,
    background = Brand.paperDark,
    onBackground = Brand.inkLight,
    surface = Brand.cardDark,
    onSurface = Brand.inkLight,
    surfaceVariant = Brand.washDark,
    onSurfaceVariant = Brand.inkSoftDark,
    outline = Brand.lineDark,
    outlineVariant = Brand.lineDark,
)

private val typography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LatchwayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dark else light,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
