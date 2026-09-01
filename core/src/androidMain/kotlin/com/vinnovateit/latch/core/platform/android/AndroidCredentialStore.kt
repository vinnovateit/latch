package com.vinnovateit.latch.core.platform.android

import android.content.Context
import com.vinnovateit.latch.core.platform.CredentialStore

/**
 * Thin adapter over StoredCredentials -- kept as the real implementation
 * rather than reimplemented here, since it's the security-sensitive
 * EncryptedSharedPreferences/Keystore logic and shouldn't be duplicated.
 */
class AndroidCredentialStore(private val context: Context) : CredentialStore {
    override fun save(userId: String, password: String) {
        StoredCredentials.saveCredentials(context, userId, password)
    }

    override fun userId(): String? = StoredCredentials.getUserId(context)

    override fun password(): String? = StoredCredentials.getPassword(context)

    override fun exists(): Boolean = StoredCredentials.credentialsExist(context)

    override fun clear() = StoredCredentials.clearCredentials(context)
}
