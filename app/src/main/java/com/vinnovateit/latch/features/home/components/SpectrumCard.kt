package com.vinnovateit.latch.features.home.components

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.R
import com.vinnovateit.latch.domain.model.LiveDataPoint
import com.vinnovateit.latch.domain.model.SessionSummary
import com.vinnovateit.latch.features.stats.StatsActivity
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.ui.theme.ModernizFontFamily

@Composable
fun SpectrumCard(
  session: SessionSummary?,
  historyForHomeScreen: List<LiveDataPoint>,
  connectionStatus: ConnectionStatus,
  speedUnit: String,
  isLandscape: Boolean,
) {
  val context = LocalContext.current
  val topPadding = if (isLandscape) 0.dp else 105.dp
  Card(
    modifier = Modifier
      .padding(top = topPadding)
      .padding(horizontal = 24.dp, vertical = 24.dp)
      .fillMaxSize(),
    shape = RoundedCornerShape(28.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable {
            val intent = Intent(context, StatsActivity::class.java)
            (context as? Activity)?.startActivity(intent)
          }
          .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
      ) {
        Text(
          text = stringResource(id = R.string.home_network_statistics),
          fontFamily = ModernizFontFamily,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f)
        )
        Icon(
          imageVector = Icons.Rounded.ArrowOutward,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(end = 4.dp, top = 0.dp)
        )
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentAlignment = Alignment.Center
      ) {
        val showGraph = connectionStatus is ConnectionStatus.Idle && session?.history?.isNotEmpty() == true

        AnimatedContent(
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
  AnimatedContent(
    targetState = connectionStatus,
    transitionSpec = {
      (slideInVertically { height -> height } + fadeIn()) togetherWith (slideOutVertically { height -> -height } + fadeOut())
    },
    label = "StatusIndicatorAnimation"
  ) { status ->
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.padding(16.dp)
    ) {
      val iconVisible = status !is ConnectionStatus.Idle
      val textOffsetY by animateDpAsState(targetValue = if (iconVisible) 12.dp else 0.dp, label = "textOffset")

      AnimatedVisibility(visible = iconVisible, enter = fadeIn(), exit = fadeOut()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(92.dp)) {
          when (status) {
            is ConnectionStatus.Companion.Connecting -> LoadingIndicator(
              modifier = Modifier
                .size(92.dp)
                .graphicsLayer { alpha = 0.35f }
            )
            is ConnectionStatus.Success -> Icon(
              imageVector = Icons.Rounded.Check,
              contentDescription = stringResource(R.string.status_connected),
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(64.dp)
            )
            is ConnectionStatus.Failed -> Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = stringResource(R.string.status_login_failed),
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(64.dp)
            )
            else -> {}
          }
        }
      }

      Text(
        text = when (status) {
          is ConnectionStatus.Idle -> stringResource(R.string.home_no_data_for_graph)
          is ConnectionStatus.Companion.Connecting -> status.message
          is ConnectionStatus.Success -> stringResource(R.string.status_connected)
          is ConnectionStatus.Failed -> status.message
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.offset(y = textOffsetY)
      )
    }
  }
}