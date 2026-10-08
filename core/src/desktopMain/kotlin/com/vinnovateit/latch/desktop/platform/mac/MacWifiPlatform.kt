package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.TimeUnit

internal data class SimpleMacNetworkHandle(override val id: String) : NetworkHandle

class MacWifiPlatform(private val logger: Logger) : WifiPlatform {

    private companion object {
        const val TAG = "MacWifiPlatform"
        const val POLL_INTERVAL_MS = 1_500L
        const val CACHE_TTL_MS = 1_000L
        const val CMD_TIMEOUT_SEC = 5L
        const val ENABLE_SETTLE_ATTEMPTS = 6
        const val ENABLE_SETTLE_INTERVAL_MS = 1_000L
    }

    private data class WifiSnapshot(
        val interfaceName: String?,
        val wifiEnabled: Boolean,
        val connected: Boolean,
        val ssid: String?,
        val gateway: String?,
    )

    private var cached: WifiSnapshot? = null
    private var cachedAt: Long = 0
    private var lastFingerprint: String? = null

    private fun invalidate() {
        cached = null
        cachedAt = 0
        lastFingerprint = null
    }

    // Not implemented, returns a empty string
    private fun getNetworkFingerprint(): String = return ""

    private fun runCommand(vararg args: String): String? = try {
        val process = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(CMD_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() == 0) {
            output.trim()
        } else {
            null
        }
    } catch (e: Throwable) {
        null
    }

    private fun snapshot(): WifiSnapshot {
        val currentFingerprint = getNetworkFingerprint()
        val now = System.currentTimeMillis()

        cached?.let {
            if (currentFingerprint == lastFingerprint && (now - cachedAt < 10_000L)) {
                return it
            }
        }

        lastFingerprint = currentFingerprint
        val wifiEnabled = checkWifiEnabled()
        val (iface, ssid) = resolveConnectedWifi()
        val gateway = resolveGateway(iface)

        val snap = WifiSnapshot(
            interfaceName = iface,
            wifiEnabled = wifiEnabled,
            connected = !ssid.isNullOrEmpty(),
            ssid = ssid?.takeIf { it.isNotEmpty() },
            gateway = gateway?.takeIf { it.isNotEmpty() },
        )

        cached = snap
        cachedAt = now
        return snap
    }

    private fun checkWifiEnabled(): Boolean {
        // Option 1: networksetup
        // This reports on even if wifi is on 'disconnected' state.
        val power = runCommand("networksetup", "-getairportpower", findFirstWirelessInterface() as String)
        if (power != null) {
            return power.lowercase().contains("on")
        }

        return true
    }

    private fun resolveConnectedWifi(): Pair<String?, String?> {
        // Cannot resolve connected wifi, using hardcoded value
        return Pair(findFirstWirelessInterface(), "B-VIT")
    }

    private fun findFirstWirelessInterface(): String? {
        val output = runCommand("networksetup", "-listallhardwareports")
        if (output != null) {
            val lines = output.split("\n")
            val ifaceL = lines[lines.indexOf("Hardware Port: Wi-Fi") + 1]
            val iface = ifaceL.split(":")[1].trim()
            logger.w(TAG, "Wireless interface detected is " + iface)
            return iface
        }
        return null
    }

    private fun resolveGateway(iface: String?): String? {
        val routeOut = runCommand("ipconfig", "getoption", findFirstWirelessInterface() as String, "router")
        logger.w(TAG, "Route Out: ${routeOut}")
        return routeOut
    }

    override fun isWifiEnabled(): Boolean = snapshot().wifiEnabled

    override fun enableWifi(): Boolean {
        if (isWifiEnabled()) return true

        logger.d(TAG, "Attempting to enable Wi-Fi radio via networksetup...")
        // Turning off then turning it on handles the case where the wifi
        // was in 'disconnected' state.
        runCommand("networksetup", "-setairportpower", findFirstWirelessInterface() as String, "off")
        runCommand("networksetup", "-setairportpower", findFirstWirelessInterface() as String, "on")
        invalidate()

        repeat(ENABLE_SETTLE_ATTEMPTS) {
            if (isWifiEnabled() && isConnectedToWifi()) return true
            Thread.sleep(ENABLE_SETTLE_INTERVAL_MS)
            invalidate()
        }
        return isWifiEnabled()
    }

    override fun isConnectedToWifi(): Boolean = snapshot().connected

    override fun currentSsid(): String? = snapshot().ssid

    override fun gatewayIp(): String? = snapshot().gateway

    override fun activeHandle(): NetworkHandle? =
        snapshot().takeIf { it.connected }?.interfaceName?.let { SimpleMacNetworkHandle(it) }

    override fun wifiInterfaceName(): String? = snapshot().interfaceName

    override val events: Flow<WifiEvent> = flow {
        val seed = snapshot()
        var lastKey = if (seed.connected && seed.ssid != null && seed.interfaceName != null) {
            "${seed.interfaceName}::${seed.ssid}"
        } else {
            null
        }

        while (true) {
            invalidate()
            val snap = snapshot()
            val key = if (snap.connected && snap.ssid != null && snap.interfaceName != null) {
                "${snap.interfaceName}::${snap.ssid}"
            } else {
                null
            }

            if (key != lastKey) {
                if (lastKey != null) {
                    logger.d(TAG, "[NetworkEvent] Wi-Fi connection lost: $lastKey")
                    emit(WifiEvent.Lost(SimpleMacNetworkHandle(lastKey.substringBefore("::"))))
                }
                if (key != null) {
                    logger.d(TAG, "[NetworkEvent] Wi-Fi connection available: $key (SSID='${snap.ssid}')")
                    emit(WifiEvent.Available(SimpleMacNetworkHandle(snap.interfaceName!!)))
                }
                lastKey = key
            }
            delay(POLL_INTERVAL_MS)
        }
    }
}
