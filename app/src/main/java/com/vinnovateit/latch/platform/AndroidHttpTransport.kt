package com.vinnovateit.latch.platform

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android binds a connection to a specific Network via Network.openConnection(),
 * exactly what AutoLoginManager/CaptivePortalDetector already did by hand --
 * this is just that same call behind the shared HttpTransport seam.
 */
class AndroidHttpTransport : HttpTransport {
    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val network = (handle as? AndroidNetworkHandle)?.network
        return (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
    }
}
