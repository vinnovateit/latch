package com.vinnovateit.latch.ui

import androidx.compose.runtime.Composable
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.status_authenticating
import com.vinnovateit.latch.desktop.resources.status_checking_internet
import com.vinnovateit.latch.desktop.resources.status_connected
import com.vinnovateit.latch.desktop.resources.status_disconnected_message
import com.vinnovateit.latch.desktop.resources.status_initializing
import com.vinnovateit.latch.desktop.resources.status_logging_out
import com.vinnovateit.latch.desktop.resources.status_login_failed
import com.vinnovateit.latch.desktop.resources.status_logout_failed
import com.vinnovateit.latch.desktop.resources.status_not_on_wifi
import com.vinnovateit.latch.desktop.resources.status_wifi_off
import org.jetbrains.compose.resources.stringResource

/**
 * Maps the engine's typed status onto the existing status_* string resources.
 *
 * The Android app resolved these inside the foreground service. Moving the
 * mapping into the UI is what keeps the engine free of resource dependencies and
 * lets the tray render plain English instead.
 */
@Composable
fun ConnectionStatus.displayText(): String = when (this) {
    is ConnectionStatus.Idle -> ""
    is ConnectionStatus.Success -> stringResource(Res.string.status_connected)
    is ConnectionStatus.Connecting -> when (step) {
        ConnectionStatus.Step.Initializing -> stringResource(Res.string.status_initializing)
        ConnectionStatus.Step.CheckingInternet -> stringResource(Res.string.status_checking_internet)
        ConnectionStatus.Step.Authenticating -> stringResource(Res.string.status_authenticating)
        ConnectionStatus.Step.LoggingOut -> stringResource(Res.string.status_logging_out)
    }

    is ConnectionStatus.Failed -> when (reason) {
        ConnectionStatus.Reason.WifiOff -> stringResource(Res.string.status_wifi_off)
        ConnectionStatus.Reason.NotOnWifi -> stringResource(Res.string.status_not_on_wifi)
        // Desktop-only: no Android string exists for the SSID gate.
        ConnectionStatus.Reason.NotTargetNetwork -> "Not a known Latch network"
        // Deliberately names the fix, not just the fault: the user cannot guess
        // that a working VPN is what stopped a Wi-Fi login from going through.
        ConnectionStatus.Reason.VpnRouting ->
            "VPN is routing your traffic — pause it, or add phc.prontonetworks.com " +
                "to its split-tunnel exclusions"
        ConnectionStatus.Reason.NoCredentials -> stringResource(Res.string.status_login_failed)
        ConnectionStatus.Reason.LoginFailed -> stringResource(Res.string.status_login_failed)
        ConnectionStatus.Reason.LogoutFailed -> stringResource(Res.string.status_logout_failed)
        ConnectionStatus.Reason.Disconnected ->
            stringResource(Res.string.status_disconnected_message)
        ConnectionStatus.Reason.NetworkTimeoutAfterLogin -> "Network timeout after login"
    }
}
