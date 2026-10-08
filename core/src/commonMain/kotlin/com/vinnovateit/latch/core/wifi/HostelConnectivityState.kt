package com.vinnovateit.latch.core.wifi

enum class HostelConnectivityState {
    OFFLINE,
    WIFI_CONNECTED,
    CHECKING,
    CAPTIVE_PORTAL,
    AUTHENTICATING,
    VERIFYING,
    CONNECTED,
    DEGRADED,
    RECOVERING,
    FAILED,
}

data class HostelConnectivitySnapshot(
    val state: HostelConnectivityState,
    val ssid: String? = null,
    val connectivityLevel: String? = null,
    val latencyMs: Long? = null,
    val lastSuccessfulProbe: Long? = null,
    val lastAuthAttempt: Long? = null,
    val lastSuccessfulAuth: Long? = null,
    val retryCount: Int = 0,
    val failureCount: Int = 0,
    val error: String? = null,
)
