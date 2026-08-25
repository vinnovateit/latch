package com.vinnovateit.latch.features.wifi.background

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vinnovateit.latch.R
import com.vinnovateit.latch.data.StoredCredentials
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.wifi.detector.CaptivePortalDetector
import com.vinnovateit.latch.features.wifi.detector.WiFiStateDetector
import com.vinnovateit.latch.features.wifi.manager.AutoLoginManager
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatusManager
import com.vinnovateit.latch.features.wifi.manager.LoginResult
import com.vinnovateit.latch.features.wifi.manager.UiNotifier
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var connectivityManager: ConnectivityManager
    private var healthCheckJob: Job? = null
    private val notificationId = 1
    private val channelId = "WIFI_LOGIN_CHANNEL"

    private var currentWifiNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var isNotificationHidden = false
    private var notificationUpdateJob: Job? = null

    // Guards bindProcessToNetwork(...) usage: handleCaptivePortal() and logoutAndStop()
    // both bind/unbind the process to a network around an HTTP request, and can run
    // concurrently from independent coroutines (network callback, health check, manual
    // login/logout intents) - without this, one can unbind the process out from under
    // the other's in-flight request.
    private val networkBindMutex = Mutex()

    companion object {
        const val ACTION_TRIGGER_LOGIN_CHECK = "com.vinnovateit.latch.ACTION_TRIGGER_LOGIN_CHECK"
        const val ACTION_TRIGGER_LOGOUT = "com.vinnovateit.latch.ACTION_TRIGGER_LOGOUT"
        const val ACTION_HIDE_NOTIFICATION = "com.vinnovateit.latch.ACTION_HIDE_NOTIFICATION"
        const val ACTION_SILENT_CHECK = "com.vinnovateit.latch.ACTION_SILENT_CHECK"
    }

    override fun onCreate() {
        super.onCreate()

        Log.d("ForegroundService", "Service created")
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        SettingsManager.initialize(applicationContext)

        try {
            val notification = createNotificationBuilder("Latch is Running", getString(R.string.notification_text)).build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notificationId, notification)
            }
        } catch (e: Exception) {
            Log.e("ForegroundService", "Foreground service start not allowed. System time limit exhausted.", e)
            stopSelf()
            return
        }

        serviceScope.launch {
            delay(5L * 60L * 60L * 1000L + 45L * 60L * 1000L) // 5 hours 45 mins
            Log.w("ForegroundService", "Proactively stopping service to avoid OS FGS time limit.")
            stopSelf()
        }

        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_TRIGGER_LOGIN_CHECK -> {
                Log.d("ForegroundService", "Manual login check triggered via intent.")
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Companion.Connecting(getString(R.string.status_initializing))
                )

                if (!WiFiStateDetector.isWiFiEnabled(this)) {
                    Log.w("ForegroundService", "Wi-Fi is disabled, aborting manual check.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_wifi_off)))
                    return START_STICKY
                }

                val targetWifiNetwork = currentWifiNetwork ?: getActiveWifiNetwork()

                if (targetWifiNetwork != null) {
                    checkNetworkAndAct(targetWifiNetwork, false, isSilent = false)
                } else {
                    Log.w("ForegroundService", "Could not find a connected Wi-Fi network. Aborting check.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_not_on_wifi)))
                }
            }
            ACTION_SILENT_CHECK -> {
                Log.d("ForegroundService", "Silent check triggered via intent.")
                val targetWifiNetwork = currentWifiNetwork ?: getActiveWifiNetwork()
                if (targetWifiNetwork != null) {
                    checkNetworkAndAct(targetWifiNetwork, false, isSilent = true)
                } else {
                    stopSelf()
                }
            }
            ACTION_TRIGGER_LOGOUT -> {
                Log.d("ForegroundService", "Manual logout triggered via intent.")
                serviceScope.launch(Dispatchers.IO) { logoutAndStop() }
            }
            ACTION_HIDE_NOTIFICATION -> {
                Log.d("ForegroundService", "Hiding notification on tap.")
                isNotificationHidden = true
                stopForeground(STOP_FOREGROUND_DETACH)
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(notificationId)
                notificationUpdateJob?.cancel()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("ForegroundService", "Service destroyed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("ForegroundService", "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
        serviceScope.cancel()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.e("ForegroundService", "Foreground service timeout reached for type $fgsType. Stopping service.")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationBuilder(title: String, text: String): NotificationCompat.Builder {
        val channelName = getString(R.string.notification_channel_name)

        val chan = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)

        val hideIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_HIDE_NOTIFICATION
        }
        val pendingIntent = PendingIntent.getService(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_latch)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
    }

    private fun updateNotification(title: String, text: String) {
        if (isNotificationHidden) return
        val notification = createNotificationBuilder(title, text).build()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            SessionRepository.liveStatus.collect { status ->
                if (status != null && status.liveData.isNotEmpty()) {
                    val latestUsage = status.liveData.last().usage
                    val downloadBps = latestUsage.rxBps
                    val uploadBps = latestUsage.txBps
                    val isDownloadDominant = downloadBps >= uploadBps
                    val dominatingBps = if (isDownloadDominant) downloadBps else uploadBps
                    
                    val speedUnit = SettingsManager.speedUnits.value
                    val (value, unit) = com.vinnovateit.latch.common.util.formatBitsPerSecond(dominatingBps, speedUnit)
                    val direction = if (isDownloadDominant) "↓" else "↑"
                    
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    val timeString = timeFormat.format(Date(status.startTimeMillis))
                    
                    updateNotification("Latched", "Speed: $direction $value $unit • Since $timeString")
                }
            }
        }
    }

    private fun getActiveWifiNetwork(): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) activeNetwork else null
    }

    private fun checkNetworkAndAct(
        network: Network,
        isRevalidating: Boolean,
        retryCount: Int = 0,
        isSilent: Boolean = false,
        notifyOnReconnect: Boolean = false
    ) {
        serviceScope.launch(Dispatchers.IO) {
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Companion.Connecting(getString(R.string.status_checking_internet))
            )

            val internetStatus = CaptivePortalDetector.checkPortalStatus(applicationContext, network)

            if (internetStatus == 204) {
                Log.d("ForegroundService", "Valid WiFi with internet. Starting session.")
                connectivityManager.reportNetworkConnectivity(network, true)
                ConnectionStatusManager.postStatus(ConnectionStatus.Success)
                SessionRepository.startSession(network)

                val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
                val timeString = timeFormat.format(Date())
                updateNotification("Latched", "Connected at $timeString")

                if (notifyOnReconnect) {
                    UiNotifier.showToast(applicationContext, "Reconnected to VIT WiFi at $timeString")
                }

                startHealthCheck(network)
                startNotificationUpdates()
                return@launch
            }

            // --- IF INTERNET IS NOT 204 YET ---

            if (internetStatus == CaptivePortalDetector.DNS_RESOLUTION_FAILED && !isRevalidating) {
                Log.w("ForegroundService", "Connectivity probe failed to resolve host.")
                val message = if (isPrivateDnsActive()) {
                    getString(R.string.status_private_dns_blocking)
                } else {
                    getString(R.string.status_unsupported_network)
                }
                ConnectionStatusManager.postStatus(ConnectionStatus.Failed(message))
                stopSelf()
                return@launch
            }

            // FIX #2: If we just logged in, give the portal 2 seconds to open the gates instead of infinite looping.
            if (isRevalidating) {
                if (retryCount < 3) {
                    Log.d("ForegroundService", "Waiting for network gates to open... (Attempt ${retryCount + 1})")
                    delay(2000) // Wait 2 seconds
                    checkNetworkAndAct(network, true, retryCount + 1, isSilent, notifyOnReconnect)
                } else {
                    Log.w("ForegroundService", "Network never granted internet after successful login.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed("Network timeout after login"))
                }
                return@launch
            }

            if (isSilent || !SettingsManager.autoLogin.value) {
                Log.d("ForegroundService", "Silent check or auto-login disabled. Stopping service without login.")
                ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_login_failed)))
                stopSelf()
                return@launch
            }

            Log.d("ForegroundService", "Captive portal detected. Proceeding to login.")
            handleCaptivePortal(network, notifyOnReconnect)
        }
    }

    private fun handleCaptivePortal(network: Network, notifyOnReconnect: Boolean = false) {
        serviceScope.launch(Dispatchers.IO) {
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Companion.Connecting(getString(R.string.status_authenticating))
            )
            networkBindMutex.withLock {
                connectivityManager.bindProcessToNetwork(network)
                try {
                    val user = StoredCredentials.getUserId(applicationContext)
                    val pass = StoredCredentials.getPassword(applicationContext)
                    if (user != null && pass != null) {
                        val fallbackIp = getGatewayIp()
                        when (AutoLoginManager.attemptLogin(user, pass, network, false, fallbackIp)) {
                            is LoginResult.Success -> {
                                Log.d("ForegroundService", "Login successful, re-validating network.")
                                checkNetworkAndAct(network, true, notifyOnReconnect = notifyOnReconnect)
                            }
                            is LoginResult.Failure -> {
                                ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_login_failed)))
                            }
                        }
                    } else {
                        ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_login_failed)))
                    }
                } finally {
                    connectivityManager.bindProcessToNetwork(null)
                }
            }
        }
    }

    private suspend fun logoutAndStop() {
        ConnectionStatusManager.postStatus(
            ConnectionStatus.Companion.Connecting(getString(R.string.status_logging_out))
        )
        healthCheckJob?.cancel()

        val network = currentWifiNetwork ?: connectivityManager.activeNetwork

        if (network != null) {
            networkBindMutex.withLock {
                connectivityManager.bindProcessToNetwork(network)
                try {
                    val fallbackIp = getGatewayIp()
                    val ok = AutoLoginManager.attemptLogout(false, fallbackIp, network)
                    if (ok) {
                        Log.d("ForegroundService", "Logout success.")
                        SessionRepository.stopSession()
                        ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_disconnected_message)))
                    } else {
                        Log.w("ForegroundService", "Logout failed.")
                        ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_logout_failed)))
                    }
                } finally {
                    connectivityManager.bindProcessToNetwork(null)
                }
            }
        } else {
            Log.w("ForegroundService", "No active network during logout.")
            SessionRepository.stopSession()
            ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getString(R.string.status_disconnected_message)))
        }

        stopSelf()
    }

    private fun isPrivateDnsActive(): Boolean {
        return try {
            val mode = Settings.Global.getString(contentResolver, "private_dns_mode")
            mode != null && mode != "off"
        } catch (e: Exception) {
            Log.e("ForegroundService", "Failed to read private_dns_mode", e)
            false
        }
    }

    private fun getGatewayIp(): String? {
        return try {
            val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcpInfo = wifiManager.dhcpInfo ?: return null
            val ipAddress = dhcpInfo.gateway
            if (ipAddress == 0) return null
            "${ipAddress and 0xFF}.${(ipAddress shr 8) and 0xFF}.${(ipAddress shr 16) and 0xFF}.${(ipAddress ushr 24) and 0xFF}"
        } catch (e: Exception) {
            Log.e("ForegroundService", "Failed to get gateway IP", e)
            null
        }
    }

    private fun startHealthCheck(network: Network) {
        healthCheckJob?.cancel()
        healthCheckJob = serviceScope.launch {
            while (isActive) {
                delay(60_000)
                Log.d("ForegroundService", "Performing periodic health check...")
                val status = CaptivePortalDetector.checkPortalStatus(applicationContext, network)
                if (status != 204) {
                    Log.w("ForegroundService", "Health check failed (status: $status). Session may have expired. Triggering re-login.")
                    checkNetworkAndAct(network, false, notifyOnReconnect = true)
                } else {
                    Log.d("ForegroundService", "Health check passed.")
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                currentWifiNetwork = network

                if (!WiFiStateDetector.isWiFiEnabled(this@ForegroundService)) {
                    return
                }
                checkNetworkAndAct(network, false, isSilent = !SettingsManager.autoLogin.value, notifyOnReconnect = true)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                if (currentWifiNetwork == network) {
                    currentWifiNetwork = null
                }

                Log.d("ForegroundService", "Network lost: $network")
                healthCheckJob?.cancel()
                SessionRepository.stopSession()
            }
        }
        this.networkCallback = networkCallback
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }
}