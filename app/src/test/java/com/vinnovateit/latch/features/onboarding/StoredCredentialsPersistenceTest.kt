package com.vinnovateit.latch.features.onboarding

import android.content.SharedPreferences
import com.vinnovateit.latch.core.platform.android.StoredCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the synchronous persistence step behind
 * [StoredCredentials.saveCredentials] with in-memory preferences whose
 * `commit()` result is controlled, since EncryptedSharedPreferences and the
 * Android Keystore are unavailable in JVM unit tests.
 */
class StoredCredentialsPersistenceTest {

    @Test
    fun successfulCommitsReportSuccessAndSetTheCredentialFlag() {
        val credentials = FakePreferences(commitSucceeds = true)
        val appPrefs = FakePreferences(commitSucceeds = true)

        val saved = StoredCredentials.persistCredentials(credentials, appPrefs, "22BCE0001", "test-pass")

        assertTrue(saved)
        assertEquals("22BCE0001", credentials.values["user_id"])
        assertEquals("test-pass", credentials.values["password"])
        assertEquals(true, appPrefs.values["has_credentials"])
    }

    @Test
    fun aFailedCredentialCommitIsReportedAndLeavesTheFlagUnset() {
        val credentials = FakePreferences(commitSucceeds = false)
        val appPrefs = FakePreferences(commitSucceeds = true)

        val saved = StoredCredentials.persistCredentials(credentials, appPrefs, "22BCE0001", "test-pass")

        assertFalse(saved)
        assertNull(appPrefs.values["has_credentials"])
    }

    @Test
    fun aFailedFlagCommitIsReported() {
        val credentials = FakePreferences(commitSucceeds = true)
        val appPrefs = FakePreferences(commitSucceeds = false)

        val saved = StoredCredentials.persistCredentials(credentials, appPrefs, "22BCE0001", "test-pass")

        assertFalse(saved)
    }

    @Test
    fun persistenceNeverFallsBackToFireAndForgetApply() {
        val credentials = FakePreferences(commitSucceeds = true)
        val appPrefs = FakePreferences(commitSucceeds = true)

        StoredCredentials.persistCredentials(credentials, appPrefs, "22BCE0001", "test-pass")

        assertEquals(0, credentials.applyCalls + appPrefs.applyCalls)
        assertEquals(1, credentials.commitCalls)
        assertEquals(1, appPrefs.commitCalls)
    }
}

/** Stages edits and publishes them only when `commit()` is set to succeed. */
private class FakePreferences(private val commitSucceeds: Boolean) : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var commitCalls = 0
    var applyCalls = 0

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = also { staged[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = also { staged[key] = values }
        override fun putInt(key: String, value: Int) = also { staged[key] = value }
        override fun putLong(key: String, value: Long) = also { staged[key] = value }
        override fun putFloat(key: String, value: Float) = also { staged[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { staged[key] = value }
        override fun remove(key: String) = also { staged[key] = null }
        override fun clear() = also { values.clear() }
        override fun commit(): Boolean {
            commitCalls++
            if (commitSucceeds) values.putAll(staged)
            return commitSucceeds
        }
        override fun apply() {
            applyCalls++
            values.putAll(staged)
        }
    }

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
