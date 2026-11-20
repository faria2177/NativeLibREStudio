package com.nativelib.restudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.nativelib.restudio.ThemeMode

private val Teal = Color(0xFF4FD1C5)
private val TealDark = Color(0xFF0F766E)

private val DarkScheme = darkColorScheme(
    primary = Teal, onPrimary = Color(0xFF00201D), primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFB2F5EA),
    secondary = Color(0xFF93C5FD), onSecondary = Color(0xFF0B1F3A), secondaryContainer = Color(0xFF1E3A5F), onSecondaryContainer = Color(0xFFDBEAFE),
    tertiary = Color(0xFFFBBF24), onTertiary = Color(0xFF2A1E00), tertiaryContainer = Color(0xFF4A3500), onTertiaryContainer = Color(0xFFFEF3C7),
    error = Color(0xFFF87171), errorContainer = Color(0xFF5C1A1A), onErrorContainer = Color(0xFFFEE2E2),
    background = Color(0xFF0F1115), onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F1115), onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1C2028), onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainerLowest = Color(0xFF0B0D10), surfaceContainerLow = Color(0xFF14171D), surfaceContainer = Color(0xFF181B22),
    surfaceContainerHigh = Color(0xFF1E222A), surfaceContainerHighest = Color(0xFF252A33),
    outline = Color(0xFF3A414D), outlineVariant = Color(0xFF2A2F38),
)

private val LightScheme = lightColorScheme(
    primary = TealDark, onPrimary = Color.White, primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF042F2E),
    secondary = Color(0xFF1D4ED8), secondaryContainer = Color(0xFFDBEAFE), onSecondaryContainer = Color(0xFF0B1F3A),
    tertiary = Color(0xFFB45309), tertiaryContainer = Color(0xFFFEF3C7), onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFF8FAFC), surface = Color(0xFFF8FAFC), surfaceVariant = Color(0xFFE2E8F0), onSurfaceVariant = Color(0xFF475569),
)

/** Monospace style used for addresses, hashes, offsets and other binary data. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)

@Composable
fun NativeLibTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = Typography(), content = content)
}
