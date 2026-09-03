package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.WifiPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Matches the engine's own portal-probe budget in checkAndAct. */
private const val PROBE_TIMEOUT_MS = 3_500L

private const val NO_CONTENT = 204

/** What the network says about itself right now, independent of any engine state. */
data class CampusNetworkState(
    val connected: Boolean,
    val online: Boolean,
    val ssid: String?,
) {
    /**
     * Latch's own definition of latched: real internet on a campus network,
     * i.e. someone has already cleared the portal. Real internet on a cafe
     * network is online but not latched.
     */
    val latched: Boolean get() = online && isVitCampusSsid(ssid)
}

/**
 * Asks the network, rather than this process's memory, whether the portal has
 * been cleared.
 *
 * Read-only by construction: it starts no session, posts no status and attempts
 * no login, so it is safe on paths that must not change state -- a `--status`
 * query, or deciding whether a logout has anything to log out of.
 */
suspend fun probeCampusNetwork(
    wifi: WifiPlatform,
    transport: HttpTransport,
    logger: Logger,
): CampusNetworkState = withContext(Dispatchers.IO) {
    val ssid = wifi.currentSsid()
    if (!wifi.isConnectedToWifi()) return@withContext CampusNetworkState(false, false, ssid)

    val detector = CaptivePortalDetector(transport, logger)
    val code = withTimeoutOrNull(PROBE_TIMEOUT_MS) { detector.checkPortalStatus(wifi.activeHandle()) } ?: -1
    CampusNetworkState(connected = true, online = code == NO_CONTENT, ssid = ssid)
}
