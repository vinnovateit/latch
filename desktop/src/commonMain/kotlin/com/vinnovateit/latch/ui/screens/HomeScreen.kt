package com.vinnovateit.latch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.vinnovateit.latch.ui.components.LatchIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.engine.LatchController
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.ui.components.CircularPowerButton
import com.vinnovateit.latch.ui.components.HowItWorksDialog
import com.vinnovateit.latch.ui.components.LatchHomeTopBar
import com.vinnovateit.latch.ui.components.LeafOverlay
import com.vinnovateit.latch.ui.components.MorphingPowerButton
import com.vinnovateit.latch.ui.components.SpectrumCard
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay

/** Width at which the home screen switches to the Android landscape arrangement. */
private val WideBreakpoint = 900.dp

/** Only the last 150 samples are drawn, matching the Android home chart. */
private const val CHART_WINDOW = 150

/**
 * The home screen, laid out as the Android app lays it out.
 *
 * Narrow windows get the portrait composition -- top bar, status pill, a
 * primaryContainer panel filling the lower half with a semicircular bite taken
 * out of its top edge, the circular power button sitting in that bite, and the
 * stats card below. Wide windows get the landscape composition: controls on the
 * left, a full-height stats card on the right.
 *
 * Everything the previous desktop home screen did is still reachable. The pieces
 * that were only there because there was nowhere else to put them -- credential
 * editing, start-at-login, the update panel -- have moved to Settings, where the
 * Android app keeps their equivalents.
 */
@Composable
fun HomeScreen(
    controller: LatchController,
    sessions: SessionRepository,
    platform: PlatformServices,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    showNavigationMenuItems: Boolean,
) {
    val isLatched by controller.isLatched.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val liveStatus by sessions.liveStatus.collectAsStateWithLifecycle()
    val speedUnit by SettingsManager.speedUnits.collectAsStateWithLifecycle()

    val history = liveStatus?.liveData?.takeLast(CHART_WINDOW) ?: emptyList()

    var showHowItWorks by remember { mutableStateOf(false) }

    /*
     * Mirrors the Android app's "smart" power button: when there is no Wi-Fi to
     * log in to, the button opens the OS Wi-Fi UI instead of failing. Toggling it
     * also writes auto-login, so an explicit disconnect is not undone by the next
     * network event.
     */
    val onPowerClick: () -> Unit = {
        if (isLatched || status is ConnectionStatus.Connecting) {
            controller.submit(LatchCommand.Logout)
            SettingsManager.setAutoLogin(false)
        } else {
            SettingsManager.setAutoLogin(true)
            controller.submit(LatchCommand.CheckAndLogin)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val topBar: @Composable () -> Unit = {
            LatchHomeTopBar(
                isLatched = isLatched,
                onHowItWorks = { showHowItWorks = true },
                onOpenStats = onOpenStats,
                onOpenSettings = onOpenSettings,
                onOpenAbout = onOpenAbout,
                showNavigationItems = showNavigationMenuItems,
            )
        }

        if (maxWidth >= WideBreakpoint) {
            WideHome(
                topBar = topBar,
                isLatched = isLatched,
                onPowerClick = onPowerClick,
                onOpenWifiSettings = { platform.systemActions.openWifiSettings() },
                history = history,
                connectionStatus = status,
                speedUnit = speedUnit,
                onOpenStats = onOpenStats,
            )
        } else {
            CompactHome(
                topBar = topBar,
                availableWidth = maxWidth,
                isLatched = isLatched,
                onPowerClick = onPowerClick,
                onOpenWifiSettings = { platform.systemActions.openWifiSettings() },
                history = history,
                connectionStatus = status,
                speedUnit = speedUnit,
                onOpenStats = onOpenStats,
            )
        }
    }

    if (showHowItWorks) {
        HowItWorksDialog(onDismiss = { showHowItWorks = false })
    }
}

@Composable
private fun CompactHome(
    topBar: @Composable () -> Unit,
    availableWidth: Dp,
    isLatched: Boolean,
    onPowerClick: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    history: List<LiveDataPoint>,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onOpenStats: () -> Unit,
) {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    // Android sizes the button off the screen width (48%). Clamped here because a
    // desktop window can be far wider than a phone without being any taller.
    val buttonDiameter = (availableWidth * 0.48f).coerceIn(132.dp, 196.dp)
    // The bite in the panel is proportionally larger than the button, as on
    // Android (0.6 of screen width, scaled to 90%), so the button floats inside it.
    val cutoutDiameter = buttonDiameter * 1.125f
    val cutoutDiameterPx = with(LocalDensity.current) { cutoutDiameter.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        LeafOverlay(
            modifier = Modifier.fillMaxWidth(),
            contentDescription = null,
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth().weight(0.5f)) {
                topBar()
                Spacer(Modifier.weight(1f))
                WifiSettingsLink(
                    onClick = onOpenWifiSettings,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                // Clears the top half of the power button, which is centred on the
                // seam between the two halves and drawn over everything.
                Spacer(Modifier.height(buttonDiameter / 2))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    .graphicsLayer(alpha = 0.99f)
                    .drawBehind {
                        if (isAmoled) return@drawBehind
                        drawRect(color = primaryContainer, size = size)
                        val radius = cutoutDiameterPx / 2f
                        drawArc(
                            color = Color.Transparent,
                            startAngle = 0f,
                            sweepAngle = 180f,
                            useCenter = true,
                            topLeft = Offset((size.width - cutoutDiameterPx) / 2f, -radius),
                            size = Size(cutoutDiameterPx, cutoutDiameterPx),
                            blendMode = BlendMode.Clear,
                        )
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                SpectrumCard(
                    history = history,
                    connectionStatus = connectionStatus,
                    speedUnit = speedUnit,
                    onNavigateToStats = onOpenStats,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = buttonDiameter / 2 + 20.dp,
                            start = 24.dp,
                            end = 24.dp,
                            bottom = 24.dp,
                        ),
                )
            }
        }

        CircularPowerButton(
            isConnected = isLatched,
            onClick = onPowerClick,
            diameter = buttonDiameter,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun WideHome(
    topBar: @Composable () -> Unit,
    isLatched: Boolean,
    onPowerClick: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    history: List<LiveDataPoint>,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onOpenStats: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LeafOverlay(
            modifier = Modifier.fillMaxSize(),
            contentDescription = null,
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop,
        )

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(0.45f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                topBar()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Capped so a maximised window does not turn the hero control
                    // into a wall; Android has no cap because a phone cannot get
                    // this large.
                    MorphingPowerButton(
                        isConnected = isLatched,
                        onClick = onPowerClick,
                        modifier = Modifier.sizeIn(maxWidth = 340.dp, maxHeight = 340.dp),
                    )
                }
                WifiSettingsLink(onClick = onOpenWifiSettings)
                Spacer(Modifier.height(16.dp))
            }

            Box(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .padding(top = 20.dp, bottom = 24.dp, end = 24.dp),
            ) {
                SpectrumCard(
                    history = history,
                    connectionStatus = connectionStatus,
                    speedUnit = speedUnit,
                    onNavigateToStats = onOpenStats,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Kept as an explicit affordance even though the power button already falls back
 * to it: the fallback only fires when Latch can tell there is no Wi-Fi, and
 * "let me go look at the network list" is a thing people want on a laptop that
 * has just been carried between buildings.
 */
@Composable
private fun WifiSettingsLink(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClick) {
            Icon(
                imageVector = LatchIcons.WifiLock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Open Wi-Fi settings",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
