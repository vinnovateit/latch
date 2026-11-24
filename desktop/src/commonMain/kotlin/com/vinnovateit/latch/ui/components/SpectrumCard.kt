package com.vinnovateit.latch.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.formatBitsPerSecond
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.home_network_statistics
import com.vinnovateit.latch.desktop.resources.home_no_data_for_graph
import com.vinnovateit.latch.ui.displayText
import com.vinnovateit.latch.ui.theme.ColorGraphDownload
import com.vinnovateit.latch.ui.theme.ColorGraphUpload
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import com.vinnovateit.latch.ui.theme.modernizFontFamily
import com.vinnovateit.latch.ui.theme.satoshiFontFamily
import org.jetbrains.compose.resources.stringResource

/**
 * The "STATS" card from the Android home screen: a 28dp-cornered surfaceContainer
 * card whose body cross-fades between the live throughput curve and whatever the
 * engine is currently doing.
 *
 * Kept faithful to the Android original, including the AMOLED primary border and
 * the dominant-direction speed readout in the header that fades in only once
 * there is actual traffic.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpectrumCard(
    history: List<LiveDataPoint>,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onNavigateToStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current

    Card(
        // Clip before clickable so the press ripple stays inside the rounded
        // card instead of spreading into the window corners.
        modifier = modifier.clip(RoundedCornerShape(28.dp)).clickable(onClick = onNavigateToStats),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (isAmoled) {
            BorderStroke(4.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpectrumHeader(
                history = history,
                speedUnit = speedUnit,
            )

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val showChart =
                    connectionStatus is ConnectionStatus.Idle && history.size > 1

                AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    targetState = showChart,
                    transitionSpec = {
                        fadeIn(tween(500)) togetherWith fadeOut(tween(500))
                    },
                    label = "ChartVsStatus",
                ) { chartVisible ->
                    if (chartVisible) {
                        ThroughputChart(
                            history = history,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        StatusIndicator(connectionStatus = connectionStatus)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpectrumHeader(
    history: List<LiveDataPoint>,
    speedUnit: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = stringResource(Res.string.home_network_statistics),
                fontFamily = modernizFontFamily(),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val latest = history.lastOrNull()?.usage
        val downloadBps = latest?.rxBps ?: 0L
        val uploadBps = latest?.txBps ?: 0L
        val downloadDominant = downloadBps >= uploadBps
        val dominantBps = if (downloadDominant) downloadBps else uploadBps
        val (value, unit) = formatBitsPerSecond(dominantBps, speedUnit)

        AnimatedVisibility(
            visible = dominantBps > 0L,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = if (downloadDominant) {
                        LatchIcons.ArrowDownward
                    } else {
                        LatchIcons.ArrowUpward
                    },
                    contentDescription = null,
                    tint = if (downloadDominant) ColorGraphDownload else ColorGraphUpload,
                    modifier = Modifier.size(16.dp),
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        fontFamily = satoshiFontFamily(FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = " $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusIndicator(connectionStatus: ConnectionStatus) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(16.dp),
    ) {
        AnimatedVisibility(
            visible = connectionStatus !is ConnectionStatus.Idle,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedContent(
                    targetState = connectionStatus,
                    transitionSpec = {
                        (fadeIn(tween(300)) + scaleIn(initialScale = 0.8f, animationSpec = tween(300)))
                            .togetherWith(
                                fadeOut(tween(300)) + scaleOut(targetScale = 0.8f, animationSpec = tween(300)),
                            )
                    },
                    label = "StatusIcon",
                ) { status ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(92.dp),
                    ) {
                        when (status) {
                            is ConnectionStatus.Connecting -> LoadingIndicator(
                                modifier = Modifier
                                    .size(92.dp)
                                    .graphicsLayer { alpha = 0.35f },
                            )

                            is ConnectionStatus.Success -> Icon(
                                imageVector = LatchIcons.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp),
                            )

                            is ConnectionStatus.Failed -> Icon(
                                imageVector = LatchIcons.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp),
                            )

                            is ConnectionStatus.Idle -> Unit
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        AnimatedContent(
            targetState = connectionStatus,
            transitionSpec = {
                (fadeIn(tween(300)) + scaleIn(initialScale = 0.9f, animationSpec = tween(300)))
                    .togetherWith(
                        fadeOut(tween(300)) + scaleOut(targetScale = 0.9f, animationSpec = tween(300)),
                    )
            },
            label = "StatusText",
        ) { status ->
            Text(
                text = if (status is ConnectionStatus.Idle) {
                    stringResource(Res.string.home_no_data_for_graph)
                } else {
                    status.displayText().replace(".", "")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                fontFamily = satoshiFontFamily(FontWeight.Medium),
                textAlign = TextAlign.Center,
            )
        }
    }
}
