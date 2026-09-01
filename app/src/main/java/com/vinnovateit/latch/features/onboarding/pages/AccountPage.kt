package com.vinnovateit.latch.features.onboarding.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.platform.android.StoredCredentials
import com.vinnovateit.latch.features.onboarding.components.SlideContent
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily

@Composable
fun SetUpAccountPage(
    slide: SlideContent,
    onCredentialsClick: () -> Unit
) {
    val context = LocalContext.current
    var credentialsExist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        credentialsExist = StoredCredentials.credentialsExist(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding() // Handles status and navigation bars
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = slide.title,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp),
            textAlign = TextAlign.Start
        )

        // Spacer to push content down from the title
        Spacer(modifier = Modifier.height(100.dp))

        // Main content (icon, text, button)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(180.dp)
            ) { slide.icon() }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = slide.description.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
                textAlign = TextAlign.Center,
                fontFamily = SatoshiFontFamily
            )

            Spacer(modifier = Modifier.height(44.dp))

            Button(
                onClick = onCredentialsClick,
                enabled = !credentialsExist,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
            ) {
                if (credentialsExist) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (credentialsExist) "Credentials Set" else "Set Up Credentials",
                    fontFamily = SatoshiFontFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f).heightIn(min = 32.dp))

        // Bottom notice Surface
        Surface(
            modifier = Modifier.padding(bottom = 50.dp), // Padding from the absolute bottom
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.encrypted_24px),
                    contentDescription = "Security Notice",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = buildAnnotatedString {
                        append("Latch does not collect your data. Your credentials are encrypted and ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("stored securely on your device.")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


@Composable
fun SetUpAccountPageLandscape(slide: SlideContent, onCredentialsClick: () -> Unit) {
    val context = LocalContext.current
    var credentialsExist by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        credentialsExist = StoredCredentials.credentialsExist(context)
    }

    PageScaffoldLandscape(slide) {
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onCredentialsClick,
            enabled = !credentialsExist,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
        ) {
            if (credentialsExist) {
                Icon(Icons.Rounded.Check, null)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (credentialsExist) "Credentials Set" else "Set Up Credentials",
                fontFamily = SatoshiFontFamily,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(R.drawable.encrypted_24px), "Security Notice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(buildAnnotatedString {
                    append("Your credentials are encrypted and ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("stored securely on your device.") }
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}