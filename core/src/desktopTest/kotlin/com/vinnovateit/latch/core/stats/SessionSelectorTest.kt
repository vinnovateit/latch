package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.model.PortalSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionSelectorTest {

    private fun record(login: Long, up: Long, down: Long) = PortalSessionRecord(
        location = "",
        macAddress = "",
        loginTime = login,
        logoutTime = login,
        durationFormatted = "",
        durationMillis = 0L,
        uploadBytes = up,
        downloadBytes = down,
        totalBytes = up + down,
    )

    @Test
    fun `sessionsForDay keeps only non-zero sessions logged on the requested day`() {
        val target = 1_700_000_000_000L
        val other = target + 86_400_000L * 3
        val wanted = record(target, 10L, 20L)
        val records = listOf(
            wanted,
            record(target, 0L, 0L), // zero traffic
            record(target, 0L, 5L), // wanted too
            record(0L, 10L, 20L), // no login time
            record(other, 10L, 20L), // different day
        )

        assertEquals(
            listOf(wanted, records[2]),
            sessionsForDay(records, dayKey(target)),
        )
    }

    @Test
    fun `nonZeroSessions drops records with no traffic`() {
        val a = record(1L, 1L, 0L)
        val b = record(2L, 0L, 0L)
        val c = record(3L, 0L, 1L)
        assertEquals(listOf(a, c), nonZeroSessions(listOf(a, b, c)))
    }

    @Test
    fun `chartWindow keeps only the newest 150 points`() {
        val points = (0 until 200).map { LiveDataPoint(it.toLong(), com.vinnovateit.latch.core.model.DataUsage(0, 0)) }
        val window = chartWindow(points)
        assertEquals(150, window.size)
        assertEquals(50L, window.first().timestamp)
        assertEquals(199L, window.last().timestamp)
    }
}
