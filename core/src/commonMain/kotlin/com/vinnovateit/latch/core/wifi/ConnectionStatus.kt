package com.vinnovateit.latch.core.wifi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Typed connection status shared across platforms.
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
        NoCredentials,
        LoginFailed,
        LogoutFailed,
        Disconnected,
        NetworkTimeoutAfterLogin,
    }
}

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
