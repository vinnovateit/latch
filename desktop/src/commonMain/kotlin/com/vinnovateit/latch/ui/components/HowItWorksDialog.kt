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

/**
 * Desktop port of Android's "How it works" bottom sheet. The sheet container is
 * desktop-specific (see [LatchBottomSheet]), but its content mirrors the Android
 * original so the two platforms read the same.
 */
@Composable
internal fun HowItWorksDialog(onDismiss: () -> Unit) {
    LatchBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "How it Works",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = modernizFontFamily(),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                HowItWorksRow(
                    icon = LatchIcons.Wifi,
                    text = "If any VIT Wi-Fi is auto-connected, Latch retrieves your encrypted credentials and attempts to connect.",
                )
                HowItWorksRow(
                    icon = LatchIcons.BarChart,
                    text = "Once connected, you can watch your real-time stats and data usage.",
                )
                HowItWorksRow(
                    icon = LatchIcons.PowerSettingsNew,
                    text = "If you do not wish this to work, disable auto-connect from settings or press the disconnect button (which also disables auto-connect).",
                )
            }
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { dismiss() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth(0.7f).height(56.dp),
            ) {
                Text(
                    text = "GOT IT",
                    fontSize = 18.sp,
                    fontFamily = satoshiFontFamily(FontWeight.Bold),
                    fontWeight = FontWeight.Bold,
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
            modifier = Modifier.size(28.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = satoshiFontFamily(FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
