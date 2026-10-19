package app.monogatari.android.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Original system backgrounds and system-pink tint, including Material's derived surfaces. */
internal fun monogatariColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = Pink, onPrimary = Color.White,
    primaryContainer = Color(0xFF4C1524), onPrimaryContainer = Color(0xFFFFD9E2),
    inversePrimary = Pink,
    secondary = Pink, onSecondary = Color.White,
    secondaryContainer = Color(0xFF4C1524), onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Pink, onTertiary = Color.White,
    tertiaryContainer = Color(0xFF4C1524), onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF1C1C1E), onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF2F2F7), inverseOnSurface = Color.Black,
    surfaceDim = Color(0xFF1C1C1E), surfaceBright = Color(0xFF3A3A3C),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E), surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    outline = Color(0xFF636366), outlineVariant = Color(0xFF48484A),
    error = Color(0xFFFF453A), onError = Color.White,
    errorContainer = Color(0xFF561D1A), onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black
) else lightColorScheme(
    primary = Pink, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5EC), onPrimaryContainer = Color(0xFF8F1734),
    inversePrimary = Pink,
    secondary = Pink, onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE5EC), onSecondaryContainer = Color(0xFF8F1734),
    tertiary = Pink, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE5EC), onTertiaryContainer = Color(0xFF8F1734),
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Color(0xFFEEEEF0), onSurfaceVariant = Color(0xFF8E8E93),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2C2C2E), inverseOnSurface = Color.White,
    surfaceDim = Color(0xFFE5E5EA), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9F9FB),
    surfaceContainer = Color(0xFFF2F2F7), surfaceContainerHigh = Color(0xFFF2F2F7),
    surfaceContainerHighest = Color(0xFFEEEEF0),
    outline = Color(0xFFC7C7CC), outlineVariant = Color(0xFFD1D1D6),
    error = Color(0xFFFF3B30), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF7D1710),
    scrim = Color.Black
)
