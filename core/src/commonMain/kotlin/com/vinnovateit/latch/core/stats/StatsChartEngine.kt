package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.HistoryChartItem
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.mp.KSimpleDateFormat as SimpleDateFormat
import com.vinnovateit.latch.core.mp.KDate as Date
import com.vinnovateit.latch.core.mp.KCalendar as Calendar
import com.vinnovateit.latch.core.mp.KLocale as Locale
import com.vinnovateit.latch.core.mp.KThreadLocal
import com.vinnovateit.latch.core.mp.nowMillis

private val dayKeyFormat = KThreadLocal { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
private val monthKeyFormat = KThreadLocal { SimpleDateFormat("yyyy-MM", Locale.US) }
private val monthNameFormat = KThreadLocal { SimpleDateFormat("MMM", Locale.US) }
private val monthYearNameFormat = KThreadLocal { SimpleDateFormat("MMM yyyy", Locale.US) }

/**
 * Pure engine for generating all-time history chart items with monthly separators,
 * collapsed non-activity months, and daily bar buckets.
 */
fun computeChartItems(
    records: List<PortalSessionRecord>,
    liveRxBytes: Long = 0L,
    liveTxBytes: Long = 0L,
    nowMillis: Long = nowMillis()
): List<HistoryChartItem> {
    val nonZero = records.filter { it.uploadBytes > 0 || it.downloadBytes > 0 }
    val groupedByDay = mutableMapOf<String, DataUsage>()
    val recordsByDay = mutableMapOf<String, MutableList<PortalSessionRecord>>()

    val dayKeyFmt = dayKeyFormat.get() ?: SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val monthKeyFmt = monthKeyFormat.get() ?: SimpleDateFormat("yyyy-MM", Locale.US)
    val monthNameFmt = monthNameFormat.get() ?: SimpleDateFormat("MMM", Locale.US)
    val monthYearNameFmt = monthYearNameFormat.get() ?: SimpleDateFormat("MMM yyyy", Locale.US)

    for (record in nonZero) {
        if (record.loginTime <= 0) continue
        val dayKey = dayKeyFmt.format(Date(record.loginTime))
        val current = groupedByDay.getOrPut(dayKey) { DataUsage(0, 0) }
        groupedByDay[dayKey] = DataUsage(
            rxBytes = current.rxBytes + record.downloadBytes,
            txBytes = current.txBytes + record.uploadBytes
        )
        recordsByDay.getOrPut(dayKey) { mutableListOf() }.add(record)
    }

    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val todayKey = dayKeyFmt.format(Date(nowMillis))
    if (liveRxBytes > 0L || liveTxBytes > 0L) {
        val existing = groupedByDay.getOrPut(todayKey) { DataUsage(0, 0) }
        groupedByDay[todayKey] = DataUsage(
            rxBytes = existing.rxBytes + liveRxBytes,
            txBytes = existing.txBytes + liveTxBytes
        )
    }

    val startCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val endCal = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }

    val validRecords = nonZero.filter { it.loginTime > 0 }
    val earliest = validRecords.minOfOrNull { it.loginTime } ?: (nowMillis - 30L * 86400000L)
    val minAllowed = Calendar.getInstance().apply { set(2020, Calendar.JANUARY, 1) }.timeInMillis
    // Assigning timeInMillis discards the midnight normalisation above and
    // carries the earliest record's time of day into the cursor. Left as-is, the
    // final iteration overshoots `now` for the rest of the day and today's bar,
    // the one holding the live bytes, never gets emitted.
    startCal.timeInMillis = maxOf(earliest, minAllowed)
    startCal.set(Calendar.DAY_OF_MONTH, 1)
    startCal.set(Calendar.HOUR_OF_DAY, 0)
    startCal.set(Calendar.MINUTE, 0)
    startCal.set(Calendar.SECOND, 0)
    startCal.set(Calendar.MILLISECOND, 0)

    val monthsWithData = mutableSetOf<String>()
    groupedByDay.forEach { (dayKey, usage) ->
        if (usage.rxBytes + usage.txBytes > 0L && dayKey.length >= 7) {
            monthsWithData.add(dayKey.substring(0, 7))
        }
    }
    if (todayKey.length >= 7) {
        monthsWithData.add(todayKey.substring(0, 7))
    }

    val currentYear = now.get(Calendar.YEAR)
    val items = mutableListOf<HistoryChartItem>()
    val maxEnd = if (endCal.after(now)) now else endCal
    val cursor = startCal.clone()

    while (!cursor.after(maxEnd)) {
        val cursorDate = Date(cursor.timeInMillis)
        val monthKey = monthKeyFmt.format(cursorDate)
        val itemYear = cursor.get(Calendar.YEAR)
        val monthName = if (itemYear == currentYear) monthNameFmt.format(cursorDate) else monthYearNameFmt.format(cursorDate)

        if (!monthsWithData.contains(monthKey)) {
            items.add(HistoryChartItem.CollapsedMonth(monthName, cursor.timeInMillis))
            cursor.add(Calendar.MONTH, 1)
            cursor.set(Calendar.DAY_OF_MONTH, 1)
            continue
        }

        items.add(HistoryChartItem.MonthSeparator(monthName))
        val currentMonthInt = cursor.get(Calendar.MONTH)
        while (!cursor.after(maxEnd) && cursor.get(Calendar.MONTH) == currentMonthInt) {
            val dayTimestamp = cursor.timeInMillis
            val dayDate = Date(dayTimestamp)
            val key = dayKeyFmt.format(dayDate)
            val usage = groupedByDay[key] ?: DataUsage(0, 0)
            val label = cursor.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
            val dayRecords = recordsByDay[key] ?: emptyList()
            val sessionCount = dayRecords.size
            val durationMillis = dayRecords.sumOf { it.durationMillis }
            val durationFormatted = formatDurationDynamic(durationMillis)

            items.add(
                HistoryChartItem.BarData(
                    usage = usage,
                    label = label,
                    timestamp = dayTimestamp,
                    formattedDate = "",
                    sessionCount = sessionCount,
                    durationMillis = durationMillis,
                    durationFormatted = durationFormatted
                )
            )
            cursor.add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    return items
}
