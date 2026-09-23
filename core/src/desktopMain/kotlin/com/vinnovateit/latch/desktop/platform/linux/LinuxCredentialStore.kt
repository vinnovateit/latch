package com.vinnovateit.latch.desktop.platform.linux

import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
private data class StoredCreds(val userId: String, val password: String)

/**
 * Seam over the `secret-tool` subprocess so tests can exercise every branch of
 * [LinuxCredentialStore] without a real desktop keyring. [store] and [lookup]
 * report failure as `false`/`null` rather than throwing, mirroring what the
 * real `secret-tool` subprocess does (a non-zero exit, not an exception).
 */
internal interface SecretServiceBackend {
    val isAvailable: Boolean
    fun store(payload: String): Boolean
    fun lookup(): String?
    fun clear()
}

private class ProcessSecretServiceBackend : SecretServiceBackend {
    override val isAvailable: Boolean by lazy {
        try {
            val process = ProcessBuilder("secret-tool", "--help").start()
            process.waitFor(2, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Throwable) {
            false
        }
    }

    override fun store(payload: String): Boolean = try {
        val process = ProcessBuilder("secret-tool", "store", "--label=Latch Credentials", "service", "Latch")
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use {
            it.write(payload)
            it.flush()
        }
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: Throwable) {
        false
    }

    override fun lookup(): String? = try {
        val process = ProcessBuilder("secret-tool", "lookup", "service", "Latch")
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0 && text.isNotEmpty()) text else null
    } catch (e: Throwable) {
        null
    }

    override fun clear() {
        try {
            val process = ProcessBuilder("secret-tool", "clear", "service", "Latch").start()
            process.waitFor(2, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            // Ignore
        }
    }
}

/**
 * Linux credential storage leveraging Secret Service API (`secret-tool`) when available,
 * with fallback to AES-256 GCM encrypted storage with strict POSIX 0600 file permissions.
 *
 * The fallback key is derived from machine/user identifiers plus a locally
 * generated salt -- it is a local encrypted-at-rest fallback for machines
 * without a usable Secret Service, not a hardware-backed key store or a
 * secret only the user can produce. Anyone with read access to this process's
 * user account and `/etc/machine-id` can, in principle, rederive it.
 */
class LinuxCredentialStore internal constructor(
    private val file: File,
    private val logger: Logger,
    private val secretService: SecretServiceBackend,
) : CredentialStore {
    constructor(file: File, logger: Logger) : this(file, logger, ProcessSecretServiceBackend())


    private companion object {
        const val TAG = "LinuxCredentialStore"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
        const val SALT = "LatchLinuxCredsSalt"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: StoredCreds? = null

    private fun getOrCreateSalt(): ByteArray {
        val saltFile = File(file.parentFile, ".creds_salt")
        if (saltFile.exists() && saltFile.length() >= 16) {
            return runCatching { saltFile.readBytes() }.getOrNull() ?: SALT.toByteArray(Charsets.UTF_8)
        }
        val randomSalt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        runCatching {
            saltFile.parentFile?.mkdirs()
            saltFile.writeBytes(randomSalt)
            val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(saltFile.toPath(), perms)
        }
        return randomSalt
    }

    private fun deriveKey(): SecretKey {
        val machineId = runCatching {
            File("/etc/machine-id").takeIf { it.exists() }?.readText()?.trim()
                ?: File("/var/lib/dbus/machine-id").takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull() ?: (System.getProperty("user.name") + SALT)

        val user = System.getProperty("user.name").orEmpty()
        val salt = getOrCreateSalt()
        val rawPrefix = "$machineId:$user:".toByteArray(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(rawPrefix + salt)
        return SecretKeySpec(sha256, "AES")
    }

    /**
     * Secret Service, when it successfully stores the credential, is treated
     * as the sole authoritative store: the fallback blob is removed only
     * *after* that write is confirmed, so a mid-write crash never loses both
     * copies at once. When Secret Service is unavailable or the write fails,
     * the fallback is written and its own failure is what `save` reports.
     */
    override fun save(userId: String, password: String): Result<Unit> {
        val creds = StoredCreds(userId, password)
        val plainJson = json.encodeToString(creds)

        if (secretService.isAvailable && secretService.store(plainJson)) {
            cache = creds
            runCatching { file.delete() }
            return Result.success(Unit)
        }

        return runCatching {
            val plain = plainJson.toByteArray(Charsets.UTF_8)
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encrypted = cipher.doFinal(plain)

            val payload = iv + encrypted
            file.parentFile?.mkdirs()
            file.writeBytes(payload)

            // Restrict file permissions to owner read/write only (POSIX 0600)
            runCatching {
                val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                Files.setPosixFilePermissions(file.toPath(), perms)
            }
            Unit
        }.onSuccess {
            cache = creds
        }.onFailure { e ->
            logger.e(TAG, "Failed to save credentials", e)
        }
    }

    private fun read(): StoredCreds? {
        cache?.let { return it }

        if (secretService.isAvailable) {
            secretService.lookup()?.let { text ->
                val creds = try {
                    json.decodeFromString<StoredCreds>(text)
                } catch (_: Throwable) {
                    StoredCreds(userId = "", password = text)
                }
                cache = creds
                return creds
            }
        }

        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            if (bytes.size <= GCM_IV_LENGTH) return null

            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(encrypted)

            json.decodeFromString<StoredCreds>(decrypted.toString(Charsets.UTF_8)).also { cache = it }
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
        if (secretService.isAvailable) {
            secretService.clear()
        }
        runCatching { file.delete() }
    }
}
