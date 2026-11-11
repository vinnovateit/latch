package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.net.URL
import java.net.UnknownHostException

/**
 * Typed outcome of a captive portal probe attempt.
 */
sealed interface PortalProbeResult {
    /** The network has real internet (HTTP 204). */
    data object Online : PortalProbeResult

    /** The network intercepted the probe with a captive portal. */
    data class Portal(val responseCode: Int, val location: String?) : PortalProbeResult

    /** DNS resolution failed, typically Private DNS blocking detection. */
    data object DnsBlocked : PortalProbeResult

    /** Network error or timeout. */
    data class Error(val message: String?) : PortalProbeResult
}

/**
 * Probes a known no-content endpoint to decide whether a captive portal is in the way.
 */
class CaptivePortalDetector(
    private val transport: HttpTransport,
    private val logger: Logger,
) {
    private companion object {
        private val PROBE_URLS = listOf(
            "http://clients3.google.com/generate_204",
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://detectportal.firefox.com/canonical.html",
        )
        private const val TAG = "CaptivePortalDetector"
    }

    fun probe(handle: NetworkHandle? = null): PortalProbeResult {
        var lastException: Throwable? = null
        var allDnsBlocked = true

        for (urlStr in PROBE_URLS) {
            val start = System.currentTimeMillis()
            var connection: java.net.HttpURLConnection? = null
            try {
                logger.d(TAG, "Probing portal endpoint: $urlStr (handle=${handle?.id ?: "default"})")
                val conn = transport.open(URL(urlStr), handle)
                connection = conn
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.useCaches = false
                conn.connect()

                val responseCode = conn.responseCode
                val elapsed = System.currentTimeMillis() - start
                val location = conn.getHeaderField("Location")

                logger.d(TAG, "Portal probe completed in ${elapsed}ms: HTTP $responseCode ${if (location != null) "(Location: $location)" else ""}")

                val isSuccess = responseCode == 204 ||
                    (responseCode == 200 && urlStr.contains("detectportal.firefox.com") && conn.contentLength in 0..100)

                return if (isSuccess) {
                    PortalProbeResult.Online
                } else {
                    PortalProbeResult.Portal(responseCode, location)
                }
            } catch (e: UnknownHostException) {
                lastException = e
                val elapsed = System.currentTimeMillis() - start
                logger.w(TAG, "Probe DNS failed for $urlStr in ${elapsed}ms: ${e.message}")
            } catch (e: Exception) {
                allDnsBlocked = false
                lastException = e
                val elapsed = System.currentTimeMillis() - start
                logger.w(TAG, "Probe error for $urlStr in ${elapsed}ms: ${e::class.simpleName}: ${e.message}")
            } finally {
                try {
                    connection?.disconnect()
                } catch (_: Throwable) {}
            }
        }

        return if (allDnsBlocked && lastException is UnknownHostException) {
            PortalProbeResult.DnsBlocked
        } else {
            PortalProbeResult.Error(lastException?.message)
        }
    }
}
