package com.vinnovateit.latch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

actual fun seedColorScheme(seedColor: Color, isDark: Boolean, style: ThemePaletteStyle): ColorScheme =
    com.materialkolor.dynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        style = com.materialkolor.PaletteStyle.valueOf(style.name),
    )
