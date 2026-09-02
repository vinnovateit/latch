package com.vinnovateit.latch.features.home.components

import android.annotation.SuppressLint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.common.util.createGraphPaths
import com.vinnovateit.latch.core.model.LiveDataPoint
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.ui.theme.ColorTransparent
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

const val GRAPH_HEIGHT_SCALE = 0.70f
val Y_AXIS_WIDTH = 70.dp
const val POINTS_IN_30_SECONDS = 20

fun calculateNiceMaxSpeed(maxSpeed: Float): Float {
  if (maxSpeed <= 0f) return 1f
  val exponent = floor(log10(maxSpeed))
  val fraction = maxSpeed / 10f.pow(exponent)

  val niceFraction = when {
    fraction <= 1 -> 1f
    fraction <= 2 -> 2f
    fraction <= 5 -> 5f
    else -> 10f
  }
  return niceFraction * 10f.pow(exponent)
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun HomeScreenGraph(
  modifier: Modifier = Modifier,
  rateHistory: List<LiveDataPoint>,
  speedUnit: String
) {
  val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
  
  val initialScale = if (rateHistory.size > POINTS_IN_30_SECONDS) {
    rateHistory.size.toFloat() / POINTS_IN_30_SECONDS.toFloat()
  } else {
    1f
  }
  var scale by remember { mutableStateOf(initialScale) }
  val scrollState = rememberScrollState()
  var lastInteraction by remember { mutableLongStateOf(0L) }

  var isAutoScrolling by remember { mutableStateOf(false) }

  LaunchedEffect(rateHistory.size) {
    val idleDuration = System.currentTimeMillis() - lastInteraction
    if (lastInteraction == 0L || idleDuration > 5000L) {
      isAutoScrolling = true
      scrollState.animateScrollTo(
        scrollState.maxValue,
        animationSpec = tween(durationMillis = 500)
      )
      isAutoScrolling = false
    }
  }

  LaunchedEffect(lastInteraction) {
    if (lastInteraction == 0L) return@LaunchedEffect
    delay(5000L)
    if (scale != initialScale) {
      animate(initialValue = scale, targetValue = initialScale) { value, _ ->
        scale = value
      }
    }
  }


  Box(
    modifier = modifier
      .pointerInput(Unit) {
        detectTransformGestures { _, _, zoom, _ ->
          scale = (scale * zoom).coerceIn(1f, initialScale * 3)
          lastInteraction = System.currentTimeMillis()
        }
      }
  ) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val containerWidthPx = constraints.maxWidth
      val containerHeightPx = constraints.maxHeight

      LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress && !isAutoScrolling) {
          lastInteraction = System.currentTimeMillis()
        }
      }

      if (containerWidthPx > 0 && rateHistory.size > 1) {

        var maxSpeed by remember { mutableLongStateOf(1L) }

        LaunchedEffect(rateHistory, scrollState.isScrollInProgress, scale) {
          if (rateHistory.size < 2 || scrollState.isScrollInProgress) return@LaunchedEffect

          val totalGraphWidthPx = containerWidthPx * scale
          val firstTimestamp = rateHistory.first().timestamp
          val totalDuration = (rateHistory.last().timestamp - firstTimestamp).coerceAtLeast(1)

          val visibleStartRatio = scrollState.value / totalGraphWidthPx
          val visibleEndRatio = (scrollState.value + containerWidthPx) / totalGraphWidthPx

          val visibleStartTime = firstTimestamp + (totalDuration * visibleStartRatio).toLong()
          val visibleEndTime = firstTimestamp + (totalDuration * visibleEndRatio).toLong()

          val visiblePoints = rateHistory.filter { it.timestamp in visibleStartTime..visibleEndTime }

          maxSpeed = if (visiblePoints.isEmpty()) {
            1L
          } else {
            val maxSpeedCombined = visiblePoints.maxOfOrNull { it.usage.rxBytes + it.usage.txBytes } ?: 0L
            maxSpeedCombined.coerceAtLeast(1L)
          }
        }

        val animatedMaxSpeed by animateFloatAsState(
          targetValue = maxSpeed.toFloat(),
          animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
          ),
          label = "MaxSpeedAnimation"
        )

        val graphData by remember(rateHistory, containerWidthPx, containerHeightPx, scale, animatedMaxSpeed) {
          derivedStateOf {
            val graphWidthPx = containerWidthPx * scale
            createGraphPaths(
              history = rateHistory,
              width = graphWidthPx,
              height = containerHeightPx.toFloat(),
              maxRate = animatedMaxSpeed.coerceAtLeast(1f),
              graphHeightScale = GRAPH_HEIGHT_SCALE
            )
          }
        }

        val canvasWidthDp = with(LocalDensity.current) { (containerWidthPx * scale).toDp() }
        val backgroundColor = MaterialTheme.colorScheme.background
        val onBackgroundColor = MaterialTheme.colorScheme.onBackground

        Box(modifier = Modifier.fillMaxSize()) {
          // Layer 1: The Scrollable Graph
          Box(
            modifier = Modifier
              .fillMaxSize()
              .horizontalScroll(scrollState),
            contentAlignment = Alignment.CenterEnd
          ) {
            val primaryColor = MaterialTheme.colorScheme.primary
            Canvas(
              modifier = Modifier
                .width(canvasWidthDp)
                .fillMaxHeight()
            ) {
              val totalBrush = Brush.verticalGradient(listOf(primaryColor.copy(0.4f), ColorTransparent))

              if (!usePureBlack) {
                  drawPath(graphData.totalPath, brush = totalBrush)
              }
              drawPath(graphData.lineTotalPath, primaryColor, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
          }


        }
      }
    }
  }
}