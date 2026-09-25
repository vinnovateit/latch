package com.vinnovateit.latch.ui.screens.stats.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.stats.StatsInsights

@Composable
fun UsageInsightsCards(
    insights: StatsInsights,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        GameStatRow(
            label = "Daily average",
            value = insights.dailyAverageFormatted.first,
            unit = insights.dailyAverageFormatted.second,
        )
        GameStatRow(
            label = "Highest usage day",
            value = insights.highestUsageDayFormatted,
            sublabel = if (insights.highestUsageDayDate != "N/A") insights.highestUsageDayDate else "",
        )
        GameStatRow(
            label = "Peak usage window",
            value = insights.peakUsageTimeWindow,
        )
        if (insights.currentStreakDays > 0) {
            GameStatRow(
                label = "Active streak",
                value = "${insights.currentStreakDays}",
                unit = if (insights.currentStreakDays == 1) "day" else "days",
            )
        }
        GameStatRow(
            label = "Max streak",
            value = "${insights.longestStreakDays}",
            unit = if (insights.longestStreakDays == 1) "day" else "days",
        )
        GameStatRow(
            label = "Active days",
            value = "${insights.activeDaysCount}",
            unit = if (insights.activeDaysCount == 1) "day" else "days",
        )
        GameStatRow(
            label = "Longest session",
            value = insights.mostActiveSessionDurationFormatted,
        )
        if (insights.nightOwlPercentage >= 10) {
            GameStatRow(
                label = "Night owl traffic",
                value = insights.nightOwlFormatted.first,
                unit = insights.nightOwlFormatted.second,
                sublabel = "${insights.nightOwlPercentage}% after midnight (12 - 6 AM)",
            )
        }
    }
}
