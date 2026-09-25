package com.vinnovateit.latch.ui.screens.stats.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.model.AggregatedDayRecord
import com.vinnovateit.latch.core.model.PortalSessionRecord
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.formatDurationDynamic
import com.vinnovateit.latch.ui.components.LatchIcons

fun groupedItemShape(index: Int, totalCount: Int, cornerRadius: Dp = 24.dp, innerRadius: Dp = 4.dp): Shape {
    return when {
        totalCount <= 1 -> RoundedCornerShape(cornerRadius)
        index == 0 -> RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = innerRadius, bottomEnd = innerRadius)
        index == totalCount - 1 -> RoundedCornerShape(topStart = innerRadius, topEnd = innerRadius, bottomStart = cornerRadius, bottomEnd = cornerRadius)
        else -> RoundedCornerShape(innerRadius)
    }
}

@Composable
fun TodaySessionListItem(
    session: PortalSessionRecord,
    shape: Shape = RoundedCornerShape(16.dp),
    isAmoled: Boolean = false,
    dlColor: Color = MaterialTheme.colorScheme.primary,
    ulColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (isAmoled) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        val totalFormatted = remember(session.totalBytes, session.uploadBytes, session.downloadBytes) {
            val effectiveTotal = if (session.totalBytes > 0) session.totalBytes else (session.uploadBytes + session.downloadBytes)
            formatBytes(effectiveTotal)
        }
        val dlFormatted = remember(session.downloadBytes) { formatBytes(session.downloadBytes) }
        val ulFormatted = remember(session.uploadBytes) { formatBytes(session.uploadBytes) }
        val durationStr = remember(session.durationFormatted, session.durationMillis) {
            session.durationFormatted.ifBlank { formatDurationDynamic(session.durationMillis) }
        }

        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session.location.ifBlank { "Session" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (session.isManual) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                        ) {
                            Text(
                                text = "Manual",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (session.loginTime > 0) formatDate(session.loginTime, "hh:mm a") else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            supportingContent = {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LatchIcons.ArrowDownward, null, tint = dlColor, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("${dlFormatted.first} ${dlFormatted.second}", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LatchIcons.ArrowUpward, null, tint = ulColor, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("${ulFormatted.first} ${ulFormatted.second}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (durationStr.isNotBlank()) {
                        Text(durationStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            },
            trailingContent = {
                Text(
                    text = "${totalFormatted.first} ${totalFormatted.second}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun DayAggregateListItem(
    record: AggregatedDayRecord,
    shape: Shape = RoundedCornerShape(16.dp),
    isAmoled: Boolean = false,
    dlColor: Color = MaterialTheme.colorScheme.primary,
    ulColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        ListItem(
            headlineContent = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = record.dateFormatted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (record.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    if (record.durationFormatted.isNotBlank()) {
                        Text(
                            text = record.durationFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            },
            supportingContent = {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LatchIcons.ArrowDownward, null, tint = dlColor, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            "${record.downloadFormatted.first} ${record.downloadFormatted.second}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(LatchIcons.ArrowUpward, null, tint = ulColor, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            "${record.uploadFormatted.first} ${record.uploadFormatted.second}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = record.totalFormatted.first,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = record.totalFormatted.second,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
