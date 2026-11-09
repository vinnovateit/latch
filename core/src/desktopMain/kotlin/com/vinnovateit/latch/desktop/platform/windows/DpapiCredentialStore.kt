package com.vinnovateit.latch.desktop.platform.windows

import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.Win32Exception
import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

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

/** DPAPI reported that the blob itself is malformed, so retrying cannot help. */
internal class DpapiBlobCorruptException(cause: Throwable) : Exception(cause)

private const val ERROR_INVALID_DATA = 13
private const val NTE_BAD_DATA = 0x80090005.toInt()

private object JnaDpapiBackend : DpapiBackend {
    override fun protect(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain)
    override fun unprotect(encrypted: ByteArray): ByteArray = try {
        Crypt32Util.cryptUnprotectData(encrypted)
    } catch (e: Win32Exception) {
        if (e.errorCode == ERROR_INVALID_DATA || e.errorCode == NTE_BAD_DATA) throw DpapiBlobCorruptException(e)
        throw e
    }
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
        } catch (e: DpapiBlobCorruptException) {
            discardCorruptBlob(e)
        } catch (e: SerializationException) {
            discardCorruptBlob(e)
        } catch (e: Throwable) {
            // A locked file, a user profile that has not finished loading: the
            // blob may read fine next time, and a later save replaces it anyway.
            logger.e(TAG, "Credential blob could not be read right now; keeping it", e)
            null
        }
    }

    private fun discardCorruptBlob(cause: Exception): StoredCreds? {
        logger.e(TAG, "Credential blob is corrupt; clearing it", cause)
        runCatching { file.delete() }
        return null
    }

    override fun userId(): String? = read()?.userId

    override fun password(): String? = read()?.password

    override fun exists(): Boolean = read() != null

    override fun clear(): Result<Unit> = runCatching {
        cache = null
        Files.deleteIfExists(file.toPath())
        Unit
    }.onFailure { e ->
        logger.e(TAG, "Failed to remove credentials", e)
    }
}
