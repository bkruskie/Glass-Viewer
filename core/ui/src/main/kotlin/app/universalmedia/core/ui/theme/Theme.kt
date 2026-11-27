package app.universalmedia.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand palette: deep "glass" blues with a teal accent for detected media. */
object UmColors {
    val Primary = Color(0xFF8AB4FF)
    val PrimaryContainer = Color(0xFF1C3566)
    val Accent = Color(0xFF5EE6C4)
    val AccentContainer = Color(0xFF0F3E35)
    val Warning = Color(0xFFFFC46B)
    val Danger = Color(0xFFFF8A80)
    val Background = Color(0xFF0D1016)
    val Surface = Color(0xFF0D1016)
    val SurfaceLow = Color(0xFF141821)
    val Surface1 = Color(0xFF181D27)
    val Surface2 = Color(0xFF1E2430)
    val Surface3 = Color(0xFF252C3A)
}

private fun scheme(pureBlack: Boolean) = darkColorScheme(
    primary = UmColors.Primary,
    onPrimary = Color(0xFF07214F),
    primaryContainer = UmColors.PrimaryContainer,
    onPrimaryContainer = Color(0xFFD9E3FF),
    secondary = UmColors.Accent,
    onSecondary = Color(0xFF00382D),
    secondaryContainer = UmColors.AccentContainer,
    onSecondaryContainer = Color(0xFFB8F5E4),
    tertiary = UmColors.Warning,
    onTertiary = Color(0xFF462A00),
    error = UmColors.Danger,
    background = if (pureBlack) Color.Black else UmColors.Background,
    onBackground = Color(0xFFE3E6EE),
    surface = if (pureBlack) Color.Black else UmColors.Surface,
    onSurface = Color(0xFFE3E6EE),
    surfaceVariant = UmColors.Surface2,
    onSurfaceVariant = Color(0xFFAAB2C2),
    surfaceContainerLowest = if (pureBlack) Color.Black else Color(0xFF090B10),
    surfaceContainerLow = if (pureBlack) Color(0xFF0B0C10) else UmColors.SurfaceLow,
    surfaceContainer = if (pureBlack) Color(0xFF111318) else UmColors.Surface1,
    surfaceContainerHigh = if (pureBlack) Color(0xFF171A21) else UmColors.Surface2,
    surfaceContainerHighest = if (pureBlack) Color(0xFF1E222B) else UmColors.Surface3,
    outline = Color(0xFF4A5366),
    outlineVariant = Color(0xFF2B3240),
)

private val typography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun UniversalMediaTheme(pureBlack: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme(pureBlack),
        typography = typography,
        shapes = shapes,
        content = content,
    )
}
