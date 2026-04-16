package com.vinnovateit.latch.features.stats.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.common.util.formatBitsPerSecond
import com.vinnovateit.latch.ui.theme.ColorGraphDownload
import com.vinnovateit.latch.ui.theme.ColorGraphUpload

@Composable
fun LiveSpeedSection(
  isLive: Boolean,
  downloadBps: Long,
  uploadBps: Long,
  speedUnit: String,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    Text(
      text = if (isLive) "LIVE" else "MAX",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.onSurface,
      letterSpacing = 2.sp
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically
    ) {
      SpeedIndicator(
        isDownload = true,
        bytesPerSecond = downloadBps,
        speedUnit = speedUnit,
        icon = Icons.Rounded.ArrowDownward,
        iconColor = ColorGraphDownload
      )

      SpeedIndicator(
        isDownload = false,
        bytesPerSecond = uploadBps,
        speedUnit = speedUnit,
        icon = Icons.Rounded.ArrowUpward,
        iconColor = ColorGraphUpload
      )
    }
  }
}

@Composable
fun SpeedIndicator(
  isDownload: Boolean,
  bytesPerSecond: Long,
  speedUnit: String,
  icon: ImageVector,
  iconColor: Color
) {
  val (value, unit) = formatBitsPerSecond(bytesPerSecond, speedUnit)
  val offsetX = if (isDownload) (-36).dp else 36.dp

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(80.dp)
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .offset(x = offsetX)
          .clip(RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = iconColor,
          modifier = Modifier
            .fillMaxSize()
            .scale(1.5f)
            .alpha(0.5f)
        )
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
      ) {
        RollingNumberText(
          value = value,
          textStyle = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )
        )
        Text(
          text = unit,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

@Composable
fun RollingNumberText(
  value: String,
  textStyle: TextStyle
) {
  var previousValue by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(value) {
    previousValue = value
  }

  Row(
    horizontalArrangement = Arrangement.Center
  ) {
    val previousChars = previousValue?.padStart(value.length, ' ')?.toCharArray() ?: " ".repeat(value.length).toCharArray()
    val currentChars = value.padStart(previousValue?.length ?: value.length, ' ').toCharArray()

    for (i in currentChars.indices) {
      val oldChar = previousChars.getOrNull(i)
      val newChar = currentChars[i]

      AnimatedContent(
        targetState = newChar,
        transitionSpec = {
          if (oldChar != null && newChar.isDigit() && oldChar.isDigit()) {
            if (newChar > oldChar) {
              slideInVertically { it } togetherWith slideOutVertically { -it }
            } else {
              slideInVertically { -it } togetherWith slideOutVertically { it }
            }
          } else {
            fadeIn() togetherWith fadeOut()
          }
        },
        label = "char_animation"
      ) { char ->
        Text(
          text = char.toString(),
          style = textStyle,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}