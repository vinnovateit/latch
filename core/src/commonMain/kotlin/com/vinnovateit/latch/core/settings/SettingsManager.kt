package com.vinnovateit.latch.core.settings

import com.vinnovateit.latch.core.platform.InMemoryKeyValueStore
import com.vinnovateit.latch.core.platform.KeyValueStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings, ported from the Android app with its API shape preserved: still an
 * object, still a StateFlow plus an imperative setter per setting, still storing
 * display strings ("System Default", "Red", "bps") rather than enums so the keys
 * and values stay compatible.
 *
 * Two Android couplings are deliberately gone:
 *  - setAutoLogin no longer starts/stops a Service via Intent. The desktop
 *    daemon is always running; autoLogin is consulted by the engine instead.
 *  - sendSettingsChangedBroadcast() existed only to nudge the Glance widget.
 *    Replaced by [settingsChanged] for anything that needs to react.
 */
object SettingsManager {

    // Keys -- must match the Android app's SharedPreferences keys.
    private const val KEY_AUTO_LOGIN = "auto_login"
    private const val KEY_SPEED_UNITS = "speed_units"
    private const val KEY_THEME = "theme"
    private const val KEY_USE_DYNAMIC_COLORS = "use_dynamic_colors"
    private const val KEY_USE_PURE_BLACK = "use_pure_black"
    private const val KEY_USE_MONOCHROME = "use_monochrome"
    private const val KEY_ACCENT_COLOR = "accent_color"
    // Desktop-only additions.
    private const val KEY_ALLOWED_SSIDS = "allowed_ssids"
    private const val KEY_HAS_SEEN_ONBOARDING = "hasSeenOnboarding"

    /**
     * Whether we have already applied the default "start at login" behaviour.
     *
     * Note this tracks only that the *default was applied once* -- the actual
     * autostart state always comes from the registry, so a user who turns it off
     * does not get it silently re-enabled on next launch.
     */
    private const val KEY_AUTOSTART_DEFAULT_APPLIED = "autostart_default_applied"

    /** Epoch day (UTC) of the last automatic update check, so it runs once per calendar day rather than on every launch. */
    private const val KEY_LAST_UPDATE_CHECK_EPOCH_DAY = "last_update_check_epoch_day"

    private const val DEFAULT_AUTO_LOGIN = true
    private const val DEFAULT_SPEED_UNITS = "bps"
    private const val DEFAULT_THEME = "System Default"
    private const val DEFAULT_USE_DYNAMIC_COLORS = false
    private const val DEFAULT_USE_PURE_BLACK = false
    private const val DEFAULT_USE_MONOCHROME = false
    private const val DEFAULT_ACCENT_COLOR = "Red"

    /**
     * SSID fragments Latch is allowed to authenticate against, matched as
     * case-insensitive substrings (see LatchEngine.isTargetNetwork).
     *
     * Substring rather than exact match, because exact matching proved fragile in
     * testing: the saved WLAN profile was "G-VIT" while the live network name
     * reported by Windows was "G-VIT 5". Observed campus names include G-VIT,
     * G-VIT 5, E-VIT, VIT5 and VIT2.4 -- the single fragment "VIT" covers all of
     * them, and the portal-host DNS check is the real discriminator anyway.
     */
    val DEFAULT_ALLOWED_SSIDS: Set<String> = setOf("VIT")

    private var store: KeyValueStore = InMemoryKeyValueStore()

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

    private val _allowedSsids = MutableStateFlow(DEFAULT_ALLOWED_SSIDS)
    val allowedSsids: StateFlow<Set<String>> = _allowedSsids

    private val _hasSeenOnboarding = MutableStateFlow(false)
    val hasSeenOnboarding: StateFlow<Boolean> = _hasSeenOnboarding

    private val _settingsChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val settingsChanged: SharedFlow<Unit> = _settingsChanged

    fun initialize(keyValueStore: KeyValueStore) {
        store = keyValueStore
        loadSettings()
    }

    private fun loadSettings() {
        _autoLogin.value = store.getBoolean(KEY_AUTO_LOGIN, DEFAULT_AUTO_LOGIN)
        _speedUnits.value = store.getString(KEY_SPEED_UNITS, DEFAULT_SPEED_UNITS)
        _theme.value = store.getString(KEY_THEME, DEFAULT_THEME)
        _useDynamicColors.value = store.getBoolean(KEY_USE_DYNAMIC_COLORS, DEFAULT_USE_DYNAMIC_COLORS)
        _usePureBlack.value = store.getBoolean(KEY_USE_PURE_BLACK, DEFAULT_USE_PURE_BLACK)
        _useMonochrome.value = store.getBoolean(KEY_USE_MONOCHROME, DEFAULT_USE_MONOCHROME)
        _accentColor.value = store.getString(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)
        _allowedSsids.value = store.getStringSet(KEY_ALLOWED_SSIDS, DEFAULT_ALLOWED_SSIDS)
        _hasSeenOnboarding.value = store.getBoolean(KEY_HAS_SEEN_ONBOARDING, false)
    }

    fun setAutoLogin(enabled: Boolean) {
        _autoLogin.value = enabled
        store.putBoolean(KEY_AUTO_LOGIN, enabled)
    }

    fun setSpeedUnits(units: String) {
        _speedUnits.value = units
        store.putString(KEY_SPEED_UNITS, units)
    }

    fun setTheme(themeValue: String) {
        _theme.value = themeValue
        store.putString(KEY_THEME, themeValue)
        notifyChanged()
    }

    fun setUseDynamicColors(enabled: Boolean) {
        _useDynamicColors.value = enabled
        store.putBoolean(KEY_USE_DYNAMIC_COLORS, enabled)
        notifyChanged()
    }

    fun setUsePureBlack(enabled: Boolean) {
        _usePureBlack.value = enabled
        store.putBoolean(KEY_USE_PURE_BLACK, enabled)
        notifyChanged()
    }

    fun setUseMonochrome(enabled: Boolean) {
        _useMonochrome.value = enabled
        store.putBoolean(KEY_USE_MONOCHROME, enabled)
        notifyChanged()
    }

    fun setAccentColor(color: String) {
        _accentColor.value = color
        store.putString(KEY_ACCENT_COLOR, color)
        notifyChanged()
    }

    fun setAllowedSsids(ssids: Set<String>) {
        _allowedSsids.value = ssids
        store.putStringSet(KEY_ALLOWED_SSIDS, ssids)
    }

    fun setHasSeenOnboarding(seen: Boolean) {
        _hasSeenOnboarding.value = seen
        store.putBoolean(KEY_HAS_SEEN_ONBOARDING, seen)
    }

    var autostartDefaultApplied: Boolean
        get() = store.getBoolean(KEY_AUTOSTART_DEFAULT_APPLIED, false)
        set(value) = store.putBoolean(KEY_AUTOSTART_DEFAULT_APPLIED, value)

    /**
     * Stored as a string (epoch day) rather than adding a Long to [KeyValueStore]
     * -- this is the only caller that needs one, and it round-trips cleanly.
     */
    var lastUpdateCheckEpochDay: Long?
        get() = store.getString(KEY_LAST_UPDATE_CHECK_EPOCH_DAY, "").toLongOrNull()
        set(value) = store.putString(KEY_LAST_UPDATE_CHECK_EPOCH_DAY, value?.toString() ?: "")

    private fun notifyChanged() {
        _settingsChanged.tryEmit(Unit)
    }
}
