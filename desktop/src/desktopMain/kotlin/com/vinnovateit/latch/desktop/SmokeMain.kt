package com.vinnovateit.latch.desktop

import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.stats.formatBitsPerSecond
import com.vinnovateit.latch.core.wifi.CaptivePortalDetector
import com.vinnovateit.latch.desktop.platform.DesktopHttpTransport
import com.vinnovateit.latch.desktop.platform.DesktopPlatformServices
import com.vinnovateit.latch.desktop.platform.TrayNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Headless smoke check for the Windows platform layer.
 *
 * Exists because the real app is a GUI tray process, which is awkward to assert
 * against in CI or from a terminal. This exercises every platform seam that could
 * plausibly be wrong on a given machine -- Wi-Fi/SSID detection, OSHI counters,
 * DPAPI round-trip, JSON settings, Room, and the captive-portal probe -- and
 * prints what it found.
 *
 * Run with: ./gradlew :desktop:smoke
 */
fun main() = runBlocking {
    println("=== Latch desktop smoke test ===")
    println("dataDir: ${AppPaths.dataDir}")

    val platform = DesktopPlatformServices(echoLogsToStdout = true, notifier = TrayNotifier())
    SettingsManager.initialize(platform.settingsStore)

    println()
    println("--- Wi-Fi platform ---")
    println("wifiEnabled      : ${platform.wifi.isWifiEnabled()}")
    println("connectedToWifi  : ${platform.wifi.isConnectedToWifi()}")
    println("currentSsid      : ${platform.wifi.currentSsid()}")
    println("gatewayIp        : ${platform.wifi.gatewayIp()}")
    println("activeHandle     : ${platform.wifi.activeHandle()?.id}")
    println("tunnelHoldingRoute: ${platform.wifi.activeTunnelName() ?: "none"}")
    println("portal via wifiDns: ${platform.wifi.resolveViaWifiDns("phc.prontonetworks.com") ?: "unresolved"}")

    println()
    println("--- SSID gate ---")
    val ssid = platform.wifi.currentSsid()
    val allowed = SettingsManager.allowedSsids.value
    val ssidMatches = ssid != null && allowed.any { ssid.contains(it, ignoreCase = true) }
    println("allowedSsids     : $allowed")
    println("ssid matches gate: $ssidMatches")

    println()
    println("--- Captive portal probe ---")
    val detector = CaptivePortalDetector(DesktopHttpTransport(), platform.logger)
    val code = detector.checkPortalStatus(platform.wifi.activeHandle())
    println("generate_204     : $code  (204 = online, other = portal, -1 = error)")

    println()
    println("--- Byte counters (OSHI) ---")
    val first = platform.counters.sample()
    println("sample #1        : $first")
    val monitor = ThroughputMonitor(platform.counters, intervalMs = 1000L)
    monitor.start()
    delay(3500)
    val usage = monitor.dataUsageFlow.value
    monitor.stop()
    val (dv, du) = formatBitsPerSecond(usage.rxBps, "bps")
    println("observed download: $dv $du  (raw rxBps=${usage.rxBps} bytes/s)")

    println()
    println("--- Credential store (DPAPI) ---")
    val creds = platform.credentials
    val had = creds.exists()
    println("pre-existing     : $had")
    if (!had) {
        creds.save("smoke-test-user", "smoke-test-pass")
        val ok = creds.userId() == "smoke-test-user" && creds.password() == "smoke-test-pass"
        println("roundtrip        : ${if (ok) "OK" else "FAILED"}")
        creds.clear()
        println("cleared          : ${!creds.exists()}")
    } else {
        println("roundtrip        : SKIPPED (real credentials present, not touching them)")
    }

    println()
    println("--- Settings (JSON) ---")
    val originalAccent = SettingsManager.accentColor.value
    SettingsManager.setAccentColor("Blue")
    println("set/read         : ${SettingsManager.accentColor.value}")
    SettingsManager.setAccentColor(originalAccent)
    println("restored         : ${SettingsManager.accentColor.value}")
    println("settingsFile     : ${AppPaths.settingsFile.exists()}")

    println()
    println("--- Room ---")
    val db = buildDatabase()
    val dao = db.statsDao()
    println("dao obtained     : ${dao::class.simpleName}")
    val existing = dao.getAllSessions().first()
    println("existing rows    : ${existing.size}")
    if (existing.isEmpty()) {
        // Only write to an empty database, and remove the row afterwards, so the
        // smoke test never pollutes real session history.
        val inserted = dao.insertSession(
            com.vinnovateit.latch.core.data.Session(
                startTime = System.currentTimeMillis() - 60_000,
                endTime = System.currentTimeMillis(),
                rxBytes = 2048,
                txBytes = 1024,
                maxRxBps = 500,
                maxTxBps = 250,
            )
        )
        val roundTripped = dao.getAllSessions().first().size == 1
        dao.clearAllSessions()
        println("insert rowId     : $inserted")
        println("read back        : ${if (roundTripped) "OK" else "FAILED"}")
        println("cleaned up       : ${dao.getAllSessions().first().isEmpty()}")
    } else {
        println("write test       : SKIPPED (real history present, not touching it)")
    }
    db.close()

    println()
    println("--- Autostart ---")
    println("supported        : ${platform.capabilities.supportsAutostart}")
    val autostartBefore = platform.systemActions.isAutostartEnabled()
    println("currently enabled: $autostartBefore")
    // Safety assertion: running from Gradle we are java.exe, not Latch.exe, so the
    // exe-path guard must refuse to write a Run key. Without that guard a
    // developer machine would get a startup entry launching a bare JVM.
    if (!autostartBefore) {
        platform.systemActions.setAutostart(true)
        val leaked = platform.systemActions.isAutostartEnabled()
        println("guard holds      : ${if (!leaked) "OK (refused, not an installed build)" else "FAILED - wrote a Run key!"}")
        if (leaked) platform.systemActions.setAutostart(false)
    } else {
        println("guard test       : SKIPPED (autostart already enabled)")
    }

    println()
    println("=== smoke test complete ===")
}
