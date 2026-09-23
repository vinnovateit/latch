package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
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
    fun `a failed replacement keeps the previous payload and the cached credentials`() {
        DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend()).save("22BCE0001", "old-secret")
        val original = file.readBytes()
        val failingWriter = SecureFileWriter(ownerOnly = false, move = { _, _ -> throw IOException("simulated rename failure") })
        val store = DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend(), failingWriter)
        assertEquals("old-secret", store.password())

        val result = store.save("22BCE0001", "new-secret")

        assertTrue(result.isFailure)
        assertTrue(original.contentEquals(file.readBytes()), "the previous protected payload must be untouched")
        assertEquals("old-secret", store.password(), "the cache may only move after the replacement succeeds")
        assertEquals("old-secret", DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend()).password())
        assertEquals(emptyList(), leftoverTempFiles())
    }

    @Test
    fun `a filesystem without atomic rename fails the save instead of replacing non-atomically`() {
        DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend()).save("22BCE0001", "old-secret")
        val original = file.readBytes()
        val writer = SecureFileWriter(ownerOnly = false, move = { source, target ->
            throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "simulated")
        })

        val result = DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend(), writer).save("22BCE0001", "new-secret")

        assertTrue(result.isFailure)
        assertTrue(original.contentEquals(file.readBytes()))
        assertEquals(emptyList(), leftoverTempFiles())
    }

    @Test
    fun `a successful save replaces the previous payload for a new instance`() {
        DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend()).save("22BCE0001", "old-secret")

        DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend()).save("22BCE0001", "new-secret")

        assertEquals("new-secret", DpapiCredentialStore(file, NoOpLogger, PassthroughDpapiBackend()).password())
        assertEquals(emptyList(), leftoverTempFiles())
    }

    private fun leftoverTempFiles(): List<String> =
        directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }

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
