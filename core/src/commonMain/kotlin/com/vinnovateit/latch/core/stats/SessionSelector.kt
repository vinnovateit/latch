package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.PortalSessionRecord

/**
 * The one place that decides which portal sessions count as real traffic and
 * which of those belong to a selected day. Android and desktop used to each
 * carry a copy of these predicates; both now call through here so a rule change
 * reaches every surface at once.
 */

/** Day key shared by every "which sessions belong to this day" decision. */
fun dayKey(timestamp: Long): String = formatDate(timestamp, "yyyy-MM-dd")

/** Records with at least one byte of traffic, in the order they arrived. */
fun nonZeroSessions(sessions: List<PortalSessionRecord>): List<PortalSessionRecord> =
    sessions.filter { it.uploadBytes > 0L || it.downloadBytes > 0L }

/**
 * Sessions shown for a selected day. A record with no login timestamp or no
 * traffic never reaches the UI; matching is on the login day key.
 */
fun sessionsForDay(sessions: List<PortalSessionRecord>, dayKey: String): List<PortalSessionRecord> =
    sessions.filter {
        it.loginTime > 0 && (it.uploadBytes > 0L || it.downloadBytes > 0L) &&
            formatDate(it.loginTime, "yyyy-MM-dd") == dayKey
    }

/** Live chart keeps the newest [CHART_WINDOW_POINTS] samples. */
private const val CHART_WINDOW_POINTS = 150

fun <T> chartWindow(points: List<T>): List<T> = points.takeLast(CHART_WINDOW_POINTS)
