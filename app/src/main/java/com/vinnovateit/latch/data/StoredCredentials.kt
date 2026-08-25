package com.vinnovateit.latch.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import androidx.core.content.edit

object StoredCredentials {

    private const val PREFS_NAME = "latch_encrypted_credentials"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_PASSWORD = "password"

    private fun getEncryptedPrefs(context: Context): EncryptedSharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
        } catch (e: GeneralSecurityException) {

            clearCorruptedPreferences(context)
            null
        } catch (e: IOException) {

            clearCorruptedPreferences(context)
            null
        }
    }

    private fun clearCorruptedPreferences(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit { clear() }
    }

    fun saveCredentials(context: Context, userId: String, password: String): Boolean {
        val prefs = getEncryptedPrefs(context) ?: return false
        prefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_PASSWORD, password)
            .apply()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().putBoolean("has_credentials", true).apply()
        return true
    }

    fun getUserId(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs?.getString(KEY_USER_ID, null)
    }

    fun getPassword(context: Context): String? {
        val prefs = getEncryptedPrefs(context)
        return prefs?.getString(KEY_PASSWORD, null)
    }

    fun credentialsExist(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.contains("has_credentials")) {
            return prefs.getBoolean("has_credentials", false)
        }
        val exists = getUserId(context) != null
        prefs.edit().putBoolean("has_credentials", exists).apply()
        return exists
    }

    fun clearCredentials(context: Context) {
        val prefs = getEncryptedPrefs(context)
        prefs?.edit()?.clear()?.apply()
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().putBoolean("has_credentials", false).apply()
    }
}