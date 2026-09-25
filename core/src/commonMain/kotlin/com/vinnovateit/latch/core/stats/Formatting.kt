package com.vinnovateit.latch.core.stats

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class DisplayMode { TOTAL, DOWNLOAD, UPLOAD }

/*
 * Ported verbatim from the Android app's StatsUtils.kt. Note the deliberate
 * asymmetry, which is correct and must be preserved: byte sizes use binary
 * divisors (1024) while bit rates use SI divisors (1000), which is the
 * conventional way to report storage vs link speed.
 *
 * The pointless android.util.SparseArray usage from the original is dropped.
 */

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

/** Formats duration into words (e.g. "2 hr 15 min", "9 min 52 sec", "0 sec"). */
fun formatDurationWords(ms: Long): String {
    if (ms <= 0) return "0 sec"
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    val parts = ArrayList<String>(3)
    if (h > 0) parts.add("$h hr")
    if (m > 0) parts.add("$m min")
    if (s > 0 || parts.isEmpty()) parts.add("$s sec")
    return parts.joinToString(" ")
}

/** Locale.US to match Android exactly -- chart labels must not shift by locale. */
fun formatDate(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.US).format(Date(millis))

/** Wall-clock time for the tray tooltip / "connected since" text. */
fun formatClockTime(millis: Long): String =
    SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))

/** Date formatting omitting week name and omitting year for current year. */
fun formatDisplayDate(millis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val recordYear = cal.get(java.util.Calendar.YEAR)
    val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    val currentYear = nowCal.get(java.util.Calendar.YEAR)

    val pattern = if (recordYear == currentYear) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.US).format(Date(millis))
}
