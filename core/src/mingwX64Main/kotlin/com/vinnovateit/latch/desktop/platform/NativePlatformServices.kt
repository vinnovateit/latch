@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.JFileWriter
import com.vinnovateit.latch.core.mp.JHttpConnection
import com.vinnovateit.latch.core.mp.JPrintWriter
import com.vinnovateit.latch.core.mp.JUrl
import com.vinnovateit.latch.core.mp.MPIOException
import com.vinnovateit.latch.core.mp.nowMillis
import com.vinnovateit.latch.core.mp.printStackTraceTo
import com.vinnovateit.latch.core.mp.useOutput
import com.vinnovateit.latch.core.mp.usePrint
import com.vinnovateit.latch.core.mp.userHome
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
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.fread
import platform.posix.fclose
import platform.posix.pclose
import platform.posix.popen

private const val TAG = "NativePlatform"

class NativeLogger(private val echoToStdout: Boolean = false) : Logger {
    private val logFile: JFile = JFile(AppPaths.logsDir, "latch.log")

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
    override val versionName: String get() = "1.5.1"
    override val isDebug: Boolean = false
    override val isInstalled: Boolean = false
}

private object NativeCapabilities : PlatformCapabilities {
    override val supportsAutostart: Boolean = true
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
        file.parentFile?.mkdirs()
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
        JFileWriter(file, false).useOutput {
            it.write(json.encodeToString(NativeCredentials(userId, password)).encodeToByteArray())
        }
        // Remove inherited ACLs so only the current user can read the file —
        // the Windows equivalent of the native build's 0600 credentials file.
        runCommand("icacls \"${file.path}\" /inheritance:r /grant:r \"%USERNAME%:(F)\" >nul 2>&1")
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
 * Windows Wi-Fi platform for the native build.
 *
 * Uses netsh instead of nmcli: resolves the active interface/SSID/gateway,
 * polls for changes and emits Available/Lost so the engine tears a session
 * down on reconnect, and exposes activeHandle() even when already connected
 * at startup.
 */
class NativeWifiPlatform(private val logger: Logger) : WifiPlatform {
    private val _events = MutableSharedFlow<WifiEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<WifiEvent> get() = _events

    @Volatile private var currentHandle: NativeHandle? = null
    private var cachedInterface: String? = null
    private var cachedSsid: String? = null

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    init {
        scope.launch {
            var lastKey: String? = null
            var lastSignalLog = 0L
            while (true) {
                val details = wifiDetails()
                val iface = details?.interfaceName
                val ssid = details?.ssid
                val signal = details?.signal
                val now = nowMillis()
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
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private data class Details(val interfaceName: String, val ssid: String?, val signal: Int?, val state: String?)

    private fun wifiDetails(): Details? {
        val output = runCommand("netsh wlan show interfaces")
        if (!output.contains("State", ignoreCase = true)) return null
        var iface: String? = null
        var ssid: String? = null
        var signal: Int? = null
        var state: String? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val i = line.indexOf(':')
            if (i <= 0) return@forEach
            val key = line.substring(0, i).trim()
            val value = line.substring(i + 1).trim()
            when {
                key.equals("Name", true) && iface == null -> iface = value
                key.equals("SSID", true) && ssid == null -> ssid = value
                key.equals("Signal", true) && signal == null -> signal = value.trimEnd('%').toIntOrNull()
                key.equals("State", true) && state == null -> state = value
            }
        }
        val name = iface ?: return null
        cachedInterface = name
        cachedSsid = ssid
        return Details(name, ssid, signal, state)
    }

    override fun isWifiEnabled(): Boolean =
        runCommand("netsh interface show interface").contains("Wi-Fi", ignoreCase = true)

    override fun isConnectedToWifi(): Boolean = wifiDetails()?.state.equals("connected", ignoreCase = true)
    override fun currentSsid(): String? = cachedSsid ?: wifiDetails()?.ssid
    override fun gatewayIp(): String? {
        val iface = wifiInterface() ?: return null
        return netshConfig(iface).lineSequence()
            .firstOrNull { it.contains("Default Gateway", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("0.0.0.0") }
    }

    override fun wifiInterfaceName(): String? = cachedInterface ?: wifiDetails()?.interfaceName
    override fun activeHandle(): NetworkHandle? =
        currentHandle ?: wifiInterface()?.let { NativeHandle(it).also { currentHandle = it } }
    override fun enableWifi(): Boolean {
        val iface = wifiInterface() ?: return false
        runCommand("netsh interface set interface name=\"$iface\" admin=enabled")
        return isWifiEnabled()
    }

    /** OS-level link check: associated, IPv4 assigned, and adapter up. */
    override fun linkHealthy(handle: NetworkHandle): Boolean {
        val iface = wifiInterface() ?: return false
        if (iface != handle.id) return false
        val state = wifiDetails()?.state ?: return false
        if (!state.equals("connected", ignoreCase = true)) return false
        val config = netshConfig(iface)
        if (!config.contains("IPv4 Address", ignoreCase = true)) return false
        val status = runCommand("netsh interface show interface name=\"$iface\"")
        return status.contains("Connected", ignoreCase = true) && !status.contains("Disconnected", ignoreCase = true)
    }

    private fun wifiInterface(): String? = cachedInterface ?: wifiDetails()?.interfaceName

    override fun signalPercent(): Int? = wifiDetails()?.signal

    /** Reassociate by saved profile; cycle the adapter only when that fails. Never leaves Wi-Fi off. */
    override fun reconnect(handle: NetworkHandle?): Boolean {
        val ssid = currentSsid()
        if (!ssid.isNullOrBlank() && !ssid.contains('"') && !ssid.contains('&') && !ssid.contains('|')) {
            runCommand("netsh wlan connect name=\"$ssid\"")
            if (isConnectedToWifi()) return true
        }
        val iface = wifiInterface()
        if (iface != null && !iface.contains('"') && !iface.contains('&') && !iface.contains('|')) {
            runCommand("netsh interface set interface name=\"$iface\" admin=disabled")
            runCommand("netsh interface set interface name=\"$iface\" admin=enabled")
        }
        if (!isWifiEnabled()) runCommand("netsh interface set interface name=\"Wi-Fi\" admin=enabled")
        return isConnectedToWifi()
    }

    private fun netshConfig(iface: String): String =
        runCommand("netsh interface ipv4 show config name=\"$iface\"")

    private companion object {
        const val POLL_INTERVAL_MS = 1_500L
    }
}

class NativeByteCounters(private val wifi: WifiPlatform) : ByteCounterSource {
    override fun sample(): ByteCounts? {
        val iface = wifi.wifiInterfaceName() ?: return null
        val ps = "\$s=Get-NetAdapterStatistics -Name '$iface'; " +
            "Write-Output (\$s.ReceivedBytes.ToString()+','+\$s.SentBytes.ToString())"
        val output = runCommand("powershell.exe -NoProfile -Command \"$ps\"")
        val parts = output.trim().split(',')
        if (parts.size != 2) return null
        val rx = parts[0].trim().toLongOrNull() ?: return null
        val tx = parts[1].trim().toLongOrNull() ?: return null
        return ByteCounts(rx, tx)
    }
}

class NativeSystemActions(private val logger: Logger) : SystemActions {
    override fun openWifiSettings() { runCommand("start ms-settings:wifi", redirect = false) }
    override fun openUrl(url: String) { runCommand("start \"\" \"$url\"", redirect = false) }

    override fun setAutostart(enabled: Boolean) {
        val dir = JFile(userHome(), "AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup")
        dir.mkdirs()
        val file = JFile(dir, "latch.bat")
        if (enabled) {
            val exe = currentExecutable()
            JFileWriter(file, false).useOutput {
                it.write("@echo off\r\nstart \"\" \"$exe\" --hidden\r\n".encodeToByteArray())
            }
        } else file.delete()
    }

    override fun isAutostartEnabled(): Boolean {
        val dir = JFile(userHome(), "AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup")
        return JFile(dir, "latch.bat").exists()
    }
}

/** Absolute path of the running executable, for the autostart entry. */
private fun currentExecutable(): String = kotlinx.cinterop.memScoped {
    val buf = allocArray<platform.windows.WCHARVar>(1024)
    val length = platform.windows.GetModuleFileNameW(null, buf, 1024u)
    if (length == 0u) "" else buf.toKString()
}

class NativeNotifier : UserNotifier {
    /** Installed by the desktop tray so high-frequency status can refresh its tooltip. */
    @Volatile var onOngoing: ((title: String, text: String) -> Unit)? = null
    @Volatile var onHideOngoing: (() -> Unit)? = null

    override fun showOngoing(title: String, text: String) { onOngoing?.invoke(title, text) }
    override fun hideOngoing() { onHideOngoing?.invoke() }
    override fun notifyTransient(title: String, text: String, isError: Boolean) { /* tray tooltip carries status */ }
}

class NativeHttpTransport : HttpTransport {
    override fun isOnline(): Boolean = kotlinx.cinterop.memScoped {
        val flags = alloc<UIntVar>()
        platform.windows.InternetGetConnectedState(flags.ptr, 0u) != 0
    }

    override fun open(url: JUrl, handle: NetworkHandle?): JHttpConnection {
        // NetworkHandle.id is the Wi-Fi interface name. Portal credentials must
        // leave on Wi-Fi even when Ethernet owns the default route; JHttpConnection
        // refuses to send without a binding.
        if (handle == null) throw MPIOException("No Wi-Fi handle: refusing to send portal traffic on the default route")
        return JHttpConnection(url).also { it.bindToInterface(handle.id) }
    }

    override fun openPublic(url: JUrl): JHttpConnection =
        JHttpConnection(url).also { it.allowDefaultRoute() }
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

private val cmdCounter = AtomicInt(0)

/** Runs a command through cmd.exe and returns its stdout. */
internal fun runCommand(command: String, redirect: Boolean = true): String {
    val stem = "latch-cmd-${platform.posix.getpid()}-${cmdCounter.addAndGet(1)}"
    val dir = com.vinnovateit.latch.core.mp.winTempDir()
    val outFile = "$dir\\$stem.out"
    val suffix = if (redirect) " > \"$outFile\" 2>nul" else ""
    com.vinnovateit.latch.core.mp.runHidden("cmd /c $command$suffix")
    val result = if (redirect) runCatching { readText(JFile(outFile)) }.getOrDefault("") else ""
    runCatching { platform.posix.unlink(outFile) }
    return result
}

internal fun readText(file: JFile): String {
    val handle = platform.posix.fopen(file.path, "rb") ?: return ""
    return try {
        val buffer = ByteArray(8192)
        val out = StringBuilder()
        while (true) {
            val n = fread(buffer.refTo(0), 1u, buffer.size.toULong(), handle).toInt()
            if (n <= 0) break
            out.append(buffer.copyOf(n).decodeToString())
        }
        out.toString()
    } finally {
        fclose(handle)
    }
}
