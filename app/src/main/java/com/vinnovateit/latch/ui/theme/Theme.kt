package com.vinnovateit.latch.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.features.settings.manager.SettingsManager

val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC01221),
    onPrimary = Color(0xFFFFDFB1),
    primaryContainer = Color(0xFFD2222C),
    onPrimaryContainer = Color(0xFFFFDFB1),
    secondary = Color(0xFF670002),
    onSecondary = Color(0xFFFF8686),
    secondaryContainer = Color(0xFFC01221),
    onSecondaryContainer = Color(0xFF410002),
    tertiary = Color(0xFFC01221),
    onTertiary = Color(0xFFFFD078),
    tertiaryContainer = Color(0xFFE0E0E0),
    onTertiaryContainer = Color(0xFF241A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDF0D5),
    onBackground = Color(0xFF201A19),
    surface = Color(0xFFFDF0D5),
    surfaceContainer = Color(0xFFF3E7CC),
    surfaceContainerHighest = Color(0xFFFFF8E8),
    onSurface = Color(0xFF0d0d0d),
    surfaceVariant = Color(0xFFFFF8E8),
    onSurfaceVariant = Color(0xFF534341),
    outline = Color(0xFFC01221),
    inverseOnSurface = Color(0xFF000000),
    inverseSurface = Color(0xFF362F2E),
    inversePrimary = Color(0xFFFFB4AB),
    surfaceTint = Color(0xFFC01221),
    outlineVariant = Color(0xFFD7C1BE),
    scrim = Color(0xFF000000),
)

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFF6B6B),
    onPrimary = Color(0xFF090F29),
    primaryContainer = Color(0xFFFF6B6B),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFF073691),
    onSecondary = Color(0xFF690005),
    secondaryContainer = Color(0xFFFF5F5F),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFe0e0e0),
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF242424),
    onTertiaryContainer = Color(0xFFFBDD88),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF00092E),
    onBackground = Color(0xFFe0e0e0),
    surface = Color(0xFF00092E),
    onSurface = Color(0xFFe0e0e0),
    surfaceContainer = Color(0xFF000C38),
    surfaceContainerHighest = Color(0xFF000A3D),
    surfaceContainerLowest = Color(0xFF000F47),
    surfaceVariant = Color(0xFF364075),
    onSurfaceVariant = Color(0xFFD7C1BE),
    outline = Color(0xFFA08C8A),
    inverseOnSurface = Color(0xFF000A3D),
    inverseSurface = Color(0xFFE6E1E5),
    inversePrimary = Color(0xFFC01221),
    surfaceTint = Color(0xFFFFB4AB),
    outlineVariant = Color(0xFFC9C9C9),
    scrim = Color(0xFF000000),
)

val LocalIsDarkTheme = compositionLocalOf { false }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LatchTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeSetting by SettingsManager.theme.collectAsStateWithLifecycle()
    val useDynamicColors by SettingsManager.useDynamicColors.collectAsStateWithLifecycle() // Read the new setting
    val systemIsDark = isSystemInDarkTheme()
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Determine if the final theme should be dark
    val darkTheme = when (themeSetting) {
        "Light" -> false
        "Dark" -> true
        else -> systemIsDark
    }

    val colorScheme = when {
        // Highest priority: Dynamic colors if toggled on and supported
        useDynamicColors && supportsDynamic -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Fallback to standard themes
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = AppTypography,
            content = content
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
}

val ColorScheme.tooltipContainer: Color
    @Composable
    get() = if (LocalIsDarkTheme.current) Color(0xFFE0E0E0) else Color(0xFF3A3A3A)

val ColorScheme.tooltipContent: Color
    @Composable
    get() = if (LocalIsDarkTheme.current) Color.Black else Color.White