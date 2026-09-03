package com.vinnovateit.latch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.vinnovateit.latch.core.settings.SettingsManager

val LocalIsDarkTheme = compositionLocalOf { false }

/*
 * Ported from the Android app with exactly three changes:
 *
 *  1. The Activity/window SideEffect (transparent status + navigation bars) is
 *     gone. It was the only thing tying the theme to an Activity and has no
 *     meaning on a desktop window.
 *
 *  2. The Material You branch (dynamicDark/LightColorScheme(context)) is gone.
 *     It reads the OS wallpaper palette, which has no desktop equivalent. The
 *     persisted use_dynamic_colors flag is still respected in the sense that it
 *     simply falls through to the seed-based scheme, so no data migration is
 *     needed -- the Settings toggle is hidden on desktop instead.
 *
 *  3. Accent seeds come from AccentSeeds instead of being inlined here.
 *
 * Everything else -- PaletteStyle.Monochrome, the pure-black AMOLED overlay,
 * MaterialExpressiveTheme + MotionScheme.expressive(), LocalIsDarkTheme, and the
 * tooltip colour extensions -- is unchanged. material-kolor is a real KMP
 * library, so scheme generation is identical to Android's.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LatchTheme(
    content: @Composable () -> Unit,
) {
    val themeSetting by SettingsManager.theme.collectAsStateWithLifecycle()
    val useMonochrome by SettingsManager.useMonochrome.collectAsStateWithLifecycle()
    val accentColor by SettingsManager.accentColor.collectAsStateWithLifecycle()
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val systemIsDark = isSystemInDarkTheme()

    val darkTheme = when (themeSetting) {
        "Light" -> false
        "Dark" -> true
        else -> systemIsDark
    }

    val seedColor = AccentSeeds.forName(accentColor)

    val baseColorScheme = when {
        // Monochrome takes highest priority if enabled.
        useMonochrome -> dynamicColorScheme(
            // Seed is irrelevant for monochrome.
            seedColor = Color.Black,
            isDark = darkTheme,
            style = PaletteStyle.Monochrome,
        )

        else -> dynamicColorScheme(
            seedColor = seedColor,
            isDark = darkTheme,
        )
    }

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
                surfaceDim = Color.Black,
            )
        } else {
            colorScheme.copy(
                surfaceVariant = colorScheme.surfaceContainerHigh,
            )
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = appTypography(),
            content = content,
        )
    }
}

val ColorScheme.tooltipContainer: Color
    @Composable
    get() = if (LocalIsDarkTheme.current) Color(0xFFE0E0E0) else Color(0xFF3A3A3A)

val ColorScheme.tooltipContent: Color
    @Composable
    get() = if (LocalIsDarkTheme.current) Color.Black else Color.White
