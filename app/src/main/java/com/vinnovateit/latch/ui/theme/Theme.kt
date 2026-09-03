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
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

// Removed hardcoded schemes to use dynamic seed generation

val LocalIsDarkTheme = compositionLocalOf { false }
 
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LatchTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeSetting by SettingsManager.theme.collectAsStateWithLifecycle()
    val useDynamicColors by SettingsManager.useDynamicColors.collectAsStateWithLifecycle() // Read the new setting
    val useMonochrome by SettingsManager.useMonochrome.collectAsStateWithLifecycle()
    val accentColor by SettingsManager.accentColor.collectAsStateWithLifecycle()
    val systemIsDark = isSystemInDarkTheme()
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Determine if the final theme should be dark
    val darkTheme = when (themeSetting) {
        "Light" -> false
        "Dark" -> true
        else -> systemIsDark
    }

    val seedColor = when (accentColor) {
        "Blue" -> Color(0xFF005AC1)
        "Green" -> Color(0xFF0F5223)
        "Purple" -> Color(0xFF7D00B8)
        "Pink" -> Color(0xFFD81B60)
        "Yellow" -> Color(0xFFF5B300)
        else -> Color(0xFFC01221) // Red
    }

    val baseColorScheme = when {
        // Monochrome takes highest priority if enabled
        useMonochrome -> {
            dynamicColorScheme(
                seedColor = Color.Black, // Seed doesn't matter much for monochrome
                isDark = darkTheme,
                style = PaletteStyle.Monochrome
            )
        }
        // Then Dynamic colors if toggled on and supported
        useDynamicColors && supportsDynamic -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Fallback to Material Kolor dynamic seed generation
        else -> {
            dynamicColorScheme(
                seedColor = seedColor,
                isDark = darkTheme
            )
        }
    }

    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()

    var colorScheme = baseColorScheme

    if (darkTheme) {
        colorScheme = if (usePureBlack) {
            colorScheme.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainer = Color.Black,
                surfaceContainerLow = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceVariant = Color(0xFF121212),
                surfaceContainerHigh = Color(0xFF121212),
                surfaceContainerHighest = Color(0xFF1A1A1A),
                surfaceDim = Color.Black
            )
        } else {
            colorScheme.copy(
                surfaceVariant = colorScheme.surfaceContainerHigh
            )
        }
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

// Removed applyAccentColor since we now generate the entire scheme from seed