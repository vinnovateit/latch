package com.vinnovateit.latch.features.wifi.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class LatchWidgetReceiver : GlanceAppWidgetReceiver() {
  override val glanceAppWidget: GlanceAppWidget = LatchWidget()

  companion object {
    private const val ACTION_SETTINGS_CHANGED = "com.vinnovateit.latch.ACTION_SETTINGS_CHANGED"
  }

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    val action = intent.action
    if (action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
      action == Intent.ACTION_CONFIGURATION_CHANGED ||
      action == "android.intent.action.UI_MODE_CHANGED" ||
      action == Intent.ACTION_WALLPAPER_CHANGED ||
      action == ACTION_SETTINGS_CHANGED) {
      LatchWidgetUpdater.enqueueOneTimeUpdate(context)
    }
  }

  override fun onEnabled(context: Context) {
    super.onEnabled(context)
    LatchWidgetUpdater.enqueueOneTimeUpdate(context)
    LatchWidgetUpdater.enqueuePeriodicUpdate(context)
  }

  override fun onDisabled(context: Context) {
    super.onDisabled(context)
    LatchWidgetUpdater.cancelPeriodicUpdate(context)
  }
}
