package com.vinnovateit.latch.core.platform.android

import android.content.Context
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

private object AndroidCapabilities : PlatformCapabilities {
    override val supportsDynamicColor: Boolean = true
    override val supportsAutostart: Boolean = false
}

/** Mirrors DesktopPlatformServices's shape; notifier is injected since only the live ForegroundService can supply a ForegroundController. */
class AndroidPlatformServices(
    context: Context,
    override val notifier: UserNotifier,
) : PlatformServices {
    private val appContext = context.applicationContext

    override val logger: Logger = AndroidLogger()
    override val buildInfo: BuildInfo = AndroidBuildInfo(appContext)
    override val capabilities: PlatformCapabilities = AndroidCapabilities
    override val settingsStore: KeyValueStore = AndroidKeyValueStore(appContext)
    override val credentials: CredentialStore = AndroidCredentialStore(appContext)
    override val wifi: WifiPlatform = AndroidWifiPlatform(appContext, logger)
    override val counters: ByteCounterSource = AndroidByteCounterSource()
    override val systemActions: SystemActions = AndroidSystemActions(appContext, logger)
    override val httpTransport: HttpTransport = AndroidHttpTransport()
}
