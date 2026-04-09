package com.vinnovateit.latch.features.wifi.detector

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.vinnovateit.latch.common.debug.DebugRuntimeLogger
import com.vinnovateit.latch.common.network.NetworkAwareDnsResolver
import java.net.HttpURLConnection
import java.net.UnknownHostException
import java.net.URL

object CaptivePortalDetector {
    private const val PROBE_HOST = "clients3.google.com"
    private const val PROBE_URL = "http://clients3.google.com/generate_204"

    private fun toIpHostLiteral(ip: String): String {
        return if (':' in ip && !ip.startsWith("[")) "[$ip]" else ip
    }

    /**
     * Checks for a captive portal by connecting to a known endpoint.
     * Ensures the request is made specifically over the provided network.
     *
     * @param context Application context
     * @param network The network to check. If null, uses the active network.
     * @return The HTTP response code from the check. Returns 204 for success, other codes
     * for a captive portal, or -1 for an exception.
     */
    fun checkPortalStatus(context: Context, network: Network? = null): Int {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Prioritize the passed network, fall back to the active one.
        val targetNetwork = network ?: connectivityManager.activeNetwork ?: return -1 // No network

        return try {
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "A",
                location = "CaptivePortalDetector.kt:checkPortalStatus",
                message = "Starting captive portal probe",
                data = mapOf(
                    "hasPassedNetwork" to (network != null),
                    "targetNetwork" to targetNetwork.toString(),
                    "url" to PROBE_URL
                )
            )
            // #endregion

            val resolvedIp = NetworkAwareDnsResolver.resolveFirst(PROBE_HOST, targetNetwork).hostAddress
            val url = URL("http://${toIpHostLiteral(resolvedIp)}/generate_204")
            val connection = targetNetwork.openConnection(url) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.setRequestProperty("Host", PROBE_HOST)
            connection.connect()

            val responseCode = connection.responseCode
            connection.disconnect()
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "A",
                location = "CaptivePortalDetector.kt:checkPortalStatus",
                message = "Captive portal probe completed",
                data = mapOf("responseCode" to responseCode)
            )
            // #endregion
            responseCode
        } catch (e: UnknownHostException) {
            Log.w("CaptivePortalDetector", "Probe DNS blocked, treating as captive", e)
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "A",
                location = "CaptivePortalDetector.kt:checkPortalStatus",
                message = "Probe DNS blocked; treating as captive",
                data = mapOf(
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                )
            )
            // #endregion
            302
        } catch (e: Exception) {
            Log.e("CaptivePortalDetector", "Portal check failed with exception", e)
            // #region agent log
            DebugRuntimeLogger.log(
                runId = "pre-fix",
                hypothesisId = "A",
                location = "CaptivePortalDetector.kt:checkPortalStatus",
                message = "Captive portal probe exception",
                data = mapOf(
                    "exceptionClass" to e.javaClass.name,
                    "exceptionMessage" to (e.message ?: "null")
                )
            )
            // #endregion
            -1
        }
    }
}
