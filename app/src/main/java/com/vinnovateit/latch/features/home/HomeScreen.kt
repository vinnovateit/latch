package com.vinnovateit.latch.features.home

import com.vinnovateit.latch.common.ui.LeafOverlay
import android.content.Intent
import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.R
import com.vinnovateit.latch.common.util.TooltipHint
import com.vinnovateit.latch.domain.model.SessionSummary
import com.vinnovateit.latch.features.home.components.SpectrumCard
import com.vinnovateit.latch.features.settings.SettingsActivity
import com.vinnovateit.latch.ui.theme.*
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vinnovateit.latch.domain.model.LiveDataPoint
import com.vinnovateit.latch.features.about.MeetTheTeamActivity
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.features.onboarding.OnboardingActivity
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val autoLoginEnabled by SettingsManager.autoLogin.collectAsStateWithLifecycle()


    val smartOnConnectClick = {
        if (autoLoginEnabled) {
            // TOGGLE IS ON: Behave as a "Disconnect" button
            val intent = Intent(context, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_TRIGGER_LOGOUT
            }
            context.startService(intent)
            // Also, turn off the auto-login toggle
            SettingsManager.setAutoLogin(false)
        } else {
            // TOGGLE IS OFF: Behave as a "Connect" button for a single manual login
            val intent = Intent(context, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
            }
            context.startService(intent)
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
                isConnected,
                networkSpeed,
                session,
                smartOnConnectClick as () -> Unit,
                historyForHomeScreen,
                connectionStatus,
                speedUnit,
            )
        } else {
            LandscapeHomeScreen(
                isConnected,
                networkSpeed,
                session,
                smartOnConnectClick as () -> Unit,
                historyForHomeScreen,
                connectionStatus,
                speedUnit,
            )
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
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalResources.current.displayMetrics.widthPixels.toFloat() }
    val buttonDiameterPx = screenWidthPx * 0.5f
    Box(modifier = Modifier.fillMaxSize()) {
        LeafOverlay(
            contentDescription = "Background Pattern",
            modifier = Modifier
                .fillMaxWidth(),
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
            ) {
                HomeTopSection(isConnected = isConnected, networkSpeed = networkSpeed, modifier = Modifier.statusBarsPadding())
            }
            // Bottom Section with Canvas Cutout
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
                    // --- FIX: Added navigationBarsPadding to this Box ---
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                HomeRedCanvasBackground(buttonSizePx = buttonDiameterPx, isPortrait = true)
                SpectrumCard(session, historyForHomeScreen, connectionStatus, speedUnit, false)
            }
        }
        // Power Button Overlay
        PowerButtonOverlay(
            onConnectClick = onConnectClick,
            isPortrait = true,
            modifier = Modifier
                .align(Alignment.Center)
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
) {
    LeafOverlay(
        contentDescription = "Background Pattern",
        modifier = Modifier
            .fillMaxSize(),
        alignment = Alignment.TopCenter,
        contentScale = ContentScale.Crop
    )
    Row(
        // Apply padding to the whole landscape view
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        // Left Section (Status and Button)
        Box(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                HomeTopSection( isConnected =  isConnected, networkSpeed = networkSpeed, isLandscape = true)
                Spacer(modifier = Modifier.height(24.dp))
                PowerButtonOverlay(
                    onConnectClick = onConnectClick,
                    isPortrait = false
                )
            }
        }

        // Right Section (Graph)
        Box(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            SpectrumCard(
                session,
                historyForHomeScreen,
                connectionStatus,
                speedUnit,
                true,
            )
        }
    }
}

@Composable
fun HomeTopSection(
    modifier: Modifier = Modifier,
    isConnected: Boolean,
    networkSpeed: String,
    isLandscape: Boolean = false
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            if (isConnected) ColorBoxConnected else ColorBoxDisconnected,
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isConnected) "CONNECTED" else "DISCONNECTED",
                        color = if (isConnected) ColorStatusConnected else ColorStatusDisconnected,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SatoshiFontFamily
                    )
                }
                Text(
                    text = networkSpeed,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SatoshiFontFamily
                )
            }
        }
    }
}


@Composable
fun PowerButtonOverlay(onConnectClick: () -> Unit, isPortrait: Boolean, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val buttonDiameterDp = with(density) {
        if (isPortrait) {
            (LocalResources.current.displayMetrics.widthPixels * 0.5f).toDp()
        } else {
            (LocalResources.current.displayMetrics.heightPixels * 0.6f).toDp()
        }
    }

    Box(
        modifier = modifier
            .size(buttonDiameterDp)
            .drawBehind {
                val shadowColor = ColorPowerButtonShadow
                val radius = size.minDimension / 2
                val paint = Paint()
                    .asFrameworkPaint()
                    .apply {
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
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
        ) {
            val powerIconColor = MaterialTheme.colorScheme.onPrimary
            // Use a fixed size for the icon canvas
            Canvas(modifier = Modifier.size(64.dp)) {
                val stroke = 7.dp.toPx()
                val arcR = size.minDimension / 2.2f
                val topLeft = Offset((size.width - arcR * 2) / 2f, (size.height - arcR * 2) / 2f)

                drawArc(
                    color = powerIconColor,
                    startAngle = -135f,
                    sweepAngle = -270f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                    size = Size(arcR * 2, arcR * 2),
                    topLeft = topLeft
                )

                val cx = size.width / 2
                val cy = size.height / 2
                drawLine(
                    color = powerIconColor,
                    start = Offset(cx, cy - arcR * 1.2f),
                    end = Offset(cx, cy - arcR * 0.6f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
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
            Text(
                modifier = Modifier.padding(top = 5.dp),
                text = stringResource(R.string.app_name_uppercase),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 23.sp,
                fontFamily = ModernizFontFamily,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        },
        navigationIcon = {
            Icon(
                painter = if(isDark) painterResource(id = R.drawable.ic_latch_dark) else painterResource(id = R.drawable.ic_latch_light),
                contentDescription = "LATCH Logo",
                tint = Color.Unspecified,
                modifier = Modifier
                    .size(48.dp)
                    .padding(start = 12.dp)
            )
        },
        actions = {
            TooltipHint(tooltipText = "More options") {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "More options",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.width(200.dp)
            ) {
                DropdownMenuItem(
                    text = {
                        Text("How It Works", fontSize = 16.sp, fontFamily = SatoshiFontFamily)
                    },
                    onClick = {
                        menuExpanded = false
                        onHowItWorksClick()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.QuestionMark,
                            contentDescription = "How It Works"
                        )
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text("Settings", fontSize = 16.sp, fontFamily = SatoshiFontFamily)
                    },
                    onClick = {
                        menuExpanded = false
                        onPreferencesClick()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "Settings"
                        )
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text("Meet The Team", fontSize = 16.sp, fontFamily = SatoshiFontFamily)
                    },
                    onClick = {
                        menuExpanded = false
                        onMeetTheTeamClick()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Groups,
                            contentDescription = "Meet The Team"
                        )
                    }
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            navigationIconContentColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp")
@Composable
fun HomeScreenPortraitPreview() {
    LatchTheme {
        HomeScreen(
            isConnected = false,
            networkSpeed = "6 mbps",
            session = null,
            connectionStatus = ConnectionStatus.Idle,
            "B/s"
        )
    }
}

@Preview(showBackground = true, device = "spec:width=891dp,height=411dp")
@Composable
fun HomeScreenLandscapePreview() {
    LatchTheme {
        HomeScreen(
            isConnected = true,
            networkSpeed = "12 mbps",
            session = null,
            connectionStatus = ConnectionStatus.Idle,
            speedUnit = "B/s"
        )
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