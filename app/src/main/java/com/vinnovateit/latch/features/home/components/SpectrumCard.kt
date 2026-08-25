package com.vinnovateit.latch.features.home.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.R
import com.vinnovateit.latch.domain.model.LiveDataPoint
import com.vinnovateit.latch.domain.model.SessionSummary
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.ui.theme.ModernizFontFamily
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SpectrumCard(
  session: SessionSummary?,
  historyForHomeScreen: List<LiveDataPoint>,
  connectionStatus: ConnectionStatus,
  speedUnit: String,
  isLandscape: Boolean,
  onNavigateToStats: () -> Unit = {},
) {
  val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
  val isDarkTheme = com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current
  val isAmoledTheme = usePureBlack && isDarkTheme

  val topPadding = if (isLandscape) 0.dp else 125.dp
  Card(
    modifier = Modifier
      .padding(top = topPadding)
      .padding(horizontal = 24.dp)
      .padding(bottom = 24.dp)
      .fillMaxSize(),
    shape = RoundedCornerShape(28.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border = if (isAmoledTheme) androidx.compose.foundation.BorderStroke(4.dp, MaterialTheme.colorScheme.primary) else null,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onNavigateToStats() }
          .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.Top) {
          Text(
            text = stringResource(id = R.string.home_network_statistics),
            fontFamily = ModernizFontFamily,
            color = MaterialTheme.colorScheme.primary,
          )
          Icon(
            imageVector = Icons.Rounded.ArrowOutward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp, top = 1.dp).size(14.dp)
          )
        }
        val latestUsage = session?.history?.lastOrNull()?.usage
        val (downloadBps, uploadBps) = if (latestUsage != null) {
            latestUsage.rxBps to latestUsage.txBps
        } else {
            0L to 0L
        }

        val isDownloadDominant = downloadBps >= uploadBps
        val dominatingBps = if (isDownloadDominant) downloadBps else uploadBps
        val icon = if (isDownloadDominant) androidx.compose.material.icons.Icons.Rounded.ArrowDownward else androidx.compose.material.icons.Icons.Rounded.ArrowUpward
        val iconColor = if (isDownloadDominant) com.vinnovateit.latch.ui.theme.ColorGraphDownload else com.vinnovateit.latch.ui.theme.ColorGraphUpload
        val (value, unit) = com.vinnovateit.latch.common.util.formatBitsPerSecond(dominatingBps, speedUnit)

        androidx.compose.animation.AnimatedVisibility(
            visible = dominatingBps > 0L,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp)
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    com.vinnovateit.latch.features.stats.components.RollingNumberText(
                        value = value,
                        textStyle = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Text(
                        text = " $unit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
        }
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentAlignment = Alignment.Center
      ) {
        val showGraph = connectionStatus is ConnectionStatus.Idle && session?.history?.isNotEmpty() == true

        AnimatedContent(
          modifier = Modifier.fillMaxSize(),
          targetState = showGraph,
          transitionSpec = { fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500)) },
          label = "GraphVsStatus"
        ) { isGraphVisible ->
          if (isGraphVisible) {
            HomeScreenGraph(
              modifier = Modifier.fillMaxSize(),
              rateHistory = historyForHomeScreen,
              speedUnit = speedUnit
            )
          } else {
            StatusIndicator(connectionStatus = connectionStatus)
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class)
@Composable
private fun StatusIndicator(connectionStatus: ConnectionStatus) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier
        .padding(16.dp)
        .animateContentSize()
    ) {
      AnimatedVisibility(
        visible = connectionStatus !is ConnectionStatus.Idle,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          AnimatedContent(
            targetState = connectionStatus,
            transitionSpec = { 
                (fadeIn(animationSpec = tween(300)) + scaleIn(initialScale = 0.8f, animationSpec = tween(300))) togetherWith (fadeOut(animationSpec = tween(300)) + scaleOut(targetScale = 0.8f, animationSpec = tween(300)))
            },
            label = "IconAnimation"
          ) { status ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp)) {
              when (status) {
                is ConnectionStatus.Companion.Connecting -> LoadingIndicator(
                  modifier = Modifier
                    .size(92.dp)
                    .graphicsLayer { alpha = 0.35f }
                )
                is ConnectionStatus.Success -> Icon(
                  imageVector = Icons.Rounded.Wifi,
                  contentDescription = stringResource(R.string.status_connected),
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(64.dp)
                )
                is ConnectionStatus.Failed -> {
                    val isUnsupported = status.message.contains(stringResource(R.string.status_unsupported_network), ignoreCase = true)
                    Icon(
                      imageVector = if (isUnsupported) Icons.Rounded.QuestionMark else Icons.Rounded.Error,
                      contentDescription = stringResource(R.string.status_login_failed),
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(64.dp)
                    )
                }
                else -> {}
              }
            }
          }
          Spacer(modifier = Modifier.height(12.dp))
        }
      }

      AnimatedContent(
        targetState = connectionStatus,
        transitionSpec = {
          (fadeIn(tween(300)) + scaleIn(initialScale = 0.9f, animationSpec = tween(300))) togetherWith (fadeOut(tween(300)) + scaleOut(targetScale = 0.9f, animationSpec = tween(300)))
        },
        label = "TextAnimation"
      ) { status ->
        Text(
          text = when (status) {
            is ConnectionStatus.Idle -> stringResource(R.string.home_no_data_for_graph)
            is ConnectionStatus.Companion.Connecting -> status.message.replace(".", "")
            is ConnectionStatus.Success -> stringResource(R.string.status_connected).replace(".", "")
            is ConnectionStatus.Failed -> status.message.replace(".", "")
          },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 14.sp,
          fontWeight = FontWeight.Medium,
          textAlign = TextAlign.Center
        )
      }
    }
}