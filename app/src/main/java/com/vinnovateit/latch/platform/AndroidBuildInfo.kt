package com.vinnovateit.latch.platform

import com.vinnovateit.latch.BuildConfig
import com.vinnovateit.latch.core.platform.BuildInfo

/**
 * isInstalled distinguishes jpackage installs from dev runs on desktop; that
 * distinction doesn't exist on Android (every build is "installed" once it's
 * running), and nothing in LatchEngine itself reads this flag -- it's only
 * consumed by desktop's own composition-root logic (autostart-default-once,
 * GithubUpdater), neither of which apply here.
 */
class AndroidBuildInfo : BuildInfo {
    override val versionName: String = BuildConfig.VERSION_NAME
    override val isDebug: Boolean = BuildConfig.DEBUG
    override val isInstalled: Boolean = true
}
