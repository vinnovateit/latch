package com.vinnovateit.latch.desktop.platform

import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.KeyValueStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.PlatformCapabilities
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.desktop.AppPaths

private object DesktopBuildInfo : BuildInfo {
    // Kept in step with the MSI packageVersion in build.gradle.kts.
    override val versionName: String = "1.3.6"
    override val isDebug: Boolean = System.getProperty("latch.debug") == "true"
    override val isInstalled: Boolean = InstalledBuild.isInstalled
}

private object DesktopCapabilities : PlatformCapabilities {
    // Material You reads the OS wallpaper palette; no desktop equivalent, so the
    // Settings toggle is hidden and the theme falls through to a seed scheme.
    override val supportsDynamicColor: Boolean = false
    override val supportsAutostart: Boolean = AppPaths.isWindows
}

/**
 * Wires the concrete Windows implementations together.
 *
 * Only Windows is implemented today. Linux and macOS would each supply their own
 * WifiPlatform / CredentialStore / SystemActions here -- the dispatch is at
 * runtime rather than compile time precisely so that stays an additive change.
 */
class DesktopPlatformServices(
    echoLogsToStdout: Boolean = false,
    override val notifier: UserNotifier,
) : PlatformServices {

    override val logger: Logger = FileLogger(AppPaths.logsDir, echoLogsToStdout)

    override val buildInfo: BuildInfo = DesktopBuildInfo

    override val capabilities: PlatformCapabilities = DesktopCapabilities

    override val settingsStore: KeyValueStore = JsonKeyValueStore(AppPaths.settingsFile, logger)

    override val credentials: CredentialStore =
        DpapiCredentialStore(AppPaths.credentialsFile, logger)

    private val windowsWifi = WindowsWifiPlatform(logger)
    override val wifi: WifiPlatform = windowsWifi

    override val counters: ByteCounterSource = OshiByteCounters(windowsWifi, logger)

    override val systemActions: SystemActions = WindowsSystemActions(logger)

    override val httpTransport: HttpTransport = DesktopHttpTransport()
}
