package com.vinnovateit.latch.features.settings.manager

import android.content.Context
import android.content.Intent
import com.vinnovateit.latch.core.settings.SettingsManager as CoreSettings
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import kotlinx.coroutines.flow.StateFlow

/**
 * Android compatibility wrapper delegating to core SettingsManager.
 */
object SettingsManager {

    private var appContext: Context? = null
    private const val ACTION_SETTINGS_CHANGED = "com.vinnovateit.latch.ACTION_SETTINGS_CHANGED"

    val autoLogin: StateFlow<Boolean> get() = CoreSettings.autoLogin
    val speedUnits: StateFlow<String> get() = CoreSettings.speedUnits
    val theme: StateFlow<String> get() = CoreSettings.theme
    val useDynamicColors: StateFlow<Boolean> get() = CoreSettings.useDynamicColors
    val usePureBlack: StateFlow<Boolean> get() = CoreSettings.usePureBlack
    val useMonochrome: StateFlow<Boolean> get() = CoreSettings.useMonochrome
    val accentColor: StateFlow<String> get() = CoreSettings.accentColor

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun setAutoLogin(enabled: Boolean) {
        CoreSettings.setAutoLogin(enabled)

        appContext?.let {
            if (enabled) {
                it.startService(Intent(it, ForegroundService::class.java))
            } else if (SessionRepository.liveStatus.value != null) {
                it.startService(
                    Intent(it, ForegroundService::class.java).apply {
                        action = ForegroundService.ACTION_TRIGGER_LOGOUT
                    }
                )
            }
        }
    }

    fun setSpeedUnits(units: String) {
        CoreSettings.setSpeedUnits(units)
    }

    fun setTheme(themeValue: String) {
        CoreSettings.setTheme(themeValue)
        sendSettingsChangedBroadcast()
    }

    fun setUseDynamicColors(enabled: Boolean) {
        CoreSettings.setUseDynamicColors(enabled)
        sendSettingsChangedBroadcast()
    }

    fun setUsePureBlack(enabled: Boolean) {
        CoreSettings.setUsePureBlack(enabled)
        sendSettingsChangedBroadcast()
    }

    fun setUseMonochrome(enabled: Boolean) {
        CoreSettings.setUseMonochrome(enabled)
        sendSettingsChangedBroadcast()
    }

    fun setAccentColor(color: String) {
        CoreSettings.setAccentColor(color)
        sendSettingsChangedBroadcast()
    }

    private fun sendSettingsChangedBroadcast() {
        appContext?.let {
            val intent = Intent(ACTION_SETTINGS_CHANGED)
            it.sendBroadcast(intent)
        }
    }
}
