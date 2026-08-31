package com.vinnovateit.latch.features.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.os.Build
import android.provider.Settings
import com.vinnovateit.latch.features.wifi.detector.WiFiConnectionDetector
import com.vinnovateit.latch.features.wifi.detector.WiFiStateDetector
import com.vinnovateit.latch.R
import com.vinnovateit.latch.common.ui.LeafOverlay
import com.vinnovateit.latch.common.util.TooltipHint
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.features.home.components.SpectrumCard
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isConnected: Boolean,
    session: SessionSummary?,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStats: () -> Unit = {},
    onNavigateToMeetTheTeam: () -> Unit = {}
) {
    val historyForHomeScreen = session?.history?.takeLast(150) ?: emptyList()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var showHowItWorksDialog by remember { mutableStateOf(false) }
    var showStatusText by remember { mutableStateOf(false) }
    var statusTimerTrigger by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(statusTimerTrigger) {
        showStatusText = true
        delay(5000)
        showStatusText = false
    }

    val view = LocalView.current
    val isDarkTheme = isSystemInDarkTheme()
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDarkTheme
            insetsController.isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    val smartOnConnectClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        statusTimerTrigger = System.currentTimeMillis()

        val isWifiConnected = WiFiConnectionDetector.isConnectedToWiFi(context)

        if (!isConnected && (!WiFiStateDetector.isWiFiEnabled(context) || !isWifiConnected)) {
            val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent(Settings.Panel.ACTION_WIFI)
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            context.startActivity(panelIntent)
        } else if (isConnected) {
            val intent = Intent(context, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_TRIGGER_LOGOUT
            }
            context.startService(intent)
            SettingsManager.setAutoLogin(false)
        } else {
            val intent = Intent(context, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
            }
            context.startService(intent)
            SettingsManager.setAutoLogin(true)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isPortrait = maxHeight > maxWidth

        if (isPortrait) {
            PortraitHomeScreen(
                isConnected = isConnected,
                session = session,
                onConnectClick = smartOnConnectClick as () -> Unit,
                historyForHomeScreen = historyForHomeScreen,
                connectionStatus = connectionStatus,
                speedUnit = speedUnit,
                onHowItWorksClick = { showHowItWorksDialog = true },
                showStatusText = showStatusText,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStats = onNavigateToStats,
                onNavigateToMeetTheTeam = onNavigateToMeetTheTeam
            )
        } else {
            LandscapeHomeScreen(
                isConnected = isConnected,
                session = session,
                onConnectClick = smartOnConnectClick as () -> Unit,
                historyForHomeScreen = historyForHomeScreen,
                connectionStatus = connectionStatus,
                speedUnit = speedUnit,
                onHowItWorksClick = { showHowItWorksDialog = true },
                showStatusText = showStatusText,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStats = onNavigateToStats,
                onNavigateToMeetTheTeam = onNavigateToMeetTheTeam
            )
        }

        if (showHowItWorksDialog) {
            HowItWorksBottomSheet(onDismiss = { showHowItWorksDialog = false })
        }
    }
}

@Composable
fun PortraitHomeScreen(
    isConnected: Boolean,
    session: SessionSummary?,
    onConnectClick: () -> Unit,
    historyForHomeScreen: List<LiveDataPoint>,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onHowItWorksClick: () -> Unit,
    showStatusText: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToMeetTheTeam: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalResources.current.displayMetrics.widthPixels.toFloat() }
    val buttonDiameterPx = screenWidthPx * 0.6f
    val colorScheme = MaterialTheme.colorScheme
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current

    Box(modifier = Modifier.fillMaxSize()) {
        LeafOverlay(
            contentDescription = "Background Pattern",
            modifier = Modifier.fillMaxWidth(),
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    .statusBarsPadding()
            ) {
                TopBarSection(
                    onPreferencesClick = onNavigateToSettings,
                    onHowItWorksClick = onHowItWorksClick,
                    onMeetTheTeamClick = onNavigateToMeetTheTeam
                )

                StaggeredStatusText(
                    visible = showStatusText,
                    isConnected = isConnected,
                    modifier = Modifier.offset(y = (-14).dp)
                )

                Spacer(modifier = Modifier.height(10.dp))
                Spacer(modifier = Modifier.height(60.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    .graphicsLayer(alpha = 0.99f)
                    .drawBehind {
                        if (!isAmoled) {
                            drawRect(color = colorScheme.primaryContainer, size = size)
                            val cutoutRatio = 0.9f
                            val cutoutDiameter = buttonDiameterPx * cutoutRatio
                            val cutoutRadius = cutoutDiameter / 2f
                            val circleTopLeft = Offset(x = (size.width - cutoutDiameter) / 2f, y = -cutoutRadius)
                            drawArc(
                                color = Color.Transparent, startAngle = 0f, sweepAngle = 180f,
                                useCenter = true, topLeft = circleTopLeft, size = Size(cutoutDiameter, cutoutDiameter),
                                blendMode = BlendMode.Clear
                            )
                        }
                    }
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                SpectrumCard(
                    session = session,
                    historyForHomeScreen = historyForHomeScreen,
                    connectionStatus = connectionStatus,
                    speedUnit = speedUnit,
                    isLandscape = false,
                    onNavigateToStats = onNavigateToStats
                )
            }
        }
        PowerButtonOverlay(
            onConnectClick = onConnectClick,
            isConnected = isConnected,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun LandscapeHomeScreen(
    isConnected: Boolean,
    session: SessionSummary?,
    onConnectClick: () -> Unit,
    historyForHomeScreen: List<LiveDataPoint>,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onHowItWorksClick: () -> Unit,
    showStatusText: Boolean,
    onNavigateToSettings: () -> Unit,
    onNavigateToStats: () -> Unit,
    onNavigateToMeetTheTeam: () -> Unit
) {
    val context = LocalContext.current

    LeafOverlay(
        contentDescription = "Background Pattern",
        modifier = Modifier.fillMaxSize(),
        alignment = Alignment.TopCenter,
        contentScale = ContentScale.Crop
    )
    Row(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        Box(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TopBarSection(
                    onPreferencesClick = onNavigateToSettings,
                    onHowItWorksClick = onHowItWorksClick,
                    onMeetTheTeamClick = onNavigateToMeetTheTeam
                )

                StaggeredStatusText(
                    visible = showStatusText,
                    isConnected = isConnected,
                    modifier = Modifier.offset(y = (-14).dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth().weight(0.3f), contentAlignment = Alignment.Center) {
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth().weight(0.7f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        LandscapePowerButton(
                            modifier = Modifier.fillMaxSize(),
                            onConnectClick = onConnectClick,
                            isConnected = isConnected
                        )
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            SpectrumCard(
                session = session,
                historyForHomeScreen = historyForHomeScreen,
                connectionStatus = connectionStatus,
                speedUnit = speedUnit,
                isLandscape = true,
                onNavigateToStats = onNavigateToStats
            )
        }
    }
}

@Composable
fun StaggeredStatusText(
    visible: Boolean,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val text = if (isConnected) "LATCHED" else "DISCONNECTED"
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier.fillMaxWidth(),
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = containerColor,
                modifier = Modifier.wrapContentSize()
            ) {
                androidx.compose.animation.AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) + androidx.compose.animation.scaleIn(initialScale = 0.95f))
                            .togetherWith(androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(300)) + androidx.compose.animation.scaleOut(targetScale = 0.95f))
                            .using(androidx.compose.animation.SizeTransform(clip = false))
                    },
                    label = "StatusTextAnimation"
                ) { targetText ->
                    Text(
                        text = targetText,
                        color = contentColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}


@Composable
fun PowerButtonOverlay(
    onConnectClick: () -> Unit,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val buttonDiameterDp = with(density) {
        (LocalResources.current.displayMetrics.widthPixels * 0.48f).toDp()
    }

    val rotation by animateFloatAsState(
        targetValue = if (isConnected) 0f else 180f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "powerIconRotation"
    )

    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current

    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer

    val contentColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "powerBtnContentColor"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier.size(buttonDiameterDp),
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            border = if (isAmoled) androidx.compose.foundation.BorderStroke(4.dp, if (isConnected) primaryColor else MaterialTheme.colorScheme.outline) else null
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = "Power Button",
                modifier = Modifier
                    .size(80.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}

@Composable
fun LandscapePowerButton(
    modifier: Modifier = Modifier,
    onConnectClick: () -> Unit,
    isConnected: Boolean
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressedFromInteraction by interactionSource.collectIsPressedAsState()
    var pressedManual by remember { mutableStateOf(false) }
    val isPressed = pressedFromInteraction || pressedManual

    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 24.dp else 50.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cornerRadiusAnim"
    )

    val rotation by animateFloatAsState(
        targetValue = if (isConnected) 0f else 180f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "powerIconRotation"
    )

    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current

    val containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer

    val contentColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "powerBtnContentColor"
    )

    val primaryColor = MaterialTheme.colorScheme.primary
    Button(
        onClick = onConnectClick,
        interactionSource = interactionSource,
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressedManual = true
                    try { awaitRelease() } finally { pressedManual = false }
                })
            },
        shape = RoundedCornerShape(cornerRadius),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        contentPadding = PaddingValues(0.dp),
        border = if (isAmoled) androidx.compose.foundation.BorderStroke(4.dp, if (isConnected) primaryColor else MaterialTheme.colorScheme.outline) else null
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = "Power Button",
                modifier = Modifier
                    .fillMaxSize(fraction = 0.5f)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBarSection(
    onPreferencesClick: () -> Unit,
    onHowItWorksClick: () -> Unit,
    onMeetTheTeamClick: () -> Unit,
) {
    val isDark = LocalIsDarkTheme.current
    var menuExpanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Text(modifier = Modifier.padding(top = 0.dp), text = stringResource(R.string.app_name_uppercase), color = MaterialTheme.colorScheme.primary, fontSize = 23.sp, fontFamily = ModernizFontFamily, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
        },
        navigationIcon = {
            Icon(painter = if (isDark) painterResource(id = R.drawable.ic_latch_dark) else painterResource(id = R.drawable.ic_latch_light), contentDescription = "LATCH Logo", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp).size(36.dp))
        },
        actions = {
            TooltipHint(tooltipText = "More options") {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.padding(end = 12.dp).size(52.dp)) {
                    Icon(imageVector = Icons.Rounded.Menu, contentDescription = "More options", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, shape = RoundedCornerShape(12.dp), containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(200.dp)) {
                DropdownMenuItem(text = { Text("How It Works", fontSize = 16.sp, fontFamily = SatoshiFontFamily) }, onClick = { menuExpanded = false; onHowItWorksClick() }, leadingIcon = { Icon(Icons.Rounded.QuestionMark, contentDescription = "How It Works") })
                DropdownMenuItem(text = { Text("Settings", fontSize = 16.sp, fontFamily = SatoshiFontFamily) }, onClick = { menuExpanded = false; onPreferencesClick() }, leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = "Settings") })
                DropdownMenuItem(text = { Text("Meet The Team", fontSize = 16.sp, fontFamily = SatoshiFontFamily) }, onClick = { menuExpanded = false; onMeetTheTeamClick() }, leadingIcon = { Icon(Icons.Rounded.Groups, contentDescription = "Meet The Team") })
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent, navigationIconContentColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.primary, actionIconContentColor = MaterialTheme.colorScheme.primary)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HowItWorksBottomSheet(onDismiss: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "How it Works",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = ModernizFontFamily,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                HowItWorksRow(
                    icon = Icons.Rounded.Wifi,
                    text = "If any VIT Wi-Fi is auto-connected, Latch retrieves your encrypted credentials and attempts to connect."
                )
                HowItWorksRow(
                    icon = Icons.Rounded.BarChart,
                    text = "Once connected, you can watch your real-time stats and data usage."
                )
                HowItWorksRow(
                    icon = Icons.Rounded.Speed,
                    text = "The 20 mbps cap will not be bypassable."
                )
                HowItWorksRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    text = "If you do not wish this to work, disable auto-connect from settings or press the disconnect button (which also disables auto-connect)."
                )
            }
            
            Spacer(modifier = Modifier.height(40.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth(0.7f).height(56.dp)
            ) {
                Text(
                    text = "GOT IT",
                    fontSize = 18.sp,
                    fontFamily = SatoshiFontFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HowItWorksRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp).padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = SatoshiFontFamily,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 0.dp)
        )
    }
}

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
fun HomeScreenPortraitPreview() {
    LatchTheme {
        HomeScreen(isConnected = false, session = null, connectionStatus = ConnectionStatus.Idle, speedUnit = "B/s")
    }
}

@Preview(showBackground = true, device = "spec:width=891dp,height=411dp")
@Composable
fun HomeScreenLandscapePreview() {
    LatchTheme {
        HomeScreen(isConnected = true, session = null, connectionStatus = ConnectionStatus.Idle, speedUnit = "B/s")
    }
}