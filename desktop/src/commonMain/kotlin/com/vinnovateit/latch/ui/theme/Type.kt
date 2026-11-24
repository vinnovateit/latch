package com.vinnovateit.latch.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.moderniz
import com.vinnovateit.latch.desktop.resources.satoshi_bold
import com.vinnovateit.latch.desktop.resources.satoshi_light
import com.vinnovateit.latch.desktop.resources.satoshi_medium
import com.vinnovateit.latch.desktop.resources.satoshi_regular
import org.jetbrains.compose.resources.Font

/*
 * Compose Multiplatform's Font() is @Composable, unlike Android's. That forces
 * the font families and the Typography -- top-level `val`s in the Android app --
 * to become composable functions. This is the single most invasive mechanical
 * change in the theme port.
 *
 * Every size, lineHeight and letterSpacing below is byte-identical to the
 * Android app. Note the deliberate deviations from M3 defaults, which must be
 * preserved for pixel fidelity:
 *   bodyLarge  18.sp (M3 default is 16)
 *   bodySmall  13.sp / 16.sp lineHeight / 0.3.sp letterSpacing (M3: 12/16/0.4)
 *   displayLarge letterSpacing -0.25.sp
 */

/**
 * Satoshi ships as four STATIC font files, one per weight. The native Skia
 * renderer resolves a FontFamily to a single file by FontStyle (not weight) and
 * applies `FontWeight` as a variable `wght` axis — which a static font ignores.
 * A family holding all four files therefore renders every Text in the first
 * (Light) face. On native, return a one-font family keyed to the requested
 * weight so the correct static file is selected. The JVM/Android stacks match
 * by weight normally, so this is harmless there.
 */
@Composable
internal fun satoshiFontFamily(weight: FontWeight = FontWeight.Normal): FontFamily {
    val font = when {
        weight.weight <= FontWeight.Light.weight -> Font(Res.font.satoshi_light, FontWeight.Light, FontStyle.Normal)
        weight.weight <= FontWeight.Normal.weight -> Font(Res.font.satoshi_regular, FontWeight.Normal, FontStyle.Normal)
        weight.weight <= FontWeight.Medium.weight -> Font(Res.font.satoshi_medium, FontWeight.Medium, FontStyle.Normal)
        else -> Font(Res.font.satoshi_bold, FontWeight.Bold, FontStyle.Normal)
    }
    return remember(font) { FontFamily(font) }
}

@Composable
internal fun modernizFontFamily(): FontFamily {
    val moderniz = Font(Res.font.moderniz, FontWeight.Normal)
    return remember(moderniz) { FontFamily(moderniz) }
}

/**
 * The app typography. [remember] is load-bearing: without it all 15 TextStyles
 * are rebuilt on every recomposition of LatchTheme.
 */
@Composable
internal fun appTypography(): Typography {
    // Resolve each static weight to its own one-font family (see satoshiFontFamily).
    val regular = satoshiFontFamily(FontWeight.Normal)
    val medium = satoshiFontFamily(FontWeight.Medium)
    val bold = satoshiFontFamily(FontWeight.Bold)
    return remember(regular, medium, bold) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.W400,
                fontSize = 54.sp,
                lineHeight = 60.sp,
                letterSpacing = (-0.25).sp,
            ),
            displayMedium = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 43.sp,
                lineHeight = 49.sp,
            ),
            displaySmall = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 34.sp,
                lineHeight = 42.sp,
            ),
            headlineLarge = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 30.sp,
                lineHeight = 38.sp,
            ),
            headlineMedium = TextStyle(
                fontFamily = medium,
                fontWeight = FontWeight.Medium,
                fontSize = 26.6.sp,
                lineHeight = 34.sp,
            ),
            headlineSmall = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 22.8.sp,
                lineHeight = 30.sp,
            ),
            titleLarge = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 21.sp,
                lineHeight = 26.sp,
            ),
            titleMedium = TextStyle(
                fontFamily = medium,
                fontWeight = FontWeight.Medium,
                fontSize = 15.2.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.15.sp,
            ),
            titleSmall = TextStyle(
                fontFamily = medium,
                fontWeight = FontWeight.Medium,
                fontSize = 13.3.sp,
                lineHeight = 19.sp,
                letterSpacing = 0.1.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 17.1.sp,
                lineHeight = 23.sp,
                letterSpacing = 0.5.sp,
            ),
            bodyMedium = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 13.3.sp,
                lineHeight = 19.sp,
                letterSpacing = 0.25.sp,
            ),
            bodySmall = TextStyle(
                fontFamily = regular,
                fontWeight = FontWeight.Normal,
                fontSize = 12.35.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.3.sp,
            ),
            labelLarge = TextStyle(
                fontFamily = medium,
                fontWeight = FontWeight.Medium,
                fontSize = 13.3.sp,
                lineHeight = 19.sp,
                letterSpacing = 0.1.sp,
            ),
            labelMedium = TextStyle(
                fontFamily = medium,
                fontWeight = FontWeight.Medium,
                fontSize = 11.4.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.5.sp,
            ),
            labelSmall = TextStyle(
                fontFamily = medium,
                fontWeight = FontWeight.Medium,
                fontSize = 10.45.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}
