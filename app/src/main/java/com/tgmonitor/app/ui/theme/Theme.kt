package com.tgmonitor.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0), onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FF), onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF4A6078), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8F7), onSecondaryContainer = Color(0xFF0A1C32),
    tertiary = Color(0xFF00796B), onTertiary = Color.White,
    background = Color(0xFFF4F7FB), onBackground = Color(0xFF161B22),
    surface = Color.White, onSurface = Color(0xFF161B22),
    surfaceVariant = Color(0xFFE6ECF4), onSurfaceVariant = Color(0xFF45505E),
    outline = Color(0xFF7A8696), outlineVariant = Color(0xFFD9E0EA),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CCBFF), onPrimary = Color(0xFF00315D),
    primaryContainer = Color(0xFF004A8A), onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFB4C8E0), onSecondary = Color(0xFF1E3248),
    secondaryContainer = Color(0xFF354A60), onSecondaryContainer = Color(0xFFDCE8F7),
    tertiary = Color(0xFF6FD6C6), onTertiary = Color(0xFF003731),
    background = Color(0xFF0E1218), onBackground = Color(0xFFE3E8EF),
    surface = Color(0xFF171C24), onSurface = Color(0xFFE3E8EF),
    surfaceVariant = Color(0xFF2A323D), onSurfaceVariant = Color(0xFFB7C1CE),
    outline = Color(0xFF8793A3), outlineVariant = Color(0xFF2F3844),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

private val BaseTypography = Typography()
private val AppTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

object Semantic {
    @Composable
    fun success(): Color = if (isSystemInDarkTheme()) Color(0xFF6FCF97) else Color(0xFF1E8E4E)

    @Composable
    fun warning(): Color = if (isSystemInDarkTheme()) Color(0xFFFFC857) else Color(0xFFB26A00)
}

@Composable
fun TgMonitorTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    // Arabic UI: force RTL regardless of device locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
