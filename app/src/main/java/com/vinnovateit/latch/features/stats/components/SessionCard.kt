package com.vinnovateit.latch.features.stats.components

import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.common.util.DisplayMode
import com.vinnovateit.latch.common.util.Tag
import com.vinnovateit.latch.common.util.createGraphPaths
import com.vinnovateit.latch.common.util.formatBitsPerSecond
import com.vinnovateit.latch.common.util.formatBytes
import com.vinnovateit.latch.common.util.formatDurationDynamic
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.core.model.SessionSummary
import com.vinnovateit.latch.features.home.components.GRAPH_HEIGHT_SCALE
import com.vinnovateit.latch.features.home.components.POINTS_IN_30_SECONDS
import com.vinnovateit.latch.ui.theme.ColorGraphDownload
import com.vinnovateit.latch.ui.theme.ColorGraphUpload
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import kotlinx.coroutines.delay
import java.util.*
import kotlin.math.atan2
import kotlin.math.max

@Composable
fun SessionCard(session: SessionSummary, speedUnit: String) {
    var lastInteraction by remember { mutableStateOf(0L) }
    var showOverlay by remember { mutableStateOf(true) }

    LaunchedEffect(lastInteraction) {
        if (lastInteraction != 0L) {
            delay(3000)
            showOverlay = true
        }
    }

    val overlayAlpha by animateFloatAsState(targetValue = if (showOverlay) 1f else 0f, animationSpec = tween(durationMillis = 300))

    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && com.vinnovateit.latch.ui.theme.LocalIsDarkTheme.current

    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = if (isAmoled) androidx.compose.foundation.BorderStroke(4.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SessionRateGraph(
                modifier = Modifier.fillMaxSize(),
                rateHistory = session.history,
                overlayAlpha = overlayAlpha,
                onUserInteraction = { timestamp ->
                    lastInteraction = timestamp
                    showOverlay = false
                }
            )

            SessionDetailsOverlay(
                modifier = Modifier.graphicsLayer { alpha = overlayAlpha },
                session = session,
                speedUnit = speedUnit,
                isAmoled = isAmoled
            )
        }
    }
}

@Composable
private fun SessionDetailsOverlay(
    modifier: Modifier = Modifier,
    session: SessionSummary,
    speedUnit: String,
    isAmoled: Boolean = false
) {
    val overlayColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.0f to overlayColor,
                        0.9f to Color.Transparent
                    ),
                )
            )
            .padding(24.dp)
    ) {
        Column {
            SessionHeader(session, speedUnit)
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                DataUsageCircle(modifier = Modifier.size(100.dp), data = session.totalData, isAmoled = isAmoled)
            }
        }
    }
}

@Composable
private fun SessionHeader(session: SessionSummary, speedUnit: String) {
    var duration by remember(session.startTimestamp) {
        mutableLongStateOf(System.currentTimeMillis() - session.startTimestamp)
    }
    LaunchedEffect(Unit) {
        while (true) {
            duration = System.currentTimeMillis() - session.startTimestamp
            delay(1000)
        }
    }
    
    val latestUsage = session.history.lastOrNull()?.usage
    val (downloadBps, uploadBps) = if (latestUsage != null) {
        latestUsage.rxBps to latestUsage.txBps
    } else {
        0L to 0L
    }
    
    val isDownloadDominant = downloadBps >= uploadBps
    val dominatingBps = if (isDownloadDominant) downloadBps else uploadBps
    val icon = if (isDownloadDominant) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward
    val iconColor = if (isDownloadDominant) ColorGraphDownload else ColorGraphUpload
    val (value, unit) = formatBitsPerSecond(dominatingBps, speedUnit)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = formatDurationDynamic(duration),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(16.dp)
            )
            Row(verticalAlignment = Alignment.Bottom) {
                RollingNumberText(
                    value = value,
                    textStyle = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionRateGraph(
    modifier: Modifier = Modifier,
    rateHistory: List<LiveDataPoint>,
    overlayAlpha: Float,
    onUserInteraction: (Long) -> Unit,
) {
    val initialScale = if (rateHistory.size > POINTS_IN_30_SECONDS) {
        rateHistory.size.toFloat() / POINTS_IN_30_SECONDS
    } else 1f

    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()

    var scale by remember { mutableStateOf(initialScale) }
    val scrollState = rememberScrollState()
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(rateHistory.size) {
        val idleDuration = System.currentTimeMillis() - lastInteractionTime
        if (lastInteractionTime == 0L || idleDuration > 5000L) {
            isAutoScrolling = true
            delay(50L)
            scrollState.animateScrollTo(
                scrollState.maxValue,
                animationSpec = tween(durationMillis = 300, easing = LinearEasing)
            )
            isAutoScrolling = false
        }
    }

    LaunchedEffect(lastInteractionTime) {
        if (lastInteractionTime == 0L) return@LaunchedEffect
        delay(5000L)
        if (System.currentTimeMillis() - lastInteractionTime >= 5000L) {
            animate(initialValue = scale, targetValue = initialScale) { value, _ ->
                scale = value
            }
        }
    }

    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val context = LocalContext.current
    val timeFormat = remember(context) {
        DateFormat.getTimeFormat(context)
    }
    val satoshiTypeface = remember(context) {
        androidx.core.content.res.ResourcesCompat.getFont(context, com.vinnovateit.latch.R.font.satoshi_regular) ?: Typeface.DEFAULT
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, initialScale * 3)
                    val time = System.currentTimeMillis()
                    lastInteractionTime = time
                    onUserInteraction(time)
                }
            }
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val containerWidth = constraints.maxWidth
            val containerHeight = constraints.maxHeight
            val density = LocalDensity.current

            LaunchedEffect(scrollState.isScrollInProgress) {
                if (scrollState.isScrollInProgress && !isAutoScrolling) {
                    val time = System.currentTimeMillis()
                    lastInteractionTime = time
                    onUserInteraction(time)
                }
            }

            if (containerWidth > 0 && rateHistory.size > 1) {
                var maxSpeed by remember { mutableStateOf(1L) }
                val xAxisSpace = with(density) { 40.dp.toPx() }
                val graphDrawHeight = containerHeight - xAxisSpace

                LaunchedEffect(rateHistory, scrollState.value, scale) {
                    if (rateHistory.size < 2) return@LaunchedEffect
                    val totalGraphWidthPx = containerWidth * scale
                    val firstTimestamp = rateHistory.first().timestamp
                    val totalDuration = rateHistory.last().timestamp - firstTimestamp
                    if (totalDuration <= 0) return@LaunchedEffect

                    val visibleStartRatio = scrollState.value / totalGraphWidthPx
                    val visibleEndRatio = (scrollState.value + containerWidth) / totalGraphWidthPx

                    val visibleStartTime = firstTimestamp + (totalDuration * visibleStartRatio).toLong()
                    val visibleEndTime = firstTimestamp + (totalDuration * visibleEndRatio).toLong()

                    val visiblePoints = rateHistory.filter {
                        it.timestamp in visibleStartTime..visibleEndTime
                    }

                    val maxSpeedCombined = visiblePoints.maxOfOrNull { it.usage.rxBps + it.usage.txBps } ?: 0L
                    maxSpeed = if (visiblePoints.isEmpty()) 1L else maxSpeedCombined.coerceAtLeast(1L)
                }

                val animatedMaxSpeed by animateFloatAsState(
                    targetValue = maxSpeed.toFloat(),
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    label = "maxSpeedAnimation"
                )

                val graphData by remember(rateHistory, containerWidth, containerHeight, scale, animatedMaxSpeed) {
                    derivedStateOf {
                        createGraphPaths(
                            history = rateHistory,
                            width = containerWidth * scale,
                            height = graphDrawHeight,
                            maxRate = animatedMaxSpeed.coerceAtLeast(1f),
                            graphHeightScale = GRAPH_HEIGHT_SCALE
                        )
                    }
                }

                val canvasWidthDp = with(density) { (containerWidth * scale).toDp() }

                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier.horizontalScroll(scrollState),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        Canvas(
                            modifier = Modifier
                                .width(canvasWidthDp)
                                .fillMaxHeight()
                        ) {
                            val totalBrush = Brush.verticalGradient(listOf(primaryColor.copy(alpha = 0.4f), Color.Transparent))

                            if (!usePureBlack) {
                                drawPath(graphData.totalPath, brush = totalBrush)
                            }

                            drawPath(graphData.lineTotalPath, color = primaryColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

                            val xAxisY = graphDrawHeight
                            drawLine(
                                color = onSurfaceColor.copy(alpha = 0.3f),
                                start = Offset(0f, xAxisY),
                                end = Offset(size.width, xAxisY),
                                strokeWidth = 1.dp.toPx()
                            )

                            val axisPaint = Paint().apply {
                                color = onSurfaceColor.copy(alpha = 0.7f).toArgb()
                                textAlign = Paint.Align.CENTER
                                textSize = 10.sp.toPx()
                                typeface = satoshiTypeface
                            }
                            val startTimestamp = rateHistory.first().timestamp
                            val totalDuration = max(rateHistory.last().timestamp - startTimestamp, 1L)
                            val divisions = 5
                            for (i in 0 until divisions) {
                                val fraction = i.toFloat() / divisions
                                val x = size.width * fraction
                                val time = startTimestamp + (totalDuration * fraction).toLong()
                                drawContext.canvas.nativeCanvas.drawText(
                                    timeFormat.format(Date(time)).uppercase(),
                                    x,
                                    xAxisY + 22.dp.toPx(),
                                    axisPaint
                                )
                            }
                        }
                    }

                    if (overlayAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = overlayAlpha }
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun DataUsageCircle(
    modifier: Modifier = Modifier,
    data: DataUsage,
    isAmoled: Boolean = false
) {
    var mode by remember { mutableStateOf(DisplayMode.TOTAL) }
    LaunchedEffect(mode) {
        if (mode != DisplayMode.TOTAL) {
            delay(3000)
            mode = DisplayMode.TOTAL
        }
    }

    val totalBytes = (data.rxBytes + data.txBytes).coerceAtLeast(1L)
    val rawDownloadFraction = data.rxBytes.toFloat() / totalBytes
    val rawUploadFraction = data.txBytes.toFloat() / totalBytes

    val minFraction = 0.05f
    val (downloadFraction, uploadFraction) = when {
        rawDownloadFraction < minFraction && rawUploadFraction < minFraction -> 0.5f to 0.5f
        rawDownloadFraction < minFraction -> minFraction to 1f - minFraction
        rawUploadFraction < minFraction -> 1f - minFraction to minFraction
        else -> rawDownloadFraction to rawUploadFraction
    }

    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val angle = (atan2(offset.y - centerY, offset.x - centerX) * 180 / Math.PI + 450) % 360

                mode = if (angle < downloadFraction * 360) DisplayMode.DOWNLOAD else DisplayMode.UPLOAD
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = if (isAmoled) 4.dp.toPx() else 12.dp.toPx()
            val gapAngle = 4f
            val totalSweep = 360f - gapAngle * 2

            val downloadSweep = downloadFraction * totalSweep
            val uploadSweep = uploadFraction * totalSweep

            drawArc(
                color = outlineColor.copy(alpha = 0.2f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            drawArc(
                color = ColorGraphDownload,
                startAngle = -90f + gapAngle,
                sweepAngle = downloadSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
            drawArc(
                color = ColorGraphUpload,
                startAngle = -90f + gapAngle + downloadSweep + gapAngle,
                sweepAngle = uploadSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
            )
        }

        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn()).togetherWith(
                    slideOutVertically { height -> -height } + fadeOut()
                ).using(SizeTransform(clip = false))
            },
            label = "DataUsageCircleTransition"
        ) { targetMode ->
            val (value, unit, color, icon) = when (targetMode) {
                DisplayMode.DOWNLOAD -> Quadruple(
                    formatBytes(data.rxBytes).first,
                    formatBytes(data.rxBytes).second,
                    ColorGraphDownload,
                    Icons.Rounded.ArrowDownward
                )
                DisplayMode.UPLOAD -> Quadruple(
                    formatBytes(data.txBytes).first,
                    formatBytes(data.txBytes).second,
                    ColorGraphUpload,
                    Icons.Rounded.ArrowUpward
                )
                DisplayMode.TOTAL -> Quadruple(
                    formatBytes(totalBytes).first,
                    formatBytes(totalBytes).second,
                    MaterialTheme.colorScheme.onSurface,
                    null
                )
            }
            DataUsageValueBlock(value, unit, color, icon)
        }
    }
}

data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@Composable
private fun DataUsageValueBlock(
    value: String,
    unit: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(5.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun RollingNumberText(
  value: String,
  textStyle: androidx.compose.ui.text.TextStyle
) {
  Text(
    text = value,
    style = textStyle,
    textAlign = androidx.compose.ui.text.style.TextAlign.Center
  )
}