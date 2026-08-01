package com.vinnovateit.latch.core.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Typed status, replacing the Android version which carried pre-resolved
 * user-facing strings.
 *
 * Two reasons for the change: the engine has no Compose context to resolve
 * strings in, and the tray needs plain English while the window needs localised
 * resources. The UI maps Step/Reason onto the existing status_* string resources.
 *
 * Also flattens `Connecting` out of the companion object, which in the Android
 * app produced the awkward `ConnectionStatus.Companion.Connecting`.
 */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Success : ConnectionStatus
    data class Connecting(val step: Step) : ConnectionStatus
    data class Failed(val reason: Reason) : ConnectionStatus

    enum class Step { Initializing, CheckingInternet, Authenticating, LoggingOut }

    enum class Reason {
        WifiOff,
        NotOnWifi,
        NotTargetNetwork,
        /** A VPN holds the default route, so portal traffic never reaches the portal. */
        VpnRouting,
        NoCredentials,
        LoginFailed,
        LogoutFailed,
        Disconnected,
        NetworkTimeoutAfterLogin,
    }
}

/**
 * Holds the current status and auto-resets to Idle 2s after a terminal state,
 * matching the Android behaviour.
 *
 * The Android version also enqueued a WorkManager job on every status change to
 * refresh the Glance widget; that is gone.
 */
object ConnectionStatusManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status = _status.asStateFlow()

    fun postStatus(newStatus: ConnectionStatus) {
        _status.value = newStatus

        if (newStatus is ConnectionStatus.Success || newStatus is ConnectionStatus.Failed) {
            scope.launch {
                delay(2000)
                if (_status.value == newStatus) {
                    _status.value = ConnectionStatus.Idle
                }
            }
        }
    }
}
