package com.vinnovateit.latch.features.stats

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vinnovateit.latch.common.ui.components.ExpressiveTopBarContent
import com.vinnovateit.latch.common.util.generateHtmlReport
import com.vinnovateit.latch.common.util.TooltipHint
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.stats.components.SessionCard
import com.vinnovateit.latch.features.stats.components.StatsList
import com.vinnovateit.latch.ui.theme.LatchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



@Composable
private fun StatsTopBar(
  collapseFraction: Float,
  headerHeight: Dp,
  onBackPressed: () -> Unit,
  onSaveReport: () -> Unit
) {
  val surfaceColor = MaterialTheme.colorScheme.surface
  val haptic = LocalHapticFeedback.current

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(headerHeight)
      .background(surfaceColor)
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
    ) {
      ExpressiveTopBarContent(
        title = "Stats",
        collapseFraction = collapseFraction,
        modifier = Modifier.fillMaxSize()
      )

      // Back Button
      FilledIconButton(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(start = 12.dp, top = 4.dp)
          .size(40.dp)
          .clip(CircleShape),
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          onBackPressed()
        },
        colors = IconButtonDefaults.filledIconButtonColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
          contentDescription = "Back",
          tint = MaterialTheme.colorScheme.primary
        )
      }

      Row(
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(end = 12.dp, top = 4.dp)
      ) {
        TooltipHint(tooltipText = "Export Report") {
          IconButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onSaveReport()
            }
          ) {
            Icon(
              imageVector = com.vinnovateit.latch.ui.icons.ExportNotes,
              contentDescription = "Save Report",
              tint = MaterialTheme.colorScheme.primary
            )
          }
        }
      }
    }
  }
}

@SuppressLint("ContextCastToActivity")
@Composable
fun StatsScreen(
  modifier: Modifier = Modifier,
  onSaveReport: () -> Unit,
  onBackPressed: () -> Unit = {},
  statsViewModel: StatsViewModel = viewModel()
) {
  val sessionToShow by statsViewModel.sessionToShow.collectAsStateWithLifecycle()
  val historyToShow by statsViewModel.historyToShow.collectAsStateWithLifecycle()
  val liveStatus by statsViewModel.liveStatus.collectAsStateWithLifecycle()
  val isLive = remember(liveStatus) { liveStatus != null }
  val speedUnits by SettingsManager.speedUnits.collectAsStateWithLifecycle()
  var showAllSessions by remember { mutableStateOf(false) }

  val density = LocalDensity.current

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isPortrait = maxHeight > maxWidth
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 180.dp
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

    var topBarHeightPx by remember { mutableFloatStateOf(maxTopBarHeightPx) }

    val nestedScrollConnection = remember(minTopBarHeightPx, maxTopBarHeightPx) {
      object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
          val delta = available.y
          if (delta < 0 && topBarHeightPx > minTopBarHeightPx) {
            val newHeight = (topBarHeightPx + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
            val consumed = newHeight - topBarHeightPx
            topBarHeightPx = newHeight
            return Offset(0f, consumed)
          }
          return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
          val delta = available.y
          if (delta > 0 && topBarHeightPx < maxTopBarHeightPx) {
            val newHeight = (topBarHeightPx + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
            val consumedHeight = newHeight - topBarHeightPx
            topBarHeightPx = newHeight
            return Offset(0f, consumedHeight)
          }
          return Offset.Zero
        }
      }
    }

    val currentTopBarHeightDp = with(density) { topBarHeightPx.toDp() }
    val collapseFraction = 1f - ((topBarHeightPx - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)

    if (!isLive && historyToShow.isEmpty()) {
      Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
          StatsTopBar(
            collapseFraction = 0f,
            headerHeight = maxTopBarHeight,
            onBackPressed = onBackPressed,
            onSaveReport = onSaveReport
          )
        }
      ) { innerPadding ->
        EmptyStatsView(modifier = Modifier.padding(innerPadding).fillMaxSize())
      }
    } else {
      if (!isPortrait && isLive && sessionToShow != null) {
        Row(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier = Modifier
              .weight(0.5f)
              .fillMaxHeight()
              .background(MaterialTheme.colorScheme.background)
          ) {
            StatsTopBar(
              collapseFraction = 1f,
              headerHeight = minTopBarHeight,
              onBackPressed = onBackPressed,
              onSaveReport = onSaveReport
            )
            Box(
              modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxSize(),
              contentAlignment = Alignment.Center
            ) {
              SessionCard(session = sessionToShow!!, speedUnit = speedUnits)
            }
          }

          StatsList(
            modifier = Modifier.weight(0.5f).fillMaxHeight().background(MaterialTheme.colorScheme.background),
            isLive = true,
            showSessionCard = false,
            sessionToShow = sessionToShow,
            historyToShow = historyToShow,
            liveStatus = liveStatus,
            speedUnits = speedUnits,
            showAllSessions = showAllSessions,
            onToggleShowAll = { showAllSessions = !showAllSessions },
            addSpacer = true,
            contentPadding = PaddingValues(top = 16.dp),
            statsViewModel = statsViewModel
          )
        }
      } else {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(nestedScrollConnection)
        ) {
          StatsList(
            modifier = Modifier.fillMaxSize(),
            isLive = isLive,
            showSessionCard = true,
            sessionToShow = sessionToShow,
            historyToShow = historyToShow,
            liveStatus = liveStatus,
            speedUnits = speedUnits,
            showAllSessions = showAllSessions,
            onToggleShowAll = { showAllSessions = !showAllSessions },
            contentPadding = PaddingValues(top = maxTopBarHeight),
            statsViewModel = statsViewModel
          )

          StatsTopBar(
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackPressed = onBackPressed,
            onSaveReport = onSaveReport
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyStatsView(modifier: Modifier = Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      Icon(
        imageVector = Icons.Rounded.BarChart,
        contentDescription = "Empty Stats Icon",
        modifier = Modifier.size(128.dp),
        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = "No stats to show yet. Connect to a network to get started!",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 32.dp)
      )
    }
  }
}