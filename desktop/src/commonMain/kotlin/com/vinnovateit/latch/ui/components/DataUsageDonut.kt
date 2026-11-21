package com.vinnovateit.latch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.core.model.DataUsage
import com.vinnovateit.latch.core.stats.formatBytes
import com.vinnovateit.latch.ui.theme.ColorGraphDownload
import com.vinnovateit.latch.ui.theme.ColorGraphUpload
import com.vinnovateit.latch.ui.theme.satoshiFontFamily

/**
 * The split ring from the Android session card: one arc for received bytes, one
 * for sent, with the total in the middle.
 *
 * The 5% floor on each arc is carried over from Android and is deliberate -- a
 * session that is 99.5% download would otherwise draw an upload arc too thin to
 * see, which reads as "no upload at all" rather than "a little".
 *
 * The Android version lets you tap a segment to swap the centre readout to that
 * direction. Here the two figures are simply printed beside the ring instead,
 * since a desktop card has the width for both.
 */
@Composable
internal fun DataUsageDonut(
    data: DataUsage,
    modifier: Modifier = Modifier,
    isAmoled: Boolean = false,
    dlColor: Color = ColorGraphDownload,
    ulColor: Color = ColorGraphUpload,
) {
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
    val (totalValue, totalUnit) = formatBytes(data.rxBytes + data.txBytes)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
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
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
            drawArc(
                color = dlColor,
                startAngle = -90f + gapAngle,
                sweepAngle = downloadSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
            drawArc(
                color = ulColor,
                startAngle = -90f + gapAngle + downloadSweep + gapAngle,
                sweepAngle = uploadSweep,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = totalValue,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                fontFamily = satoshiFontFamily(FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = totalUnit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
