@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.JHttpConnection
import com.vinnovateit.latch.core.mp.JUrl
import com.vinnovateit.latch.core.mp.JFileWriter
import com.vinnovateit.latch.core.mp.JPrintWriter
import com.vinnovateit.latch.core.mp.nowMillis
import com.vinnovateit.latch.core.mp.userHome
import com.vinnovateit.latch.core.mp.printStackTraceTo
import com.vinnovateit.latch.core.mp.useOutput
import com.vinnovateit.latch.core.mp.usePrint
import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.KeyValueStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.PlatformCapabilities
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.desktop.AppPaths
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.refTo
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.readlink
import platform.posix.system

private const val TAG = "NativePlatform"

class NativeLogger(private val echoToStdout: Boolean = false) : Logger {
    private val logFile: JFile = JFile(AppPaths.logsDir, "latch.log")
    private val json = Json { encodeDefaults = true }

    override fun d(tag: String, message: String) = log("DEBUG", tag, message, null)
    override fun w(tag: String, message: String) = log("WARN", tag, message, null)
    override fun e(tag: String, message: String, throwable: Throwable?) = log("ERROR", tag, message, throwable)

    private fun log(level: String, tag: String, message: String, throwable: Throwable?) {
        val line = "${nowMillis()} [$level] [$tag] $message"
        if (echoToStdout) println(line)
        runCatching {
            JFileWriter(logFile, true).useOutput { out ->
                JPrintWriter(out).usePrint { writer ->
                    writer.println(line)
                    throwable?.printStackTraceTo(writer)
                }
            }
        }
    }
}

private object NativeBuildInfo : BuildInfo {
    override val versionName: String get() = "1.0.0"
    override val isDebug: Boolean = false
    override val isInstalled: Boolean = false
}

private object NativeCapabilities : PlatformCapabilities {
    override val supportsAutostart: Boolean = true
}

class NativeKeyValueStore(private val file: JFile, private val logger: Logger) : KeyValueStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val lock = Any()
    private val values = mutableMapOf<String, String>()

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val root = json.parseToJsonElement(readText(file)) as? kotlinx.serialization.json.JsonObject ?: return@runCatching
            com.vinnovateit.latch.core.mp.withLock(lock) {
                root.forEach { (key, element) ->
                    if (element is kotlinx.serialization.json.JsonPrimitive) values[key] = element.content
                }
            }
        }.onFailure { logger.e(TAG, "Settings file unreadable; using defaults", it) }
    }

    override fun getString(key: String, default: String): String =
        com.vinnovateit.latch.core.mp.withLock(lock) { values[key] ?: default }
    override fun getBoolean(key: String, default: Boolean): Boolean =
        com.vinnovateit.latch.core.mp.withLock(lock) { values[key]?.toBooleanStrictOrNull() ?: default }
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        com.vinnovateit.latch.core.mp.withLock(lock) {
            values[key]?.split(',')?.filter(String::isNotBlank)?.toSet() ?: default
        }

    override fun putString(key: String, value: String) =
        com.vinnovateit.latch.core.mp.withLock(lock) { values[key] = value; persistLocked() }
    override fun putBoolean(key: String, value: Boolean) =
        com.vinnovateit.latch.core.mp.withLock(lock) { values[key] = value.toString(); persistLocked() }
    override fun putStringSet(key: String, value: Set<String>) =
        com.vinnovateit.latch.core.mp.withLock(lock) { values[key] = value.joinToString(","); persistLocked() }

    private fun persistLocked() {
        val obj = kotlinx.serialization.json.buildJsonObject {
            values.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }
        val bytes = json.encodeToString(obj).encodeToByteArray()
        val parent = file.parentFile
        parent?.mkdirs()
        val tmp = JFile("${file.path}.tmp")
        JFileWriter(tmp, false).useOutput { it.write(bytes) }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            JFileWriter(file, false).useOutput { it.write(bytes) }
        }
    }
}

@Serializable
private data class NativeCredentials(val userId: String, val password: String)

class NativeCredentialStore(private val file: JFile, private val logger: Logger) : CredentialStore {
    private val json = Json { ignoreUnknownKeys = true }
    private fun load(): NativeCredentials? = runCatching {
        if (!file.exists()) null else json.decodeFromString<NativeCredentials>(readText(file))
    }.getOrNull()

    override fun save(userId: String, password: String): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        val bytes = json.encodeToString(NativeCredentials(userId, password)).encodeToByteArray()
        JFileWriter(file, false).useOutput { it.write(bytes) }
        platform.posix.chmod(file.path, 0x180u)
        Unit
    }.onFailure { logger.e(TAG, "Failed to save credentials", it) }

    override fun userId(): String? = load()?.userId
    override fun password(): String? = load()?.password
    override fun exists(): Boolean = load() != null
    override fun clear(): Result<Unit> = runCatching {
        if (file.exists() && !file.delete()) error("Unable to remove credentials file")
        check(!file.exists()) { "Credentials still present after clear" }
    }
}

internal data class NativeHandle(override val id: String) : NetworkHandle

/**
 * Splits one line of terse tabular `nmcli -t` output into its fields. nmcli
 * separates fields with `:` and escapes a literal `:` or `\` inside a value with
 * a backslash, so a Wi-Fi name may contain either.
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
 * Linux Wi-Fi platform for the native build.
 *
 * Mirrors the JVM [LinuxWifiPlatform] behaviour the engine relies on:
 *  - resolve the active interface / SSID through nmcli,
 *  - poll for changes and emit WifiEvent.Available / WifiEvent.Lost so the
 *    engine can tear a session down and re-login on reconnect,
 *  - expose [activeHandle] once connected (including when already connected
 *    at startup, before any event is emitted).
 */
class NativeWifiPlatform(private val logger: Logger) : WifiPlatform {
    private val _events = MutableSharedFlow<WifiEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<WifiEvent> get() = _events

    @Volatile private var currentHandle: NativeHandle? = null

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    private data class ActiveWifiInfo(val iface: String, val ssid: String?, val signal: Int?)

    init {
        // Do NOT shell out to nmcli here: the first call can block ~800ms on
        // D-Bus/Wi-Fi scan, delaying app startup. The poller below resolves the
        // handle on a background dispatcher immediately and emits Available;
        // activeHandle() also falls back to a live lookup when needed.
        scope.launch {
            var lastKey: String? = null
            var lastSignalLog = 0L
            while (isActive) {
                val info = readActiveWifi()
                val iface = info?.iface
                val ssid = info?.ssid
                val signal = info?.signal
                val now = nowMillis()
                // Log weak signal at most once a minute — useful for diagnosing
                // portal flaps without emitting a WifiEvent for every RSSI change.
                if (iface != null && signal != null && signal <= 25 && now - lastSignalLog > 60_000L) {
                    logger.w(TAG, "[NetworkEvent] Weak Wi-Fi signal ${signal}% on '$ssid'")
                    lastSignalLog = now
                }
                val key = if (iface != null) "$iface::${ssid ?: "unknown"}" else null
                if (key != lastKey) {
                    if (lastKey != null) {
                        logger.d(TAG, "[NetworkEvent] Wi-Fi connection lost: $lastKey")
                        _events.tryEmit(WifiEvent.Lost(NativeHandle(lastKey.substringBefore("::"))))
                    }
                    if (key != null && iface != null) {
                        logger.d(TAG, "[NetworkEvent] Wi-Fi connection available: $key")
                        currentHandle = NativeHandle(iface)
                        _events.tryEmit(WifiEvent.Available(NativeHandle(iface)))
                    } else {
                        currentHandle = null
                    }
                    lastKey = key
                }
                kotlinx.coroutines.delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun close() {
        scope.cancel()
    }

    override fun isWifiEnabled(): Boolean =
        runCommand("nmcli -t -f WIFI general").contains("enabled", ignoreCase = true) ||
        runCommand("nmcli -t -f RUNNING general").contains("running", ignoreCase = true)

    override fun isConnectedToWifi(): Boolean = readActiveWifi() != null
    override fun currentSsid(): String? = readActiveWifi()?.ssid
    // The default route may be Ethernet or USB tethering. Portal traffic is
    // still pinned to the Wi-Fi interface (see NativeHttpTransport), so the
    // gateway fallback must come from that interface too — never the default
    // route's. Do not require Wi-Fi to own the default route: with tethering
    // plugged in it may have no `default dev` entry at all.
    override fun gatewayIp(): String? {
        val iface = wifiInterface() ?: return null
        val fromNmcli = runCommand("nmcli -g IP4.GATEWAY device show $iface 2>/dev/null")
            .lineSequence().map(String::trim)
            .firstOrNull { it.isNotBlank() && it != "--" }
        if (fromNmcli != null) return fromNmcli
        return runCommand("ip route show dev $iface 2>/dev/null | awk '\$1==\"default\"{print \$3}' | head -1")
            .lineSequence().firstOrNull()?.takeIf(String::isNotBlank)
    }

    override fun wifiInterfaceName(): String? = wifiInterface()
    override fun activeHandle(): NetworkHandle? =
        currentHandle ?: wifiInterface()?.let { NativeHandle(it).also { currentHandle = it } }
    override fun enableWifi(): Boolean = isWifiEnabled()

    /**
     * Independent of the portal endpoint. An associated Wi-Fi with no carrier,
     * no IPv4 address or a state other than connected is a dead link, even if
     * generate_204 never answers. Missing sysfs files are ignored so an
     * unusual interface is not marked down by a read failure.
     */
    override fun linkHealthy(handle: NetworkHandle): Boolean {
        val carrier = runCatching { readText(JFile("/sys/class/net/${handle.id}/carrier")).trim() }.getOrNull()
        if (carrier == "0") return false
        val operState = runCatching { readText(JFile("/sys/class/net/${handle.id}/operstate")).trim() }.getOrNull()
        if (operState != null && operState != "up") return false
        val iface = wifiInterface() ?: return false
        if (iface != handle.id) return false
        val state = runCommand("nmcli -t -f GENERAL.STATE device show $iface 2>/dev/null")
            .lineSequence().firstOrNull()?.substringAfterLast(':')?.trim().orEmpty()
        if (!state.startsWith("100")) return false
        return runCommand("nmcli -g IP4.ADDRESS device show $iface 2>/dev/null")
            .lineSequence().map(String::trim).any { it.isNotBlank() && it != "--" }
    }

    private fun readActiveWifi(): ActiveWifiInfo? {
        val output = runCommand("nmcli -t -f active,device,signal,ssid dev wifi list --rescan no 2>/dev/null")
        for (rawLine in output.lineSequence()) {
            if (rawLine.isBlank()) continue
            val parts = parseNmcliTerseLine(rawLine)
            if (parts.firstOrNull() == "yes" && parts.size >= 4) {
                val iface = parts[1].trim().takeIf { it.isNotEmpty() } ?: continue
                val signal = parts[2].trim().toIntOrNull()
                val ssid = parts[3].trim().takeIf { it.isNotEmpty() }
                return ActiveWifiInfo(iface, ssid, signal)
            }
        }
        return null
    }

    private fun wifiInterface(): String? = readActiveWifi()?.iface

    /** Active Wi-Fi signal in percent, for weak-signal diagnostics. */
    override fun signalPercent(): Int? = readActiveWifi()?.signal

    /** nmcli reports quality percent; approximate dBm over the usual -100..-50 range. */
    override fun signalDbm(): Int? = signalPercent()?.let { (it / 2) - 100 }

    private fun activeProfileName(iface: String?): String? {
        if (iface.isNullOrBlank()) return null
        return runCommand("nmcli -g GENERAL.CONNECTION device show ${shellEscape(iface)} 2>/dev/null")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() && it != "--" }
    }

    private fun shellEscape(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun awaitConnected(iface: String?, attempts: Int = 12): Boolean {
        repeat(attempts) {
            val active = readActiveWifi()
            if (active != null && (iface == null || active.iface == iface)) {
                if (linkHealthy(NativeHandle(active.iface))) return true
            }
            platform.posix.usleep(250_000u)
        }
        return isConnectedToWifi()
    }

    /** Bounce the wedged association first; cycle the radio when that fails. Never leaves Wi-Fi off. */
    override fun reconnect(handle: NetworkHandle?): Boolean {
        val iface = handle?.id ?: wifiInterface()
        val profile = (activeProfileName(iface) ?: currentSsid())?.takeIf { it.isNotBlank() }
        if (profile != null) {
            val escapedProfile = shellEscape(profile)
            runCommand("nmcli connection down $escapedProfile 2>/dev/null")
            platform.posix.usleep(250_000u)
            runCommand("nmcli connection up $escapedProfile 2>/dev/null")
            if (awaitConnected(iface)) return true
        } else if (iface != null) {
            val escapedIface = shellEscape(iface)
            runCommand("nmcli device disconnect $escapedIface 2>/dev/null")
            platform.posix.usleep(250_000u)
            runCommand("nmcli device connect $escapedIface 2>/dev/null")
            if (awaitConnected(iface)) return true
        }
        runCommand("nmcli radio wifi off 2>/dev/null")
        platform.posix.usleep(500_000u)
        runCommand("nmcli radio wifi on 2>/dev/null")
        platform.posix.usleep(1_000_000u)
        if (profile != null) {
            runCommand("nmcli connection up ${shellEscape(profile)} 2>/dev/null")
        } else if (iface != null) {
            runCommand("nmcli device connect ${shellEscape(iface)} 2>/dev/null")
        }
        return awaitConnected(iface)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L
    }
}

class NativeByteCounters(private val wifi: WifiPlatform) : ByteCounterSource {
    override fun sample(): ByteCounts? {
        val iface = (wifi.activeHandle()?.id ?: wifi.wifiInterfaceName()) ?: return null
        fun read(name: String): Long? =
            runCatching { readText(JFile("/sys/class/net/$iface/statistics/$name")).trim().toLongOrNull() }.getOrNull()
        val rx = read("rx_bytes") ?: return null
        val tx = read("tx_bytes") ?: return null
        return ByteCounts(rx, tx)
    }
}

private fun currentExecutable(): String = kotlinx.cinterop.memScoped {
    val buf = allocArray<ByteVar>(4096)
    val n = readlink("/proc/self/exe", buf, 4095u)
    if (n > 0) {
        buf[n.toInt()] = 0
        val exe = buf.toKString()
        if (exe.isNotBlank() && JFile(exe).exists()) return@memScoped exe
    }
    if (JFile("/opt/latch/bin/Latch").exists()) "/opt/latch/bin/Latch" else "latch"
}

class NativeSystemActions(private val logger: Logger) : SystemActions {
    override fun openWifiSettings() { runCommand("nm-connection-editor >/dev/null 2>&1 &") }
    override fun openUrl(url: String) { runCommand("xdg-open '$url' >/dev/null 2>&1 &") }
    override fun setAutostart(enabled: Boolean) {
        val dir = JFile(userHome(), ".config/autostart")
        val file = JFile(dir, "latch.desktop")
        if (enabled) {
            if (!dir.mkdirs() && !dir.isDirectory) {
                logger.w(TAG, "Unable to create autostart directory: ${dir.path}")
                return
            }
            val exe = currentExecutable()
            val quotedExe = "\"$exe\""
            JFileWriter(file, false).useOutput {
                it.write(
                    "[Desktop Entry]\nType=Application\nName=Latch\nComment=Auto-login for VIT hostel Wi-Fi\nExec=$quotedExe --hidden\nTerminal=false\nX-GNOME-Autostart-enabled=true\nCategories=Network;\nNoDisplay=true\n"
                        .encodeToByteArray(),
                )
            }
        } else {
            file.delete()
        }
    }
    override fun isAutostartEnabled(): Boolean = JFile(userHome(), ".config/autostart/latch.desktop").exists()
}

class NativeNotifier : UserNotifier {
    /** Installed by the desktop tray so high-frequency status can refresh its tooltip. */
    @Volatile var onOngoing: ((title: String, text: String) -> Unit)? = null
    @Volatile var onHideOngoing: (() -> Unit)? = null

    override fun showOngoing(title: String, text: String) {
        onOngoing?.invoke(title, text)
    }

    override fun hideOngoing() {
        onHideOngoing?.invoke()
    }

    override fun notifyTransient(title: String, text: String, isError: Boolean) {
        // No-op on hosts without a notification daemon (e.g. CI/headless).
        val urgency = if (isError) "-u critical" else ""
        runCatching { runCommand("notify-send -a Latch $urgency ${shellQuote(title)} ${shellQuote(text)}") }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

class NativeHttpTransport : HttpTransport {
    override fun open(url: JUrl, handle: NetworkHandle?): JHttpConnection {
        val connection = JHttpConnection(url)
        // NetworkHandle.id is the Wi-Fi interface name (e.g. wlp8s0). Every
        // portal request must leave on Wi-Fi, never on the default route which
        // may be Ethernet. JHttpConnection refuses to send without a binding.
        if (handle == null) {
            throw com.vinnovateit.latch.core.mp.MPIOException(
                "No Wi-Fi handle: refusing to send portal traffic on the default route",
            )
        }
        connection.bindToInterface(handle.id)
        return connection
    }

    override fun openPublic(url: JUrl): JHttpConnection {
        // Credential validation talks to the publicly reachable account host,
        // so the default route (Ethernet) is correct here.
        return JHttpConnection(url).also { it.allowDefaultRoute() }
    }
}

class NativePlatformServices(
    echoLogsToStdout: Boolean = false,
    override val notifier: UserNotifier = NativeNotifier(),
) : PlatformServices {
    override val logger: Logger = NativeLogger(echoLogsToStdout)
    override val buildInfo: BuildInfo = NativeBuildInfo
    override val capabilities: PlatformCapabilities = NativeCapabilities
    override val settingsStore: KeyValueStore = NativeKeyValueStore(AppPaths.settingsFile, logger)
    override val credentials: CredentialStore = NativeCredentialStore(AppPaths.credentialsFile, logger)
    override val wifi: WifiPlatform = NativeWifiPlatform(logger)
    override val counters: ByteCounterSource = NativeByteCounters(wifi)
    override val systemActions: SystemActions = NativeSystemActions(logger)
    override val httpTransport: HttpTransport = NativeHttpTransport()

    fun close() {
        (wifi as? NativeWifiPlatform)?.close()
    }
}

private val cmdCounter = kotlin.concurrent.AtomicInt(0)

internal fun runCommand(command: String, redirect: Boolean = true): String {
    // `$$` is expanded by the shell inside [system] but NOT by Kotlin, so reading
    // back a literal "/tmp/latch-cmd.$$" never found the file. Use a PID + counter
    // so concurrent callers don't clobber each other's output either.
    val path = "/tmp/latch-cmd-${platform.posix.getpid()}-${cmdCounter.addAndGet(1)}"
    val suffix = if (redirect) " > $path 2>/dev/null" else ""
    val result = system("$command$suffix")
    val out = if (result != 0 && redirect) "" else runCatching { readText(JFile(path)) }.getOrDefault("")
    runCatching { platform.posix.unlink(path) }
    return out
}

internal fun readText(file: JFile): String {
    val handle = platform.posix.fopen(file.path, "r") ?: return ""
    return try {
        val out = StringBuilder()
        val buffer = ByteArray(8192)
        while (true) {
            val n = platform.posix.fread(buffer.refTo(0), 1u, buffer.size.toULong(), handle).toInt()
            if (n <= 0) break
            out.append(buffer.decodeToString(0, n))
        }
        out.toString()
    } finally { platform.posix.fclose(handle) }
}
