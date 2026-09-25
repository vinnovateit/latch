package com.vinnovateit.latch.features.stats

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.features.stats.components.SessionCard
import com.vinnovateit.latch.features.stats.components.StatsList
import com.vinnovateit.latch.ui.theme.ModernizFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsTopBar(
  scrollBehavior: TopAppBarScrollBehavior? = null,
  onBackPressed: () -> Unit,
  onSaveReport: () -> Unit,
  isSyncing: Boolean = false,
  onResyncHistory: () -> Unit = {},
  onNavigateToHistory: () -> Unit = {}
) {
  val haptic = LocalHapticFeedback.current
  var menuExpanded by remember { mutableStateOf(false) }

  LargeTopAppBar(
    title = {
      Text(
        text = "Stats",
        fontFamily = ModernizFontFamily,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp)
      )
    },
    navigationIcon = {
      FilledIconButton(
        modifier = Modifier
          .padding(start = 12.dp)
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
    },
    actions = {
      Box(modifier = Modifier.padding(end = 8.dp)) {
        IconButton(
          onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            menuExpanded = true
          },
          modifier = Modifier.size(48.dp)
        ) {
          Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "More Options",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
          )
        }

        DropdownMenu(
          expanded = menuExpanded,
          onDismissRequest = { menuExpanded = false },
          shape = RoundedCornerShape(16.dp),
          containerColor = MaterialTheme.colorScheme.surfaceContainer
        ) {
          DropdownMenuItem(
            text = { Text("Session History") },
            onClick = {
              menuExpanded = false
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onNavigateToHistory()
            },
            leadingIcon = {
              Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
              )
            }
          )
          DropdownMenuItem(
            text = { Text("Export Full Report") },
            onClick = {
              menuExpanded = false
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onSaveReport()
            },
            leadingIcon = {
              Icon(
                imageVector = com.vinnovateit.latch.ui.icons.ExportNotes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
              )
            }
          )
          DropdownMenuItem(
            text = { Text(if (isSyncing) "Syncing…" else "Resync History") },
            onClick = {
              if (!isSyncing) {
                menuExpanded = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onResyncHistory()
              } else {
                menuExpanded = false
              }
            },
            leadingIcon = {
              if (isSyncing) {
                CircularProgressIndicator(
                  modifier = Modifier.size(18.dp),
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.primary
                )
              } else {
                Icon(
                  imageVector = Icons.Rounded.Refresh,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
              }
            }
          )
        }
      }
    },
    scrollBehavior = scrollBehavior,
    colors = TopAppBarDefaults.largeTopAppBarColors(
      containerColor = MaterialTheme.colorScheme.surface,
      scrolledContainerColor = MaterialTheme.colorScheme.surface
    )
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("ContextCastToActivity")
@Composable
fun StatsScreen(
  modifier: Modifier = Modifier,
  onSaveReport: () -> Unit,
  onBackPressed: () -> Unit = {},
  onNavigateToHistory: () -> Unit = {},
  onNavigateToPortalAccount: () -> Unit = {},
  statsViewModel: StatsViewModel = viewModel()
) {
  val sessionToShow by statsViewModel.sessionToShow.collectAsStateWithLifecycle()
  val portalHistory by statsViewModel.portalHistory.collectAsStateWithLifecycle()
  val isSyncing by statsViewModel.isSyncing.collectAsStateWithLifecycle()
  val liveStatus by statsViewModel.liveStatus.collectAsStateWithLifecycle()
  val isLive = remember(liveStatus) { liveStatus != null }
  val speedUnits by SettingsManager.speedUnits.collectAsStateWithLifecycle()
  var showAllSessions by remember { mutableStateOf(false) }

  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isPortrait = maxHeight > maxWidth

    if (!isLive && portalHistory.isEmpty()) {
      Scaffold(
        topBar = {
          StatsTopBar(
            onBackPressed = onBackPressed,
            onSaveReport = onSaveReport,
            isSyncing = isSyncing,
            onResyncHistory = { statsViewModel.refreshHistory(force = true) },
            onNavigateToHistory = onNavigateToHistory
          )
        }
      ) { innerPadding ->
        EmptyStatsView(
          modifier = Modifier
            .padding(innerPadding)
            .fillMaxSize()
        )
      }
    } else if (!isPortrait && isLive && sessionToShow != null) {
      Scaffold(
        topBar = {
          StatsTopBar(
            onBackPressed = onBackPressed,
            onSaveReport = onSaveReport,
            isSyncing = isSyncing,
            onResyncHistory = { statsViewModel.refreshHistory(force = true) },
            onNavigateToHistory = onNavigateToHistory
          )
        }
      ) { innerPadding ->
        Row(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
          Box(
            modifier = Modifier
              .weight(0.5f)
              .fillMaxHeight()
              .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
          ) {
            SessionCard(session = sessionToShow!!, speedUnit = speedUnits)
          }

          StatsList(
            modifier = Modifier.weight(0.5f).fillMaxHeight().background(MaterialTheme.colorScheme.background),
            isLive = true,
            showSessionCard = false,
            sessionToShow = sessionToShow,
            portalHistory = portalHistory,
            liveStatus = liveStatus,
            speedUnits = speedUnits,
            showAllSessions = showAllSessions,
            onToggleShowAll = { showAllSessions = !showAllSessions },
            addSpacer = true,
            contentPadding = PaddingValues(top = 16.dp),
            onNavigateToHistory = onNavigateToHistory,
            statsViewModel = statsViewModel
          )
        }
      }
    } else {
      Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
          StatsTopBar(
            scrollBehavior = scrollBehavior,
            onBackPressed = onBackPressed,
            onSaveReport = onSaveReport,
            isSyncing = isSyncing,
            onResyncHistory = { statsViewModel.refreshHistory(force = true) },
            onNavigateToHistory = onNavigateToHistory
          )
        }
      ) { innerPadding ->
        StatsList(
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
          contentPadding = innerPadding,
          isLive = isLive,
          showSessionCard = true,
          sessionToShow = sessionToShow,
          portalHistory = portalHistory,
          liveStatus = liveStatus,
          speedUnits = speedUnits,
          showAllSessions = showAllSessions,
          onToggleShowAll = { showAllSessions = !showAllSessions },
          onNavigateToHistory = onNavigateToHistory,
          statsViewModel = statsViewModel
        )
      }
    }
  }
}

@Composable
private fun EmptyStatsView(
  modifier: Modifier = Modifier
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.padding(horizontal = 32.dp)
    ) {
      Icon(
        imageVector = Icons.Rounded.BarChart,
        contentDescription = "Empty Stats Icon",
        modifier = Modifier.size(96.dp),
        tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
      )
      Spacer(modifier = Modifier.height(16.dp))
      Text(
        text = "No stats available",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = "Connect to Wi-Fi to start tracking your data usage.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
    }
  }
}