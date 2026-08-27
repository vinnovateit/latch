package com.vinnovateit.latch.desktop.platform.windows

import com.sun.jna.platform.win32.Crypt32Util
import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StoredCreds(val userId: String, val password: String)

/**
 * Credential storage backed by Windows DPAPI.
 */
class DpapiCredentialStore(
    private val file: File,
    private val logger: Logger,
) : CredentialStore {

    private companion object {
        const val TAG = "DpapiCredentialStore"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: StoredCreds? = null

    override fun save(userId: String, password: String) {
        try {
            val plain = json.encodeToString(StoredCreds(userId, password)).toByteArray(Charsets.UTF_8)
            val encrypted = Crypt32Util.cryptProtectData(plain)
            file.parentFile?.mkdirs()
            file.writeBytes(encrypted)
            cache = StoredCreds(userId, password)
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to save credentials", e)
        }
    }

    private fun read(): StoredCreds? {
        cache?.let { return it }
        if (!file.exists()) return null
        return try {
            val decrypted = Crypt32Util.cryptUnprotectData(file.readBytes())
            json.decodeFromString<StoredCreds>(decrypted.toString(Charsets.UTF_8))
                .also { cache = it }
        } catch (e: Throwable) {
            logger.e(TAG, "Credential blob unreadable; clearing it", e)
            runCatching { file.delete() }
            null
        }
    }

    override fun userId(): String? = read()?.userId

    override fun password(): String? = read()?.password

    override fun exists(): Boolean = read() != null

    override fun clear() {
        cache = null
        runCatching { file.delete() }
    }
}
