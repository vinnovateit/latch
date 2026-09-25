package com.vinnovateit.latch.core.stats

import com.vinnovateit.latch.core.model.PortalSessionRecord
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsInsightsEngineTest {

    @Test
    fun testFormatInsightDateOmitsWeekdayAndCurrentYear() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 8, 12, 0, 0)
        }
        val fixedNow = cal.timeInMillis

        // Same year (2026) -> "8 Mar"
        val formattedSameYear = formatInsightDate(fixedNow, fixedNow)
        assertEquals("8 Mar", formattedSameYear)

        // Previous year (2025) -> "8 Mar 2025"
        cal.set(2025, Calendar.MARCH, 8, 12, 0, 0)
        val formattedOlderYear = formatInsightDate(cal.timeInMillis, fixedNow)
        assertEquals("8 Mar 2025", formattedOlderYear)
    }

    @Test
    fun testFormatDisplayDateOmitsWeekdayAndCurrentYear() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 8, 12, 0, 0)
        }
        val fixedNow = cal.timeInMillis

        // Same year (2026) -> "8 Mar"
        val formattedSameYear = formatDisplayDate(fixedNow, fixedNow)
        assertEquals("8 Mar", formattedSameYear)

        // Previous year (2025) -> "8 Mar 2025"
        cal.set(2025, Calendar.MARCH, 8, 12, 0, 0)
        val formattedOlderYear = formatDisplayDate(cal.timeInMillis, fixedNow)
        assertEquals("8 Mar 2025", formattedOlderYear)
    }

    @Test
    fun testComputeStatsInsightsCalculatesAccurateMetrics() {
        val cal = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 1, 14, 0, 0) }
        val session1 = PortalSessionRecord(
            location = "Hostel",
            macAddress = "AA:BB:CC",
            loginTime = cal.timeInMillis,
            logoutTime = cal.timeInMillis + 3600_000L,
            durationFormatted = "01:00:00",
            durationMillis = 3600_000L,
            uploadBytes = 500_000_000L,
            downloadBytes = 1_500_000_000L,
            totalBytes = 2_000_000_000L,
        )

        cal.set(2026, Calendar.MARCH, 2, 20, 0, 0)
        val session2 = PortalSessionRecord(
            location = "Hostel",
            macAddress = "AA:BB:CC",
            loginTime = cal.timeInMillis,
            logoutTime = cal.timeInMillis + 7200_000L,
            durationFormatted = "02:00:00",
            durationMillis = 7200_000L,
            uploadBytes = 1_000_000_000L,
            downloadBytes = 4_000_000_000L,
            totalBytes = 5_000_000_000L,
        )

        val insights = computeStatsInsights(listOf(session1, session2), cal.timeInMillis)
        assertEquals("2 Mar", insights.highestUsageDayDate)
        assertEquals(5_000_000_000L, insights.highestUsageDayBytes)
        assertEquals(2, insights.activeDaysCount)
        assertEquals(3_500_000_000L, insights.dailyAverageBytes) // (2GB + 5GB)/2
        assertEquals("6 - 9 PM", insights.peakUsageTimeWindow)
    }

    @Test
    fun testEmptySessionsProducesZeroStatsInsights() {
        val insights = computeStatsInsights(emptyList<PortalSessionRecord>())
        assertEquals("N/A", insights.peakUsageTimeWindow)
        assertEquals("0 B", insights.highestUsageDayFormatted)
        assertEquals("N/A", insights.highestUsageDayDate)
        assertEquals(0L, insights.highestUsageDayBytes)
        assertEquals(0L, insights.dailyAverageBytes)
        assertEquals("0" to "B", insights.dailyAverageFormatted)
        assertEquals("0m", insights.mostActiveSessionDurationFormatted)
        assertEquals(0L, insights.mostActiveSessionBytes)
        assertEquals("0" to "B", insights.mostActiveSessionFormatted)
        assertEquals(0, insights.activeDaysCount)
        assertEquals(0, insights.currentStreakDays)
        assertEquals(0, insights.longestStreakDays)
        assertEquals(0, insights.nightOwlPercentage)
    }

    @Test
    fun testStreakAndNightOwl() {
        // Session 1: 2026-03-01 02:30 (Night owl: 2 AM), 3 GB DL, 1 GB UL
        val cal = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 1, 2, 30, 0) }
        val session1 = PortalSessionRecord(
            location = "Hostel",
            macAddress = "AA:BB",
            loginTime = cal.timeInMillis,
            logoutTime = cal.timeInMillis + 3600_000L,
            durationFormatted = "01:00:00",
            durationMillis = 3600_000L,
            uploadBytes = 1_000_000_000L,
            downloadBytes = 3_000_000_000L,
            totalBytes = 4_000_000_000L,
        )

        // Session 2: 2026-03-02 14:00 (Day), 6 GB DL, 1 GB UL
        cal.set(2026, Calendar.MARCH, 2, 14, 0, 0)
        val session2 = PortalSessionRecord(
            location = "Hostel",
            macAddress = "AA:BB",
            loginTime = cal.timeInMillis,
            logoutTime = cal.timeInMillis + 3600_000L,
            durationFormatted = "01:00:00",
            durationMillis = 3600_000L,
            uploadBytes = 1_000_000_000L,
            downloadBytes = 6_000_000_000L,
            totalBytes = 7_000_000_000L,
        )

        // Session 3: 2026-03-03 01:30 (Night owl: 1 AM), 10 GB DL, 2 GB UL
        cal.set(2026, Calendar.MARCH, 3, 1, 30, 0)
        val session3 = PortalSessionRecord(
            location = "Hostel",
            macAddress = "AA:BB",
            loginTime = cal.timeInMillis,
            logoutTime = cal.timeInMillis + 3600_000L,
            durationFormatted = "01:00:00",
            durationMillis = 3600_000L,
            uploadBytes = 2_000_000_000L,
            downloadBytes = 10_000_000_000L,
            totalBytes = 12_000_000_000L,
        )

        // Test with now = 2026-03-03 12:00:00
        cal.set(2026, Calendar.MARCH, 3, 12, 0, 0)
        val insights = computeStatsInsights(listOf(session1, session2, session3), cal.timeInMillis)

        assertEquals(3, insights.activeDaysCount)
        assertEquals(3, insights.currentStreakDays)
        assertEquals(3, insights.longestStreakDays)
        assertEquals(16_000_000_000L, insights.nightOwlBytes) // 4GB + 12GB
        assertEquals(69, insights.nightOwlPercentage) // 16GB / 23GB = 69%
    }
}
