package com.vinnovateit.latch.features.wifi.detector

import android.content.Context
import android.provider.Settings

object PrivateDnsChecker {

    /**
     * Checks if Private DNS (DNS-over-TLS) is enabled on the device.
     * Values can typically be: "off", "opportunistic", or "hostname" (strict mode).
     *
     * @param context the application context.
     * @return true if Private DNS is enabled (not "off"), or false if it is "off" or unknown.
     */
    fun isPrivateDnsEnabled(context: Context): Boolean {
        return try {
            val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
            // Default on modern Android is often opportunistic if not explicitly turned off.
            // If it's null, we assume it's off or default (though default is usually opportunistic in Android 9+).
            // We'll warn if it's explicitly opportunistic or hostname.
            mode != null && mode.lowercase() != "off"
        } catch (e: Exception) {
            false
        }
    }
}
