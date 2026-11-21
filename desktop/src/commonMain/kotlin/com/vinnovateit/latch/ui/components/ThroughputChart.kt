package com.vinnovateit.latch.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * Reuses the two Paths while nothing the curve depends on has changed. The
 * spring-redraw loop and unrelated recompositions would otherwise rebuild a
 * 150-point cubic path (and allocate two Paths) on every frame.
 */
private class GraphPathCache {
    private var source: List<LiveDataPoint>? = null
    private var width = 0f
    private var height = 0f
    private var peak = 0f
    private var paths: GraphPaths? = null

    fun get(history: List<LiveDataPoint>, width: Float, height: Float, maxRate: Float): GraphPaths {
        val cached = paths
        if (cached != null && history === source && width == this.width &&
            height == this.height && maxRate == peak
        ) return cached
        val built = buildGraphPaths(history, width, height, maxRate)
        source = history
        this.width = width
        this.height = height
        peak = maxRate
        paths = built
        return built
    }
}

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

    val rawPeak = remember(history) {
        history.maxOfOrNull { it.usage.rxBps + it.usage.txBps } ?: 0L
    }
    val pathCache = remember { GraphPathCache() }
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

        val paths = pathCache.get(
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
