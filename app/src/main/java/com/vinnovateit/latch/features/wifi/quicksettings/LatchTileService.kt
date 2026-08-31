package com.vinnovateit.latch.features.wifi.quicksettings

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.platform.LatchAppGraph
import kotlinx.coroutines.*

class LatchTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var isProcessing = false

    companion object {
        private const val TAG = "LatchTileService"
        private const val ACTION_TIMEOUT_MS = 10000L // 10 seconds timeout
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d(TAG, "=== onStartListening called ===")
        updateTileState()

        serviceScope.launch {
            SessionRepository.liveStatus.collect {
                if (!isProcessing) {
                    updateTileState()
                }
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d(TAG, "=== onStopListening called ===")
        serviceScope.coroutineContext.cancelChildren()
    }


    override fun onClick() {
        Log.d(TAG, "=== TILE CLICKED ===")

        // SettingsManager may not have been initialized yet in this process (e.g. the
        // tile is tapped right after a reboot, before MainActivity/ForegroundService/
        // the widget ever ran) - setAutoLogin() below would crash on its lateinit
        // sharedPreferences without this.
        SettingsManager.initialize(applicationContext)

        if (isProcessing) {
            Log.d(TAG, "Already processing, ignoring click")
            return
        }

        val wifi = LatchAppGraph.platform.wifi
        if (!wifi.isWifiEnabled() || !wifi.isConnectedToWifi()) {
            Log.d(TAG, "WiFi is off or disconnected. Opening settings.")
            val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            return
        }

        serviceScope.launch {
            try {
                isProcessing = true
                updateTileState()

                val isConnected = SessionRepository.liveStatus.value != null

                if (isConnected) {
                    Log.d(TAG, "Currently connected. Triggering logout...")
                    // setAutoLogin(false) dispatches ACTION_TRIGGER_LOGOUT itself --
                    // sending it manually here too used to fire the portal logout
                    // twice. Always call it (not just when auto-login was on) since
                    // it's the sole path to the logout dispatch now.
                    SettingsManager.setAutoLogin(false)
                } else {
                    Log.d(TAG, "Currently disconnected and auto-login disabled. Triggering login check...")
                    val intent = Intent(this@LatchTileService, ForegroundService::class.java).apply {
                        action = ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
                    }
                    startService(intent)
                }

                waitForStatusChange(timeoutMs = ACTION_TIMEOUT_MS)

            } catch (e: Exception) {
                Log.e(TAG, "Error handling tile click", e)
            } finally {
                isProcessing = false
                updateTileState()
            }
        }
    }
    private suspend fun waitForStatusChange(timeoutMs: Long) {
        val initialStatus = SessionRepository.liveStatus.value
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val currentStatus = SessionRepository.liveStatus.value
            if (currentStatus != initialStatus) break
            delay(200)
        }
    }

    private fun updateTileState() {
        serviceScope.launch {
            try {
                val qsTile = qsTile ?: return@launch
                val isConnected = SessionRepository.liveStatus.value != null

                val wifi = LatchAppGraph.platform.wifi
                val isWifiReady = wifi.isWifiEnabled() && wifi.isConnectedToWifi()

                if (!isWifiReady) {
                    qsTile.state = Tile.STATE_UNAVAILABLE
                    qsTile.label = "Latch"
                } else {
                    when {
                        isConnected && !isProcessing -> {
                            qsTile.state = Tile.STATE_ACTIVE
                            qsTile.label = "Latched"
                        }
                        !isConnected && !isProcessing -> {
                            qsTile.state = Tile.STATE_INACTIVE
                            qsTile.label = "Latch"
                        }
                        isProcessing && isConnected -> {
                            qsTile.state = Tile.STATE_ACTIVE
                            qsTile.label = "Disconnecting..."
                        }
                        isProcessing && !isConnected -> {
                            qsTile.state = Tile.STATE_INACTIVE
                            qsTile.label = "Latching..."
                        }
                    }
                }

                qsTile.updateTile()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating tile state", e)
            }
        }
    }
}