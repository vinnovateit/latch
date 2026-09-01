package com.vinnovateit.latch.core.platform.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wi-Fi state and events for Android, folding in what used to be split across
 * WiFiConnectionDetector, WiFiStateDetector, and ForegroundService's own
 * network callback and getActiveWifiNetwork()/getGatewayIp() helpers.
 */
class AndroidWifiPlatform(
    private val context: Context,
    private val logger: Logger,
) : WifiPlatform {

    private companion object {
        const val TAG = "AndroidWifiPlatform"
    }

    private val connectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifiManager
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun isWifiEnabled(): Boolean = wifiManager?.isWifiEnabled == true

    override fun isConnectedToWifi(): Boolean {
        // activeNetwork is not reliable here: a captive-portal Wi-Fi network
        // (VIT's) stays unvalidated until login, so Android keeps mobile data
        // as the "active" default network in the meantime. Same fallback
        // chain WiFiConnectionDetector used to run independently.
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
        } catch (e: Exception) {
            logger.w(TAG, "activeNetwork check failed: ${e.message}")
        }

        try {
            val hasWifiTransport = connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (hasWifiTransport) return true
        } catch (e: Exception) {
            logger.w(TAG, "allNetworks scan failed: ${e.message}")
        }

        return false
    }

    // The app has never read the connected SSID -- doing so needs a location
    // permission it doesn't request. LatchEngine.isVitCampusSsid() already
    // treats a null SSID as "could be VIT, don't block" (returns true), which
    // is exactly today's Android behavior: gate on portal detection alone.
    override fun currentSsid(): String? = null

    override fun gatewayIp(): String? {
        return try {
            val dhcpInfo = wifiManager?.dhcpInfo ?: return null
            val ip = dhcpInfo.gateway
            if (ip == 0) return null
            "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip ushr 24) and 0xFF}"
        } catch (e: Exception) {
            logger.e(TAG, "Failed to get gateway IP", e)
            null
        }
    }

    override fun activeHandle(): NetworkHandle? = findActiveWifiNetwork()?.let(::AndroidNetworkHandle)

    override val events: Flow<WifiEvent> = callbackFlow {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(WifiEvent.Available(AndroidNetworkHandle(network)))
            }

            override fun onLost(network: Network) {
                trySend(WifiEvent.Lost(AndroidNetworkHandle(network)))
            }
        }

        connectivityManager.registerNetworkCallback(request, callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    override fun bindProcess(handle: NetworkHandle?) {
        connectivityManager.bindProcessToNetwork((handle as? AndroidNetworkHandle)?.network)
    }

    override fun reportConnectivity(handle: NetworkHandle, ok: Boolean) {
        (handle as? AndroidNetworkHandle)?.let {
            connectivityManager.reportNetworkConnectivity(it.network, ok)
        }
    }

    private fun findActiveWifiNetwork(): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            return activeNetwork
        }
        return connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }
}
