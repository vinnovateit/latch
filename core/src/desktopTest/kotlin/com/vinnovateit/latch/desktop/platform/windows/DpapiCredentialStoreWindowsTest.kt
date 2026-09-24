package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.desktop.AppPaths
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real Windows DPAPI qualification. [DpapiCredentialStoreTest] drives the
 * store through a fake backend; this one uses the production store -- the
 * public constructor, JNA's Crypt32 -- and the production credential path
 * under an isolated `latch.dataDir`. A pass means DPAPI protected the bytes
 * on disk and both a fresh store and a separate JVM unprotected them.
 *
 * It does nothing off Windows. The Windows CI job selects it by name, so if
 * it ever stops matching, Gradle fails that job instead of passing vacuously.
 *
 * The credentials are random per run and are only compared, never printed:
 * assertion messages carry no values, and the child process reports a digest.
 */
class DpapiCredentialStoreWindowsTest {
    private val isWindows = System.getProperty("os.name").startsWith("Windows")
    private val userId = "qual-" + UUID.randomUUID()
    private val password = "pw-" + UUID.randomUUID()

    private lateinit var dataDir: File
    private var previousDataDir: String? = null

    @BeforeTest
    fun setUp() {
        if (!isWindows) return
        dataDir = createTempDirectory("latch-dpapi-qual-").toFile()
        previousDataDir = System.getProperty(DATA_DIR_PROPERTY)
        System.setProperty(DATA_DIR_PROPERTY, dataDir.path)
    }

    @AfterTest
    fun tearDown() {
        if (!isWindows) return
        previousDataDir?.let { System.setProperty(DATA_DIR_PROPERTY, it) } ?: System.clearProperty(DATA_DIR_PROPERTY)
        dataDir.deleteRecursively()
    }

    @Test
    fun `a DPAPI save is recovered by a fresh store and a separate process, then cleared`() {
        if (!isWindows) return
        val file = AppPaths.credentialsFile
        assertEquals(dataDir.canonicalFile, file.canonicalFile.parentFile, "the store must write under the isolated data directory")

        assertTrue(DpapiCredentialStore(file, NoOpLogger).save(userId, password).isSuccess, "the DPAPI save failed")

        val stored = file.readBytes()
        for (value in listOf(userId, password)) {
            assertFalse(stored.containsBytes(value.toByteArray(Charsets.UTF_8)), "the stored blob contains a plaintext credential (UTF-8)")
            assertFalse(stored.containsBytes(value.toByteArray(Charsets.UTF_16LE)), "the stored blob contains a plaintext credential (UTF-16LE)")
        }

        val fresh = DpapiCredentialStore(file, NoOpLogger)
        assertTrue(fresh.userId() == userId && fresh.password() == password, "a fresh store did not recover the credentials")
        assertEquals(fingerprint(userId, password), readBackInSeparateJvm(), "a separate process did not recover the credentials")

        fresh.clear()

        assertFalse(file.exists(), "clear left the protected blob on disk")
        assertFalse(DpapiCredentialStore(file, NoOpLogger).exists(), "a fresh store still sees credentials after clear")
    }

    @Test
    fun `a second DPAPI save replaces the protected file for a fresh store`() {
        if (!isWindows) return
        val file = AppPaths.credentialsFile
        assertTrue(DpapiCredentialStore(file, NoOpLogger).save(userId, "old-$password").isSuccess, "the first save failed")

        assertTrue(DpapiCredentialStore(file, NoOpLogger).save(userId, password).isSuccess, "the replacing save failed")

        assertTrue(DpapiCredentialStore(file, NoOpLogger).password() == password, "a fresh store did not see the replacement")
        assertEquals(emptyList(), dataDir.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") })
    }

    /**
     * Reads the credentials back in a new JVM on the same classpath, which
     * shares nothing with this one but the file on disk and the Windows user.
     * The classpath goes through an argument file because the test runtime
     * classpath can exceed the Windows command-line limit.
     */
    private fun readBackInSeparateJvm(): String {
        val java = File(System.getProperty("java.home"), "bin/java.exe").path
        val classpath = System.getProperty("java.class.path").replace('\\', '/')
        val argFile = File(dataDir, "readback.args").apply { writeText("-cp \"$classpath\"\n") }
        val process = ProcessBuilder(
            java,
            "-D$DATA_DIR_PROPERTY=${dataDir.path}",
            "@${argFile.path}",
            DpapiReadback::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the readback process did not finish")
        assertEquals(0, process.exitValue(), "the readback process failed")
        return output.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty).orEmpty()
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    private companion object {
        const val DATA_DIR_PROPERTY = "latch.dataDir"
    }
}

/** Child-process entry point for [DpapiCredentialStoreWindowsTest]; prints only a digest. */
object DpapiReadback {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = DpapiCredentialStore(AppPaths.credentialsFile, NoOpLogger)
        println(fingerprint(store.userId(), store.password()))
    }
}

private fun fingerprint(userId: String?, password: String?): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$userId\u0000$password".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
