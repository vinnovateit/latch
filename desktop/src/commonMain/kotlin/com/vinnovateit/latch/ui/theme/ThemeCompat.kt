package com.vinnovateit.latch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Palette styles Latch exposes; material-kolor has no linuxX64 artifact. */
enum class ThemePaletteStyle {
    TonalSpot, Neutral, Vibrant, Expressive, Rainbow, FruitSalad, Monochrome, Fidelity,
}

expect fun seedColorScheme(seedColor: Color, isDark: Boolean, style: ThemePaletteStyle): ColorScheme
