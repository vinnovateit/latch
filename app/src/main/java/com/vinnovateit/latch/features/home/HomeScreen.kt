package com.vinnovateit.latch.features.home

import com.vinnovateit.latch.common.ui.LeafOverlay
import android.content.Intent
import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
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
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.features.about.MeetTheTeamPage

@Composable
fun HomeRedCanvasBackground(buttonSizePx: Float, isPortrait: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(alpha = 0.99f) // For BlendMode to work
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
    onConnectClick: () -> Unit,
    connectionStatus: ConnectionStatus,
    speedUnit: String
) {
    var showAbout by remember { mutableStateOf(false) }

    if (showAbout) {
        MeetTheTeamPage(onBackClick = { showAbout = false })
    } else {
        val historyForHomeScreen = session?.history?.takeLast(150) ?: emptyList()

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
                    onConnectClick,
                    historyForHomeScreen,
                    connectionStatus,
                    speedUnit,
                    onShowAbout = { showAbout = true }
                )
            } else {
                LandscapeHomeScreen(
                    isConnected,
                    networkSpeed,
                    session,
                    onConnectClick,
                    historyForHomeScreen,
                    connectionStatus,
                    speedUnit,
                    onShowAbout = { showAbout = true }
                )
            }
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
    onShowAbout: () -> Unit
) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalResources.current.displayMetrics.widthPixels.toFloat() }
    val buttonDiameterPx = screenWidthPx * 0.5f
    val isDark = LocalIsDarkTheme.current
    Box(modifier = Modifier.fillMaxSize()) {
        LeafOverlay(
            contentDescription = "Background Pattern",
            modifier = Modifier.fillMaxSize(),
            alignment = Alignment.TopCenter
        )
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.54f)
            ) {
                HomeTopSection(isConnected, networkSpeed, onShowAbout = onShowAbout)
            }
            // Bottom Section with Canvas Cutout
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.46f),
                contentAlignment = Alignment.BottomCenter
            ) {
                HomeRedCanvasBackground(buttonSizePx = buttonDiameterPx, isPortrait = true)
                SpectrumCard(session, historyForHomeScreen, connectionStatus, speedUnit)
            }
        }
        // Power Button Overlay
        PowerButtonOverlay(
            onConnectClick = onConnectClick,
            isPortrait = true,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 25.dp) // Move button down slightly
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
    onShowAbout: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Left Section (Status and Button)
        Box(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight()
        ) {
            Image(
                painter = if(LocalIsDarkTheme.current) painterResource(id = R.drawable.background_overlay_dark) else painterResource(R.drawable.background_overlay_light),
                contentDescription = "Background Pattern",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                HomeTopSection(isConnected, networkSpeed, isLandscape = true, onShowAbout = onShowAbout)
                Spacer(modifier = Modifier.height(32.dp))
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
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            SpectrumCard(
                session,
                historyForHomeScreen,
                connectionStatus,
                speedUnit
            )
        }
    }
}

@Composable
fun HomeTopSection(
    isConnected: Boolean,
    networkSpeed: String,
    isLandscape: Boolean = false,
    onShowAbout: () -> Unit
) {
    val context = LocalContext.current
    Column {
        TopBarSection(
            onPreferencesClick = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            },
            onShowAbout = onShowAbout
        )
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
    onShowAbout: () -> Unit
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
                        Text("Preferences", fontSize = 16.sp, fontFamily = SatoshiFontFamily)
                    },
                    onClick = {
                        menuExpanded = false
                        onPreferencesClick()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Handyman,
                            contentDescription = "Preferences"
                        )
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text("Meet the Team", fontSize = 16.sp, fontFamily = SatoshiFontFamily)
                    },
                    onClick = {
                        menuExpanded = false
                        onShowAbout()
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = "About"
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
            onConnectClick = { },
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
            onConnectClick = { },
            connectionStatus = ConnectionStatus.Idle,
            speedUnit = "B/s"
        )
    }
}
