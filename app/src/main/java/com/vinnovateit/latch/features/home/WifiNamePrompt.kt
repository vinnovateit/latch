package com.vinnovateit.latch.features.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.platform.android.WifiNameAccess
import com.vinnovateit.latch.core.platform.android.wifiNameAccess
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.features.wifi.background.ForegroundService

/** Rechecks the network now that its name can be read. */
private fun recheckNetwork(context: Context) {
    context.startService(
        Intent(context, ForegroundService::class.java).apply {
            action = if (SettingsManager.autoLogin.value) {
                ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
            } else {
                ForegroundService.ACTION_SILENT_CHECK
            }
        }
    )
}

/**
 * Asks for what Android requires before it reveals the Wi-Fi name, which Latch
 * needs in order to send a password only on campus networks. Reappears on the
 * next launch after "Not now", since automatic login stays off until then.
 */
@Composable
fun WifiNamePrompt() {
    val context = LocalContext.current
    var access by remember { mutableStateOf(wifiNameAccess(context)) }
    var dismissed by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        val previous = access
        access = wifiNameAccess(context)
        if (access == WifiNameAccess.Available && previous != WifiNameAccess.Available) recheckNetwork(context)
    }

    // Permission and Location can both change in system Settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        refresh()
        val denied = result[Manifest.permission.ACCESS_FINE_LOCATION] != true
        val activity = context as? Activity
        // After a permanent denial Android shows no dialog; only Settings is left.
        if (denied && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        }
    }

    if (access == WifiNameAccess.Available || dismissed) return

    val locationOff = access == WifiNameAccess.LocationOff
    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text(stringResource(R.string.wifi_name_prompt_title)) },
        text = {
            Text(
                stringResource(
                    if (locationOff) R.string.wifi_name_prompt_location_off else R.string.wifi_name_prompt_text
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (locationOff) {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } else {
                        launcher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                }
            ) {
                Text(stringResource(if (locationOff) R.string.wifi_name_prompt_turn_on else R.string.wifi_name_prompt_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.wifi_name_prompt_not_now)) }
        },
    )
}
