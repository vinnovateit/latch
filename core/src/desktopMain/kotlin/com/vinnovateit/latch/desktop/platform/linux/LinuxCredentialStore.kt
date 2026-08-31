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
 * Linux credential storage leveraging Secret Service API (`secret-tool`) when available,
 * with fallback to AES-256 GCM encrypted storage with strict POSIX 0600 file permissions.
 */
class LinuxCredentialStore(
    private val file: File,
    private val logger: Logger,
) : CredentialStore {

    private companion object {
        const val TAG = "LinuxCredentialStore"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
        const val SALT = "LatchLinuxCredsSalt"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: StoredCreds? = null

    private val secretToolAvailable: Boolean by lazy {
        try {
            val process = ProcessBuilder("secret-tool", "--help").start()
            process.waitFor(2, TimeUnit.SECONDS)
            process.exitValue() == 0
        } catch (e: Throwable) {
            false
        }
    }

    private fun deriveKey(): SecretKey {
        val machineId = runCatching {
            File("/etc/machine-id").takeIf { it.exists() }?.readText()?.trim()
                ?: File("/var/lib/dbus/machine-id").takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull() ?: (System.getProperty("user.name") + SALT)

        val user = System.getProperty("user.name").orEmpty()
        val rawKey = "$machineId:$user:$SALT"
        val sha256 = MessageDigest.getInstance("SHA-256").digest(rawKey.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(sha256, "AES")
    }

    override fun save(userId: String, password: String) {
        val creds = StoredCreds(userId, password)
        cache = creds
        try {
            val plainJson = json.encodeToString(creds)
            if (secretToolAvailable) {
                saveSecretTool(plainJson)
            }

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
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to save credentials", e)
        }
    }

    private fun read(): StoredCreds? {
        cache?.let { return it }

        if (secretToolAvailable) {
            readSecretTool()?.let {
                cache = it
                return it
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
        if (secretToolAvailable) {
            clearSecretTool()
        }
        runCatching { file.delete() }
    }

    // --- Secret Tool helpers ---

    private fun saveSecretTool(payload: String): Boolean = try {
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

    private fun readSecretTool(): StoredCreds? = try {
        val process = ProcessBuilder("secret-tool", "lookup", "service", "Latch")
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0 && text.isNotEmpty()) {
            try {
                json.decodeFromString<StoredCreds>(text)
            } catch (_: Throwable) {
                StoredCreds(userId = "", password = text)
            }
        } else null
    } catch (e: Throwable) {
        null
    }

    private fun clearSecretTool() {
        try {
            val process = ProcessBuilder("secret-tool", "clear", "service", "Latch").start()
            process.waitFor(2, TimeUnit.SECONDS)
        } catch (e: Throwable) {
            // Ignore
        }
    }
}
