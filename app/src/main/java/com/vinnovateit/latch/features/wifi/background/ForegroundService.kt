package com.vinnovateit.latch.features.wifi.background

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.wifi.widget.LatchWidgetUpdater
import com.vinnovateit.latch.platform.AndroidNetworkHandle
import com.vinnovateit.latch.platform.AndroidUserNotifier
import com.vinnovateit.latch.platform.ForegroundController
import com.vinnovateit.latch.platform.LatchAppGraph
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thin now: every real decision (Wi-Fi state, portal probing, login retries,
 * health checks) lives in the shared LatchEngine (core/engine/LatchEngine.kt),
 * which this just starts and forwards Intent actions to as LatchCommands.
 *
 * bridgeEngineStateToLegacyObservers() keeps Android's own SessionRepository
 * (session/stats tracking) and the widget refreshed from the engine's real
 * state. Status itself (WiFiStatusViewModel, LatchWidgetUpdater) is read
 * directly from LatchAppGraph.engine.status via toLegacyStatus() now.
 */
class ForegroundService : Service(), ForegroundController {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = AndroidUserNotifier.ONGOING_NOTIFICATION_ID
    private val channelId = AndroidUserNotifier.ONGOING_CHANNEL_ID

    private var isNotificationHidden = false
    private var notificationUpdateJob: Job? = null

    companion object {
        const val ACTION_TRIGGER_LOGIN_CHECK = "com.vinnovateit.latch.ACTION_TRIGGER_LOGIN_CHECK"
        const val ACTION_TRIGGER_LOGOUT = "com.vinnovateit.latch.ACTION_TRIGGER_LOGOUT"
        const val ACTION_HIDE_NOTIFICATION = "com.vinnovateit.latch.ACTION_HIDE_NOTIFICATION"
        const val ACTION_SILENT_CHECK = "com.vinnovateit.latch.ACTION_SILENT_CHECK"

        /** How long ACTION_TRIGGER_LOGOUT waits for a terminal status before stopping anyway. */
        private const val LOGOUT_TIMEOUT_MS = 15_000L
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ForegroundService", "Service created")

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

        LatchAppGraph.foregroundController.delegate = this
        LatchAppGraph.engine.start()
        bridgeEngineStateToLegacyObservers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_TRIGGER_LOGIN_CHECK -> {
                Log.d("ForegroundService", "Manual login check triggered via intent.")
                LatchAppGraph.engine.submit(LatchCommand.CheckAndLogin)
            }
            ACTION_SILENT_CHECK -> {
                Log.d("ForegroundService", "Silent check triggered via intent.")
                LatchAppGraph.engine.submit(LatchCommand.SilentCheck)
            }
            ACTION_TRIGGER_LOGOUT -> {
                Log.d("ForegroundService", "Manual logout triggered via intent.")
                serviceScope.launch {
                    // submitAndAwait suspends until logoutNow() has actually
                    // finished (not until some StateFlow happens to already
                    // satisfy a predicate, which raced the command itself --
                    // see LatchController.submitAndAwait's doc).
                    LatchAppGraph.engine.submitAndAwait(LatchCommand.Logout, LOGOUT_TIMEOUT_MS)
                    stopSelf()
                }
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
        if (LatchAppGraph.foregroundController.delegate === this) {
            LatchAppGraph.foregroundController.delegate = null
        }
        serviceScope.cancel()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        Log.e("ForegroundService", "Foreground service timeout reached for type $fgsType. Stopping service.")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- ForegroundController: the one seam AndroidUserNotifier needs a live Service for. ---

    override fun ongoingNotificationTapIntent(): PendingIntent {
        val hideIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_HIDE_NOTIFICATION
        }
        return PendingIntent.getService(this, 0, hideIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun detachForeground() {
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    // --- Keeps Android's own SessionRepository (session/stats tracking, a
    // different concern from login status, kept as-is) and the widget in
    // sync with the real engine. Status itself is now read directly from
    // LatchAppGraph.engine.status wherever it's needed (WiFiStatusViewModel,
    // LatchWidgetUpdater) via toLegacyStatus(), so this only needs to keep
    // the widget refreshing on every status change -- the same side effect
    // ConnectionStatusManager.postStatus() used to have, before it was
    // retired for having no remaining readers. ---

    private fun bridgeEngineStateToLegacyObservers() {
        serviceScope.launch {
            LatchAppGraph.engine.status.collect {
                LatchWidgetUpdater.enqueueOneTimeUpdate(this@ForegroundService)
            }
        }
        serviceScope.launch {
            var wasLatched = false
            LatchAppGraph.engine.isLatched.collect { latched ->
                if (latched && !wasLatched) {
                    val network = (LatchAppGraph.platform.wifi.activeHandle() as? AndroidNetworkHandle)?.network
                    if (network != null) SessionRepository.startSession(network)
                    val timeString = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                    updateNotification("Latched", "Connected at $timeString")
                    startNotificationUpdates()
                    LatchAppGraph.platform.notifier.notifyTransient("Connected", "Latched onto VIT WiFi at $timeString")
                } else if (!latched && wasLatched) {
                    SessionRepository.stopSession()
                    notificationUpdateJob?.cancel()
                }
                wasLatched = latched
            }
        }
    }

    private fun createNotificationBuilder(title: String, text: String): NotificationCompat.Builder {
        val channelName = getString(R.string.notification_channel_name)

        val chan = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_latch)
            .setContentIntent(ongoingNotificationTapIntent())
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
}
