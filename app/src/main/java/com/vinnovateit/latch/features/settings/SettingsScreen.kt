package com.vinnovateit.latch.features.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.features.settings.components.CustomColorPickerDialog
import com.vinnovateit.latch.features.settings.components.parseHexOrNull
import com.vinnovateit.latch.platform.LatchAppGraph
import com.vinnovateit.latch.ui.theme.ModernizFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
  scrollBehavior: TopAppBarScrollBehavior? = null,
  onBackPressed: () -> Unit
) {
  val haptic = LocalHapticFeedback.current

  LargeTopAppBar(
    title = {
      Text(
        text = "Settings",
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
    scrollBehavior = scrollBehavior,
    colors = TopAppBarDefaults.largeTopAppBarColors(
      containerColor = MaterialTheme.colorScheme.surface,
      scrolledContainerColor = MaterialTheme.colorScheme.surface
    )
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBackClick: () -> Unit, onNavigateToCredentials: () -> Unit) {
  val haptic = LocalHapticFeedback.current
  val autoLogin by SettingsManager.autoLogin.collectAsStateWithLifecycle()
  val speedUnits by SettingsManager.speedUnits.collectAsStateWithLifecycle()
  val theme by SettingsManager.theme.collectAsStateWithLifecycle()

  var showSpeedUnitsSheet by remember { mutableStateOf(false) }
  var showThemeSheet by remember { mutableStateOf(false) }
  var showClearStatsSheet by remember { mutableStateOf(false) }
  var showAccentColorSheet by remember { mutableStateOf(false) }
  var showChartPaletteSheet by remember { mutableStateOf(false) }

  val useDynamicColors by SettingsManager.useDynamicColors.collectAsStateWithLifecycle()
  val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
  val useMonochrome by SettingsManager.useMonochrome.collectAsStateWithLifecycle()
  val accentColor by SettingsManager.accentColor.collectAsStateWithLifecycle()
  val chartPalette by SettingsManager.chartPalette.collectAsStateWithLifecycle()
  val hapticsEnabled by SettingsManager.hapticsEnabled.collectAsStateWithLifecycle()

  val lazyListState = rememberLazyListState()
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

  Scaffold(
    modifier = Modifier
      .nestedScroll(scrollBehavior.nestedScrollConnection)
      .fillMaxSize(),
    topBar = {
      SettingsTopBar(
        scrollBehavior = scrollBehavior,
        onBackPressed = onBackClick
      )
    }
  ) { innerPadding ->
    LazyColumn(
      state = lazyListState,
      contentPadding = innerPadding,
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
    ) {
      // ACCOUNT
      item {
        SettingsSection(title = "Account") {
          Column(modifier = Modifier.clip(shape = RoundedCornerShape(24.dp))) {
            SettingsItem(
              title = "Auto-login on Connect",
              subtitle = "Automatically log in to VIT Wi-Fi",
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
            Spacer(modifier = Modifier.height(2.dp))
            SettingsItem(
              title = "Update Credentials",
              subtitle = "Change your registration number and password",
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
              onClick = { showThemeSheet = true }
            )

            Spacer(modifier = Modifier.height(2.dp))

            androidx.compose.animation.AnimatedVisibility(
              visible = !useDynamicColors,
              enter = androidx.compose.animation.expandVertically(animationSpec = androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(150)),
              exit = androidx.compose.animation.shrinkVertically(animationSpec = androidx.compose.animation.core.tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
            ) {
              Column {
                SettingsItem(
                  title = "Accent Color",
                  subtitle = if (useMonochrome) "Monochrome" else accentColor,
                  trailingContent = {
                    val colors = listOf(
                      "Red" to Color(0xFFC01221),
                      "Blue" to Color(0xFF005AC1),
                      "Green" to Color(0xFF0F5223),
                      "Purple" to Color(0xFF7D00B8),
                      "Yellow" to Color(0xFFF5B300)
                    )
                    val customParsed = if (accentColor.startsWith("#")) parseHexOrNull(accentColor) else null
                    val selectedColor = if (useMonochrome) Color(0xFF808080)
                        else (customParsed ?: colors.find { it.first == accentColor }?.second ?: Color(0xFFC01221))
                    Box(
                      modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(selectedColor)
                    )
                  },
                  onClick = { showAccentColorSheet = true }
                )
                Spacer(modifier = Modifier.height(2.dp))
              }
            }

            SettingsItem(
              title = "Dynamic Colors",
              subtitle = "Adapt with your system's Material You theming",
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
            Spacer(modifier = Modifier.height(2.dp))
            SettingsItem(
              title = "Chart Bar Colors",
              subtitle = chartPalette,
              trailingContent = {
                val (previewDl, previewUl) = com.vinnovateit.latch.common.util.StatsColorPalettes.resolveColors(chartPalette)
                Canvas(
                  modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                ) {
                  drawArc(
                    color = previewDl,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = true
                  )
                  drawArc(
                    color = previewUl,
                    startAngle = 270f,
                    sweepAngle = 180f,
                    useCenter = true
                  )
                }
              },
              onClick = { showChartPaletteSheet = true }
            )
            Spacer(modifier = Modifier.height(2.dp))
            SettingsItem(
              title = "Haptic feedback",
              subtitle = "Vibrate on interactions and button taps",
              trailingContent = {
                Switch(
                  checked = hapticsEnabled,
                  onCheckedChange = { SettingsManager.setHapticsEnabled(it) }
                )
              },
              onClick = { SettingsManager.setHapticsEnabled(!hapticsEnabled) }
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
              onClick = { showSpeedUnitsSheet = true }
            )
            Spacer(modifier = Modifier.height(2.dp))
            SettingsItem(
              title = "Clear Stats",
              subtitle = "Reset usage history",
              onClick = { showClearStatsSheet = true }
            )
          }
        }
      }
      item { Spacer(modifier = Modifier.height(72.dp)) }
    }
  }

  if (showSpeedUnitsSheet) {
    SettingsSelectionBottomSheet(
      title = "Speed Units",
      description = "Choose how network speed is displayed",
      options = listOf(
        SelectionOption("bps", displayLabel = "Bits per second (bps)"),
        SelectionOption("B/s", displayLabel = "Bytes per second (B/s)")
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
      SelectionOption("System Default", displayLabel = "System Default"),
      SelectionOption("Light", displayLabel = "Light"),
      SelectionOption("Dark", displayLabel = "Dark")
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

  if (showChartPaletteSheet) {
    com.vinnovateit.latch.features.stats.components.ChartPaletteBottomSheet(
      selectedPalette = chartPalette,
      onSelectPalette = {
        SettingsManager.setChartPalette(it)
        showChartPaletteSheet = false
      },
      onDismiss = { showChartPaletteSheet = false }
    )
  }
}

data class SelectionOption(
  val label: String,
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
        color = MaterialTheme.colorScheme.primary
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
  var showCustomColorDialog by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
    verticalAlignment = Alignment.CenterVertically
  ) {
    val haptic = LocalHapticFeedback.current

    // Pen / Colorize button at the start (first item before "Red")
    val isCustomSelected = selectedColorName.startsWith("#")
    val customParsedColor = if (isCustomSelected) parseHexOrNull(selectedColorName) else null
    val customBg = if (isCustomSelected) (customParsedColor ?: MaterialTheme.colorScheme.surfaceVariant) else MaterialTheme.colorScheme.surfaceVariant
    val customTint = if (isCustomSelected && customParsedColor != null) {
      val isLight = (0.299 * customParsedColor.red + 0.587 * customParsedColor.green + 0.114 * customParsedColor.blue) > 0.5
      if (isLight) Color.Black else Color.White
    } else {
      MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .clickable {
          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          showCustomColorDialog = true
        }
        .then(
          if (isCustomSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
          else Modifier
        )
        .padding(if (isCustomSelected) 6.dp else 0.dp)
        .clip(CircleShape)
        .background(customBg),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = Icons.Rounded.Colorize,
        contentDescription = "Custom Accent Color",
        tint = customTint,
        modifier = Modifier.size(20.dp)
      )
    }

    val colors = listOf(
      "Red" to Color(0xFFC01221),
      "Blue" to Color(0xFF005AC1),
      "Green" to Color(0xFF0F5223),
      "Purple" to Color(0xFF7D00B8),
      "Yellow" to Color(0xFFF5B300)
    )

    colors.forEach { (name, color) ->
      val isSelected = name == selectedColorName
      Box(
        modifier = Modifier
          .size(44.dp)
          .clip(CircleShape)
          .clickable { 
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onColorSelected(name) 
          }
          .then(
            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            else Modifier
          )
          .padding(if (isSelected) 6.dp else 0.dp)
          .clip(CircleShape)
          .background(color)
      )
    }
  }

  if (showCustomColorDialog) {
    CustomColorPickerDialog(
      initialColorHex = if (selectedColorName.startsWith("#")) selectedColorName else "#C01221",
      onDismiss = { showCustomColorDialog = false },
      onColorConfirmed = { hex ->
        showCustomColorDialog = false
        onColorSelected(hex)
      }
    )
  }
}

@Composable
fun SettingsItem(
  title: String,
  subtitle: String,
  trailingContent: @Composable () -> Unit = {},
  shape: Shape = RoundedCornerShape(4.dp),
  onClick: () -> Unit
) {
  val haptic = LocalHapticFeedback.current
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = shape,
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape)
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

      Column(
        modifier = Modifier
          .weight(1f)
          .padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
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
        style = MaterialTheme.typography.headlineSmall.copy(
          fontWeight = FontWeight.Bold,
          fontFamily = ModernizFontFamily
        ),
        modifier = Modifier.padding(horizontal = 16.dp)
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
            .padding(vertical = 12.dp, horizontal = 24.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
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
        Text(
          title,
          style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.ExtraBold,
            fontFamily = ModernizFontFamily
          ),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          OutlinedButton(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onDismiss()
            },
            modifier = Modifier.weight(1f)
          ) {
            Text(cancelText.uppercase(), fontWeight = FontWeight.Bold)
          }
          Button(
            onClick = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onConfirm()
            },
            modifier = Modifier.weight(1f),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError
            )
          ) {
            Text(confirmText.uppercase(), fontWeight = FontWeight.Bold)
          }
        }
        Spacer(Modifier.height(16.dp))
      }
    }
  )
}