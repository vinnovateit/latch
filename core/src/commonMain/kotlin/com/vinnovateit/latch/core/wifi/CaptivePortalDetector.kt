package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

/** The host VIT's Pronto portal redirects every intercepted request to. */
const val PRONTO_PORTAL_HOST = "phc.prontonetworks.com"

/**
 * Typed outcome of a captive portal probe attempt.
 */
sealed interface PortalProbeResult {
    /** The network has real internet (HTTP 204). */
    data object Online : PortalProbeResult

    /**
     * The network intercepted the probe with a captive portal. [isPronto] is
     * true only when the interception points at [PRONTO_PORTAL_HOST], as VIT's
     * portal does. A cafe's, hotel's or airport's portal points elsewhere.
     */
    data class Portal(val responseCode: Int, val location: String?, val isPronto: Boolean) : PortalProbeResult

    /** DNS resolution failed, typically Private DNS blocking detection. */
    data object DnsBlocked : PortalProbeResult

    /** Network error or timeout. */
    data class Error(val message: String?) : PortalProbeResult
}

/**
 * Whether a portal's redirect, or the page it served instead of one, points
 * at [PRONTO_PORTAL_HOST].
 *
 * The redirect is matched on its host, never as a substring, so
 * "phc.prontonetworks.com.example" does not qualify. The page is searched
 * for the host as part of a URL, for portals that answer 200 with a
 * meta-refresh or script redirect.
 */
internal fun pointsAtPronto(location: String?, body: String?): Boolean {
    val redirectHost = location?.let { runCatching { URL(it).host }.getOrNull() }
    if (redirectHost.equals(PRONTO_PORTAL_HOST, ignoreCase = true)) return true
    return body?.contains("//$PRONTO_PORTAL_HOST", ignoreCase = true) == true
}

/**
 * Probes a known no-content endpoint to decide whether a captive portal is in the way.
 */
class CaptivePortalDetector(
    private val transport: HttpTransport,
    private val logger: Logger,
) {
    private companion object {
        private const val PROBE_URL = "http://clients3.google.com/generate_204"
        private const val TAG = "CaptivePortalDetector"

        /** Enough for a portal's redirect page; a fingerprint never needs the whole body. */
        private const val BODY_SNIPPET_BYTES = 8 * 1024
    }

    fun probe(handle: NetworkHandle? = null): PortalProbeResult {
        val start = System.currentTimeMillis()
        logger.d(TAG, "Probing portal endpoint: $PROBE_URL (handle=${handle?.id ?: "default"})")
        return try {
            val result = intercept(URL(PROBE_URL), handle)
            val elapsed = System.currentTimeMillis() - start
            logger.d(TAG, "Portal probe completed in ${elapsed}ms: $result")
            result
        } catch (e: UnknownHostException) {
            val elapsed = System.currentTimeMillis() - start
            logger.e(TAG, "Portal check failed after ${elapsed}ms: DNS resolution failed for $PROBE_URL (${e.message})")
            PortalProbeResult.DnsBlocked
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            logger.e(TAG, "Portal check failed after ${elapsed}ms with exception: ${e::class.simpleName}: ${e.message}")
            PortalProbeResult.Error(e.message)
        }
    }

    /**
     * Asks the gateway itself, by address, which portal it fronts. Null when
     * it does not answer.
     *
     * Only for when [probe] got no answer at all: a resolver the portal blocks
     * (a custom DNS, a VPN), or no route off the subnet before login. The
     * gateway is on the local network, so it still answers, and VIT's
     * redirects to Pronto. It redirects even after login, so its answer says
     * which portal this is, never whether the portal has been cleared.
     */
    fun probeGateway(gatewayIp: String, handle: NetworkHandle? = null): PortalProbeResult.Portal? {
        logger.d(TAG, "Probing gateway $gatewayIp for its portal")
        return try {
            intercept(URL("http://$gatewayIp/"), handle) as? PortalProbeResult.Portal
        } catch (e: Exception) {
            logger.w(TAG, "Gateway $gatewayIp did not answer: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun intercept(url: URL, handle: NetworkHandle?): PortalProbeResult {
        var connection: HttpURLConnection? = null
        try {
            val conn = transport.open(url, handle)
            connection = conn
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.useCaches = false
            conn.connect()

            val responseCode = conn.responseCode
            if (responseCode == 204) return PortalProbeResult.Online
            val location = conn.getHeaderField("Location")
            val body = conn.bodySnippet()
            return PortalProbeResult.Portal(responseCode, location, pointsAtPronto(location, body))
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    private fun HttpURLConnection.bodySnippet(): String? = try {
        (if (responseCode >= 400) errorStream else inputStream)
            ?.use { String(it.readUpTo(BODY_SNIPPET_BYTES), Charsets.ISO_8859_1) }
    } catch (_: Exception) {
        null
    }

    // InputStream.readNBytes is Java 11 API, which Android only has from API 33.
    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val read = read(buffer, total, limit - total)
            if (read < 0) break
            total += read
        }
        return buffer.copyOf(total)
    }
}
