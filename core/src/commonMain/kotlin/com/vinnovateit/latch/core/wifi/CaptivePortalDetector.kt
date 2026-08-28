package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.URL
import java.net.UnknownHostException

/**
 * Probes a known no-content endpoint to decide whether a captive portal is in the way.
 *
 * @return 204 when the network has real internet, another HTTP code when a portal
 *   is intercepting, [DNS_RESOLUTION_FAILED] when the probe host can't be resolved
 *   (typically Private DNS blocking captive-portal detection), or -1 on any other
 *   exception. Contract matches the Android app exactly.
 */
class CaptivePortalDetector(
    private val transport: HttpTransport,
    private val logger: Logger,
) {
    companion object {
        const val DNS_RESOLUTION_FAILED = -2
        private const val PROBE_URL = "http://clients3.google.com/generate_204"
        private const val TAG = "CaptivePortalDetector"
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
        } catch (e: UnknownHostException) {
            logger.e(TAG, "Portal check failed: DNS resolution failed", e)
            DNS_RESOLUTION_FAILED
        } catch (e: Exception) {
            logger.e(TAG, "Portal check failed with exception", e)
            -1
        }
    }
}
