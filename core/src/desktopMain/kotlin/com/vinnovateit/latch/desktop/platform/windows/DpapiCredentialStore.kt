package com.vinnovateit.latch.desktop.platform.windows

import com.sun.jna.platform.win32.Crypt32Util
import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class StoredCreds(val userId: String, val password: String)

/**
 * Seam over the actual DPAPI calls so tests can exercise success/failure
 * propagation without the Windows Crypt32 API, which is unavailable on the
 * Linux CI runners this module is tested on.
 */
internal interface DpapiBackend {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(encrypted: ByteArray): ByteArray
}

private object JnaDpapiBackend : DpapiBackend {
    override fun protect(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)
    override fun unprotect(encrypted: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(encrypted)
}

/**
 * Credential storage backed by Windows DPAPI.
 */
class DpapiCredentialStore internal constructor(
    private val file: File,
    private val logger: Logger,
    private val backend: DpapiBackend,
    private val writer: SecureFileWriter = SecureFileWriter(ownerOnly = false),
) : CredentialStore {
    constructor(file: File, logger: Logger) : this(file, logger, JnaDpapiBackend)


    private companion object {
        const val TAG = "DpapiCredentialStore"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: StoredCreds? = null

    /**
     * The protected payload replaces the previous file atomically, and the
     * cache only moves to the new credentials once that replacement has
     * succeeded, so a failed save leaves both the file and this instance on
     * the previous credentials.
     */
    override fun save(userId: String, password: String): Result<Unit> = runCatching {
        val plain = json.encodeToString(StoredCreds(userId, password)).toByteArray(Charsets.UTF_8)
        val encrypted = backend.protect(plain)
        writer.replace(file, encrypted)
        cache = StoredCreds(userId, password)
    }.onFailure { e ->
        logger.e(TAG, "Failed to save credentials", e)
    }

    private fun read(): StoredCreds? {
        cache?.let { return it }
        if (!file.exists()) return null
        return try {
            val decrypted = backend.unprotect(file.readBytes())
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
