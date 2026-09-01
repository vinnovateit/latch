package com.vinnovateit.latch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.ui.theme.modernizFontFamily
import com.vinnovateit.latch.ui.theme.satoshiFontFamily

@Composable
internal fun HowItWorksDialog(onDismiss: () -> Unit) {
    LatchBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "How it Works",
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = modernizFontFamily(),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                HowItWorksRow(
                    icon = LatchIcons.Wifi,
                    text = "Latch detects when you join a captive-portal network and logs you in silently using your saved credentials."
                )
                HowItWorksRow(
                    icon = LatchIcons.BarChart,
                    text = "Once connected, you can watch your real-time stats and data usage sampled every second."
                )
                HowItWorksRow(
                    icon = LatchIcons.DesktopWindows,
                    text = "Latch runs quietly in the system tray. Disable auto-connect from settings or press disconnect to pause."
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { dismiss() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth(0.7f).height(50.dp)
            ) {
                Text(
                    text = "GOT IT",
                    fontSize = 16.sp,
                    fontFamily = satoshiFontFamily(),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun HowItWorksRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp).padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = satoshiFontFamily(),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
