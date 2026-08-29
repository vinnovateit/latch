package com.vinnovateit.latch.platform

import android.content.Context
import android.provider.Settings
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.wifi.ConnectionStatus as SharedStatus
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus as LegacyStatus

/**
 * Translates the shared engine's typed status onto Android's own
 * ConnectionStatus (which UI/notification/widget code already reads via
 * ConnectionStatusManager). Temporary: once TileService/Widget/ViewModel are
 * repointed directly at the engine's status/isLatched, this and
 * ConnectionStatusManager both go away.
 */
fun SharedStatus.toLegacyStatus(context: Context): LegacyStatus = when (this) {
    is SharedStatus.Idle -> LegacyStatus.Idle
    is SharedStatus.Success -> LegacyStatus.Success
    is SharedStatus.Connecting -> LegacyStatus.Companion.Connecting(context.getString(step.toStringRes()))
    is SharedStatus.Failed -> LegacyStatus.Failed(reason.toMessage(context))
}

private fun SharedStatus.Step.toStringRes(): Int = when (this) {
    SharedStatus.Step.Initializing -> R.string.status_initializing
    SharedStatus.Step.CheckingInternet -> R.string.status_checking_internet
    SharedStatus.Step.Authenticating -> R.string.status_authenticating
    SharedStatus.Step.LoggingOut -> R.string.status_logging_out
}

private fun SharedStatus.Reason.toMessage(context: Context): String = when (this) {
    SharedStatus.Reason.WifiOff -> context.getString(R.string.status_wifi_off)
    SharedStatus.Reason.NotOnWifi -> context.getString(R.string.status_not_on_wifi)
    SharedStatus.Reason.NoCredentials -> context.getString(R.string.status_login_failed)
    SharedStatus.Reason.LoginFailed -> context.getString(R.string.status_login_failed)
    SharedStatus.Reason.LogoutFailed -> context.getString(R.string.status_logout_failed)
    SharedStatus.Reason.Disconnected -> context.getString(R.string.status_disconnected_message)
    SharedStatus.Reason.NetworkTimeoutAfterLogin -> "Network timeout after login"
    // No exact match on Android before -- the app has never distinguished
    // this from a generic login failure, since currentSsid() is always null
    // and isVitCampusSsid()/isTargetNetwork() only reach this via the portal
    // host DNS check, not an SSID mismatch.
    SharedStatus.Reason.NotTargetNetwork -> "Not a known Latch network"
    SharedStatus.Reason.DnsResolutionFailed -> if (isPrivateDnsActive(context)) {
        context.getString(R.string.status_private_dns_blocking)
    } else {
        context.getString(R.string.status_unsupported_network)
    }
}

private fun isPrivateDnsActive(context: Context): Boolean = try {
    val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
    mode != null && mode != "off"
} catch (e: Exception) {
    false
}
