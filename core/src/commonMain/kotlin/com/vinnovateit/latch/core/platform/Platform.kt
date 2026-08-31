package com.vinnovateit.latch.core.platform

import kotlinx.coroutines.flow.Flow
import java.net.HttpURLConnection
import java.net.URL

// ---------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------

interface Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

object NoOpLogger : Logger {
    override fun d(tag: String, message: String) = Unit
    override fun w(tag: String, message: String) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

// ---------------------------------------------------------------------------
// Build info (replaces Android's generated BuildConfig)
// ---------------------------------------------------------------------------

interface BuildInfo {
    val versionName: String
    val isDebug: Boolean
    /** Whether this is an installed (jpackage) build, not a dev/Gradle run. */
    val isInstalled: Boolean
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------

/**
 * Opaque handle to a specific network. On Android this wraps android.net.Network;
 * on desktop there is no such concept, so it mostly carries the interface name.
 */
interface NetworkHandle {
    val id: String
}

/**
 * The seam that lets AutoLoginManager stay otherwise untouched.
 */
interface HttpTransport {
    fun open(url: URL, handle: NetworkHandle?): HttpURLConnection
}

// ---------------------------------------------------------------------------
// Wi-Fi
// ---------------------------------------------------------------------------

sealed interface WifiEvent {
    data class Available(val handle: NetworkHandle) : WifiEvent
    data class Lost(val handle: NetworkHandle?) : WifiEvent
}

data class WifiAccessPoint(val ssid: String, val bssid: String, val signalPercentage: Int)

interface WifiPlatform {
    fun isWifiEnabled(): Boolean
    fun isConnectedToWifi(): Boolean

    /**
     * Scans for available Wi-Fi access points whose SSID ends with "-VIT" or contains "VIT",
     * connects to the BSSID with the highest signal strength, and returns true if connected.
     */
    fun connectToBestVitNetwork(): Boolean = false

    /**
     * Best-effort: switch the Wi-Fi radio back on if the user left it off.
     */
    fun enableWifi(): Boolean = isWifiEnabled()

    /**
     * The SSID of the connected network, or null if unknown.
     */
    fun currentSsid(): String?

    /** Best-effort; only used as a fallback when portal DNS fails. */
    fun gatewayIp(): String?

    fun activeHandle(): NetworkHandle?

    fun wifiInterfaceName(): String? = activeHandle()?.id

    val events: Flow<WifiEvent>

    fun bindProcess(handle: NetworkHandle?) {}

    fun reportConnectivityOk(handle: NetworkHandle) {}
}

// ---------------------------------------------------------------------------
// Byte counters
// ---------------------------------------------------------------------------

/** Cumulative byte counts, as read from the OS. */
data class ByteCounts(val rxBytes: Long, val txBytes: Long)

interface ByteCounterSource {
    /** @return cumulative counters, or null if unavailable/unsupported. */
    fun sample(): ByteCounts?
}

// ---------------------------------------------------------------------------
// Credentials
// ---------------------------------------------------------------------------

interface CredentialStore {
    fun save(userId: String, password: String)
    fun userId(): String?
    fun password(): String?
    fun exists(): Boolean
    fun clear()
}

// ---------------------------------------------------------------------------
// Notifications and OS actions
// ---------------------------------------------------------------------------

interface UserNotifier {
    /** Cheap, high-frequency status (the tray tooltip). Safe to call every 2s. */
    fun showOngoing(title: String, text: String)

    /**
     * A real notification. Reserve for state transitions only.
     */
    fun notifyTransient(title: String, text: String, isError: Boolean = false)

    fun hideOngoing()
}

interface SystemActions {
    fun openWifiSettings()
    fun openUrl(url: String)
    fun setAutostart(enabled: Boolean)
    fun isAutostartEnabled(): Boolean
}

interface PlatformCapabilities {
    /** Material You wallpaper-derived colours. Always false on desktop. */
    val supportsDynamicColor: Boolean
    /** Whether an autostart-at-login toggle should be offered. */
    val supportsAutostart: Boolean
}

// ---------------------------------------------------------------------------
// Bundle
// ---------------------------------------------------------------------------

interface PlatformServices {
    val logger: Logger
    val buildInfo: BuildInfo
    val capabilities: PlatformCapabilities
    val settingsStore: KeyValueStore
    val credentials: CredentialStore
    val wifi: WifiPlatform
    val counters: ByteCounterSource
    val notifier: UserNotifier
    val systemActions: SystemActions
    val httpTransport: HttpTransport
}

/** Installed exactly once, from main(). */
object Platform {
    lateinit var services: PlatformServices
        private set

    val isInstalled: Boolean get() = ::services.isInitialized

    fun install(services: PlatformServices) {
        this.services = services
    }
}
