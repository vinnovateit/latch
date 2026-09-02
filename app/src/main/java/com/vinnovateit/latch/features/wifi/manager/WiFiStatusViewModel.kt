package com.vinnovateit.latch.features.wifi.manager

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vinnovateit.latch.platform.LatchAppGraph
import com.vinnovateit.latch.platform.toLegacyStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * authenticatePortal()/toggleConnection() used to live here (dead code -- no
 * caller; HomeScreen's connect button talks to ForegroundService directly)
 * and were removed rather than migrated. connectionStatus now reads the
 * shared engine directly instead of relaying through Android's own
 * ConnectionStatusManager. isConnected keeps reading Android's own
 * SessionRepository -- that one stays the source of truth for session/stats
 * tracking (a different concern from login status), kept correct by
 * ForegroundService's isLatched bridge.
 */
@SuppressLint("StaticFieldLeak")
class WiFiStatusViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx = application.applicationContext
    val connectionStatus: StateFlow<ConnectionStatus> = LatchAppGraph.engine.status
        .map { it.toLegacyStatus(ctx) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LatchAppGraph.engine.status.value.toLegacyStatus(ctx)
        )
    val isConnected: StateFlow<Boolean> = LatchAppGraph.sessions.liveStatus
        .map { it != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LatchAppGraph.sessions.liveStatus.value != null
        )

    private val _ssid = MutableStateFlow("Not Latched")

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val isSessionActive = LatchAppGraph.sessions.liveStatus.value != null

            withContext(Dispatchers.Main) {
                _ssid.value = if (isSessionActive) "Latched" else ("Not Latched")
            }

            Log.d("WiFiStatusViewModel", "UI Refreshed: SSID is ${_ssid.value}, IsSessionActive is $isSessionActive")
        }
    }
}