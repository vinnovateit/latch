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
 * Splits one line of `nmcli -t` output into its fields. nmcli separates fields
 * with `:` and escapes a literal `:` or `\` inside a value with a backslash, so
 * a Wi-Fi name may contain either.
 */
internal fun parseNmcliTerseLine(line: String): List<String> {
    val fields = mutableListOf<String>()
    val field = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '\\' && i + 1 < line.length -> field.append(line[++i])
            c == ':' -> {
                fields += field.toString()
                field.clear()
            }
            else -> field.append(c)
        }
        i++
    }
    fields += field.toString()
    return fields
}

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
    private var lastLoggedState: WifiSnapshot? = null

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
        val hasIp = iface?.let { hasIpv4(it) } ?: false
        val isConnected = hasIp || !gateway.isNullOrEmpty() || !ssid.isNullOrEmpty()

        val snap = WifiSnapshot(
            interfaceName = iface,
            wifiEnabled = wifiEnabled,
            connected = isConnected,
            ssid = ssid?.takeIf { it.isNotEmpty() },
            gateway = gateway?.takeIf { it.isNotEmpty() },
        )

        if (lastLoggedState?.ssid != snap.ssid || lastLoggedState?.connected != snap.connected || lastLoggedState?.gateway != snap.gateway) {
            logger.d(TAG, "Network state changed: interface=${snap.interfaceName}, enabled=${snap.wifiEnabled}, connected=${snap.connected}, ssid='${snap.ssid}', gateway='${snap.gateway}'")
            lastLoggedState = snap
        }

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

        // Option 3: Check rfkill directly via sysfs
        val rfkillDir = File("/sys/class/rfkill")
        if (rfkillDir.exists()) {
            val wifiRfkills = rfkillDir.listFiles()?.filter {
                File(it, "type").takeIf { f -> f.exists() }?.readText()?.trim() == "wlan"
            }
            if (!wifiRfkills.isNullOrEmpty()) {
                val anyBlocked = wifiRfkills.any {
                    val soft = File(it, "soft").takeIf { f -> f.exists() }?.readText()?.trim()
                    val hard = File(it, "hard").takeIf { f -> f.exists() }?.readText()?.trim()
                    soft == "1" || hard == "1"
                }
                return !anyBlocked
            }
        }

        // Option 4: Check wireless interface presence and operstate in sysfs
        val netDir = File("/sys/class/net")
        if (netDir.exists()) {
            val wirelessInterfaces = netDir.listFiles()?.filter {
                File(it, "wireless").exists() || File(it, "phy80211").exists()
            } ?: emptyList()
            if (wirelessInterfaces.isNotEmpty()) {
                val anyUp = wirelessInterfaces.any {
                    val oper = File(it, "operstate").takeIf { f -> f.exists() }?.readText()?.trim()
                    oper != "down" && oper != "dormant"
                }
                return anyUp
            }
        }

        return false
    }

    private fun resolveConnectedWifi(): Pair<String?, String?> {
        // 1. Try nmcli dev wifi
        val nmcliOut = runCommand("nmcli", "-t", "-f", "ACTIVE,SSID,DEVICE,TYPE", "dev", "wifi")
        if (nmcliOut != null) {
            for (line in nmcliOut.lines()) {
                val parts = parseNmcliTerseLine(line)
                if (parts.firstOrNull() == "yes" && parts.size >= 3) {
                    val ssid = parts[1].trim().removeSurrounding("\"")
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
                val parts = parseNmcliTerseLine(line)
                if (parts.size >= 3 && (parts[2].contains("wireless", ignoreCase = true) || parts[2].contains("wifi", ignoreCase = true))) {
                    val name = parts[0].trim().removeSurrounding("\"")
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
                val connName = parseNmcliTerseLine(devShow).getOrElse(1) { "" }.trim().removeSurrounding("\"")
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
            val sysfsName = netDir.listFiles()
                ?.firstOrNull { File(it, "wireless").exists() || File(it, "phy80211").exists() }
                ?.name
            if (sysfsName != null) return sysfsName
        }
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()?.firstOrNull { iface ->
                !iface.isLoopback && iface.isUp && (iface.name.startsWith("wl") || iface.name.startsWith("wlan"))
            }?.name
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasIpv4(ifaceName: String): Boolean = try {
        val nif = java.net.NetworkInterface.getByName(ifaceName)
        nif != null && nif.isUp && nif.inetAddresses.asSequence().any {
            it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress
        }
    } catch (_: Throwable) {
        false
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
        var lastKey = if (seed.connected && seed.interfaceName != null) {
            "${seed.interfaceName}::${seed.ssid ?: "unknown"}"
        } else {
            null
        }

        while (true) {
            invalidate()
            val snap = snapshot()
            val key = if (snap.connected && snap.interfaceName != null) {
                "${snap.interfaceName}::${snap.ssid ?: "unknown"}"
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
