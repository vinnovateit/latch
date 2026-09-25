package com.vinnovateit.latch.desktop.platform.linux

import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

@Serializable
private data class StoredCreds(val userId: String, val password: String)

/**
 * Seam over the `secret-tool` subprocess so tests can exercise every branch of
 * [LinuxCredentialStore] without a real desktop keyring. [store] and [lookup]
 * report failure as `false`/`null` rather than throwing, mirroring what the
 * real `secret-tool` subprocess does (a non-zero exit, not an exception).
 */
internal interface SecretServiceBackend {
    /**
     * Whether a Secret Service provider actually answers on the session bus.
     * False both when `secret-tool` is not installed and when it is installed
     * but has no provider or session to talk to -- the two cases where the
     * encrypted fallback is the right store.
     */
    fun isReachable(): Boolean
    fun store(payload: String): Boolean
    fun lookup(): String?

    /** Removes Latch's entry; true only when no Latch entry remains afterwards. */
    fun clear(): Boolean
}

/**
 * Drives `secret-tool`. Every call is bounded by [timeoutMillis]: output is
 * drained on separate threads so a process that never exits cannot block the
 * caller, and a process still running at the deadline is killed and treated
 * as a failure. [service] is the `service` attribute value Latch stores under.
 */
internal class ProcessSecretServiceBackend(
    private val executable: String = "secret-tool",
    private val service: String = "Latch",
    private val timeoutMillis: Long = 3_000,
) : SecretServiceBackend {

    private sealed interface Outcome {
        data object NotRun : Outcome
        data object TimedOut : Outcome
        class Exited(val code: Int, val stdout: String, val stderr: String) : Outcome
    }

    /**
     * Looks up an attribute Latch never stores. A reachable service answers
     * "not found" with exit 1 and nothing on stderr; an unreachable one (no
     * session bus, no provider) exits non-zero with an error message, and a
     * missing tool never starts. Using a never-stored attribute keeps the
     * probe from reading, unlocking, or changing the real credential entry.
     *
     * `secret-tool --help` is not a usable signal: it exits 2 whether or not
     * a provider exists, which is what kept Secret Service from ever being
     * selected before.
     */
    override fun isReachable(): Boolean {
        val outcome = run(listOf("lookup", "service", service, "probe", "reachability"))
        return outcome is Outcome.Exited && (outcome.code == 0 || (outcome.code == 1 && outcome.stderr.isEmpty()))
    }

    override fun store(payload: String): Boolean {
        val outcome = run(listOf("store", "--label=Latch Credentials", "service", service), input = payload)
        return outcome is Outcome.Exited && outcome.code == 0
    }

    override fun lookup(): String? {
        val outcome = run(listOf("lookup", "service", service))
        return (outcome as? Outcome.Exited)?.takeIf { it.code == 0 }?.stdout?.takeIf { it.isNotEmpty() }
    }

    /**
     * `secret-tool clear` exits 1 with nothing on stderr both when no entry
     * matched and when a locked keyring refused the delete and the entry
     * survives, so its exit status cannot confirm removal. `search` still
     * lists a locked entry, without prompting, so an empty search afterwards
     * is the confirmation.
     */
    override fun clear(): Boolean {
        run(listOf("clear", "service", service))
        val remaining = run(listOf("search", "service", service))
        return remaining is Outcome.Exited &&
            remaining.code == 0 &&
            remaining.stdout.lineSequence().none { it.startsWith("[/") }
    }

    private fun run(arguments: List<String>, input: String? = null): Outcome {
        val process = try {
            ProcessBuilder(listOf(executable) + arguments).start()
        } catch (_: IOException) {
            return Outcome.NotRun
        }
        var stdout = ""
        var stderr = ""
        val readers = listOf(
            thread(isDaemon = true) { stdout = drain(process.inputStream) },
            thread(isDaemon = true) { stderr = drain(process.errorStream) },
        )
        try {
            process.outputStream.bufferedWriter().use { writer -> input?.let(writer::write) }
        } catch (_: IOException) {
            // The process exited without reading its input; its exit code says why.
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return Outcome.TimedOut
        }
        readers.forEach { it.join(timeoutMillis) }
        return Outcome.Exited(process.exitValue(), stdout.trim(), stderr.trim())
    }

    private fun drain(stream: InputStream): String =
        try {
            stream.bufferedReader().use { it.readText() }
        } catch (_: IOException) {
            ""
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
    private val writer: SecureFileWriter = SecureFileWriter(ownerOnly = true),
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

    private val saltFile: File get() = File(file.absoluteFile.parentFile, ".creds_salt")

    /** The persisted salt, or null when none has been written yet. Throws if present but unreadable. */
    private fun persistedSalt(): ByteArray? {
        if (!saltFile.exists() || saltFile.length() < 16) return null
        return saltFile.readBytes()
    }

    /**
     * A save must never encrypt with a salt that is not durably on disk: that
     * would report success for a blob no later process can decrypt. So an
     * unreadable salt fails the save, and a newly generated one is used only
     * after it has been persisted and read back unchanged.
     */
    private fun saltForSave(): ByteArray {
        persistedSalt()?.let { return it }
        val randomSalt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        writer.replace(saltFile, randomSalt)
        check(persistedSalt()?.contentEquals(randomSalt) == true) { "Credential salt did not persist" }
        return randomSalt
    }

    private fun deriveKey(salt: ByteArray): SecretKey {
        val machineId = runCatching {
            File("/etc/machine-id").takeIf { it.exists() }?.readText()?.trim()
                ?: File("/var/lib/dbus/machine-id").takeIf { it.exists() }?.readText()?.trim()
        }.getOrNull() ?: (System.getProperty("user.name") + SALT)

        val user = System.getProperty("user.name").orEmpty()
        val rawPrefix = "$machineId:$user:".toByteArray(Charsets.UTF_8)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(rawPrefix + salt)
        return SecretKeySpec(sha256, "AES")
    }

    /**
     * Where a save lands depends on why Secret Service did or did not take it:
     *
     * - Secret Service stored it: that entry is authoritative, and the fallback
     *   blob is removed only *after* the write is confirmed, so a mid-write
     *   crash never loses both copies at once.
     * - Secret Service is genuinely unavailable (tool missing, or no provider
     *   answering): the encrypted fallback is written atomically, and `save`
     *   succeeds only once it is durably on disk.
     * - Secret Service answers but the write fails (locked keyring, dismissed
     *   prompt, timeout): `save` fails and the fallback is not touched. Reads
     *   prefer Secret Service, so a fallback written here would be shadowed
     *   by any older keyring entry the next time the keyring is usable.
     *
     * A timed-out write is reported as a failure even though the killed
     * `secret-tool` may already have committed it; either way the keyring
     * then holds a complete credential, never a partial one.
     */
    override fun save(userId: String, password: String): Result<Unit> {
        val creds = StoredCreds(userId, password)
        val plainJson = json.encodeToString(creds)

        if (secretService.isReachable()) {
            if (secretService.store(plainJson)) {
                cache = creds
                runCatching { file.delete() }
                return Result.success(Unit)
            }
            logger.e(TAG, "Secret Service rejected the credential write; keeping existing credentials")
            return Result.failure(IllegalStateException("Secret Service rejected the credential write."))
        }

        return runCatching {
            val plain = plainJson.toByteArray(Charsets.UTF_8)
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(saltForSave()), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encrypted = cipher.doFinal(plain)

            writer.replace(file, iv + encrypted)
        }.onSuccess {
            cache = creds
        }.onFailure { e ->
            logger.e(TAG, "Failed to save credentials", e)
        }
    }

    private fun read(): StoredCreds? {
        cache?.let { return it }

        secretService.lookup()?.let { text ->
            val creds = try {
                json.decodeFromString<StoredCreds>(text)
            } catch (_: Throwable) {
                StoredCreds(userId = "", password = text)
            }
            cache = creds
            return creds
        }

        if (!file.exists()) return null
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            logger.e(TAG, "Credential file could not be read; keeping it", e)
            return null
        }
        if (bytes.size <= GCM_IV_LENGTH) return null

        // A missing or unreadable salt makes the blob undecryptable for now,
        // not corrupt: restoring the salt file (or its permissions) makes it
        // readable again. So the blob is kept and reads report no credentials
        // until then, rather than destroying what may still be recoverable.
        // An unreadable salt keeps the historical tolerance of trying the
        // constant salt, which is what an old save used in the same situation.
        val salt = runCatching { persistedSalt() }
        val saltBytes = salt.getOrNull()
        if (saltBytes == null) {
            if (salt.isFailure) {
                runCatching { decrypt(bytes, SALT.toByteArray(Charsets.UTF_8)) }.getOrNull()?.let {
                    cache = it
                    return it
                }
            }
            logger.e(TAG, "Credential salt is missing or unreadable; keeping the encrypted credentials")
            return null
        }

        return try {
            decrypt(bytes, saltBytes).also { cache = it }
        } catch (e: Throwable) {
            logger.e(TAG, "Credential blob unreadable; clearing it", e)
            runCatching { file.delete() }
            null
        }
    }

    private fun decrypt(bytes: ByteArray, salt: ByteArray): StoredCreds {
        val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decrypted = cipher.doFinal(encrypted)
        return json.decodeFromString<StoredCreds>(decrypted.toString(Charsets.UTF_8))
    }

    override fun userId(): String? = read()?.userId

    override fun password(): String? = read()?.password

    override fun exists(): Boolean = read() != null

    /**
     * Removes every copy of the credentials Latch can reach, and fails if any
     * of them remains. Both removals are attempted even when one fails.
     *
     * An unreachable Secret Service (tool missing, no provider answering) is
     * skipped: an entry there can be neither read nor removed until it is
     * reachable again. The salt is left in place -- it is not a credential and
     * decrypts nothing without the blob, and the next fallback save reuses it.
     */
    override fun clear(): Result<Unit> {
        cache = null
        val keyringCleared = !secretService.isReachable() || secretService.clear()
        val fileRemoved = runCatching { Files.deleteIfExists(file.toPath()) }.isSuccess && !file.exists()
        val failure = when {
            !keyringCleared -> "Secret Service still holds the saved credentials."
            !fileRemoved -> "The encrypted credential file could not be removed."
            else -> return Result.success(Unit)
        }
        logger.e(TAG, "Failed to clear credentials: $failure")
        return Result.failure(IllegalStateException(failure))
    }
}
