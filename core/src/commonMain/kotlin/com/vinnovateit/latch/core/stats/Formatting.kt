package com.vinnovateit.latch.core.stats

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class DisplayMode { TOTAL, DOWNLOAD, UPLOAD }

fun formatBytes(bytes: Long, unit: String = "B/s"): Pair<String, String> = when {
    unit == "bps" -> formatBitsPerSecond(bytes)
    bytes < 1_024L -> bytes.toString() to "B"
    bytes < 1_048_576L -> "%.1f".format(bytes / 1_024f) to "KB"
    bytes < 1_073_741_824L -> "%.1f".format(bytes / 1_048_576f) to "MB"
    else -> "%.2f".format(bytes / 1_073_741_824f) to "GB"
}

/** @param bytesPerSecond bytes/sec, despite what callers named rxBps imply. */
fun formatBitsPerSecond(bytesPerSecond: Long, unit: String = "bps"): Pair<String, String> {
    if (unit == "B/s") {
        val (value, byteUnit) = formatBytes(bytesPerSecond, "B/s")
        return value to "$byteUnit/s"
    }

    val bitsPerSecond = bytesPerSecond * 8
    return when {
        bitsPerSecond < 1_000L -> bitsPerSecond.toString() to "bps"
        bitsPerSecond < 1_000_000L -> "%.1f".format(bitsPerSecond / 1_000f) to "Kbps"
        bitsPerSecond < 1_000_000_000L -> "%.1f".format(bitsPerSecond / 1_000_000f) to "Mbps"
        else -> "%.2f".format(bitsPerSecond / 1_000_000_000f) to "Gbps"
    }
}

fun formatDurationDynamic(ms: Long): String {
    if (ms < 0) return "0s"
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        h > 0 -> if (m > 0) "${h}h ${m}m" else "${h}h"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

fun formatDate(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.US).format(Date(millis))

fun formatClockTime(millis: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
