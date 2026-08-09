package com.vinnovateit.latch.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatBitsPerSecond
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.core.stats.formatClockTime
import com.vinnovateit.latch.core.stats.formatDate
import com.vinnovateit.latch.core.stats.formatDurationDynamic
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.stats_empty_message
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_cancel
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_confirm
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_message
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_title
import com.vinnovateit.latch.desktop.resources.stats_sessions
import com.vinnovateit.latch.desktop.resources.stats_title
import com.vinnovateit.latch.desktop.resources.stats_total_data_usage
import com.vinnovateit.latch.ui.components.DataUsageDonut
import com.vinnovateit.latch.ui.components.LatchDetailHeader
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.components.SettingsActionDialog
import com.vinnovateit.latch.ui.theme.ColorGraphDownload
import com.vinnovateit.latch.ui.theme.ColorGraphUpload
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Session history, and the live session while one is running.
 *
 * The Android Stats screen owns the same data. What is not carried over is its
 * pinch-zoomable per-session rate graph: the desktop database stores only the
 * session totals (SessionRepository persists rx/tx and the two peaks, not the
 * per-second history), so there is no curve to draw for a finished session and a
 * chart that is always empty is worse than no chart.
 */
@Composable
fun StatsScreen(
    sessions: SessionRepository,
    onBack: (() -> Unit)?,
    onClearHistory: () -> Unit,
) {
    val liveStatus by sessions.liveStatus.collectAsStateWithLifecycle()
    val summaries by sessions.sessionSummaries.collectAsStateWithLifecycle()
    val speedUnit by SettingsManager.speedUnits.collectAsStateWithLifecycle()
    var showClearConfirmation by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        LatchDetailHeader(title = stringResource(Res.string.stats_title), onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            liveStatus?.let { live ->
                item {
                    // Whole-session totals come from the running aggregates;
                    // liveData only holds the recent window (see
                    // LiveConnectionStatus).
                    val usage = DataUsage(live.totalRxBytes, live.totalTxBytes)
                    LiveSessionCard(
                        startTimeMillis = live.startTimeMillis,
                        usage = usage,
                        latestRxBps = live.liveData.lastOrNull()?.usage?.rxBps ?: 0L,
                        latestTxBps = live.liveData.lastOrNull()?.usage?.txBps ?: 0L,
                        speedUnit = speedUnit,
                    )
                }
            }

            item {
                TotalsRow(summaries = summaries)
            }

            if (summaries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.stats_empty_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "History",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Clear",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showClearConfirmation = true }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                items(summaries) { session ->
                    SessionRow(session = session)
                }
            }
        }
    }

    if (showClearConfirmation) {
        SettingsActionDialog(
            title = stringResource(Res.string.stats_reset_dialog_title),
            description = stringResource(Res.string.stats_reset_dialog_message),
            confirmText = stringResource(Res.string.stats_reset_dialog_confirm),
            cancelText = stringResource(Res.string.stats_reset_dialog_cancel),
            onConfirm = {
                onClearHistory()
                showClearConfirmation = false
            },
            onDismiss = { showClearConfirmation = false },
        )
    }
}

@Composable
private fun LiveSessionCard(
    startTimeMillis: Long,
    usage: DataUsage,
    latestRxBps: Long,
    latestTxBps: Long,
    speedUnit: String,
) {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current

    var duration by remember(startTimeMillis) {
        mutableLongStateOf(System.currentTimeMillis() - startTimeMillis)
    }
    LaunchedEffect(startTimeMillis) {
        while (true) {
            duration = System.currentTimeMillis() - startTimeMillis
            delay(1000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        border = if (isAmoled) {
            BorderStroke(4.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DataUsageDonut(
                data = usage,
                modifier = Modifier.size(108.dp),
                isAmoled = isAmoled,
            )
            Spacer(Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Current session",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatDurationDynamic(duration)} • since " +
                        formatClockTime(startTimeMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                RateReadout(
                    downloadBps = latestRxBps,
                    uploadBps = latestTxBps,
                    speedUnit = speedUnit,
                )
            }
        }
    }
}

@Composable
private fun RateReadout(downloadBps: Long, uploadBps: Long, speedUnit: String) {
    val (rxValue, rxUnit) = formatBitsPerSecond(downloadBps, speedUnit)
    val (txValue, txUnit) = formatBitsPerSecond(uploadBps, speedUnit)

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        RateChip(LatchIcons.ArrowDownward, "$rxValue $rxUnit", ColorGraphDownload)
        RateChip(LatchIcons.ArrowUpward, "$txValue $txUnit", ColorGraphUpload)
    }
}

@Composable
private fun RateChip(icon: ImageVector, text: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TotalsRow(summaries: List<SessionSummary>) {
    val totalBytes = summaries.sumOf { it.totalData.rxBytes + it.totalData.txBytes }
    val (totalValue, totalUnit) = formatBytes(totalBytes)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TotalTile(
            label = stringResource(Res.string.stats_total_data_usage),
            value = "$totalValue $totalUnit",
            modifier = Modifier.weight(1f),
        )
        TotalTile(
            label = stringResource(Res.string.stats_sessions),
            value = summaries.size.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TotalTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** One finished session, styled after the Android StatsItemCard. */
@Composable
private fun SessionRow(session: SessionSummary) {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (isAmoled) {
            BorderStroke(4.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatDate(session.startTimestamp, "E, dd MMM"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatClockTime(session.startTimestamp) + " • " +
                        formatDurationDynamic(session.endTimestamp - session.startTimestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val (total, totalUnit) = formatBytes(
                    session.totalData.rxBytes + session.totalData.txBytes,
                )
                Text(
                    text = "$total $totalUnit",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    val (dl, dlUnit) = formatBytes(session.totalData.rxBytes)
                    Icon(
                        LatchIcons.ArrowDownward,
                        contentDescription = null,
                        tint = ColorGraphDownload,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "$dl $dlUnit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    val (ul, ulUnit) = formatBytes(session.totalData.txBytes)
                    Icon(
                        LatchIcons.ArrowUpward,
                        contentDescription = null,
                        tint = ColorGraphUpload,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "$ul $ulUnit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
