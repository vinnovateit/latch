package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.InetAddress

internal data class MacNetworkHandle(override val id: String) : NetworkHandle

/**
 * macOS Wi-Fi state for the engine, built from one [MacWifiProbe] reading
 * plus the interface's address and gateway.
 *
 * The network's name is the only thing that tells VIT Wi-Fi from any other,
 * and macOS can withhold it (no Location permission, a failed or timed-out
 * read). So this platform sets [requiresKnownSsid]: an unknown name is never
 * a campus network, and the engine sends no credentials until a VIT SSID has
 * actually been read.
 */
class MacWifiPlatform internal constructor(
    private val logger: Logger,
    private val probe: MacWifiProbe,
    private val links: MacLinkInspector,
    private val power: MacWifiPower,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MS,
    private val enableSettle: EnableSettle = EnableSettle(),
    private val clock: () -> Long = System::currentTimeMillis,
) : WifiPlatform {

    constructor(logger: Logger) : this(
        logger = logger,
        probe = defaultProbe(logger),
        links = SystemMacLinkInspector(),
        power = NetworksetupWifiPower(),
    )

    /** How long [enableWifi] waits for the radio to come up and associate. */
    internal class EnableSettle(val attempts: Int = 6, val intervalMillis: Long = 1_000L)

    internal data class Snapshot(
        val reading: MacWifiReading,
        val interfaceName: String?,
        val enabled: Boolean,
        val connected: Boolean,
        /** Only ever a name macOS actually reported for the associated network. */
        val ssid: String?,
        val address: String?,
        val gateway: String?,
    ) {
        /**
         * What identifies "the network we are on". The address is part of it
         * so that moving between two networks is still seen as a change when
         * their names are hidden.
         */
        val key: String? get() = if (connected) "$interfaceName|${ssid ?: "?"}|$address" else null
    }

    private var cached: Snapshot? = null
    private var cachedAt = 0L
    private var lastLogged: Snapshot? = null

    override val requiresKnownSsid: Boolean get() = true

    @Synchronized
    internal fun snapshot(fresh: Boolean = false): Snapshot {
        val now = clock()
        cached?.takeIf { !fresh && now - cachedAt < CACHE_TTL_MS }?.let { return it }
        val snap = observe(probe.read())
        cached = snap
        cachedAt = now
        logIfChanged(snap)
        return snap
    }

    private fun observe(reading: MacWifiReading): Snapshot = when (reading) {
        MacWifiReading.NoInterface,
        is MacWifiReading.Unavailable -> Snapshot(reading, null, enabled = false, connected = false, ssid = null, address = null, gateway = null)
        is MacWifiReading.PoweredOff -> Snapshot(reading, reading.interfaceName, enabled = false, connected = false, ssid = null, address = null, gateway = null)
        is MacWifiReading.PoweredOn -> {
            val address = links.ipv4Address(reading.interfaceName)
            val connected = address != null
            Snapshot(
                reading = reading,
                interfaceName = reading.interfaceName,
                enabled = true,
                connected = connected,
                ssid = reading.ssid?.trim()?.takeIf { connected && it.isNotEmpty() },
                address = address,
                gateway = if (connected) links.gateway(reading.interfaceName) else null,
            )
        }
    }

    private fun logIfChanged(snap: Snapshot) {
        val previous = lastLogged
        if (previous != null && previous.reading == snap.reading && previous.key == snap.key) return
        lastLogged = snap
        val reading = snap.reading
        val detail = when {
            reading is MacWifiReading.Unavailable -> "Wi-Fi state unavailable (${reading.reason}); treating as not connected"
            reading is MacWifiReading.NoInterface -> "no Wi-Fi interface"
            reading is MacWifiReading.PoweredOff -> "Wi-Fi off on ${reading.interfaceName}"
            !snap.connected -> "Wi-Fi on, not connected (${snap.interfaceName})"
            snap.ssid != null -> "connected to '${snap.ssid}' on ${snap.interfaceName}"
            reading is MacWifiReading.PoweredOn && reading.location != MacLocationAccess.Granted ->
                "connected on ${snap.interfaceName}, network name hidden (Location access: ${reading.location}); " +
                    "not treated as VIT Wi-Fi"
            else -> "connected on ${snap.interfaceName}, network name unavailable; not treated as VIT Wi-Fi"
        }
        logger.d(TAG, "Network state: $detail")
    }

    /**
     * Asks macOS for the Location access it requires before revealing the
     * network name (macOS 14+). Only meaningful from the packaged Latch.app,
     * whose Info.plist explains the request; until it is granted the name
     * stays hidden and Latch does not log in.
     */
    fun requestNetworkNameAccess() {
        MacLocationServices.requestAccessIfUndetermined(logger)
    }

    override fun isWifiEnabled(): Boolean = snapshot().enabled

    override fun isConnectedToWifi(): Boolean = snapshot().connected

    override fun currentSsid(): String? = snapshot().ssid

    /** Connected, with the name withheld because Location access is not granted. */
    override fun isNetworkNameHidden(): Boolean = snapshot().let { snap ->
        val reading = snap.reading
        snap.connected && snap.ssid == null &&
            reading is MacWifiReading.PoweredOn && reading.location != MacLocationAccess.Granted
    }

    override fun gatewayIp(): String? = snapshot().gateway

    override fun activeHandle(): NetworkHandle? =
        snapshot().takeIf { it.connected }?.interfaceName?.let(::MacNetworkHandle)

    override fun wifiInterfaceName(): String? = snapshot().interfaceName

    /**
     * The IPv4 address that [handle]'s interface (the Wi-Fi interface when
     * null) sends from, for [com.vinnovateit.latch.desktop.platform.BoundHttpTransport].
     * Null while Wi-Fi is not connected.
     */
    fun localAddress(handle: NetworkHandle?): InetAddress? {
        val snap = snapshot()
        if (!snap.connected) return null
        val interfaceName = handle?.id ?: snap.interfaceName ?: return null
        val address = if (interfaceName == snap.interfaceName) snap.address else links.ipv4Address(interfaceName)
        // Always a literal from the interface table, so this never does a DNS lookup.
        return address?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    /**
     * Switches the radio on only when it is positively reported off. An
     * unreadable state is left alone rather than toggled blind.
     */
    override fun enableWifi(): Boolean {
        val reading = snapshot(fresh = true).reading
        if (reading !is MacWifiReading.PoweredOff) return isWifiEnabled()
        logger.d(TAG, "Turning Wi-Fi on (${reading.interfaceName})")
        if (!power.turnOn(reading.interfaceName)) {
            logger.w(TAG, "macOS refused to turn Wi-Fi on")
            return false
        }
        repeat(enableSettle.attempts) {
            val snap = snapshot(fresh = true)
            if (snap.enabled && snap.connected) return true
            Thread.sleep(enableSettle.intervalMillis)
        }
        return snapshot(fresh = true).enabled
    }

    override val events: Flow<WifiEvent> = flow {
        var last = snapshot(fresh = true)
        while (true) {
            delay(pollIntervalMillis)
            val snap = snapshot(fresh = true)
            if (snap.key != last.key) {
                last.interfaceName?.takeIf { last.connected }?.let {
                    logger.d(TAG, "[NetworkEvent] Wi-Fi connection lost: ${last.key}")
                    emit(WifiEvent.Lost(MacNetworkHandle(it)))
                }
                snap.interfaceName?.takeIf { snap.connected }?.let {
                    logger.d(TAG, "[NetworkEvent] Wi-Fi connection available: ${snap.key}")
                    emit(WifiEvent.Available(MacNetworkHandle(it)))
                }
            }
            last = snap
        }
    }

    private companion object {
        const val TAG = "MacWifiPlatform"
        const val POLL_INTERVAL_MS = 1_500L
        const val CACHE_TTL_MS = 1_000L

        /** CoreWLAN, chosen on first read so that assembling the platform loads nothing native. */
        fun defaultProbe(logger: Logger): MacWifiProbe {
            val chosen by lazy {
                try {
                    CoreWlanProbe.create()
                } catch (e: Throwable) {
                    logger.e(TAG, "CoreWLAN unavailable; falling back to networksetup, which cannot read the network name", e)
                    NetworksetupWifiProbe()
                }
            }
            return MacWifiProbe { chosen.read() }
        }
    }
}
