package com.vinnovateit.latch.features.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatPaint
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.SettingsSystemDaydream
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.R
import com.vinnovateit.latch.common.ui.components.ExpressiveTopBarContent
import com.vinnovateit.latch.platform.LatchAppGraph
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
private fun SettingsTopBar(
  collapseFraction: Float,
  headerHeight: Dp,
  onBackPressed: () -> Unit
) {
  val surfaceColor = MaterialTheme.colorScheme.surface
  val haptic = LocalHapticFeedback.current

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(headerHeight)
      .background(surfaceColor.copy(alpha = collapseFraction))
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
    ) {
      FilledIconButton(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(start = 12.dp, top = 4.dp),
        onClick = {
          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          onBackPressed()
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
      ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.primary)
      }

      ExpressiveTopBarContent(
        title = "Settings",
        collapseFraction = collapseFraction,
        modifier = Modifier
          .fillMaxSize()
          .padding(start = 0.dp, end = 0.dp)
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBackClick: () -> Unit, onNavigateToCredentials: () -> Unit) {
  val context = LocalContext.current
  val haptic = LocalHapticFeedback.current
  val autoLogin by SettingsManager.autoLogin.collectAsStateWithLifecycle()
  val speedUnits by SettingsManager.speedUnits.collectAsStateWithLifecycle()
  val theme by SettingsManager.theme.collectAsStateWithLifecycle()

  var showSpeedUnitsSheet by remember { mutableStateOf(false) }
  var showThemeSheet by remember { mutableStateOf(false) }
  var showClearStatsSheet by remember { mutableStateOf(false) }
  var showAccentColorSheet by remember { mutableStateOf(false) }

  val useDynamicColors by SettingsManager.useDynamicColors.collectAsStateWithLifecycle()
  val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
  val useMonochrome by SettingsManager.useMonochrome.collectAsStateWithLifecycle()
  val accentColor by SettingsManager.accentColor.collectAsStateWithLifecycle()

  val density = LocalDensity.current
  val coroutineScope = rememberCoroutineScope()
  val lazyListState = rememberLazyListState()

  val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val minTopBarHeight = 64.dp + statusBarHeight
  val maxTopBarHeight = 180.dp
  val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
  val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }

  val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
  var collapseFraction by remember { mutableStateOf(0f) }

  LaunchedEffect(topBarHeight.value) {
    collapseFraction = 1f - ((topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)).coerceIn(0f, 1f)
  }

  val nestedScrollConnection = remember {
    object : NestedScrollConnection {
      override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val delta = available.y
        val isScrollingDown = delta < 0

        if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
          return Offset.Zero
        }

        val previousHeight = topBarHeight.value
        val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
        val consumed = newHeight - previousHeight

        if (consumed.roundToInt() != 0) {
          coroutineScope.launch {
            topBarHeight.snapTo(newHeight)
          }
        }

        val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
        return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
      }
    }
  }

  LaunchedEffect(lazyListState.isScrollInProgress) {
    if (!lazyListState.isScrollInProgress) {
      val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
      val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0

      val targetValue = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx

      if (topBarHeight.value != targetValue) {
        coroutineScope.launch {
          topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
        }
      }
    }
  }

  Box(
    modifier = Modifier
      .nestedScroll(nestedScrollConnection)
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.surface)
  ) {
    val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }
    LazyColumn(
      state = lazyListState,
      contentPadding = PaddingValues(top = currentTopBarHeightDp),
      modifier = Modifier.fillMaxSize()
    ) {
      // ACCOUNT
      item {
        SettingsSection(title = "Account") {
          Column(modifier = Modifier.clip(shape = RoundedCornerShape(24.dp))) {
            SettingsItem(
              title = "Auto-login on Connect",
              subtitle = "Automatically log in to VIT Wi-Fi",
              leadingIcon = {
                Icon(
                  Icons.Rounded.Autorenew,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
              },
              trailingContent = {
                Switch(
                  checked = autoLogin,
                  onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    SettingsManager.setAutoLogin(it)
                  })
              },
              onClick = { SettingsManager.setAutoLogin(!autoLogin) }
            )
            Spacer(modifier = Modifier.height(3.dp))
            SettingsItem(
              title = "Update Credentials",
              subtitle = "Change your registration number and password",
              leadingIcon = {
                Icon(
                  Icons.Rounded.Password,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
              },
              onClick = { onNavigateToCredentials() }
            )
          }
        }
      }

      item { Spacer(modifier = Modifier.height(16.dp)) }

      // APPEARANCE
      item {
        SettingsSection(title = "Appearance") {
          Column(modifier = Modifier.clip(shape = RoundedCornerShape(24.dp))) {
            SettingsItem(
              title = "Theme",
              subtitle = theme,
              leadingIcon = {
                val themeIcon = when (theme) {
                    "Light" -> Icons.Rounded.LightMode
                    "Dark" -> Icons.Rounded.DarkMode
                    else -> Icons.Rounded.SettingsSystemDaydream
                }
                Icon(
                  themeIcon,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
              },
              onClick = { showThemeSheet = true }
            )

            Spacer(modifier = Modifier.height(3.dp))

            androidx.compose.animation.AnimatedVisibility(
              visible = !useDynamicColors,
              enter = androidx.compose.animation.expandVertically(animationSpec = androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(150)),
              exit = androidx.compose.animation.shrinkVertically(animationSpec = androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
            ) {
              Column {
                SettingsItem(
                  title = "Accent Color",
                  subtitle = if (useMonochrome) "Monochrome" else accentColor,
                  leadingIcon = {
                    Icon(
                      Icons.Rounded.FormatPaint,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                    )
                  },
                  trailingContent = {
                    val colors = listOf(
                      "Red" to androidx.compose.ui.graphics.Color(0xFFC01221),
                      "Blue" to androidx.compose.ui.graphics.Color(0xFF005AC1),
                      "Green" to androidx.compose.ui.graphics.Color(0xFF0F5223),
                      "Purple" to androidx.compose.ui.graphics.Color(0xFF7D00B8),
                      "Pink" to androidx.compose.ui.graphics.Color(0xFFD81B60),
                      "Yellow" to androidx.compose.ui.graphics.Color(0xFFF5B300)
                    )
                    val selectedColor = if (useMonochrome) androidx.compose.ui.graphics.Color(0xFF808080) else (colors.find { it.first == accentColor }?.second ?: androidx.compose.ui.graphics.Color.Transparent)
                    Box(
                      modifier = Modifier
                        .size(24.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(selectedColor)
                    )
                  },
                  onClick = { showAccentColorSheet = true }
                )
                Spacer(modifier = Modifier.height(3.dp))
              }
            }

            SettingsItem(
              title = "Dynamic Colors",
              subtitle = "Adapt with your system's Material You theming",
              leadingIcon = {
                Icon(
                  Icons.Rounded.ColorLens,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
              },
              trailingContent = {
                Switch(
                  checked = useDynamicColors,
                  onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    SettingsManager.setUseDynamicColors(it)
                  })
              },
              onClick = { SettingsManager.setUseDynamicColors(!useDynamicColors) },
            )
          }
        }
      }

      item { Spacer(modifier = Modifier.height(16.dp)) }

      // DATA MANAGEMENT
      item {
        SettingsSection(title = "Data Management") {
          Column(modifier = Modifier.clip(shape = RoundedCornerShape(24.dp))) {
            SettingsItem(
              title = "Speed Units",
              subtitle = when (speedUnits) {
                  "bps" -> "Bits per second (bps)"
                  "B/s" -> "Bytes per second (B/s)"
                  else -> speedUnits
              },
              leadingIcon = {
                Icon(
                  Icons.Rounded.Speed,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
              },
              onClick = { showSpeedUnitsSheet = true }
            )
            Spacer(modifier = Modifier.height(3.dp))
            SettingsItem(
              title = "Clear Stats",
              subtitle = "Reset usage history",
              leadingIcon = {
                Icon(
                  Icons.Rounded.SettingsBackupRestore,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
              },
              onClick = { showClearStatsSheet = true }
            )
          }
        }
      }
      item { Spacer(modifier = Modifier.height(72.dp)) }
    }
    SettingsTopBar(
      collapseFraction = collapseFraction,
      headerHeight = maxTopBarHeight - ((maxTopBarHeight - minTopBarHeight) * collapseFraction),
      onBackPressed = onBackClick
    )
  }

  if (showSpeedUnitsSheet) {
    SettingsSelectionBottomSheet(
      title = "Speed Units",
      description = "Choose how network speed is displayed",
      options = listOf(
        SelectionOption("bps", Icons.Rounded.Speed, "Bits per second (bps)"),
        SelectionOption("B/s", Icons.Rounded.Speed, "Bytes per second (B/s)")
      ),
      selected = speedUnits,
      onSelect = {
        SettingsManager.setSpeedUnits(it.label)
        showSpeedUnitsSheet = false
      },
      onDismiss = { showSpeedUnitsSheet = false }
    )
  }

  if (showThemeSheet) {
    val themeOptions = listOf(
      SelectionOption("System Default", Icons.Rounded.SettingsSystemDaydream),
      SelectionOption("Light", Icons.Rounded.LightMode),
      SelectionOption("Dark", Icons.Rounded.DarkMode)
    )
    SettingsSelectionBottomSheet(
      title = "Theme",
      description = "Control the look of the app",
      options = themeOptions,
      selected = theme,
      onSelect = {
        SettingsManager.setTheme(it.label)
        showThemeSheet = false
      },
      onDismiss = { showThemeSheet = false },
      bottomContent = {
        val systemIsDark = androidx.compose.foundation.isSystemInDarkTheme()
        val isCurrentlyDark = when (theme) {
            "Light" -> false
            "Dark" -> true
            else -> systemIsDark
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .graphicsLayer { alpha = if (isCurrentlyDark) 1f else 0.5f },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Use pure black",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
            }
            Switch(
                checked = usePureBlack && isCurrentlyDark,
                enabled = isCurrentlyDark,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    SettingsManager.setUsePureBlack(it)
                }
            )
        }
      }
    )
  }

  if (showAccentColorSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { showAccentColorSheet = false },
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface
    ) {
      Column(
        modifier = Modifier.padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          "Accent Color",
          style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
          modifier = Modifier.padding(horizontal = 16.dp)
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = !useMonochrome,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                  "Choose a custom color",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(24.dp))
                AccentColorPicker(
                  selectedColorName = accentColor,
                  onColorSelected = { 
                      SettingsManager.setAccentColor(it)
                      showAccentColorSheet = false 
                  }
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Monochrome theme",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                  text = "Use grayscale palette",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = useMonochrome,
                onCheckedChange = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    SettingsManager.setUseMonochrome(it)
                }
            )
        }
      }
    }
  }

  if (showClearStatsSheet) {
    SettingsActionBottomSheet(
      title = stringResource(R.string.stats_reset_dialog_title),
      description = stringResource(R.string.stats_reset_dialog_message),
      confirmText = stringResource(R.string.stats_reset_dialog_confirm),
      cancelText = stringResource(R.string.stats_reset_dialog_cancel),
      onConfirm = {
        LatchAppGraph.sessions.clearHistory()
        showClearStatsSheet = false
      },
      onDismiss = { showClearStatsSheet = false }
    )
  }
}

data class SelectionOption(
  val label: String,
  val icon: ImageVector,
  val displayLabel: String = label
)

@Composable
fun SettingsSection(
  title: String,
  content: @Composable () -> Unit
) {
  Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(vertical = 8.dp)
    ) {
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
      )
    }
    content()
  }
}

@Composable
fun AccentColorPicker(
  selectedColorName: String,
  onColorSelected: (String) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.SpaceEvenly
  ) {
    val colors = listOf(
      "Red" to androidx.compose.ui.graphics.Color(0xFFC01221),
      "Blue" to androidx.compose.ui.graphics.Color(0xFF005AC1),
      "Green" to androidx.compose.ui.graphics.Color(0xFF0F5223),
      "Purple" to androidx.compose.ui.graphics.Color(0xFF7D00B8),
      "Pink" to androidx.compose.ui.graphics.Color(0xFFD81B60),
      "Yellow" to androidx.compose.ui.graphics.Color(0xFFF5B300)
    )
    val haptic = LocalHapticFeedback.current
    colors.forEach { (name, color) ->
      val isSelected = name == selectedColorName
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(androidx.compose.foundation.shape.CircleShape)
          .clickable { 
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onColorSelected(name) 
          }
          .then(
              if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape)
              else Modifier
          )
          .padding(if (isSelected) 6.dp else 0.dp)
          .clip(androidx.compose.foundation.shape.CircleShape)
          .background(color)
      )
    }
  }
}

@Composable
fun SettingsItem(
  title: String,
  subtitle: String,
  leadingIcon: @Composable () -> Unit,
  trailingContent: @Composable () -> Unit = {},
  onClick: () -> Unit
) {
  val haptic = LocalHapticFeedback.current
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
      })
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier
        .padding(16.dp)
        .fillMaxWidth()
    ) {
      Box(
        modifier = Modifier
          .padding(end = 16.dp)
          .size(24.dp),
        contentAlignment = Alignment.Center
      ) {
        leadingIcon()
      }

      Column(
        modifier = Modifier
          .weight(1f)
          .padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontSize = 16.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      Box(
        modifier = Modifier.padding(start = 16.dp),
        contentAlignment = Alignment.CenterEnd
      ) {
        trailingContent()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSelectionBottomSheet(
  title: String,
  description: String,
  options: List<SelectionOption>,
  selected: String,
  onSelect: (SelectionOption) -> Unit,
  onDismiss: () -> Unit,
  bottomContent: (@Composable () -> Unit)? = null
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val haptic = LocalHapticFeedback.current

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface
  ) {
    Column(
      modifier = Modifier
        .verticalScroll(rememberScrollState())
        .padding(vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        title,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(horizontal = 16.dp)
      )
      Text(
        description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
      )
      Spacer(Modifier.height(16.dp))
      options.forEach { option ->
        val isSelected = option.label == selected
        val contentColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable(
              interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
              indication = ripple(),
              onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSelect(option)
              }
            )
            .padding(vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(option.icon, contentDescription = null, modifier = Modifier.padding(start = 16.dp, end = 16.dp), tint = contentColor)
          Text(
            option.displayLabel,
            color = contentColor,
            fontWeight = FontWeight.W500,
            modifier = Modifier.padding(end = 16.dp)
          )
        }
      }
      if (bottomContent != null) {
          Spacer(Modifier.height(8.dp))
          bottomContent()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsActionBottomSheet(
  title: String,
  description: String,
  confirmText: String,
  cancelText: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit
) {
  val haptic = LocalHapticFeedback.current
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    content = {
      Column(
        modifier = Modifier
          .padding(16.dp)
          .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.padding(horizontal = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          Button(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onDismiss()
            },
            modifier = Modifier.weight(1f)
          ) {
            Text(cancelText, fontWeight = FontWeight.Bold)
          }
          OutlinedButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onConfirm()
            },
            modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
          ) {
            Text(confirmText, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  )
}