package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.Logger
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MacKeychainCredentialStore] against an in-memory [KeychainItem], so no
 * test here touches a real Keychain. The real Security.framework item is
 * exercised by [MacNativeIntegrationTest] on the macOS CI runner.
 */
class MacKeychainCredentialStoreTest {

    private class FakeKeychain : KeychainItem {
        var secret: ByteArray? = null
        var readFailure: Int? = null
        var writeStatus = ERR_SEC_SUCCESS
        var deleteStatus = ERR_SEC_SUCCESS
        var deleteLeavesItem = false
        var writes = 0

        override fun read(): KeychainItem.Read {
            readFailure?.let { return KeychainItem.Read.Failed(it) }
            return secret?.let { KeychainItem.Read.Found(it.copyOf()) } ?: KeychainItem.Read.Missing
        }

        override fun write(secret: ByteArray): Int {
            writes++
            if (writeStatus == ERR_SEC_SUCCESS) this.secret = secret.copyOf()
            return writeStatus
        }

        override fun delete(): Int {
            if (deleteStatus == ERR_SEC_SUCCESS && !deleteLeavesItem) secret = null
            return deleteStatus
        }
    }

    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val logger = object : Logger {
        override fun d(tag: String, message: String) { logs += message }
        override fun w(tag: String, message: String) { logs += message }
        override fun e(tag: String, message: String, throwable: Throwable?) { logs += message + (throwable?.message ?: "") }
    }

    private val keychain = FakeKeychain()
    private fun store() = MacKeychainCredentialStore(keychain, logger)

    @Test
    fun `saved credentials load back, in a fresh store as well`() {
        assertTrue(store().save("00AAA0000", "not-a-real-password").isSuccess)

        val reloaded = store()
        assertEquals("00AAA0000", reloaded.userId())
        assertEquals("not-a-real-password", reloaded.password())
        assertTrue(reloaded.exists())
    }

    @Test
    fun `a second save replaces the item rather than adding another`() {
        val store = store()
        store.save("00AAA0000", "first")
        store.save("00AAA0000", "second")

        assertEquals(2, keychain.writes)
        assertEquals("second", store().password())
    }

    @Test
    fun `nothing saved means no credentials`() {
        assertNull(store().userId())
        assertFalse(store().exists())
    }

    @Test
    fun `a refused write fails the save and keeps the previous credentials`() {
        store().save("00AAA0000", "kept")
        keychain.writeStatus = -25308

        val result = store().save("00AAA0000", "rejected")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("locked"))
        assertEquals("kept", store().password())
    }

    @Test
    fun `an unreadable Keychain reads as no credentials and says why`() {
        store().save("00AAA0000", "secret-value")
        keychain.readFailure = -128

        assertNull(store().password())
        assertTrue(logs.any { it.contains("cancelled") }, logs.toString())
    }

    @Test
    fun `clear removes the item and is confirmed by reading it back`() {
        val store = store()
        store.save("00AAA0000", "not-a-real-password")

        assertTrue(store.clear().isSuccess)
        assertNull(keychain.secret)
        assertFalse(store.exists())
        assertFalse(store().exists())
    }

    @Test
    fun `clear fails if the Keychain refuses or the item survives`() {
        store().save("00AAA0000", "not-a-real-password")
        keychain.deleteStatus = -25308
        assertTrue(store().clear().isFailure)

        keychain.deleteStatus = ERR_SEC_SUCCESS
        keychain.deleteLeavesItem = true
        assertTrue(store().clear().isFailure)
    }

    @Test
    fun `clear on an empty Keychain succeeds`() {
        assertTrue(store().clear().isSuccess)
    }

    @Test
    fun `a native Keychain that cannot load fails honestly instead of crashing`() {
        val unavailable = MacKeychainCredentialStore(LazyKeychainItem { error("no Security.framework here") }, logger)

        assertTrue(unavailable.save("00AAA0000", "not-a-real-password").isFailure)
        assertNull(unavailable.password())
        assertTrue(unavailable.clear().isFailure)
    }

    @Test
    fun `no credential value ever reaches the log`() {
        val store = store()
        store.save("00AAA0000", "s3cr3t-pa55")
        keychain.writeStatus = -25308
        store.save("00AAA0000", "s3cr3t-pa55")
        keychain.readFailure = -128
        store().password()
        keychain.deleteStatus = -25308
        store.clear()

        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.contains("s3cr3t-pa55") || it.contains("00AAA0000") }, logs.toString())
    }
}
