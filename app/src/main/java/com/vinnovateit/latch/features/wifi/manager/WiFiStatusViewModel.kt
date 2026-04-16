package com.vinnovateit.latch.features.wifi.manager

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import com.vinnovateit.latch.features.wifi.detector.WiFiConnectionDetector
import com.vinnovateit.latch.features.wifi.detector.WiFiStateDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("StaticFieldLeak")
class WiFiStatusViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx = application.applicationContext
    val connectionStatus: StateFlow<ConnectionStatus> = ConnectionStatusManager.status
    val isConnected: StateFlow<Boolean> = SessionRepository.liveStatus
        .map { it != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SessionRepository.liveStatus.value != null
        )

    private val _ssid = MutableStateFlow("Not Connected")

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val isSessionActive = SessionRepository.liveStatus.value != null

            withContext(Dispatchers.Main) {
                _ssid.value = if (isSessionActive) "Connected" else ("Not Connected")
            }

            Log.d("WiFiStatusViewModel", "UI Refreshed: SSID is ${_ssid.value}, IsSessionActive is $isSessionActive")
        }
    }

    fun authenticatePortal() {
        if (!WiFiStateDetector.isWiFiEnabled(ctx)) {
            ConnectionStatusManager.postStatus(ConnectionStatus.Failed("Wi-Fi is turned off"))
            return
        }

        if (!WiFiConnectionDetector.isConnectedToWiFi(ctx)) {
            ConnectionStatusManager.postStatus(ConnectionStatus.Failed("Not connected to Wi-Fi"))
            return
        }


        Log.d("WiFiStatusViewModel", "Delegating network check to ForegroundService.")
        val serviceIntent = Intent(getApplication(), ForegroundService::class.java).apply {
            action = ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
        }
        getApplication<Application>().startService(serviceIntent)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun toggleConnection() {
        val wifiManager = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            val intent = Intent(Settings.Panel.ACTION_WIFI)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
            return
        }
        if (!WiFiStateDetector.isWiFiEnabled(ctx)) {
            ConnectionStatusManager.postStatus(ConnectionStatus.Failed("Wi-Fi is turned off"))
            return
        }

        if (!WiFiConnectionDetector.isConnectedToWiFi(ctx)) {
            ConnectionStatusManager.postStatus(ConnectionStatus.Failed("Not connected to Wi-Fi"))
            return
        }

        val serviceIntent = Intent(getApplication(), ForegroundService::class.java)

        val sessionActive = SessionRepository.liveStatus.value != null
        if (sessionActive) {
            serviceIntent.action = ForegroundService.ACTION_TRIGGER_LOGOUT
        } else {
            serviceIntent.action = ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
        }

        getApplication<Application>().startService(serviceIntent)
    }

}