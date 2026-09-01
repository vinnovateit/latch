package com.vinnovateit.latch.core.platform.android

import android.content.Context
import android.content.pm.ApplicationInfo
import com.vinnovateit.latch.core.platform.BuildInfo

class AndroidBuildInfo(private val context: Context) : BuildInfo {
    override val versionName: String by lazy {
        runCatching {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.3"
        }.getOrDefault("1.3")
    }

    override val isDebug: Boolean by lazy {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    override val isInstalled: Boolean = true
}
