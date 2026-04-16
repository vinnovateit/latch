package com.vinnovateit.latch.features.wifi.detector

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object WiFiConnectionDetector {

    /**
     * Retrieves the current Wi-Fi network, regardless of validation status.
     * This grabs the network from `allNetworks` bypassing the default `activeNetwork`
     * which only returns validated connections that the OS promotes.
     *
     * @param context Application context
     * @return The Wi-Fi Network object, if any.
     */
    fun getWifiNetwork(context: Context): android.net.Network? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    /**
     * Checks if the device is currently connected to any Wi-Fi network.
     *
     * @param context Application context
     * @return true if connected to a Wi-Fi network, false otherwise
     */
    fun isConnectedToWiFi(context: Context): Boolean {
        return getWifiNetwork(context) != null
    }
}