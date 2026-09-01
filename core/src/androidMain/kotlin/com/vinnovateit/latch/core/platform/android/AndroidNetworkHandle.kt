package com.vinnovateit.latch.core.platform.android

import android.net.Network
import com.vinnovateit.latch.core.platform.NetworkHandle

/**
 * Carries the real [Network] Android's APIs actually need (Network.openConnection,
 * bindProcessToNetwork, reportNetworkConnectivity) -- NetworkHandle's own `id`
 * is not enough to do any of that. Only Android's own platform code
 * (AndroidWifiPlatform, AndroidHttpTransport) ever constructs or downcasts to
 * this type, the same way desktop's SimpleWindowsNetworkHandle/
 * SimpleLinuxNetworkHandle are internal to their own platform files.
 */
data class AndroidNetworkHandle(val network: Network) : NetworkHandle {
    override val id: String = network.toString()
}
