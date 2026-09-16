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

/**
'networksetup -listpreferredwirelessnetworks <device-name> (en0 for mba)' lists all saved wifi networks. 
'system_profiler SPAirPortDataType' gives info about current connected wifi network and lists available wifi networks.
'networksetup -setairportnetwork en0 <ssid> [pwd]' connects to network with name ssid
 */
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

    // Not implemented
    private fun getNetworkFingerprint(): String = try {
        val routeStr = File("/proc/net/route").takeIf { it.exists() }?.readText() ?: ""
        val operstate = File("/sys/class/net").listFiles()?.joinToString {
            "${it.name}:${File(it, "operstate").takeIf { f -> f.exists() }?.readText()?.trim()}"
        } ?: ""
        "$routeStr|$operstate"
    } catch (_: Throwable) {
        ""
    }

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
        // Using system profiler
        val scanOut = runCommand("system_profiler", "SPAirPortDataType")

        if (scanOut != null) {
            var currNetSection = false
            for (line in scanOut.lines()) {
                if (line.contains("Current Network Information:", ignoreCase = true)) {
                    currNetSection = true
                    continue
                }
                if (currNetSection) {
                    var ssid = line.trim().dropLast(1)
                    logger.d(TAG, "Connected WiFi: ${ssid}")
                    return Pair(findFirstWirelessInterface() as String, ssid)
                }
            }
        }

        return Pair(findFirstWirelessInterface(), null)
    }

    private fun findFirstWirelessInterface(): String? {
        logger.w(TAG, "Find wireless interface has hardcoded value.")
        return "en0"
        // Return correct interface instead of hardcoded value.
        val netDir = File("/sys/class/net")
        if (netDir.exists()) {
            return netDir.listFiles()
                ?.firstOrNull { File(it, "wireless").exists() || File(it, "phy80211").exists() }
                ?.name
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

fun countIndent(a: String): Int {
    var count = 0
    for(character in a) {
        if (character == ' ') count++
        else break
    }
    return count
}
