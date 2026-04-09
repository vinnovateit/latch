package com.vinnovateit.latch.features.onboarding.pages

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.vinnovateit.latch.features.onboarding.components.SlideContent
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily

import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NotificationPermissionPage(
    slide: SlideContent,
    onPermissionGranted: () -> Unit
) {
    val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            onPermissionGranted()
        }
    }

    val isGranted = permissionsState.allPermissionsGranted

    LaunchedEffect(isGranted) {
        if (isGranted) onPermissionGranted()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = slide.title,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 28.dp)
                .statusBarsPadding()
                .align(Alignment.TopStart)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // --- FIX: This inner Column is now scrollable ---
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
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
                    onClick = {
                        if (!isGranted) {
                            permissionsState.launchMultiplePermissionRequest()
                        }
                    },
                    enabled = !isGranted,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
                ) {
                    if (isGranted) {
                        Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (isGranted) "Permission Granted" else "Grant Permission",
                        fontFamily = SatoshiFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NotificationPermissionPageLandscape(slide: SlideContent, onPermissionGranted: () -> Unit) {
    val permissionsToRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            onPermissionGranted()
        }
    }

    val isGranted = permissionsState.allPermissionsGranted

    LaunchedEffect(isGranted) {
        if (isGranted) onPermissionGranted()
    }

    PageScaffoldLandscape(slide) {
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = { if (!isGranted) permissionsState.launchMultiplePermissionRequest() },
            enabled = !isGranted,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
        ) {
            if (isGranted) {
                Icon(Icons.Rounded.Check, null)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (isGranted) "Permission Granted" else "Grant Permission",
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}