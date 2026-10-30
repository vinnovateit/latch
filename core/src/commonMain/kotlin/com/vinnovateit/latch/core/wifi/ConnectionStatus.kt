package com.vinnovateit.latch.core.wifi

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
        /**
         * The OS hides the network's name from Latch (macOS without Location
         * access), so it cannot be confirmed as VIT and no login is attempted.
         */
        NetworkNameHidden,
        NoCredentials,
        LoginFailed,
        LogoutFailed,
        Disconnected,
        NetworkTimeoutAfterLogin,
        /**
         * The portal probe host couldn't be resolved at all (as opposed to
         * resolving but timing out/refusing) -- commonly Private DNS blocking
         * captive-portal detection. Whether to show a DNS-specific message or
         * a generic one is a presentation-layer decision (e.g. Android can
         * additionally check its own Private DNS setting); the engine only
         * reports that resolution failed.
         */
        DnsResolutionFailed,
    }
}

