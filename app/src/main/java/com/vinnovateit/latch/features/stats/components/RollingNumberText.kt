package com.vinnovateit.latch.features.stats.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

/**
 * Animated rolling number text where digits roll vertically like an odometer / slot machine,
 * counting up one by one with staggered easing.
 */
@Composable
fun RollingNumberText(
    value: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    val styledText = textStyle.copy(
        fontFeatureSettings = "tnum"
    )

    val resolvedColor = if (styledText.color != Color.Unspecified) {
        styledText.color
    } else {
        LocalContentColor.current
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        var digitIndex = 0
        for (i in value.indices) {
            val char = value[i]
            if (char in '0'..'9') {
                val idx = digitIndex++
                key("digit_$idx") {
                    DigitRoller(
                        digit = char.digitToInt(),
                        textStyle = styledText,
                        resolvedColor = resolvedColor,
                        colIndex = idx
                    )
                }
            } else {
                key("char_$i") {
                    Text(
                        text = char.toString(),
                        style = styledText,
                        color = resolvedColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitRoller(
    digit: Int,
    textStyle: TextStyle,
    resolvedColor: Color,
    colIndex: Int
) {
    var hasAnimated by rememberSaveable(colIndex) { mutableStateOf(false) }
    var currentDigit by rememberSaveable(colIndex) { mutableIntStateOf(if (hasAnimated) digit else 0) }
    val haptic = LocalHapticFeedback.current

    // Single Animatable sweep: no per-step recomposition, no coroutine loop overhead.
    // We animate a float from start digit → target digit and floor it each frame.
    val anim = remember(colIndex) { Animatable(currentDigit.toFloat()) }

    LaunchedEffect(digit) {
        if (hasAnimated) {
            // Already done initial roll — just jump instantly on data changes.
            currentDigit = digit
            anim.snapTo(digit.toFloat())
            return@LaunchedEffect
        }
        if (currentDigit == digit) {
            hasAnimated = true
            anim.snapTo(digit.toFloat())
            return@LaunchedEffect
        }
        // Stagger start per column for the odometer wave effect.
        delay(80L + colIndex * 20L)

        val start = currentDigit.toFloat()
        val end = digit.toFloat()
        val distance = kotlin.math.abs(end - start).coerceAtLeast(1f)
        // Duration scales with distance but stays bounded: ~400ms for a full 0→9 roll.
        val durationMs = (distance / 9f * 400f).toInt().coerceIn(120, 480)

        anim.snapTo(start)
        anim.animateTo(
            targetValue = end,
            animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing)
        ) {
            // Each frame: derive displayed digit from current animated float value.
            val floored = this.value.toInt()
            if (floored != currentDigit) {
                currentDigit = floored
            }
        }
        // Ensure we land exactly on target.
        currentDigit = digit
        // Single lightest haptic pulse when digit settles.
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        hasAnimated = true
    }

    AnimatedContent(
        targetState = currentDigit,
        transitionSpec = {
            val isFinal = targetState == digit
            val duration = if (isFinal) 160 else 80
            val easing = if (isFinal) FastOutSlowInEasing else LinearEasing
            (slideInVertically(tween(duration, easing = easing)) { h -> h } +
                fadeIn(tween(duration))).togetherWith(
                slideOutVertically(tween(duration, easing = easing)) { h -> -h } +
                    fadeOut(tween(duration))
            ).using(SizeTransform(clip = false))
        },
        label = "DigitRoll_$colIndex"
    ) { d ->
        Text(
            text = "$d",
            style = textStyle,
            color = resolvedColor,
            textAlign = TextAlign.Center
        )
    }
}
