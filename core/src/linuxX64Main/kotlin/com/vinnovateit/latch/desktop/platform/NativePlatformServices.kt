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
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    override val supportsAutostart: Boolean = false
}

class NativeKeyValueStore(private val file: JFile, private val logger: Logger) : KeyValueStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val values = mutableMapOf<String, String>()

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val root = json.parseToJsonElement(readText(file)) as? kotlinx.serialization.json.JsonObject ?: return@runCatching
            root.forEach { (key, element) ->
                if (element is kotlinx.serialization.json.JsonPrimitive) values[key] = element.content
            }
        }.onFailure { logger.e(TAG, "Settings file unreadable; using defaults", it) }
    }

    override fun getString(key: String, default: String): String = values[key] ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: default
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        values[key]?.split(',')?.filter(String::isNotBlank)?.toSet() ?: default

    override fun putString(key: String, value: String) { values[key] = value; persist() }
    override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString(); persist() }
    override fun putStringSet(key: String, value: Set<String>) { values[key] = value.joinToString(","); persist() }

    private fun persist() {
        val obj = kotlinx.serialization.json.buildJsonObject {
            values.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }
        val parent = file.parentFile
        parent?.mkdirs()
        JFileWriter(file, false).useOutput { it.write(json.encodeToString(obj).encodeToByteArray()) }
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

private class NativeHandle(override val id: String) : NetworkHandle

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

    init {
        // Do NOT shell out to nmcli here: the first call can block ~800ms on
        // D-Bus/Wi-Fi scan, delaying app startup. The poller below resolves the
        // handle on a background dispatcher immediately and emits Available;
        // activeHandle() also falls back to a live lookup when needed.
        scope.launch {
            var lastKey: String? = null
            var lastSignalLog = 0L
            while (true) {
                val iface = wifiInterface()
                val ssid = currentSsid()
                val signal = signalPercent()
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

    override fun isWifiEnabled(): Boolean =
        runCommand("nmcli -t -f WIFI general").contains("enabled", ignoreCase = true) ||
        runCommand("nmcli -t -f RUNNING general").contains("running", ignoreCase = true)

    override fun isConnectedToWifi(): Boolean = wifiInterface() != null
    override fun currentSsid(): String? = wifiField("ssid")
    // The default route may be Ethernet. Read the gateway for the active Wi-Fi
    // interface only, otherwise the engine validates/login-gates on the wrong
    // network (and reports the Ethernet gateway as the portal's).
    override fun gatewayIp(): String? {
        val iface = wifiInterface() ?: return null
        return runCommand("ip route show default dev $iface 2>/dev/null | head -1 | awk '{print \$3}'")
            .lineSequence().firstOrNull()?.takeIf(String::isNotBlank)
    }

    override fun wifiInterfaceName(): String? = wifiInterface()
    override fun activeHandle(): NetworkHandle? =
        currentHandle ?: wifiInterface()?.let { NativeHandle(it).also { currentHandle = it } }
    override fun enableWifi(): Boolean = isWifiEnabled()

    private fun wifiInterface(): String? =
        runCommand("nmcli -t -f active,device dev wifi 2>/dev/null | grep '^yes' | head -1 | cut -d: -f2")
            .lineSequence().firstOrNull()?.takeIf(String::isNotBlank)

    private fun wifiField(field: String): String? =
        runCommand("nmcli -t -f active,$field dev wifi 2>/dev/null | grep '^yes' | head -1 | cut -d: -f2")
            .lineSequence().firstOrNull()?.takeIf(String::isNotBlank)

    /** Active Wi-Fi signal in percent, for weak-signal diagnostics. */
    fun signalPercent(): Int? = wifiField("signal")?.toIntOrNull()

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L
    }
}

class NativeByteCounters(private val wifi: WifiPlatform) : ByteCounterSource {
    override fun sample(): ByteCounts? {
        val iface = wifi.wifiInterfaceName() ?: return null
        fun read(name: String): Long? =
            runCatching { readText(JFile("/sys/class/net/$iface/statistics/$name")).trim().toLongOrNull() }.getOrNull()
        val rx = read("rx_bytes") ?: return null
        val tx = read("tx_bytes") ?: return null
        return ByteCounts(rx, tx)
    }
}

class NativeSystemActions(private val logger: Logger) : SystemActions {
    override fun openWifiSettings() { runCommand("nm-connection-editor >/dev/null 2>&1 &") }
    override fun openUrl(url: String) { runCommand("xdg-open '$url' >/dev/null 2>&1 &") }
    override fun setAutostart(enabled: Boolean) {
        val dir = JFile(userHome(), ".config/autostart")
        dir.mkdirs()
        val file = JFile(dir, "latch.desktop")
        if (enabled) {
            JFileWriter(file, false).useOutput {
                it.write("[Desktop Entry]\nType=Application\nName=Latch\nExec=latch\nNoDisplay=true\n".encodeToByteArray())
            }
        } else file.delete()
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
