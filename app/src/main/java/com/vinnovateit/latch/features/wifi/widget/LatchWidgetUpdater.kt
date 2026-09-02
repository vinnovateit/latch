package com.vinnovateit.latch.features.wifi.widget

import android.app.UiModeManager
import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vinnovateit.latch.R
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatus
import com.vinnovateit.latch.platform.LatchAppGraph
import com.vinnovateit.latch.platform.toLegacyStatus
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import kotlinx.coroutines.flow.first

class LatchWidgetUpdater(
  context: Context,
  params: WorkerParameters
) : CoroutineWorker(context, params) {

  companion object {
    val WIDGET_STATE_PREF_KEY = stringPreferencesKey("latch_widget_state")

    fun enqueueOneTimeUpdate(context: Context) {
      val request = OneTimeWorkRequestBuilder<LatchWidgetUpdater>().build()
      WorkManager.getInstance(context).enqueueUniqueWork(
        "latch_widget_update_immediate",
        ExistingWorkPolicy.REPLACE,
        request
      )
    }

    private const val PERIODIC_UPDATE_WORK_NAME = "latch_widget_periodic_update"

    fun enqueuePeriodicUpdate(context: Context) {
      val periodicRequest = PeriodicWorkRequestBuilder<LatchWidgetUpdater>(
        15, TimeUnit.MINUTES
      ).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PERIODIC_UPDATE_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        periodicRequest
      )
    }

    fun cancelPeriodicUpdate(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_UPDATE_WORK_NAME)
    }
  }

  override suspend fun doWork(): Result {
    val manager = GlanceAppWidgetManager(applicationContext)
    val glanceIds = manager.getGlanceIds(LatchWidget::class.java)
    if (glanceIds.isEmpty()) return Result.success()

    SettingsManager.initialize(applicationContext)

    val uiModeManager = applicationContext.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    val isDarkMode = uiModeManager.nightMode == UiModeManager.MODE_NIGHT_YES
    val useDynamic = SettingsManager.useDynamicColors.first()
    val accentColorVal = SettingsManager.accentColor.first()

    val liveSession = LatchAppGraph.sessions.liveStatus.firstOrNull()
    val detailedStatus = LatchAppGraph.engine.status.value.toLegacyStatus(applicationContext)

    val widgetState = when (detailedStatus) {
      is ConnectionStatus.Companion.Connecting -> LatchWidgetState(
        status = detailedStatus.message,
        connectedDuration = "...",
        isConnected = false,
        isLightTheme = !isDarkMode,
        useDynamicColors = useDynamic,
        accentColor = accentColorVal
      )
      is ConnectionStatus.Failed -> LatchWidgetState(
        status = detailedStatus.message,
        connectedDuration = "-",
        isConnected = false,
        isLightTheme = !isDarkMode,
        useDynamicColors = useDynamic,
        accentColor = accentColorVal
      )
      else -> {
        if (liveSession != null) {
          val connectedAt = liveSession.startTimeMillis
          val now = System.currentTimeMillis()
          val durationMillis = now - connectedAt
          val durationString = when {
            durationMillis < 60_000 -> "Just now"
            durationMillis < 3_600_000 -> "${TimeUnit.MILLISECONDS.toMinutes(durationMillis)}m"
            else -> "${TimeUnit.MILLISECONDS.toHours(durationMillis)}h"
          }
          LatchWidgetState(
            status = applicationContext.getString(R.string.widget_status_connected),
            connectedDuration = durationString,
            isConnected = true,
            isLightTheme = !isDarkMode,
            useDynamicColors = useDynamic,
            accentColor = accentColorVal
          )
        } else {
          LatchWidgetState(
            status = applicationContext.getString(R.string.widget_status_disconnected),
            connectedDuration = "-",
            isConnected = false,
            isLightTheme = !isDarkMode,
            useDynamicColors = useDynamic,
            accentColor = accentColorVal
          )
        }
      }
    }

    glanceIds.forEach { glanceId ->
      updateAppWidgetState(applicationContext, glanceId) { prefs ->
        prefs[WIDGET_STATE_PREF_KEY] = Json.encodeToString(widgetState)
      }
      LatchWidget().update(applicationContext, glanceId)
    }

    return Result.success()
  }
}