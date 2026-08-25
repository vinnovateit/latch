package com.vinnovateit.latch.features.wifi.detector

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

object CaptivePortalDetector {

    /**
     * Sentinel returned when the connectivity probe fails specifically because the hostname
     * could not be resolved (e.g. Android Private DNS blocking resolution on an unauthenticated
     * captive network), as opposed to other connection failures.
     */
    const val DNS_RESOLUTION_FAILED = -2

    /**
     * Checks for a captive portal by connecting to a known endpoint.
     * Ensures the request is made specifically over the provided network.
     *
     * @param context Application context
     * @param network The network to check. If null, uses the active network.
     * @return The HTTP response code from the check. Returns 204 for success, other codes
     * for a captive portal, [DNS_RESOLUTION_FAILED] if hostname resolution failed, or -1 for
     * any other exception.
     */
    fun checkPortalStatus(context: Context, network: Network? = null): Int {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Prioritize the passed network, fall back to the active one.
        val targetNetwork = network ?: connectivityManager.activeNetwork ?: return -1 // No network

        return try {
            // Bind this connection to the target Wi-Fi network
            val url = URL("http://clients3.google.com/generate_204")
            val connection = targetNetwork.openConnection(url) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.useCaches = false
            connection.connect()

            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode
        } catch (e: UnknownHostException) {
            Log.e("CaptivePortalDetector", "Portal check failed to resolve host", e)
            DNS_RESOLUTION_FAILED
        } catch (e: Exception) {
            Log.e("CaptivePortalDetector", "Portal check failed with exception", e)
            -1
        }
    }
}
