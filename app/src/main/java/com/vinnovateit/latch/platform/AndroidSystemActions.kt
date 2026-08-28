package com.vinnovateit.latch.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.SystemActions

/** Autostart-at-login has no Android equivalent -- PlatformCapabilities.supportsAutostart is false. */
class AndroidSystemActions(
    private val context: Context,
    private val logger: Logger,
) : SystemActions {
    private companion object {
        const val TAG = "AndroidSystemActions"
    }

    override fun openWifiSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            logger.e(TAG, "No activity found for ACTION_WIFI_SETTINGS", e)
        }
    }

    override fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
        }
    }

    override fun setAutostart(enabled: Boolean) = Unit

    override fun isAutostartEnabled(): Boolean = false
}
