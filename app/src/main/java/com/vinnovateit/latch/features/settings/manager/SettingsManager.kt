package com.vinnovateit.latch.features.settings.manager

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SettingsManager {

  private const val PREFS_NAME = "app_settings"
  private lateinit var sharedPreferences: SharedPreferences
  private var appContext: Context? = null

  // Keys
  private const val KEY_AUTO_LOGIN = "auto_login"

  private const val KEY_SPEED_UNITS = "speed_units"
  private const val KEY_THEME = "theme"
  private const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
  private const val KEY_USE_PURE_BLACK = "use_pure_black"
  private const val KEY_ACCENT_COLOR = "accent_color"
  private const val KEY_USE_MONOCHROME = "use_monochrome"
  private const val ACTION_SETTINGS_CHANGED = "com.vinnovateit.latch.ACTION_SETTINGS_CHANGED"

  // Default Values
  private const val DEFAULT_AUTO_LOGIN = true

  private const val DEFAULT_SPEED_UNITS = "bps"
  private const val DEFAULT_THEME = "System Default"
  private const val DEFAULT_USE_DYNAMIC_COLORS = false
  private const val DEFAULT_USE_PURE_BLACK = false
  private const val DEFAULT_USE_MONOCHROME = false
  private const val DEFAULT_ACCENT_COLOR = "Red"

  // StateFlows to observe changes
  private val _autoLogin = MutableStateFlow(DEFAULT_AUTO_LOGIN)
  val autoLogin: StateFlow<Boolean> = _autoLogin



  private val _speedUnits = MutableStateFlow(DEFAULT_SPEED_UNITS)
  val speedUnits: StateFlow<String> = _speedUnits

  private val _theme = MutableStateFlow(DEFAULT_THEME)
  val theme: StateFlow<String> = _theme

  private val _useDynamicColors = MutableStateFlow(DEFAULT_USE_DYNAMIC_COLORS)
  val useDynamicColors: StateFlow<Boolean> = _useDynamicColors

  private val _usePureBlack = MutableStateFlow(DEFAULT_USE_PURE_BLACK)
  val usePureBlack: StateFlow<Boolean> = _usePureBlack

  private val _useMonochrome = MutableStateFlow(DEFAULT_USE_MONOCHROME)
  val useMonochrome: StateFlow<Boolean> = _useMonochrome

  private val _accentColor = MutableStateFlow(DEFAULT_ACCENT_COLOR)
  val accentColor: StateFlow<String> = _accentColor

  fun initialize(context: Context) {
    appContext = context.applicationContext
    sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    loadSettings()
  }

  private fun loadSettings() {
    _autoLogin.value = sharedPreferences.getBoolean(KEY_AUTO_LOGIN, DEFAULT_AUTO_LOGIN)

    _speedUnits.value = sharedPreferences.getString(KEY_SPEED_UNITS, DEFAULT_SPEED_UNITS) ?: DEFAULT_SPEED_UNITS
    _theme.value = sharedPreferences.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
    _useDynamicColors.value = sharedPreferences.getBoolean(KEY_USE_DYNAMIC_COLORS, DEFAULT_USE_DYNAMIC_COLORS)
    _usePureBlack.value = sharedPreferences.getBoolean(KEY_USE_PURE_BLACK, DEFAULT_USE_PURE_BLACK)
    _useMonochrome.value = sharedPreferences.getBoolean(KEY_USE_MONOCHROME, DEFAULT_USE_MONOCHROME)
    _accentColor.value = sharedPreferences.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR) ?: DEFAULT_ACCENT_COLOR
  }

  fun setAutoLogin(enabled: Boolean) {
    _autoLogin.value = enabled
    sharedPreferences.edit { putBoolean(KEY_AUTO_LOGIN, enabled) }

    // LatchEngine reads the shared :core SettingsManager's autoLogin, a
    // separate in-memory singleton from this one -- without this, toggling
    // the connect button writes to this object's StateFlow (and the same
    // "app_settings" file) but the engine never sees the change until the
    // next process restart re-reads it from disk. Confirmed live: this was
    // silently making every login attempt skip straight to "Login failed"
    // whenever the two fell out of sync.
    com.vinnovateit.latch.core.settings.SettingsManager.setAutoLogin(enabled)

    appContext?.let {
      if (enabled) {
        it.startService(Intent(it, ForegroundService::class.java))
      } else if (SessionRepository.liveStatus.value != null) {
        // NOT stopService(): that kills the service immediately, skipping
        // the actual portal logout HTTP call and racing ahead of
        // ForegroundService's own graceful-shutdown wait (confirmed live on
        // the real VIT network -- stopService() destroyed the service
        // before the logout request even fired, leaving the UI stuck
        // showing "connected" even though the engine had logged out).
        // Sending the same explicit intent HomeScreen/TileService already
        // send lets the service log out through the engine, then stop
        // itself once that's actually done.
        //
        // Guarded on liveStatus (same signal LatchTileService already uses
        // for "is anything connected"): without it, disabling auto-login
        // from Settings with nothing connected still cold-started the
        // service just to have it immediately log out of a session that
        // never existed.
        it.startService(
          Intent(it, ForegroundService::class.java).apply {
            action = ForegroundService.ACTION_TRIGGER_LOGOUT
          }
        )
      }
    }
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

  fun setUsePureBlack(enabled: Boolean) {
    _usePureBlack.value = enabled
    sharedPreferences.edit { putBoolean(KEY_USE_PURE_BLACK, enabled) }
    sendSettingsChangedBroadcast()
  }

  fun setUseMonochrome(enabled: Boolean) {
    _useMonochrome.value = enabled
    sharedPreferences.edit { putBoolean(KEY_USE_MONOCHROME, enabled) }
    sendSettingsChangedBroadcast()
  }

  fun setAccentColor(color: String) {
    _accentColor.value = color
    sharedPreferences.edit { putString(KEY_ACCENT_COLOR, color) }
    sendSettingsChangedBroadcast()
  }

  private fun sendSettingsChangedBroadcast() {
    appContext?.let {
      val intent = Intent(ACTION_SETTINGS_CHANGED)
      it.sendBroadcast(intent)
    }
  }
}