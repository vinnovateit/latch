package com.vinnovateit.latch.core.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** What stands between Latch and the connected network's name. */
enum class WifiNameAccess { Available, PermissionNeeded, LocationOff }

fun hasWifiNamePermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun wifiNameAccess(context: Context): WifiNameAccess {
    if (!hasWifiNamePermission(context)) return WifiNameAccess.PermissionNeeded
    val location = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val enabled = when {
        location == null -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> location.isLocationEnabled
        else -> location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    return if (enabled) WifiNameAccess.Available else WifiNameAccess.LocationOff
}

/** The network's name from WifiInfo, or null when Android withheld it. */
fun cleanAndroidSsid(raw: String?): String? =
    raw?.trim()?.removeSurrounding("\"")?.takeUnless { it.isEmpty() || it == WifiManager.UNKNOWN_SSID }

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

    @Volatile private var pinnedNetwork: Network? = null

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

    // Android reveals the name only with location access, so an unreadable name
    // says nothing about the network and must not be taken as VIT.
    override val requiresKnownSsid: Boolean get() = true

    override fun isNetworkNameHidden(): Boolean = isConnectedToWifi() && currentSsid() == null

    // connectionInfo is deprecated from API 31 in favour of a NetworkCallback
    // flag, but it is the one call that reads the name on demand on every
    // supported version, which is what the login gate needs.
    @Suppress("DEPRECATION")
    override fun currentSsid(): String? {
        if (!hasWifiNamePermission(context)) return null
        return try {
            cleanAndroidSsid(wifiManager?.connectionInfo?.ssid)
        } catch (e: SecurityException) {
            logger.w(TAG, "Wi-Fi name refused by the system: ${e.message}")
            null
        }
    }

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
        // The engine releases its binding with null, which must not undo a pin.
        connectivityManager.bindProcessToNetwork((handle as? AndroidNetworkHandle)?.network ?: pinnedNetwork)
    }

    override fun pinProcess(handle: NetworkHandle): () -> Unit {
        val network = (handle as? AndroidNetworkHandle)?.network ?: return {}
        pinnedNetwork = network
        connectivityManager.bindProcessToNetwork(network)
        return {
            pinnedNetwork = null
            connectivityManager.bindProcessToNetwork(null)
        }
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
