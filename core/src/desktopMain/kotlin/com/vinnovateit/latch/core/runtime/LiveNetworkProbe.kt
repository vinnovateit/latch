package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.core.wifi.probeCampusNetwork

/**
 * Reads the live network state without touching the engine.
 *
 * A CLI one-shot owns the runtime only for the length of one command, so its
 * engine is created cold: `isLatched` is still at its initial `false` and no
 * status has been posted yet. Reporting that as the answer to `--status` said
 * "latched: no" on a machine that was, in fact, latched -- by the desktop app,
 * by a previous session, or by anything else that had already cleared the
 * portal.
 */
suspend fun probeRuntimeSnapshot(
    wifi: WifiPlatform,
    transport: HttpTransport,
    logger: Logger,
): RuntimeSnapshot {
    val state = probeCampusNetwork(wifi, transport, logger)
    return RuntimeSnapshot(
        connection = when {
            !state.connected -> "disconnected"
            state.online -> "online"
            else -> "connected"
        },
        ssid = state.ssid,
        latched = state.latched,
    )
}
