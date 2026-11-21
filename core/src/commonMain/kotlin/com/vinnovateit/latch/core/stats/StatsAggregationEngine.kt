package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.AggregatedDayRecord
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.mp.KSimpleDateFormat as SimpleDateFormat
import com.vinnovateit.latch.core.mp.KDate as Date
import com.vinnovateit.latch.core.mp.KCalendar as Calendar
import com.vinnovateit.latch.core.mp.KLocale as Locale
import com.vinnovateit.latch.core.mp.KThreadLocal
import com.vinnovateit.latch.core.mp.nowMillis

private val dayKeyFormat = KThreadLocal { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

/**
 * Aggregates individual [PortalSessionRecord] items into daily summary buckets [AggregatedDayRecord].
 * Thread-safe using ThreadLocal formatters.
 */
fun aggregateDays(
    sessions: List<PortalSessionRecord>,
    nowMillis: Long = nowMillis(),
): List<AggregatedDayRecord> {
    val dayKeyFmt = dayKeyFormat.get() ?: SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val todayKey = dayKeyFmt.format(Date(nowMillis))

    return sessions
        .filter { it.loginTime > 0 }
        .groupBy { dayKeyFmt.format(Date(it.loginTime)) }
        .map { (dateKey, daySessions) ->
            val first = daySessions.first()
            val dl = daySessions.sumOf { it.downloadBytes }
            val ul = daySessions.sumOf { it.uploadBytes }
            val total = daySessions.sumOf { it.totalBytes.coerceAtLeast(it.downloadBytes + it.uploadBytes) }
            val totalDur = daySessions.sumOf { it.durationMillis }
            val isToday = dateKey == todayKey
            val dispDate = formatDisplayDate(first.loginTime, nowMillis)
            AggregatedDayRecord(
                dayTimestamp = first.loginTime,
                dateFormatted = if (isToday) "Today, $dispDate" else dispDate,
                downloadBytes = dl,
                uploadBytes = ul,
                totalBytes = total,
                downloadFormatted = formatBytes(dl),
                uploadFormatted = formatBytes(ul),
                totalFormatted = formatBytes(total),
                sessionCount = daySessions.size,
                totalDurationMillis = totalDur,
                durationFormatted = formatDurationDynamic(totalDur),
                isToday = isToday,
            )
        }
        .sortedByDescending { it.dayTimestamp }
}
