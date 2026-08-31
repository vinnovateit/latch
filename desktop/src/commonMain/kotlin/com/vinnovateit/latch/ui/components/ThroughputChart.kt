package com.vinnovateit.latch.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.ui.theme.ColorGraphDownload
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme

/**
 * Live throughput chart — the filled-area graph inside SpectrumCard.
 *
 * Draws total rate (rx + tx) as a smoothed cubic curve with a gradient area fill.
 * The fill is suppressed in AMOLED mode because a gradient wash over a pure-black
 * background looks muddy; the line alone reads clearly against black.
 *
 * Peak rate is spring-animated so the Y-axis never snaps abruptly downward when
 * traffic drops — the scale eases rather than jumping.
 */
@Composable
internal fun ThroughputChart(
    history: List<LiveDataPoint>,
    modifier: Modifier = Modifier,
) {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current

    val rawPeak = history.maxOfOrNull { it.usage.rxBps + it.usage.txBps } ?: 0L
    val animatedPeak by animateFloatAsState(
        targetValue = rawPeak.toFloat().coerceAtLeast(1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "chartPeak",
    )

    val lineColor = ColorGraphDownload

    Canvas(modifier = modifier) {
        if (history.size < 2 || animatedPeak <= 0f) return@Canvas

        val paths = buildGraphPaths(
            history = history,
            width = size.width,
            height = size.height,
            maxRate = animatedPeak,
        )

        if (!isAmoled) {
            drawPath(
                path = paths.area,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.35f),
                        lineColor.copy(alpha = 0.04f),
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }

        drawPath(
            path = paths.line,
            color = lineColor,
            style = Stroke(
                width = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
