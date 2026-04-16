package com.vinnovateit.latch.features.home

import android.app.Activity
import android.content.Intent
import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.vinnovateit.latch.R
import com.vinnovateit.latch.common.ui.LeafOverlay
import com.vinnovateit.latch.common.util.TooltipHint
import com.vinnovateit.latch.domain.model.LiveDataPoint
import com.vinnovateit.latch.domain.model.SessionSummary
import com.vinnovateit.latch.features.about.MeetTheTeamActivity
import com.vinnovateit.latch.features.home.components.SpectrumCard
import com.vinnovateit.latch.features.onboarding.OnboardingActivity
import com.vinnovateit.latch.features.settings.SettingsActivity
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import com.vinnovateit.latch.features.wifi.detector.PrivateDnsChecker

@Composable
fun HomeRedCanvasBackground(buttonSizePx: Float, isPortrait: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = 0.99f)
    ) {
        drawRect(
            color = colorScheme.primaryContainer,
            size = size
        )
        if (isPortrait) {
            val cutoutRatio = 1.2f
            val cutoutDiameter = buttonSizePx * cutoutRatio
            val cutoutRadius = cutoutDiameter / 2f
            val circleTopLeft = Offset(
                x = (size.width - cutoutDiameter) / 2f,
                y = -cutoutRadius
            )
            drawArc(
                color = Color.Transparent,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = circleTopLeft,
                size = Size(cutoutDiameter, cutoutDiameter),
                blendMode = BlendMode.Clear
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isConnected: Boolean,
    networkSpeed: String,
    session: SessionSummary?,
    connectionStatus: ConnectionStatus,
    speedUnit: String
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

        if (isConnected) {
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
                networkSpeed = networkSpeed,
                session = session,
                onConnectClick = smartOnConnectClick as () -> Unit,
                historyForHomeScreen = historyForHomeScreen,
                connectionStatus = connectionStatus,
                speedUnit = speedUnit,
                onHowItWorksClick = { showHowItWorksDialog = true },
                showStatusText = showStatusText
            )
        } else {
            LandscapeHomeScreen(
                isConnected = isConnected,
                networkSpeed = networkSpeed,
                session = session,
                onConnectClick = smartOnConnectClick as () -> Unit,
                historyForHomeScreen = historyForHomeScreen,
                connectionStatus = connectionStatus,
                speedUnit = speedUnit,
                onHowItWorksClick = { showHowItWorksDialog = true },
                showStatusText = showStatusText
            )
        }

        if (showHowItWorksDialog) {
            HowItWorksDialog(onDismiss = { showHowItWorksDialog = false })
        }
    }
}

@Composable
fun PortraitHomeScreen(
    isConnected: Boolean,
    networkSpeed: String,
    session: SessionSummary?,
    onConnectClick: () -> Unit,
    historyForHomeScreen: List<LiveDataPoint>,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onHowItWorksClick: () -> Unit,
    showStatusText: Boolean
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalResources.current.displayMetrics.widthPixels.toFloat() }
    val buttonDiameterPx = screenWidthPx * 0.6f
    val colorScheme = MaterialTheme.colorScheme

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
                    onPreferencesClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    onHowItWorksClick = onHowItWorksClick,
                    onMeetTheTeamClick = { context.startActivity(Intent(context, MeetTheTeamActivity::class.java)) }
                )

                StaggeredStatusText(
                    visible = showStatusText,
                    isConnected = isConnected,
                    modifier = Modifier.offset(y = (-14).dp)
                )

                Spacer(modifier = Modifier.height(10.dp))
                NetworkStatusRow(networkSpeed = networkSpeed)
                Spacer(modifier = Modifier.height(60.dp))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    .graphicsLayer(alpha = 0.99f)
                    .drawBehind {
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
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                SpectrumCard(session, historyForHomeScreen, connectionStatus, speedUnit, false)
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
    networkSpeed: String,
    session: SessionSummary?,
    onConnectClick: () -> Unit,
    historyForHomeScreen: List<LiveDataPoint>,
    connectionStatus: ConnectionStatus,
    speedUnit: String,
    onHowItWorksClick: () -> Unit,
    showStatusText: Boolean
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
                    onPreferencesClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    onHowItWorksClick = onHowItWorksClick,
                    onMeetTheTeamClick = { context.startActivity(Intent(context, MeetTheTeamActivity::class.java)) },
                )

                StaggeredStatusText(
                    visible = showStatusText,
                    isConnected = isConnected,
                    modifier = Modifier.offset(y = (-14).dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.fillMaxWidth().weight(0.3f), contentAlignment = Alignment.Center) {
                        NetworkStatusRow(networkSpeed = networkSpeed)
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
                historyForHomeScreen,
                connectionStatus = connectionStatus,
                speedUnit = speedUnit,
                isLandscape = true,
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
    val context = LocalContext.current
    Column(modifier = modifier) {
        TopBarSection(
            onPreferencesClick = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            },
            onHowItWorksClick = {
                context.startActivity(Intent(context, OnboardingActivity::class.java).apply {
                    putExtra("start_from_step_one", true)
                })
            },
            onMeetTheTeamClick = {
                context.startActivity(Intent(context, MeetTheTeamActivity::class.java))
            },
        )
        PrivateDnsWarningBanner()
        Spacer(Modifier.size(25.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = if (isLandscape) 16.dp else 90.dp) // Increased bottom padding
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                text.forEachIndexed { index, char ->
                    key(index, char) {
                        val offsetY = remember { Animatable(-20f) }

                        LaunchedEffect(visible) {
                            if (visible) {
                                delay(index * 30L)
                                offsetY.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.55f,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            } else {
                                delay(index * 20L)
                                offsetY.animateTo(
                                    targetValue = -20f,
                                    animationSpec = tween(300)
                                )
                            }
                        }

                        Text(
                            text = char.toString(),
                            color = color,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SatoshiFontFamily,
                            modifier = Modifier.offset(y = offsetY.value.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkStatusRow(networkSpeed: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = networkSpeed,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = SatoshiFontFamily
        )
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

    Box(
        modifier = modifier
            .size(buttonDiameterDp)
            .drawBehind {
                val shadowColor = ColorPowerButtonShadow
                val radius = size.minDimension / 2
                val paint = Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    color = shadowColor.toArgb()
                    maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
                }
                drawContext.canvas.nativeCanvas.drawCircle(center.x, center.y + 20f, radius, paint)
            },
        contentAlignment = Alignment.Center
    ) {
        Button(
            onClick = onConnectClick,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
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

    Button(
        onClick = onConnectClick,
        interactionSource = interactionSource,
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressedManual = true
                try { awaitRelease() } finally { pressedManual = false }
            })
        },
        shape = RoundedCornerShape(cornerRadius),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        contentPadding = PaddingValues(0.dp)
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
            Icon(painter = if (isDark) painterResource(id = R.drawable.ic_latch_dark) else painterResource(id = R.drawable.ic_latch_light), contentDescription = "LATCH Logo", tint = Color.Unspecified, modifier = Modifier.size(48.dp).padding(start = 12.dp))
        },
        actions = {
            TooltipHint(tooltipText = "More options") {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(imageVector = Icons.Rounded.Menu, contentDescription = "More options", tint = MaterialTheme.colorScheme.primary)
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HowItWorksDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "How it Works",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = ModernizFontFamily,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp).padding(top=2.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "If any VIT Wi-Fi is auto-connected, Latch retrieves your encrypted credentials and attempts to connect.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = SatoshiFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Rounded.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp).padding(top=2.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Once connected, you can watch your real-time stats and data usage.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = SatoshiFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp).padding(top=2.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "If you do not wish this to work, disable auto-connect from settings or press the disconnect button (which also disables auto-connect).",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = SatoshiFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = "Got It",
                        fontSize = 18.sp,
                        fontFamily = SatoshiFontFamily,
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    )
}

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
fun HomeScreenPortraitPreview() {
    LatchTheme {
        HomeScreen(isConnected = false, networkSpeed = "6 mbps", session = null, connectionStatus = ConnectionStatus.Idle, "B/s")
    }
}

@Preview(showBackground = true, device = "spec:width=891dp,height=411dp")
@Composable
fun HomeScreenLandscapePreview() {
    LatchTheme {
        HomeScreen(isConnected = true, networkSpeed = "12 mbps", session = null, connectionStatus = ConnectionStatus.Idle, speedUnit = "B/s")
    }
}

@Composable
fun PrivateDnsWarningBanner() {
    val context = LocalContext.current
    var isPrivateDnsOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isPrivateDnsOn = PrivateDnsChecker.isPrivateDnsEnabled(context)
    }

    if (isPrivateDnsOn) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Private DNS is enabled. If login fails, try disabling it in System Settings.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    fontFamily = SatoshiFontFamily
                )
            }
        }
    }
}