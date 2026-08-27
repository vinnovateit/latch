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
import com.vinnovateit.latch.desktop.platform.linux.LinuxCredentialStore
import com.vinnovateit.latch.desktop.platform.linux.LinuxSystemActions
import com.vinnovateit.latch.desktop.platform.linux.LinuxWifiPlatform
import com.vinnovateit.latch.desktop.platform.windows.DpapiCredentialStore
import com.vinnovateit.latch.desktop.platform.windows.WindowsSystemActions
import com.vinnovateit.latch.desktop.platform.windows.WindowsWifiPlatform

private object DesktopBuildInfo : BuildInfo {
    override val versionName: String = "1.3.8"
    override val isDebug: Boolean = System.getProperty("latch.debug") == "true"
    override val isInstalled: Boolean = InstalledBuild.isInstalled
}

private object DesktopCapabilities : PlatformCapabilities {
    override val supportsDynamicColor: Boolean = false
    override val supportsAutostart: Boolean = AppPaths.isWindows || AppPaths.isLinux
}

/**
 * Wires concrete OS implementations together via runtime OS dispatch.
 * Windows and Linux are supported; macOS can be added by adding a macos package
 * and wiring its implementations in this dispatch.
 */
class DesktopPlatformServices(
    echoLogsToStdout: Boolean = false,
    override val notifier: UserNotifier,
) : PlatformServices {

    override val logger: Logger = FileLogger(AppPaths.logsDir, echoLogsToStdout)

    override val buildInfo: BuildInfo = DesktopBuildInfo

    override val capabilities: PlatformCapabilities = DesktopCapabilities

    override val settingsStore: KeyValueStore = JsonKeyValueStore(AppPaths.settingsFile, logger)

    override val credentials: CredentialStore = when {
        AppPaths.isWindows -> DpapiCredentialStore(AppPaths.credentialsFile, logger)
        AppPaths.isLinux -> LinuxCredentialStore(AppPaths.credentialsFile, logger)
        else -> LinuxCredentialStore(AppPaths.credentialsFile, logger) // Default fallback
    }

    override val wifi: WifiPlatform = when {
        AppPaths.isWindows -> WindowsWifiPlatform(logger)
        AppPaths.isLinux -> LinuxWifiPlatform(logger)
        else -> LinuxWifiPlatform(logger) // Default fallback
    }

    override val counters: ByteCounterSource = OshiByteCounters(wifi, logger)

    override val systemActions: SystemActions = when {
        AppPaths.isWindows -> WindowsSystemActions(logger)
        AppPaths.isLinux -> LinuxSystemActions(logger)
        else -> LinuxSystemActions(logger) // Default fallback
    }

    override val httpTransport: HttpTransport = DesktopHttpTransport()
}
