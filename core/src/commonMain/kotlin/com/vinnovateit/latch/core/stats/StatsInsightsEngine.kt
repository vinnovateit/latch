package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.PortalSessionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class StatsInsights(
    val peakUsageTimeWindow: String,
    val highestUsageDayFormatted: String,
    val highestUsageDayDate: String,
    val highestUsageDayBytes: Long,
    val dailyAverageBytes: Long,
    val dailyAverageFormatted: Pair<String, String>,
    val mostActiveSessionDurationFormatted: String,
    val mostActiveSessionBytes: Long,
    val mostActiveSessionFormatted: Pair<String, String>,
    val activeDaysCount: Int,
    val currentStreakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val nightOwlBytes: Long = 0L,
    val nightOwlPercentage: Int = 0,
    val nightOwlFormatted: Pair<String, String> = Pair("0", "B")
)

fun formatInsightDate(timestamp: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val recordYear = cal.get(Calendar.YEAR)
    val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val currentYear = nowCal.get(Calendar.YEAR)

    val pattern = if (recordYear == currentYear) "d MMM" else "d MMM yyyy"
    return SimpleDateFormat(pattern, Locale.US).format(cal.time)
}

private fun getCalendarDayEpoch(timestamp: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return cal.timeInMillis / 86400000L
}

fun computeStatsInsights(
    sessions: List<PortalSessionRecord>,
    nowMillis: Long = System.currentTimeMillis()
): StatsInsights {
    val nonZero = sessions.filter { it.loginTime > 0 && (it.uploadBytes > 0 || it.downloadBytes > 0) }
    if (nonZero.isEmpty()) {
        val zeroPair = formatBytes(0L)
        return StatsInsights(
            peakUsageTimeWindow = "N/A",
            highestUsageDayFormatted = "0 B",
            highestUsageDayDate = "N/A",
            highestUsageDayBytes = 0L,
            dailyAverageBytes = 0L,
            dailyAverageFormatted = zeroPair,
            mostActiveSessionDurationFormatted = "0m",
            mostActiveSessionBytes = 0L,
            mostActiveSessionFormatted = zeroPair,
            activeDaysCount = 0,
            currentStreakDays = 0,
            longestStreakDays = 0,
            nightOwlBytes = 0L,
            nightOwlPercentage = 0,
            nightOwlFormatted = zeroPair
        )
    }

    // 1. Peak usage 3-hour window
    val windowBytes = LongArray(8)
    val windowCal = Calendar.getInstance()
    var nightOwlBytes = 0L
    for (s in nonZero) {
        windowCal.timeInMillis = s.loginTime
        val hour = windowCal.get(Calendar.HOUR_OF_DAY)
        val windowIdx = (hour / 3).coerceIn(0, 7)
        val bytes = s.totalBytes.coerceAtLeast(s.downloadBytes + s.uploadBytes)
        windowBytes[windowIdx] += bytes
        if (hour in 0..5) {
            nightOwlBytes += bytes
        }
    }
    var bestWindowIdx = 0
    var maxWindowBytes = -1L
    for (i in 0 until 8) {
        if (windowBytes[i] > maxWindowBytes) {
            maxWindowBytes = windowBytes[i]
            bestWindowIdx = i
        }
    }
    val startHour = bestWindowIdx * 3
    val endHour = startHour + 3
    fun hour12(h: Int): Int = when (val mod = h % 12) {
        0 -> 12
        else -> mod
    }
    fun ampm(h: Int): String = if (h < 12 || h == 24) "AM" else "PM"
    val start12 = hour12(startHour)
    val end12 = hour12(endHour)
    val startAmPm = ampm(startHour)
    val endAmPm = ampm(endHour)
    val peakUsageTimeWindow = if (startAmPm == endAmPm) {
        "$start12 - $end12 $endAmPm"
    } else {
        "$start12 $startAmPm - $end12 $endAmPm"
    }

    // 2. Highest Usage Day
    val dayGroups = nonZero.groupBy { formatDate(it.loginTime, "yyyy-MM-dd") }
    var highestDayBytes = 0L
    var highestDayTimestamp = 0L
    for ((_, daySessions) in dayGroups) {
        val dayTotal = daySessions.sumOf { it.totalBytes.coerceAtLeast(it.downloadBytes + it.uploadBytes) }
        if (dayTotal > highestDayBytes) {
            highestDayBytes = dayTotal
            highestDayTimestamp = daySessions.first().loginTime
        }
    }
    val highestDayDate = if (highestDayTimestamp > 0) formatInsightDate(highestDayTimestamp, nowMillis) else "N/A"
    val highestUsageFormatted = formatBytes(highestDayBytes)

    // 3. Daily Average
    val totalBytesAll = nonZero.sumOf { it.totalBytes.coerceAtLeast(it.downloadBytes + it.uploadBytes) }
    val activeDays = dayGroups.size.coerceAtLeast(1)
    val dailyAverageBytes = totalBytesAll / activeDays

    // 4. Most active session
    val topSession = nonZero.maxByOrNull { it.totalBytes.coerceAtLeast(it.downloadBytes + it.uploadBytes) }
        ?: nonZero.first()
    val topSessionBytes = topSession.totalBytes.coerceAtLeast(topSession.downloadBytes + topSession.uploadBytes)

    // 5. Streaks (Active Days)
    val activeDaysEpoch = nonZero.map { getCalendarDayEpoch(it.loginTime) }.distinct().sorted()
    var longestStreak = 0
    var currentRun = 0
    var prevDay: Long? = null
    for (day in activeDaysEpoch) {
        if (prevDay == null || day == prevDay + 1) {
            currentRun++
        } else if (day != prevDay) {
            currentRun = 1
        }
        if (currentRun > longestStreak) {
            longestStreak = currentRun
        }
        prevDay = day
    }

    val todayEpoch = getCalendarDayEpoch(nowMillis)
    val lastActiveDay = activeDaysEpoch.lastOrNull()
    var currentStreak = 0
    if (lastActiveDay != null && (lastActiveDay == todayEpoch || lastActiveDay == todayEpoch - 1)) {
        var expected = lastActiveDay
        for (i in activeDaysEpoch.indices.reversed()) {
            val d = activeDaysEpoch[i]
            if (d == expected) {
                currentStreak++
                expected--
            } else if (d < expected) {
                break
            }
        }
    }

    // 6. Night Owl Traffic
    val nightOwlPercentage = if (totalBytesAll > 0) ((nightOwlBytes * 100L) / totalBytesAll).toInt() else 0
    val nightOwlFormatted = formatBytes(nightOwlBytes)

    return StatsInsights(
        peakUsageTimeWindow = peakUsageTimeWindow,
        highestUsageDayFormatted = "${highestUsageFormatted.first} ${highestUsageFormatted.second}",
        highestUsageDayDate = highestDayDate,
        highestUsageDayBytes = highestDayBytes,
        dailyAverageBytes = dailyAverageBytes,
        dailyAverageFormatted = formatBytes(dailyAverageBytes),
        mostActiveSessionDurationFormatted = formatDurationDynamic(topSession.durationMillis),
        mostActiveSessionBytes = topSessionBytes,
        mostActiveSessionFormatted = formatBytes(topSessionBytes),
        activeDaysCount = activeDays,
        currentStreakDays = currentStreak,
        longestStreakDays = longestStreak,
        nightOwlBytes = nightOwlBytes,
        nightOwlPercentage = nightOwlPercentage,
        nightOwlFormatted = nightOwlFormatted
    )
}
