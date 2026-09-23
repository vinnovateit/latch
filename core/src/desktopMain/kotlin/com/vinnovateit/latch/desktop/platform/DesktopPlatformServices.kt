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
import com.vinnovateit.latch.core.LatchCore
import com.vinnovateit.latch.desktop.AppPaths
import com.vinnovateit.latch.desktop.platform.linux.LinuxCredentialStore
import com.vinnovateit.latch.desktop.platform.linux.LinuxSystemActions
import com.vinnovateit.latch.desktop.platform.linux.LinuxWifiPlatform
import com.vinnovateit.latch.desktop.platform.windows.DpapiCredentialStore
import com.vinnovateit.latch.desktop.platform.windows.WindowsSystemActions
import com.vinnovateit.latch.desktop.platform.windows.WindowsWifiPlatform

/** The operating systems [DesktopPlatformServices] can build concrete implementations for. */
enum class DesktopOs { WINDOWS, LINUX, MACOS, UNSUPPORTED }

fun currentDesktopOs(): DesktopOs = when {
    AppPaths.isWindows -> DesktopOs.WINDOWS
    AppPaths.isMac -> DesktopOs.MACOS
    AppPaths.isLinux -> DesktopOs.LINUX
    else -> DesktopOs.UNSUPPORTED
}

private object DesktopBuildInfo : BuildInfo {
    override val versionName: String = LatchCore.VERSION
    override val isDebug: Boolean = System.getProperty("latch.debug") == "true"
    override val isInstalled: Boolean = InstalledBuild.isInstalled
}

private object DesktopCapabilities : PlatformCapabilities {
    override val supportsAutostart: Boolean = AppPaths.isWindows || AppPaths.isLinux
}

/**
 * Wires concrete OS implementations together via runtime OS dispatch.
 * Windows and Linux are supported; macOS can be added by adding a macos package
 * and wiring its implementations in this dispatch.
 *
 * Unsupported OSes -- currently macOS -- fail here, before any OS-specific
 * side effect, rather than silently running Linux Wi-Fi/credential/system
 * code on a machine it was never written for. [os] is only a seam for tests;
 * production callers always take the default.
 */
class DesktopPlatformServices(
    echoLogsToStdout: Boolean = false,
    override val notifier: UserNotifier,
    os: DesktopOs = currentDesktopOs(),
) : PlatformServices {

    init {
        check(os == DesktopOs.WINDOWS || os == DesktopOs.LINUX) {
            "Latch CLI runtime currently supports Windows and Linux."
        }
    }

    override val logger: Logger = FileLogger(AppPaths.logsDir, echoLogsToStdout)

    override val buildInfo: BuildInfo = DesktopBuildInfo

    override val capabilities: PlatformCapabilities = DesktopCapabilities

    override val settingsStore: KeyValueStore = JsonKeyValueStore(AppPaths.settingsFile, logger)

    override val credentials: CredentialStore = when (os) {
        DesktopOs.WINDOWS -> DpapiCredentialStore(AppPaths.credentialsFile, logger)
        DesktopOs.LINUX -> LinuxCredentialStore(AppPaths.credentialsFile, logger)
        DesktopOs.MACOS, DesktopOs.UNSUPPORTED -> error("unreachable: guarded by init")
    }

    override val wifi: WifiPlatform = when (os) {
        DesktopOs.WINDOWS -> WindowsWifiPlatform(logger)
        DesktopOs.LINUX -> LinuxWifiPlatform(logger)
        DesktopOs.MACOS, DesktopOs.UNSUPPORTED -> error("unreachable: guarded by init")
    }

    override val counters: ByteCounterSource = OshiByteCounters(wifi, logger)

    override val systemActions: SystemActions = when (os) {
        DesktopOs.WINDOWS -> WindowsSystemActions(logger)
        DesktopOs.LINUX -> LinuxSystemActions(logger)
        DesktopOs.MACOS, DesktopOs.UNSUPPORTED -> error("unreachable: guarded by init")
    }

    override val httpTransport: HttpTransport = DesktopHttpTransport()
}
