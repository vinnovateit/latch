package com.vinnovateit.latch.features.settings.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SettingsManager {

  private const val PREFS_NAME = "app_settings"
  private lateinit var sharedPreferences: SharedPreferences
  private var appContext: Context? = null

  // Keys
  private const val KEY_AUTO_LOGIN = "auto_login"
  private const val KEY_BYPASS_PORTAL_CHECK = "bypass_portal_check"
  private const val KEY_SPEED_UNITS = "speed_units"
  private const val KEY_THEME = "theme"
  private const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
  private const val ACTION_SETTINGS_CHANGED = "com.vinnovateit.latch.ACTION_SETTINGS_CHANGED"

  // Default Values
  private const val DEFAULT_AUTO_LOGIN = true
  private const val DEFAULT_BYPASS_PORTAL_CHECK = true // Enabled by default as requested
  private const val DEFAULT_SPEED_UNITS = "bps"
  private const val DEFAULT_THEME = "System Default"
  private const val DEFAULT_USE_DYNAMIC_COLORS = false

  // StateFlows to observe changes
  private val _autoLogin = MutableStateFlow(DEFAULT_AUTO_LOGIN)
  val autoLogin: StateFlow<Boolean> = _autoLogin

  private val _bypassPortalCheck = MutableStateFlow(DEFAULT_BYPASS_PORTAL_CHECK)
  val bypassPortalCheck: StateFlow<Boolean> = _bypassPortalCheck

  private val _speedUnits = MutableStateFlow(DEFAULT_SPEED_UNITS)
  val speedUnits: StateFlow<String> = _speedUnits

  private val _theme = MutableStateFlow(DEFAULT_THEME)
  val theme: StateFlow<String> = _theme

  private val _useDynamicColors = MutableStateFlow(DEFAULT_USE_DYNAMIC_COLORS)
  val useDynamicColors: StateFlow<Boolean> = _useDynamicColors

  fun initialize(context: Context) {
    appContext = context.applicationContext
    sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    loadSettings()
  }

  private fun loadSettings() {
    _autoLogin.value = sharedPreferences.getBoolean(KEY_AUTO_LOGIN, DEFAULT_AUTO_LOGIN)
    _bypassPortalCheck.value = sharedPreferences.getBoolean(KEY_BYPASS_PORTAL_CHECK, DEFAULT_BYPASS_PORTAL_CHECK)
    _speedUnits.value = sharedPreferences.getString(KEY_SPEED_UNITS, DEFAULT_SPEED_UNITS) ?: DEFAULT_SPEED_UNITS
    _theme.value = sharedPreferences.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
    _useDynamicColors.value = sharedPreferences.getBoolean(KEY_USE_DYNAMIC_COLORS, DEFAULT_USE_DYNAMIC_COLORS)
  }

  fun setAutoLogin(enabled: Boolean) {
    _autoLogin.value = enabled
    sharedPreferences.edit { putBoolean(KEY_AUTO_LOGIN, enabled) }

    appContext?.let {
      val serviceIntent = Intent(it, ForegroundService::class.java)
      if (enabled) {
        it.startService(serviceIntent)
      } else {
        it.stopService(serviceIntent)
      }
    }
  }

  fun setBypassPortalCheck(enabled: Boolean) {
    _bypassPortalCheck.value = enabled
    sharedPreferences.edit { putBoolean(KEY_BYPASS_PORTAL_CHECK, enabled) }
  }

  fun setSpeedUnits(units: String) {
    _speedUnits.value = units
    sharedPreferences.edit { putString(KEY_SPEED_UNITS, units) }
  }

  fun setTheme(themeValue: String) {
    _theme.value = themeValue
    sharedPreferences.edit { putString(KEY_THEME, themeValue) }
    sendSettingsChangedBroadcast()
  }

  fun setUseDynamicColors(enabled: Boolean) {
    _useDynamicColors.value = enabled
    sharedPreferences.edit { putBoolean(KEY_USE_DYNAMIC_COLORS, enabled) }
    sendSettingsChangedBroadcast()
  }

  private fun sendSettingsChangedBroadcast() {
    appContext?.let {
      val intent = Intent(ACTION_SETTINGS_CHANGED)
      it.sendBroadcast(intent)
    }
  }
}