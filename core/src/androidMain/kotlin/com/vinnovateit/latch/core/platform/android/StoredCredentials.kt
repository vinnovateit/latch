package com.vinnovateit.latch.core.platform.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

object StoredCredentials {

    private const val PREFS_NAME = "latch_encrypted_credentials"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_PASSWORD = "password"
    // Default alias used by MasterKey.Builder when no custom alias is set.
    private const val MASTER_KEY_ALIAS = "_androidx_security_master_key"

    private fun getEncryptedPrefs(context: Context): EncryptedSharedPreferences? {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: GeneralSecurityException) {
            // Keystore key is stale (e.g. after app-data clear). Wipe everything
            // so the next attempt starts completely fresh.
            clearCorruptedState(context)
            null
        } catch (e: IOException) {
            clearCorruptedState(context)
            null
        }
    }

    /**
     * Same as [getEncryptedPrefs] but performs one recovery attempt after a
     * corruption is detected. Used by [saveCredentials] so a first-time save
     * after an app-data clear succeeds in the same call rather than requiring
     * the user to press the button twice.
     */
    private fun getEncryptedPrefsWithRecovery(context: Context): EncryptedSharedPreferences? {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: GeneralSecurityException) {
            clearCorruptedState(context)
            tryBuildEncryptedPrefsAfterRecovery(context)
        } catch (e: IOException) {
            clearCorruptedState(context)
            tryBuildEncryptedPrefsAfterRecovery(context)
        }
    }

    private fun buildEncryptedPrefs(context: Context): EncryptedSharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    private fun tryBuildEncryptedPrefsAfterRecovery(context: Context): EncryptedSharedPreferences? {
        return try {
            buildEncryptedPrefs(context)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Clears the corrupted SharedPreferences file **and** the orphaned Android
     * Keystore entry. Both must be removed together: leaving the Keystore entry
     * causes the next [MasterKey.Builder] call to fail with a key-mismatch error
     * even though the prefs file is gone.
     */
    private fun clearCorruptedState(context: Context) {
        // Wipe the encrypted prefs XML.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        // Wipe the app-level credential flag.
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("has_credentials", false).apply()
        // Delete the stale Keystore key so MasterKey.Builder can create a fresh one.
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        } catch (_: Exception) {
            // Best-effort; if this fails the next getEncryptedPrefs will return null.
        }
    }

    /** Blocks on disk I/O; call off the main thread. */
    fun saveCredentials(context: Context, userId: String, password: String): Boolean {
        val prefs = getEncryptedPrefsWithRecovery(context) ?: return false
        return persistCredentials(
            prefs,
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE),
            userId,
            password,
        )
    }

    /**
     * Writes with `commit()` so a failed disk write reaches the caller;
     * `apply()` reported success before anything was persisted. The
     * `has_credentials` flag is set only once the credentials themselves are
     * on disk, and its own failed write also fails the save.
     */
    fun persistCredentials(
        credentialPrefs: SharedPreferences,
        appPrefs: SharedPreferences,
        userId: String,
        password: String,
    ): Boolean {
        val saved = credentialPrefs.edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_PASSWORD, password)
            .commit()
        if (!saved) return false
        return appPrefs.edit().putBoolean("has_credentials", true).commit()
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
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("has_credentials", false).apply()
    }
}