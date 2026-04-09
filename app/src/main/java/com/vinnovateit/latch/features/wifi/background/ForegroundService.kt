package com.vinnovateit.latch.features.wifi.background

import android.app.*
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vinnovateit.latch.R
import com.vinnovateit.latch.data.StoredCredentials
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.wifi.detector.CaptivePortalDetector
import com.vinnovateit.latch.features.wifi.detector.WiFiConnectionDetector
import com.vinnovateit.latch.features.wifi.detector.WiFiStateDetector
import com.vinnovateit.latch.features.wifi.manager.AutoLoginManager
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatusManager
import com.vinnovateit.latch.features.wifi.manager.LoginResult
import dagger.hilt.android.internal.Contexts.getApplication
import kotlinx.coroutines.*

class ForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var connectivityManager: ConnectivityManager
    private var healthCheckJob: Job? = null

    companion object {
        const val ACTION_TRIGGER_LOGIN_CHECK = "com.vinnovateit.latch.ACTION_TRIGGER_LOGIN_CHECK"
        const val ACTION_TRIGGER_LOGOUT = "com.vinnovateit.latch.ACTION_TRIGGER_LOGOUT" // ADD
    }


    override fun onCreate() {
        super.onCreate()
        Log.d("ForegroundService", "Service created")
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        startForeground(1, createNotification())
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TRIGGER_LOGIN_CHECK -> {
                Log.d("ForegroundService", "Manual login check triggered via intent.")
                ConnectionStatusManager.postStatus(ConnectionStatus.Connecting(getApplication(applicationContext).getString(R.string.status_initializing)))

                if (!WiFiStateDetector.isWiFiEnabled(this)) {
                    Log.w("ForegroundService", "Wi-Fi is disabled, aborting manual check.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_wifi_off)))
                    return START_STICKY
                }

                if (!WiFiConnectionDetector.isConnectedToWiFi(this)) {
                    Log.w("ForegroundService", "Wi-Fi is enabled but not connected to a network.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_not_on_wifi)))
                    return START_STICKY
                }

                val activeNetwork = WiFiConnectionDetector.getWifiNetwork(this)
                if (activeNetwork != null) {
                    checkNetworkAndAct(activeNetwork)
                } else {
                    Log.w("ForegroundService", "Active network is not Wi-Fi. Aborting check.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_disconnected_message)))
                }
            }
            ACTION_TRIGGER_LOGOUT -> {
                Log.d("ForegroundService", "Manual logout triggered via intent.")
                serviceScope.launch(Dispatchers.IO) { logoutAndStop() }
            }
        }
        return START_STICKY
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d("ForegroundService", "Service destroyed")
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        val notificationChannelId = "WIFI_LOGIN_CHANNEL"
        val channelName = getApplication(applicationContext).getString(R.string.notification_channel_name)

        val chan = NotificationChannel(
            notificationChannelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)

        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle(getApplication(applicationContext).getString(R.string.notification_title))
            .setContentText(getApplication(applicationContext).getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_latch)
            .build()
    }

    private fun checkNetworkAndAct(network: Network) {
        serviceScope.launch(Dispatchers.IO) {
            ConnectionStatusManager.postStatus(ConnectionStatus.Connecting(
                getApplication(applicationContext).getString(R.string.status_checking_internet)
            ))

            connectivityManager.bindProcessToNetwork(network)
            try {
                val internetStatus = CaptivePortalDetector.checkPortalStatus(applicationContext, network)

                if (internetStatus == 204) {
                    // vit wifi check
                    if (AutoLoginManager.isTargetCaptivePortal(network)) {
                        Log.d("ForegroundService", "Valid VIT WiFi with internet. Starting session.")
                        connectivityManager.reportNetworkConnectivity(network, true)
                        ConnectionStatusManager.postStatus(ConnectionStatus.Success)
                        SessionRepository.startSession(network)
                        startHealthCheck(network)
                    } else {
                        Log.d("ForegroundService", "Non-VIT WiFi with internet. Ignoring.")
                        ConnectionStatusManager.postStatus(
                            ConnectionStatus.Failed(getApplication(applicationContext).getString(
                                R.string.status_unsupported_network
                            ))
                        )
                    }
                    return@launch
                }

                // Fallback: captive portal flow
                ConnectionStatusManager.postStatus(ConnectionStatus.Connecting(
                    getApplication(applicationContext).getString(R.string.status_verifying_network)
                ))
                if (AutoLoginManager.isTargetCaptivePortal(network)) {
                    Log.d("ForegroundService", "Target captive portal confirmed (VIT).")
                    handleCaptivePortalSuspend(network)
                } else {
                    Log.d("ForegroundService", "Non-VIT captive portal. Ignoring.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(
                        getApplication(applicationContext).getString(R.string.status_unsupported_network)
                    ))
                }
            } finally {
                connectivityManager.bindProcessToNetwork(null)
            }
        }
    }


    private suspend fun handleCaptivePortalSuspend(network: Network) {
        ConnectionStatusManager.postStatus(ConnectionStatus.Connecting(getApplication(applicationContext).getString(R.string.status_authenticating)))
        val user = StoredCredentials.getUserId(applicationContext)
        val pass = StoredCredentials.getPassword(applicationContext)
        if (user != null && pass != null) {
            when (AutoLoginManager.attemptLogin(user, pass, network)) {
                is LoginResult.Success -> {
                    Log.d("ForegroundService", "Login successful, re-validating network.")
                    checkNetworkAndAct(network)
                }
                is LoginResult.UnsupportedNetwork -> {
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_unsupported_network)))
                }
                is LoginResult.Failure -> {
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_login_failed)))
                }
            }
        } else {
            ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_login_failed)))
        }
    }

    private fun logoutAndStop() {
        ConnectionStatusManager.postStatus(ConnectionStatus.Connecting(getApplication(applicationContext).getString(R.string.status_logging_out)))
        healthCheckJob?.cancel()

        val network = WiFiConnectionDetector.getWifiNetwork(this)
        if (network != null) {

            connectivityManager.bindProcessToNetwork(network)
            try {
                val ok = AutoLoginManager.attemptLogout()
                if (ok) {
                    Log.d("ForegroundService", "Logout success.")

                    SessionRepository.stopSession()
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_disconnected_message)))
                } else {
                    Log.w("ForegroundService", "Logout failed.")
                    ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_logout_failed)))
                }
            } finally {
                connectivityManager.bindProcessToNetwork(null)
            }
        } else {
            Log.w("ForegroundService", "No active network during logout.")
            SessionRepository.stopSession()
            ConnectionStatusManager.postStatus(ConnectionStatus.Failed(getApplication(applicationContext).getString(R.string.status_disconnected_message)))
        }

        // kill service
        stopSelf()
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
                    checkNetworkAndAct(network)
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
                if (!WiFiStateDetector.isWiFiEnabled(this@ForegroundService)) {
                    return
                }
                checkNetworkAndAct(network)
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d("ForegroundService", "Network lost: $network")
                healthCheckJob?.cancel()
                SessionRepository.stopSession()
            }
        }
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }
}