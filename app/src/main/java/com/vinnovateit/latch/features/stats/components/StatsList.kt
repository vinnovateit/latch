package com.vinnovateit.latch.features.stats.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.model.LiveConnectionStatus
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.features.stats.StatsViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsList(
  modifier: Modifier = Modifier,
  isLive: Boolean,
  showSessionCard: Boolean = true,
  sessionToShow: SessionSummary?,
  historyToShow: List<SessionSummary>,
  liveStatus: LiveConnectionStatus?,
  speedUnits: String,
  showAllSessions: Boolean,
  onToggleShowAll: () -> Unit,
  addSpacer: Boolean = false,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  statsViewModel: StatsViewModel
) {
  val itemsToDisplay = if (showAllSessions) historyToShow else historyToShow.take(5)
  val layoutDirection = LocalLayoutDirection.current

  LazyColumn(
    modifier = modifier,
    contentPadding = PaddingValues(
      top = contentPadding.calculateTopPadding(),
      bottom = contentPadding.calculateBottomPadding() + 100.dp,
      start = contentPadding.calculateStartPadding(layoutDirection),
      end = contentPadding.calculateEndPadding(layoutDirection)
    ),
    verticalArrangement = Arrangement.spacedBy(2.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    if(addSpacer){
      item {
        Spacer(modifier = Modifier.height(20.dp))
      }
    }
    if (isLive && sessionToShow != null) {
      if (showSessionCard) {
        item {
          Box(Modifier.height(250.dp)) {
            SessionCard(
              session = sessionToShow,
              speedUnit = speedUnits
            )
          }
          Spacer(modifier = Modifier.height(15.dp))
        }
      }

      item {
        val chartItems by statsViewModel.chartItems.collectAsStateWithLifecycle()
        HistoryBarChart(history = chartItems)
        Spacer(modifier = Modifier.height(15.dp))
      }
    } else {

      item {
        val chartItems by statsViewModel.chartItems.collectAsStateWithLifecycle()
        HistoryBarChart(history = chartItems)
        Spacer(modifier = Modifier.height(15.dp))
      }
    }

    if (itemsToDisplay.isNotEmpty()) {
      item {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
          Text(
            stringResource(R.string.stats_sessions),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Left,
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
          )
        }
      }
      itemsIndexed(itemsToDisplay, key = { _, session -> session.startTimestamp }) { index, session ->
        val cornerRadius = 24.dp
        val listSize = itemsToDisplay.size
        val shape = when {
          listSize == 1 -> RoundedCornerShape(cornerRadius)
          index == 0 -> RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = 5.dp, bottomEnd = 5.dp)
          index == listSize - 1 -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = cornerRadius, bottomEnd = cornerRadius)
          else -> RoundedCornerShape(5.dp)
        }

        var itemVisible by remember { mutableStateOf(index < 5) }
        LaunchedEffect(showAllSessions) {
          if (showAllSessions) {
            itemVisible = true
          }
        }

        val alpha by animateFloatAsState(
          targetValue = if (itemVisible) 1f else 0f,
          animationSpec = tween(
            durationMillis = 300,
            delayMillis = if (index >= 5) (index - 4) * 70 else 0
          ),
          label = "alphaAnim"
        )

        Box(modifier = Modifier.graphicsLayer { this.alpha = alpha }.padding(start = 16.dp, end = 16.dp)) {
          StatsItemCard(session = session, shape = shape)
        }
      }
      if (historyToShow.size > 5 && !showAllSessions) {
        item {
          Button(
            onClick = onToggleShowAll,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            shape = RoundedCornerShape(24.dp)
          ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.stats_view_more), fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }
  }
}