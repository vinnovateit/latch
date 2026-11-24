package com.vinnovateit.latch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.vinnovateit.latch.ui.theme.mcu.dynamiccolor.DynamicColor
import com.vinnovateit.latch.ui.theme.mcu.dynamiccolor.MaterialDynamicColors
import com.vinnovateit.latch.ui.theme.mcu.hct.Hct as NativeHct
import com.vinnovateit.latch.ui.theme.mcu.palettes.TonalPalette as NativeTonalPalette
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeExpressive
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeFidelity
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeFruitSalad
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeMonochrome
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeNeutral
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeRainbow
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeTonalSpot
import com.vinnovateit.latch.ui.theme.mcu.scheme.SchemeVibrant

/** HCT in degrees / chroma / L* (0..100), with Float values for the theme API. */
internal data class Hct(val hue: Float, val chroma: Float, val tone: Float)

internal object MaterialHct {
    fun argbToHct(argb: Int): Hct {
        val hct = NativeHct.fromInt(argb)
        return Hct(hct.hue.toFloat(), hct.chroma.toFloat(), hct.tone.toFloat())
    }

    fun hctToArgb(hue: Float, chroma: Float, tone: Float): Int =
        NativeHct.from(hue.toDouble(), chroma.toDouble(), tone.toDouble()).toInt()
}

/** Thin facade over the upstream tonal palette. */
internal class TonalPalette private constructor(private val delegate: NativeTonalPalette) {
    val hue: Float get() = delegate.hue.toFloat()
    val chroma: Float get() = delegate.chroma.toFloat()

    fun tone(tone: Int): Int = delegate.tone(tone)

    companion object {
        fun fromHueAndChroma(hue: Float, chroma: Float): TonalPalette =
            TonalPalette(NativeTonalPalette.fromHueAndChroma(hue.toDouble(), chroma.toDouble()))

        fun fromArgb(argb: Int): TonalPalette = TonalPalette(NativeTonalPalette.fromInt(argb))
    }
}

/**
 * Material You dynamic color generation for linuxX64, ported from material-kolor 5.0.1
 * (https://github.com/jordond/materialkolor/tree/5.0.1). The upstream math lives in mcu/.
 */
internal fun dynamicColorScheme(seedColor: Color, isDark: Boolean, style: String): ColorScheme {
    val source = NativeHct.fromInt(seedColor.toArgb())
    // Contrast.Default.value in upstream; all eight styles use the default 2021 palette spec.
    val contrastLevel = 0.0
    val scheme = when (style) {
        "Neutral" -> SchemeNeutral(source, isDark, contrastLevel)
        "Vibrant" -> SchemeVibrant(source, isDark, contrastLevel)
        "Expressive" -> SchemeExpressive(source, isDark, contrastLevel)
        "Rainbow" -> SchemeRainbow(source, isDark, contrastLevel)
        "FruitSalad" -> SchemeFruitSalad(source, isDark, contrastLevel)
        "Monochrome" -> SchemeMonochrome(source, isDark, contrastLevel)
        "Fidelity" -> SchemeFidelity(source, isDark, contrastLevel)
        else -> SchemeTonalSpot(source, isDark, contrastLevel)
    }

    val colors = MaterialDynamicColors()
    fun argb(color: DynamicColor): Color = Color(color.getArgb(scheme))

    return ColorScheme(
        primary = argb(colors.primary()),
        onPrimary = argb(colors.onPrimary()),
        primaryContainer = argb(colors.primaryContainer()),
        onPrimaryContainer = argb(colors.onPrimaryContainer()),
        inversePrimary = argb(colors.inversePrimary()),
        secondary = argb(colors.secondary()),
        onSecondary = argb(colors.onSecondary()),
        secondaryContainer = argb(colors.secondaryContainer()),
        onSecondaryContainer = argb(colors.onSecondaryContainer()),
        tertiary = argb(colors.tertiary()),
        onTertiary = argb(colors.onTertiary()),
        tertiaryContainer = argb(colors.tertiaryContainer()),
        onTertiaryContainer = argb(colors.onTertiaryContainer()),
        background = argb(colors.background()),
        onBackground = argb(colors.onBackground()),
        surface = argb(colors.surface()),
        onSurface = argb(colors.onSurface()),
        surfaceVariant = argb(colors.surfaceVariant()),
        onSurfaceVariant = argb(colors.onSurfaceVariant()),
        surfaceTint = argb(colors.surfaceTint()),
        inverseSurface = argb(colors.inverseSurface()),
        inverseOnSurface = argb(colors.inverseOnSurface()),
        error = argb(colors.error()),
        onError = argb(colors.onError()),
        errorContainer = argb(colors.errorContainer()),
        onErrorContainer = argb(colors.onErrorContainer()),
        outline = argb(colors.outline()),
        outlineVariant = argb(colors.outlineVariant()),
        scrim = argb(colors.scrim()),
        surfaceBright = argb(colors.surfaceBright()),
        surfaceDim = argb(colors.surfaceDim()),
        surfaceContainer = argb(colors.surfaceContainer()),
        surfaceContainerHigh = argb(colors.surfaceContainerHigh()),
        surfaceContainerHighest = argb(colors.surfaceContainerHighest()),
        surfaceContainerLow = argb(colors.surfaceContainerLow()),
        surfaceContainerLowest = argb(colors.surfaceContainerLowest()),
        primaryFixed = argb(colors.primaryFixed()),
        primaryFixedDim = argb(colors.primaryFixedDim()),
        onPrimaryFixed = argb(colors.onPrimaryFixed()),
        onPrimaryFixedVariant = argb(colors.onPrimaryFixedVariant()),
        secondaryFixed = argb(colors.secondaryFixed()),
        secondaryFixedDim = argb(colors.secondaryFixedDim()),
        onSecondaryFixed = argb(colors.onSecondaryFixed()),
        onSecondaryFixedVariant = argb(colors.onSecondaryFixedVariant()),
        tertiaryFixed = argb(colors.tertiaryFixed()),
        tertiaryFixedDim = argb(colors.tertiaryFixedDim()),
        onTertiaryFixed = argb(colors.onTertiaryFixed()),
        onTertiaryFixedVariant = argb(colors.onTertiaryFixedVariant()),
    )
}
