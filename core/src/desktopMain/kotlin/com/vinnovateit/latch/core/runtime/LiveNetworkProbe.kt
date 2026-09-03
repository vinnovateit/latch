package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.core.wifi.CaptivePortalDetector
import com.vinnovateit.latch.core.wifi.isVitCampusSsid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Matches the engine's own portal-probe budget in checkAndAct. */
private const val PROBE_TIMEOUT_MS = 3_500L

private const val NO_CONTENT = 204

/**
 * Reads the live network state without touching the engine.
 *
 * A CLI one-shot owns the runtime only for the length of one command, so its
 * engine is created cold: `isLatched` is still at its initial `false` and no
 * status has been posted yet. Reporting that as the answer to `--status` said
 * "latched: no" on a machine that was, in fact, latched -- by the desktop app,
 * by a previous session, or by anything else that had already cleared the
 * portal. Probing the portal directly answers the question the user actually
 * asked, and, unlike submitting a command to the engine, it starts no session,
 * posts no status, and attempts no login: `--status` stays a pure read.
 */
suspend fun probeRuntimeSnapshot(
    wifi: WifiPlatform,
    transport: HttpTransport,
    logger: Logger,
): RuntimeSnapshot = withContext(Dispatchers.IO) {
    val ssid = wifi.currentSsid()
    if (!wifi.isConnectedToWifi()) return@withContext RuntimeSnapshot("disconnected", ssid, false)

    val detector = CaptivePortalDetector(transport, logger)
    val handle = wifi.activeHandle()
    val code = withTimeoutOrNull(PROBE_TIMEOUT_MS) { detector.checkPortalStatus(handle) } ?: -1
    val online = code == NO_CONTENT

    // Latched means Latch's own definition of it: real internet on a campus
    // network. Real internet on a cafe network is online but not latched.
    RuntimeSnapshot(
        connection = if (online) "online" else "connected",
        ssid = ssid,
        latched = online && isVitCampusSsid(ssid),
    )
}
