package com.vinnovateit.latch.desktop.platform.linux

import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxCredentialStoreTest {
    private lateinit var directory: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("latch-linux-creds-").toFile()
        file = File(directory, "credentials.bin")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a successful secret service write reports success and leaves no fallback blob`() {
        val secretService = FakeSecretService(reachable = true, storeSucceeds = true)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isSuccess)
        assertFalse(file.exists(), "a successful keyring write must not also leave a fallback blob")
    }

    @Test
    fun `an existing fallback blob is removed only after the secret service write succeeds`() {
        val secretService = FakeSecretService(reachable = false)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)
        store.save("22BCE0001", "secret").let { assertTrue(it.isSuccess) }
        assertTrue(file.exists(), "the fallback must have been written while Secret Service was unavailable")

        secretService.reachable = true
        secretService.storeSucceeds = true
        val result = store.save("22BCE0001", "new-secret")

        assertTrue(result.isSuccess)
        assertFalse(file.exists(), "the stale fallback should be dropped once the keyring holds the new credentials")
    }

    @Test
    fun `secret service unavailable falls back to the encrypted file and it is readable`() {
        val secretService = FakeSecretService(reachable = false)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isSuccess)
        assertTrue(file.exists())
        val reopened = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false))
        assertEquals("22BCE0001", reopened.userId())
        assertEquals("secret", reopened.password())
    }

    @Test
    fun `an installed but unreachable secret service falls back to a file a new instance can read`() {
        val secretService = FakeSecretService(reachable = false, storeSucceeds = false)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isSuccess)
        assertTrue(file.exists(), "with no provider answering, the fallback is the only store")
        val reopened = LinuxCredentialStore(file, NoOpLogger, secretService)
        assertEquals("secret", reopened.password())
    }

    @Test
    fun `a rejected keyring write fails and is not shadowed by the stale keyring entry`() {
        val secretService = FakeSecretService(reachable = true, storeSucceeds = false)
        secretService.storedPayload = """{"userId":"22BCE0001","password":"old-secret"}"""
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.save("22BCE0001", "new-secret")

        assertTrue(result.isFailure, "a fallback here would be shadowed by the old keyring entry, so save must fail")
        assertFalse(file.exists(), "no fallback may be written behind a reachable keyring")
        assertEquals("old-secret", store.password(), "a failed save must not change this instance's view")
        assertEquals("old-secret", LinuxCredentialStore(file, NoOpLogger, secretService).password())
    }

    @Test
    fun `a rejected keyring write leaves an existing fallback credential usable`() {
        LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false)).save("22BCE0001", "old-secret")
        val original = file.readBytes()
        val secretService = FakeSecretService(reachable = true, storeSucceeds = false)

        val result = LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "new-secret")

        assertTrue(result.isFailure)
        assertTrue(original.contentEquals(file.readBytes()), "the previous fallback must be untouched")
        assertEquals("old-secret", LinuxCredentialStore(file, NoOpLogger, secretService).password())
    }

    @Test
    fun `a salt that cannot be persisted fails the save and leaves no blob behind`() {
        val writer = SecureFileWriter(ownerOnly = true, move = { source, target ->
            if (target.fileName.toString() == ".creds_salt") throw IOException("simulated salt write failure")
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        })
        val store = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false), writer)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isFailure, "a blob encrypted with an unpersisted salt could never be decrypted again")
        assertFalse(file.exists())
        assertFalse(File(directory, ".creds_salt").exists())
        assertFalse(store.exists())
        assertEquals(emptyList(), leftoverTempFiles())
    }

    @Test
    fun `a failed fallback replacement keeps the previous credential file intact`() {
        LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false)).save("22BCE0001", "old-secret")
        val original = file.readBytes()
        val writer = SecureFileWriter(ownerOnly = true, move = { _, _ -> throw IOException("simulated rename failure") })
        val store = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false), writer)

        val result = store.save("22BCE0001", "new-secret")

        assertTrue(result.isFailure)
        assertTrue(original.contentEquals(file.readBytes()))
        assertEquals("old-secret", store.password(), "the cache must not claim the unsaved credentials")
        assertEquals("old-secret", LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false)).password())
        assertEquals(emptyList(), leftoverTempFiles())
    }

    @Test
    fun `a temp file that cannot be restricted to its owner fails the save before any secret is written`() {
        val writer = SecureFileWriter(ownerOnly = true, createTemp = { dir, prefix ->
            Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--")))
        })
        val store = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false), writer)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isFailure)
        assertFalse(file.exists())
        assertEquals(emptyList(), leftoverTempFiles())
    }

    @Test
    fun `saved fallback and salt files are owner-only`() {
        LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false)).save("22BCE0001", "secret")

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath())))
        val salt = File(directory, ".creds_salt").toPath()
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(salt)))
    }

    @Test
    fun `a fallback blob written in the existing on-disk format is still readable`() {
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        File(directory, ".creds_salt").writeBytes(salt)
        file.writeBytes(legacyEncrypt("""{"userId":"22BCE0001","password":"legacy-secret"}""", salt))

        val store = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false))

        assertEquals("22BCE0001", store.userId())
        assertEquals("legacy-secret", store.password())
    }

    @Test
    fun `saving over an existing fallback reuses its salt`() {
        LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false)).save("22BCE0001", "old-secret")
        val salt = File(directory, ".creds_salt").readBytes()

        LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false)).save("22BCE0001", "new-secret")

        assertTrue(salt.contentEquals(File(directory, ".creds_salt").readBytes()))
        assertEquals("new-secret", LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false)).password())
    }

    @Test
    fun `an unreachable secret service and an unwritable fallback location report failure`() {
        val readOnlyDir = File(directory, "readonly").apply { mkdirs() }
        val guardedFile = File(readOnlyDir, "credentials.bin")
        readOnlyDir.setWritable(false)
        try {
            val secretService = FakeSecretService(reachable = false, storeSucceeds = false)
            val store = LinuxCredentialStore(guardedFile, NoOpLogger, secretService)

            val result = store.save("22BCE0001", "secret")

            assertTrue(result.isFailure, "both the keyring and the fallback failed, so save must report failure")
        } finally {
            readOnlyDir.setWritable(true)
        }
    }

    @Test
    fun `reading prefers the secret service over the fallback file`() {
        val secretService = FakeSecretService(reachable = true, storeSucceeds = true)
        secretService.storedPayload = """{"userId":"22BCE0001","password":"from-keyring"}"""
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        assertEquals("22BCE0001", store.userId())
        assertEquals("from-keyring", store.password())
    }

    @Test
    fun `nothing stored anywhere means credentials do not exist`() {
        val store = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(reachable = false))

        assertFalse(store.exists())
        assertNull(store.userId())
    }

    @Test
    fun `clear removes both the keyring entry and the fallback file`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "old-secret")
        secretService.reachable = true
        secretService.storedPayload = """{"userId":"22BCE0001","password":"secret"}"""
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.clear()

        assertTrue(result.isSuccess)
        assertNull(secretService.storedPayload)
        assertFalse(file.exists())
        assertFalse(LinuxCredentialStore(file, NoOpLogger, secretService).exists())
    }

    @Test
    fun `clear fails when the keyring entry survives, but still removes the fallback file`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "old-secret")
        secretService.reachable = true
        secretService.clearSucceeds = false
        secretService.storedPayload = """{"userId":"22BCE0001","password":"kr-pass-7f3a"}"""
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.clear()

        assertTrue(result.isFailure, "a surviving keyring entry must not be reported as cleared")
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertFalse("kr-pass-7f3a" in message || "22BCE0001" in message, "the failure must not carry credential values")
        assertFalse(file.exists(), "the removal that could happen still happens")
        assertTrue(LinuxCredentialStore(file, NoOpLogger, secretService).exists())
    }

    @Test
    fun `clear with an unreachable keyring removes the fallback file and succeeds`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "secret")

        val result = LinuxCredentialStore(file, NoOpLogger, secretService).clear()

        assertTrue(result.isSuccess)
        assertEquals(0, secretService.clearCalls, "an unreachable keyring is not asked to clear")
        assertFalse(file.exists())
    }

    @Test
    fun `clear fails when the fallback file cannot be removed`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "secret")
        directory.setWritable(false)
        try {
            val result = LinuxCredentialStore(file, NoOpLogger, secretService).clear()

            assertTrue(result.isFailure, "a credential file that is still on disk must not be reported as cleared")
            assertTrue(file.exists())
        } finally {
            directory.setWritable(true)
        }
    }

    @Test
    fun `a missing salt keeps the fallback blob, which reads again once the salt is back`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "secret")
        val salt = File(directory, ".creds_salt")
        val savedSalt = salt.readBytes()
        assertTrue(salt.delete())

        assertNull(LinuxCredentialStore(file, NoOpLogger, secretService).password())
        assertTrue(file.exists(), "a blob that is only missing its salt must not be destroyed")

        salt.writeBytes(savedSalt)
        assertEquals("secret", LinuxCredentialStore(file, NoOpLogger, secretService).password())
    }

    @Test
    fun `an unreadable salt keeps the fallback blob, which reads again once the salt is readable`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "secret")
        val salt = File(directory, ".creds_salt")
        salt.setReadable(false, false)
        try {
            if (salt.canRead()) return // running as root: permissions cannot make it unreadable

            assertNull(LinuxCredentialStore(file, NoOpLogger, secretService).password())
            assertTrue(file.exists(), "a blob whose salt is temporarily unreadable must not be destroyed")
        } finally {
            salt.setReadable(true, true)
        }
        assertEquals("secret", LinuxCredentialStore(file, NoOpLogger, secretService).password())
    }

    @Test
    fun `an unreadable fallback file is kept rather than deleted`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "secret")
        file.setReadable(false, false)
        try {
            if (file.canRead()) return // running as root

            assertNull(LinuxCredentialStore(file, NoOpLogger, secretService).password())
            assertTrue(file.exists())
        } finally {
            file.setReadable(true, true)
        }
        assertEquals("secret", LinuxCredentialStore(file, NoOpLogger, secretService).password())
    }

    @Test
    fun `a blob that fails authentication with its own salt is still cleared`() {
        val secretService = FakeSecretService(reachable = false)
        LinuxCredentialStore(file, NoOpLogger, secretService).save("22BCE0001", "secret")
        val tampered = file.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        file.writeBytes(tampered)

        assertNull(LinuxCredentialStore(file, NoOpLogger, secretService).password())
        assertFalse(file.exists(), "a blob that is conclusively invalid for this machine and salt is removed")
    }

    private fun leftoverTempFiles(): List<String> =
        directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }

    /** Encrypts exactly as the fallback format has been written since the salt file was introduced. */
    private fun legacyEncrypt(json: String, salt: ByteArray): ByteArray {
        val machineId = File("/etc/machine-id").takeIf { it.exists() }?.readText()?.trim()
            ?: File("/var/lib/dbus/machine-id").takeIf { it.exists() }?.readText()?.trim()
            ?: (System.getProperty("user.name") + "LatchLinuxCredsSalt")
        val user = System.getProperty("user.name").orEmpty()
        val key = MessageDigest.getInstance("SHA-256").digest("$machineId:$user:".toByteArray(Charsets.UTF_8) + salt)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
    }
}

private class FakeSecretService(
    var reachable: Boolean,
    var storeSucceeds: Boolean = false,
) : SecretServiceBackend {
    var storedPayload: String? = null
    var clearSucceeds = true
    var storeCalls = 0
    var lookupCalls = 0
    var clearCalls = 0

    override fun store(payload: String): Boolean {
        storeCalls++
        if (!storeSucceeds) return false
        storedPayload = payload
        return true
    }

    override fun isReachable(): Boolean = reachable

    override fun lookup(): String? {
        lookupCalls++
        return storedPayload
    }

    override fun clear(): Boolean {
        clearCalls++
        if (!clearSucceeds) return false
        storedPayload = null
        return true
    }
}
