package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.URL

class CaptivePortalDetector(
    private val transport: HttpTransport,
    private val logger: Logger,
) {
    private companion object {
        const val PROBE_URL = "http://clients3.google.com/generate_204"
        const val TAG = "CaptivePortalDetector"
    }

    fun checkPortalStatus(handle: NetworkHandle? = null): Int {
        return try {
            val connection = transport.open(URL(PROBE_URL), handle)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.connect()

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode
        } catch (e: Exception) {
            logger.e(TAG, "Portal check failed with exception", e)
            -1
        }
    }
}
