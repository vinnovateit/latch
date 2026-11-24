package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.mp.KDate as Date
import com.vinnovateit.latch.core.mp.KLocale as Locale
import com.vinnovateit.latch.core.mp.KSimpleDateFormat as SimpleDateFormat
import com.vinnovateit.latch.core.mp.KThreadLocal

/**
 * The one place that decides which portal sessions count as real traffic and
 * which of those belong to a selected day. Android and desktop used to each
 * carry a copy of these predicates; both now call through here so a rule change
 * reaches every surface at once.
 */

/** Day key shared by every "which sessions belong to this day" decision. */
private val dayKeyFormat = KThreadLocal { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

fun dayKey(timestamp: Long): String =
    (dayKeyFormat.get() ?: SimpleDateFormat("yyyy-MM-dd", Locale.US)).format(Date(timestamp))

/** Records with at least one byte of traffic, in the order they arrived. */
fun nonZeroSessions(sessions: List<PortalSessionRecord>): List<PortalSessionRecord> =
    sessions.filter { it.uploadBytes > 0L || it.downloadBytes > 0L }

/**
 * Sessions shown for a selected day. A record with no login timestamp or no
 * traffic never reaches the UI; matching is on the login day key.
 */
fun sessionsForDay(sessions: List<PortalSessionRecord>, targetDayKey: String): List<PortalSessionRecord> =
    sessions.filter {
        it.loginTime > 0 && (it.uploadBytes > 0L || it.downloadBytes > 0L) &&
            dayKey(it.loginTime) == targetDayKey
    }

/** Live chart keeps the newest [CHART_WINDOW_POINTS] samples. */
private const val CHART_WINDOW_POINTS = 150

fun <T> chartWindow(points: List<T>): List<T> = points.takeLast(CHART_WINDOW_POINTS)
