package com.vinnovateit.latch.ui.components

import androidx.compose.ui.graphics.Path
import com.vinnovateit.latch.core.model.LiveDataPoint

/** Area path (closed) and line path (open) for the throughput chart. */
internal data class GraphPaths(val area: Path, val line: Path)

/**
 * Builds the area and line paths for the live throughput chart.
 *
 * Only total rate (rx + tx) is drawn — the Android two-colour area was
 * three-pass draw with clip regions that don't translate cleanly to a desktop
 * canvas, and the single-colour version is cleaner at desktop sizes.
 *
 * Cubic smoothing: each control point X is the midpoint of the previous and
 * current X, which avoids overshoots while keeping the curve natural.
 */
internal fun buildGraphPaths(
    history: List<LiveDataPoint>,
    width: Float,
    height: Float,
    maxRate: Float,
    graphHeightScale: Float = 0.70f,
): GraphPaths {
    val area = Path()
    val line = Path()

    if (history.size < 2 || maxRate <= 0f) return GraphPaths(area, line)

    val graphH = height * graphHeightScale
    val stepX = width / (history.size - 1).coerceAtLeast(1)

    fun xAt(i: Int) = i * stepX
    fun yAt(point: LiveDataPoint): Float {
        val rate = point.usage.rxBps + point.usage.txBps
        return height - (rate.toFloat() / maxRate.toFloat()) * graphH
    }

    var prevX = xAt(0)
    var prevY = yAt(history[0])

    area.moveTo(prevX, height)
    area.lineTo(prevX, prevY)
    line.moveTo(prevX, prevY)

    for (i in 1 until history.size) {
        val px = xAt(i)
        val py = yAt(history[i])
        val controlX = (prevX + px) / 2f
        area.cubicTo(controlX, prevY, controlX, py, px, py)
        line.cubicTo(controlX, prevY, controlX, py, px, py)
        prevX = px
        prevY = py
    }

    area.lineTo(prevX, height)
    area.close()

    return GraphPaths(area, line)
}
