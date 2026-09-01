package com.vinnovateit.latch.core.platform.android

import android.content.Context
import androidx.core.content.edit
import com.vinnovateit.latch.core.platform.KeyValueStore

/**
 * Same SharedPreferences file Android's own SettingsManager already used
 * ("app_settings") -- existing settings are read as-is, no migration needed.
 */
class AndroidKeyValueStore(context: Context) : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default) ?: default

    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }

    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    override fun putStringSet(key: String, value: Set<String>) = prefs.edit { putStringSet(key, value) }
}
