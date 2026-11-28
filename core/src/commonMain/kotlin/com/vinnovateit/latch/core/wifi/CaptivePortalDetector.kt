package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.mp.JHttpConnection as HttpURLConnection
import com.vinnovateit.latch.core.mp.JInputStream as InputStream
import com.vinnovateit.latch.core.mp.JUnknownHostException as UnknownHostException
import com.vinnovateit.latch.core.mp.JUrl as URL
import com.vinnovateit.latch.core.mp.httpDispatcher
import com.vinnovateit.latch.core.mp.nowMillis
import com.vinnovateit.latch.core.mp.useStream
import com.vinnovateit.latch.core.mp.withLock
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** The host VIT's Pronto portal redirects every intercepted request to. */
const val PRONTO_PORTAL_HOST = "phc.prontonetworks.com"

/** The same portal's address, used by account management and some redirects. */
const val PRONTO_PORTAL_IP = "136.233.9.110"

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
 * at Pronto's host or address.
 *
 * The redirect is matched on its host, never as a substring, so
 * "phc.prontonetworks.com.example" does not qualify. The page is searched
 * for the destination as part of a URL, for portals that answer 200 with a
 * meta-refresh or script redirect. Neither form authorises sending credentials
 * anywhere except a destination the caller has validated.
 */
internal fun pointsAtPronto(location: String?, body: String?): Boolean {
    val redirectHost = location?.let { runCatching { URL(it).host }.getOrNull() }
    if (redirectHost.equals(PRONTO_PORTAL_HOST, ignoreCase = true)) return true
    if (redirectHost.equals(PRONTO_PORTAL_IP, ignoreCase = true)) return true
    val page = body ?: return false
    return page.contains("//$PRONTO_PORTAL_HOST", ignoreCase = true) ||
        page.contains("//$PRONTO_PORTAL_IP", ignoreCase = true)
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

    suspend fun probe(handle: NetworkHandle? = null): PortalProbeResult {
        val start = nowMillis()
        logger.d(TAG, "Probing portal endpoint: $PROBE_URL (handle=${handle?.id ?: "default"})")
        return try {
            val result = intercept(URL(PROBE_URL), handle)
            val elapsed = nowMillis() - start
            logger.d(TAG, "Portal probe completed in ${elapsed}ms: $result")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            val elapsed = nowMillis() - start
            logger.e(TAG, "Portal check failed after ${elapsed}ms: DNS resolution failed for $PROBE_URL (${e.message})")
            PortalProbeResult.DnsBlocked
        } catch (e: Exception) {
            val elapsed = nowMillis() - start
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
    suspend fun probeGateway(gatewayIp: String, handle: NetworkHandle? = null): PortalProbeResult.Portal? {
        logger.d(TAG, "Probing gateway $gatewayIp for its portal")
        return try {
            intercept(URL("http://$gatewayIp/"), handle) as? PortalProbeResult.Portal
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Gateway $gatewayIp did not answer: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private val probeScope = CoroutineScope(SupervisorJob() + httpDispatcher)

    private class ConnHolder {
        private val lock = Any()
        private var cancelled = false
        private var conn: HttpURLConnection? = null

        fun attach(connection: HttpURLConnection): Boolean = withLock(lock) {
            if (cancelled) return@withLock false
            conn = connection
            true
        }

        fun cancel() {
            val toDisconnect = withLock(lock) {
                cancelled = true
                val current = conn
                conn = null
                current
            }
            if (toDisconnect != null) {
                try {
                    toDisconnect.disconnect()
                } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun intercept(url: URL, handle: NetworkHandle?): PortalProbeResult =
        suspendCancellableCoroutine { cont ->
            val holder = ConnHolder()
            cont.invokeOnCancellation {
                holder.cancel()
            }
            probeScope.launch {
                var opened: HttpURLConnection? = null
                try {
                    val conn = transport.open(url, handle)
                    opened = conn
                    if (!holder.attach(conn)) {
                        try {
                            conn.disconnect()
                        } catch (_: Throwable) {}
                        return@launch
                    }
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.useCaches = false
                    conn.connect()

                    val responseCode = conn.responseCode
                    val result = if (responseCode == 204) {
                        PortalProbeResult.Online
                    } else {
                        val location = conn.getHeaderField("Location")
                        val body = conn.bodySnippet()
                        PortalProbeResult.Portal(responseCode, location, pointsAtPronto(location, body))
                    }
                    if (cont.isActive) cont.resume(result)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                } finally {
                    try {
                        opened?.disconnect()
                    } catch (_: Throwable) {}
                }
            }
        }

    private fun HttpURLConnection.bodySnippet(): String? = try {
        (if (responseCode >= 400) errorStream else inputStream)
            ?.useStream { it.readUpTo(BODY_SNIPPET_BYTES).decodeToString() }
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
