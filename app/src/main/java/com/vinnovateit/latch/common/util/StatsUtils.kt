package com.vinnovateit.latch.common.util

import androidx.compose.ui.graphics.Path
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.stats.formatBitsPerSecond
import com.vinnovateit.latch.core.stats.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class DisplayMode { TOTAL, DOWNLOAD, UPLOAD }

data class GraphData(
    val totalPath: Path,
    val lineTotalPath: Path,
    val rxPath: Path = Path(),
    val lineRxPath: Path = Path(),
    val txPath: Path = Path(),
    val lineTxPath: Path = Path(),
    val labels: List<Pair<String, Float>>,
    private val startTime: Long,
    private val duration: Long,
    private val width: Float,
    private val height: Float,
    private val maxRate: Float,
    private val graphHeightScale: Float
) {
    fun xAtTimestamp(timestamp: Long): Float {
        val elapsedTime = (timestamp - startTime).toFloat()
        return (elapsedTime / duration) * width
    }

    fun yAtRate(rate: Long): Float {
        val usageFraction = (rate.toFloat() / maxRate).coerceIn(0f, 1f)
        return height - (usageFraction * height * graphHeightScale)
    }

    fun timestampAtX(x: Float): Long {
        return startTime + ((x / width) * duration).toLong()
    }
}

fun formatBytes(bytes: Long, unit: String = "B/s"): Pair<String, String> =
    com.vinnovateit.latch.core.stats.formatBytes(bytes, unit)

fun formatBitsPerSecond(bytesPerSecond: Long, unit: String = "bps"): Pair<String, String> =
    com.vinnovateit.latch.core.stats.formatBitsPerSecond(bytesPerSecond, unit)

fun formatDurationDynamic(ms: Long): String {
    if (ms < 0) return "0s"
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
        m > 0 -> "${m}m ${s}s"
        else  -> "${s}s"
    }
}

fun formatDate(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.US).format(Date(millis))

fun createGraphPaths(
    history: List<LiveDataPoint>,
    width: Float,
    height: Float,
    maxRate: Float,
    graphHeightScale: Float = 0.6f
): GraphData {
    if (history.size < 2) {
        return GraphData(Path(), Path(), Path(), Path(), Path(), Path(), emptyList(), 0, 0, 0f, 0f, 0f, 0f)
    }

    val effectiveMaxRate = maxRate.coerceAtLeast(1f)
    val startTime = history.first().timestamp
    val duration = (history.last().timestamp - startTime).coerceAtLeast(1)
    fun x(t: Long): Float = ((t - startTime).toFloat() / duration) * width
    fun y(bytes: Long): Float {
        val usageFraction = (bytes.toFloat() / effectiveMaxRate).coerceIn(0f, 1f)
        return height - (usageFraction * height * graphHeightScale)
    }

    val fillTotal = Path().apply { moveTo(0f, height) }
    val lineTotal = Path()
    val fillRx = Path().apply { moveTo(0f, height) }
    val lineRx = Path()
    val fillTx = Path().apply { moveTo(0f, height) }
    val lineTx = Path()
    for (i in history.indices) {
        val p = history[i]
        val xp = x(p.timestamp)
        val yTotal = y(p.usage.rxBps + p.usage.txBps)
        val yRx = y(p.usage.rxBps)
        val yTx = y(p.usage.txBps)

        if (i == 0) {
            lineTotal.moveTo(xp, yTotal)
            fillTotal.lineTo(xp, yTotal)
            lineRx.moveTo(xp, yRx)
            fillRx.lineTo(xp, yRx)
            lineTx.moveTo(xp, yTx)
            fillTx.lineTo(xp, yTx)
        } else {
            val prevPoint = history[i - 1]
            val prevX = x(prevPoint.timestamp)
            val prevYTotal = y(prevPoint.usage.rxBps + prevPoint.usage.txBps)
            val prevYRx = y(prevPoint.usage.rxBps)
            val prevYTx = y(prevPoint.usage.txBps)
            val cx = (prevX + xp) / 2f

            lineTotal.cubicTo(cx, prevYTotal, cx, yTotal, xp, yTotal)
            fillTotal.cubicTo(cx, prevYTotal, cx, yTotal, xp, yTotal)
            
            lineRx.cubicTo(cx, prevYRx, cx, yRx, xp, yRx)
            fillRx.cubicTo(cx, prevYRx, cx, yRx, xp, yRx)
            
            lineTx.cubicTo(cx, prevYTx, cx, yTx, xp, yTx)
            fillTx.cubicTo(cx, prevYTx, cx, yTx, xp, yTx)
        }
    }

    val lastX = x(history.last().timestamp)
    fillTotal.lineTo(lastX, height)
    fillTotal.close()
    
    fillRx.lineTo(lastX, height)
    fillRx.close()
    
    fillTx.lineTo(lastX, height)
    fillTx.close()

    val labels = if (history.size > 1) {
        (0..5).map { i ->
            val timestamp = startTime + (i * duration / 5)
            formatDate(timestamp, "hh:mm a") to x(timestamp)
        }
    } else {
        emptyList()
    }

    return GraphData(fillTotal, lineTotal, fillRx, lineRx, fillTx, lineTx, labels, startTime, duration, width, height, effectiveMaxRate, graphHeightScale)
}