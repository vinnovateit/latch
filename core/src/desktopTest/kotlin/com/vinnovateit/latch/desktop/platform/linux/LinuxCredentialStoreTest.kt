package com.vinnovateit.latch.desktop.platform.linux

import com.vinnovateit.latch.core.platform.NoOpLogger
import java.io.File
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
        val secretService = FakeSecretService(available = true, storeSucceeds = true)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isSuccess)
        assertFalse(file.exists(), "a successful keyring write must not also leave a fallback blob")
    }

    @Test
    fun `an existing fallback blob is removed only after the secret service write succeeds`() {
        val secretService = FakeSecretService(available = false)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)
        store.save("22BCE0001", "secret").let { assertTrue(it.isSuccess) }
        assertTrue(file.exists(), "the fallback must have been written while Secret Service was unavailable")

        secretService.available = true
        secretService.storeSucceeds = true
        val result = store.save("22BCE0001", "new-secret")

        assertTrue(result.isSuccess)
        assertFalse(file.exists(), "the stale fallback should be dropped once the keyring holds the new credentials")
    }

    @Test
    fun `secret service unavailable falls back to the encrypted file and it is readable`() {
        val secretService = FakeSecretService(available = false)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isSuccess)
        assertTrue(file.exists())
        val reopened = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(available = false))
        assertEquals("22BCE0001", reopened.userId())
        assertEquals("secret", reopened.password())
    }

    @Test
    fun `secret service write failure falls back to the encrypted file`() {
        val secretService = FakeSecretService(available = true, storeSucceeds = false)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isSuccess)
        assertTrue(file.exists(), "the fallback must be written when the keyring write fails")
    }

    @Test
    fun `secret service failure and an unwritable fallback location report failure`() {
        val readOnlyDir = File(directory, "readonly").apply { mkdirs() }
        val guardedFile = File(readOnlyDir, "credentials.bin")
        readOnlyDir.setWritable(false)
        try {
            val secretService = FakeSecretService(available = true, storeSucceeds = false)
            val store = LinuxCredentialStore(guardedFile, NoOpLogger, secretService)

            val result = store.save("22BCE0001", "secret")

            assertTrue(result.isFailure, "both the keyring and the fallback failed, so save must report failure")
        } finally {
            readOnlyDir.setWritable(true)
        }
    }

    @Test
    fun `reading prefers the secret service over the fallback file`() {
        val secretService = FakeSecretService(available = true, storeSucceeds = true)
        secretService.storedPayload = """{"userId":"22BCE0001","password":"from-keyring"}"""
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)

        assertEquals("22BCE0001", store.userId())
        assertEquals("from-keyring", store.password())
    }

    @Test
    fun `nothing stored anywhere means credentials do not exist`() {
        val store = LinuxCredentialStore(file, NoOpLogger, FakeSecretService(available = false))

        assertFalse(store.exists())
        assertNull(store.userId())
    }

    @Test
    fun `clear removes both the keyring entry and the fallback file`() {
        val secretService = FakeSecretService(available = true, storeSucceeds = true)
        val store = LinuxCredentialStore(file, NoOpLogger, secretService)
        store.save("22BCE0001", "secret")

        store.clear()

        assertNull(secretService.storedPayload)
        assertFalse(store.exists())
    }
}

private class FakeSecretService(
    var available: Boolean,
    var storeSucceeds: Boolean = false,
) : SecretServiceBackend {
    var storedPayload: String? = null
    var storeCalls = 0
    var lookupCalls = 0

    override val isAvailable: Boolean get() = available

    override fun store(payload: String): Boolean {
        storeCalls++
        if (!storeSucceeds) return false
        storedPayload = payload
        return true
    }

    override fun lookup(): String? {
        lookupCalls++
        return storedPayload
    }

    override fun clear() {
        storedPayload = null
    }
}
