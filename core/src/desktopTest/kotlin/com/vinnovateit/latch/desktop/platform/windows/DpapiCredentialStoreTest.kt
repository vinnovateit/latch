package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.NoOpLogger
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises failure-propagation through [DpapiCredentialStore] with a fake
 * [DpapiBackend] rather than the real Windows Crypt32 API, which does not
 * exist on the Linux CI runners this module is built and tested on. This
 * proves the save/read contract; it does not exercise the real DPAPI round
 * trip, which only a Windows machine can do.
 */
class DpapiCredentialStoreTest {
    private lateinit var directory: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("latch-dpapi-creds-").toFile()
        file = File(directory, "credentials.bin")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `successful persistence reports success and is readable back`() {
        val store = DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend())

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isSuccess)
        assertTrue(file.exists())
        val reopened = DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend())
        assertEquals("22BCE0001", reopened.userId())
        assertEquals("secret", reopened.password())
    }

    @Test
    fun `an underlying encryption failure reports failure and writes nothing`() {
        val store = DpapiCredentialStore(file, NoOpLogger, FailingDpapiBackend())

        val result = store.save("22BCE0001", "secret")

        assertTrue(result.isFailure)
        assertFalse(file.exists())
    }

    @Test
    fun `a corrupt blob is cleared rather than surfacing a decryption exception`() {
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        val store = DpapiCredentialStore(file, NoOpLogger, FailingDpapiBackend())

        assertEquals(null, store.userId())
        assertFalse(file.exists(), "an unreadable blob should be cleared, not left behind")
    }
}

private class PassthroughDpapiBackend : DpapiBackend {
    override fun protect(plain: ByteArray): ByteArray = plain
    override fun unprotect(encrypted: ByteArray): ByteArray = encrypted
}

private class FailingDpapiBackend : DpapiBackend {
    override fun protect(plain: ByteArray): ByteArray = error("DPAPI is unavailable")
    override fun unprotect(encrypted: ByteArray): ByteArray = error("DPAPI is unavailable")
}
