package com.vinnovateit.latch.features.home.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.ui.theme.ModernizFontFamily

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
      .fillMaxSize()
      .clickable { onNavigateToStats() },
    shape = RoundedCornerShape(28.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border = if (isAmoledTheme) androidx.compose.foundation.BorderStroke(4.dp, MaterialTheme.colorScheme.primary) else null,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
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
        }
        val latestUsage = session?.history?.lastOrNull()?.usage
        val (downloadBps, uploadBps) = if (latestUsage != null) {
            latestUsage.rxBps to latestUsage.txBps
        } else {
            0L to 0L
        }

        val isDownloadDominant = downloadBps >= uploadBps
        val dominatingBps = if (isDownloadDominant) downloadBps else uploadBps
        val icon = if (isDownloadDominant) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward
        val iconColor = if (isDownloadDominant) com.vinnovateit.latch.ui.theme.ColorGraphDownload else com.vinnovateit.latch.ui.theme.ColorGraphUpload
        val (value, unit) = com.vinnovateit.latch.core.stats.formatBitsPerSecond(dominatingBps, speedUnit)

        androidx.compose.animation.AnimatedVisibility(
            visible = dominatingBps > 0L,
            enter = fadeIn(),
            exit = fadeOut()
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
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge.copy(
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
                      tint = MaterialTheme.colorScheme.error,
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