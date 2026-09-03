package com.vinnovateit.latch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Desktop port of Android's "How it works" bottom sheet, adapted as an AlertDialog
 * (bottom sheets are a touch-drag gesture with no desktop keyboard/mouse analogue).
 */
@Composable
internal fun HowItWorksDialog(onDismiss: () -> Unit) {
    LatchBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "How Latch works",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontFamily = com.vinnovateit.latch.ui.theme.satoshiFontFamily(),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            HowItWorksRow(
                icon = LatchIcons.Wifi,
                title = "Automatic Wi-Fi login",
                body = "Latch detects when you join a captive-portal network and logs you in silently using your saved credentials.",
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksRow(
                icon = LatchIcons.BarChart,
                title = "Live session stats",
                body = "Download and upload speeds are sampled every second and displayed on the home screen while you're connected.",
            )
            Spacer(Modifier.height(16.dp))
            HowItWorksRow(
                icon = LatchIcons.DesktopWindows,
                title = "Lives in the system tray",
                body = "Latch runs quietly in the background. The tray icon turns red when you're latched and grey when you're not.",
            )
            Spacer(Modifier.height(20.dp))
            TextButton(
                onClick = { dismiss() },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Got it", fontFamily = com.vinnovateit.latch.ui.theme.satoshiFontFamily())
            }
        }
    }
}

@Composable
private fun HowItWorksRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
