package com.vinnovateit.latch.desktop.platform.linux

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.concurrent.TimeUnit

internal data class SimpleLinuxNetworkHandle(override val id: String) : NetworkHandle

/**
 * Linux Wi-Fi platform implementation leveraging standard Linux CLI toolchains
 * (`nmcli`, `iwgetid`, `ip route`, `rfkill`).
 */
class LinuxWifiPlatform(private val logger: Logger) : WifiPlatform {

    private companion object {
        const val TAG = "LinuxWifiPlatform"
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
        // Option 1: nmcli radio wifi
        val nmcliRadio = runCommand("nmcli", "radio", "wifi")
        if (nmcliRadio != null) {
            return nmcliRadio.lowercase().contains("enabled")
        }

        // Option 2: rfkill
        val rfkillOut = runCommand("rfkill", "list", "wifi")
        if (rfkillOut != null) {
            val softBlocked = rfkillOut.contains("Soft blocked: yes", ignoreCase = true)
            val hardBlocked = rfkillOut.contains("Hard blocked: yes", ignoreCase = true)
            return !softBlocked && !hardBlocked
        }

        // Option 3: Check wireless interface presence in sysfs
        val netDir = File("/sys/class/net")
        if (netDir.exists()) {
            val hasWireless = netDir.listFiles()?.any { File(it, "wireless").exists() || File(it, "phy80211").exists() } == true
            if (hasWireless) return true
        }

        return true
    }

    private fun resolveConnectedWifi(): Pair<String?, String?> {
        // 1. Try nmcli dev wifi
        val nmcliOut = runCommand("nmcli", "-t", "-f", "ACTIVE,SSID,DEVICE,TYPE", "dev", "wifi")
        if (nmcliOut != null) {
            for (line in nmcliOut.lines()) {
                val parts = line.split(":")
                if (parts.firstOrNull() == "yes" && parts.size >= 3) {
                    val ssid = parts[1].replace("\\:", ":").trim().removeSurrounding("\"")
                    val dev = parts[2]
                    if (ssid.isNotEmpty()) {
                        return Pair(dev, ssid)
                    }
                }
            }
        }

        // 2. Try nmcli connection show --active
        val connOut = runCommand("nmcli", "-t", "-f", "NAME,DEVICE,TYPE", "connection", "show", "--active")
        if (connOut != null) {
            for (line in connOut.lines()) {
                val parts = line.split(":")
                if (parts.size >= 3 && (parts[2].contains("wireless", ignoreCase = true) || parts[2].contains("wifi", ignoreCase = true))) {
                    val name = parts[0].replace("\\:", ":").trim().removeSurrounding("\"")
                    val dev = parts[1]
                    if (name.isNotEmpty()) {
                        return Pair(dev, name)
                    }
                }
            }
        }

        // 3. Fallback: iwgetid
        val ssid = runCommand("iwgetid", "-r")?.trim()?.removeSurrounding("\"")
        val dev = runCommand("iwgetid", "-c")?.split("\\s+".toRegex())?.firstOrNull() ?: findFirstWirelessInterface()
        if (!ssid.isNullOrEmpty()) {
            return Pair(dev, ssid)
        }

        // 4. Fallback: If gateway route exists via wireless interface, get active SSID via nmcli dev show
        val iface = findFirstWirelessInterface()
        if (iface != null) {
            val devShow = runCommand("nmcli", "-t", "-f", "GENERAL.CONNECTION", "dev", "show", iface)
            if (!devShow.isNullOrEmpty() && devShow != "--") {
                val connName = devShow.substringAfter(":").trim().removeSurrounding("\"")
                if (connName.isNotEmpty() && connName != "--") {
                    return Pair(iface, connName)
                }
            }
        }

        return Pair(findFirstWirelessInterface(), null)
    }

    private fun findFirstWirelessInterface(): String? {
        val netDir = File("/sys/class/net")
        if (netDir.exists()) {
            return netDir.listFiles()
                ?.firstOrNull { File(it, "wireless").exists() || File(it, "phy80211").exists() }
                ?.name
        }
        return null
    }

    private fun resolveGateway(iface: String?): String? {
        val routeOut = runCommand("ip", "route", "show", "default")
        if (routeOut != null) {
            val parts = routeOut.split("\\s+".toRegex())
            val viaIdx = parts.indexOf("via")
            if (viaIdx >= 0 && viaIdx + 1 < parts.size) {
                return parts[viaIdx + 1]
            }
        }
        return null
    }

    override fun connectToBestVitNetwork(): Boolean {
        enableWifi()
        logger.d(TAG, "Scanning Wi-Fi access points for -VIT / VIT networks...")
        val scanOut = runCommand("nmcli", "-t", "-f", "SSID,BSSID,SIGNAL", "dev", "wifi", "list", "--rescan", "yes")
            ?: runCommand("nmcli", "-t", "-f", "SSID,BSSID,SIGNAL", "dev", "wifi", "list")

        val apList = mutableListOf<com.vinnovateit.latch.core.platform.WifiAccessPoint>()
        if (scanOut != null) {
            for (line in scanOut.lines()) {
                val parts = line.split(":")
                if (parts.size >= 3) {
                    val ssid = parts[0].replace("\\:", ":").trim()
                    val bssid = parts[1].replace("\\:", ":").trim()
                    val signal = parts[2].toIntOrNull() ?: 0
                    if (ssid.isNotEmpty() && bssid.isNotEmpty()) {
                        apList.add(com.vinnovateit.latch.core.platform.WifiAccessPoint(ssid, bssid, signal))
                    }
                }
            }
        }

        val vitAps = apList.filter { ap ->
            ap.ssid.endsWith("-VIT", ignoreCase = true) || ap.ssid.contains("VIT", ignoreCase = true)
        }.sortedByDescending { it.signalPercentage }

        val bestAp = vitAps.firstOrNull()
        if (bestAp == null) {
            logger.w(TAG, "No -VIT / VIT Wi-Fi networks found in scan.")
            return isConnectedToWifi()
        }

        logger.d(TAG, "Best VIT AP found: SSID='${bestAp.ssid}', BSSID='${bestAp.bssid}', Signal=${bestAp.signalPercentage}%")
        runCommand("nmcli", "dev", "wifi", "connect", bestAp.bssid)
            ?: runCommand("nmcli", "dev", "wifi", "connect", bestAp.ssid, "bssid", bestAp.bssid)
            ?: runCommand("nmcli", "dev", "wifi", "connect", bestAp.ssid)

        invalidate()
        repeat(ENABLE_SETTLE_ATTEMPTS) {
            if (isConnectedToWifi()) return true
            Thread.sleep(ENABLE_SETTLE_INTERVAL_MS)
            invalidate()
        }
        return isConnectedToWifi()
    }

    override fun isWifiEnabled(): Boolean = snapshot().wifiEnabled

    override fun enableWifi(): Boolean {
        if (isWifiEnabled()) return true

        logger.d(TAG, "Attempting to enable Wi-Fi radio via nmcli/rfkill...")
        runCommand("nmcli", "radio", "wifi", "on") ?: runCommand("rfkill", "unblock", "wifi")
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
        snapshot().takeIf { it.connected }?.interfaceName?.let { SimpleLinuxNetworkHandle(it) }

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
                    emit(WifiEvent.Lost(SimpleLinuxNetworkHandle(lastKey.substringBefore("::"))))
                }
                if (key != null) {
                    logger.d(TAG, "[NetworkEvent] Wi-Fi connection available: $key (SSID='${snap.ssid}')")
                    emit(WifiEvent.Available(SimpleLinuxNetworkHandle(snap.interfaceName!!)))
                }
                lastKey = key
            }
            delay(POLL_INTERVAL_MS)
        }
    }
}
