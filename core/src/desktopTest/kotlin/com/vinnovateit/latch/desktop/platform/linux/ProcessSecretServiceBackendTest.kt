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

/**
 * Drives the real [ProcessSecretServiceBackend] against stand-in `secret-tool`
 * scripts, so the process handling itself (start failure, exit codes, stderr,
 * timeouts) is what decides between Secret Service and the fallback -- not a
 * fake backend. The scripts only reproduce the exit/stderr behaviour observed
 * from libsecret's real `secret-tool`; they never touch a real keyring.
 */
class ProcessSecretServiceBackendTest {
    private lateinit var directory: File
    private lateinit var file: File
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("latch-secret-tool-").toFile()
        file = File(directory, "credentials.bin")
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a missing secret-tool is unreachable and the fallback is used`() {
        if (isWindows) return
        val backend = ProcessSecretServiceBackend(executable = File(directory, "no-such-tool").path)

        assertFalse(backend.isReachable())
        assertTrue(LinuxCredentialStore(file, NoOpLogger, backend).save("22BCE0001", "test-pass").isSuccess)
        assertTrue(file.exists())
        assertEquals("test-pass", LinuxCredentialStore(file, NoOpLogger, backend).password())
    }

    @Test
    fun `an installed secret-tool with no provider is unreachable and the fallback is used`() {
        if (isWindows) return
        val backend = backend(
            """
            echo 'secret-tool: Cannot autolaunch D-Bus without X11 ${'$'}DISPLAY' >&2
            exit 1
            """,
        )

        assertFalse(backend.isReachable())
        assertTrue(LinuxCredentialStore(file, NoOpLogger, backend).save("22BCE0001", "test-pass").isSuccess)
        assertEquals("test-pass", LinuxCredentialStore(file, NoOpLogger, backend).password())
    }

    @Test
    fun `the reachability probe never addresses the real credential entry`() {
        if (isWindows) return
        val calls = File(directory, "calls.log")
        val backend = backend(
            """
            echo "${'$'}*" >> '${calls.path}'
            exit 1
            """,
        )

        assertTrue(backend.isReachable(), "exit 1 with an empty stderr is a reachable provider with no match")
        assertEquals(listOf("lookup service Latch probe reachability"), calls.readLines())
    }

    @Test
    fun `a reachable provider stores the credential and no fallback is written`() {
        if (isWindows) return
        val backend = backend(reachableKeyring())
        val store = LinuxCredentialStore(file, NoOpLogger, backend)

        val result = store.save("22BCE0001", "test-pass")

        assertTrue(result.isSuccess)
        assertFalse(file.exists(), "Secret Service is authoritative, so no fallback copy may exist")
        val reopened = LinuxCredentialStore(file, NoOpLogger, backend)
        assertEquals("22BCE0001", reopened.userId())
        assertEquals("test-pass", reopened.password())
    }

    @Test
    fun `a reachable provider that stores the credential removes an older fallback`() {
        if (isWindows) return
        LinuxCredentialStore(file, NoOpLogger, ProcessSecretServiceBackend(executable = File(directory, "absent").path))
            .save("22BCE0001", "old-pass")
        assertTrue(file.exists())

        val result = LinuxCredentialStore(file, NoOpLogger, backend(reachableKeyring())).save("22BCE0001", "new-pass")

        assertTrue(result.isSuccess)
        assertFalse(file.exists())
    }

    @Test
    fun `a reachable provider that refuses the write fails without a fallback substitute`() {
        if (isWindows) return
        val backend = backend(
            """
            case "${'$'}1" in
              store) cat > /dev/null; echo 'secret-tool: Cannot create an item in a locked collection' >&2; exit 1 ;;
              *) exit 1 ;;
            esac
            """,
        )

        val result = LinuxCredentialStore(file, NoOpLogger, backend).save("22BCE0001", "test-pass")

        assertTrue(result.isFailure)
        assertFalse(file.exists(), "a refused keyring write must not be papered over with a fallback")
    }

    @Test
    fun `a keyring write that hangs times out as an explicit failure`() {
        if (isWindows) return
        val backend = backend(
            """
            case "${'$'}1" in
              store) exec sleep 30 ;;
              *) exit 1 ;;
            esac
            """,
            timeoutMillis = 500,
        )

        val started = System.nanoTime()
        val result = LinuxCredentialStore(file, NoOpLogger, backend).save("22BCE0001", "test-pass")
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertTrue(result.isFailure, "a write that never confirmed must not be reported as saved")
        assertFalse(file.exists())
        assertTrue(elapsedMillis < 10_000, "the timeout must bound the save, took ${elapsedMillis}ms")
    }

    @Test
    fun `a probe that hangs is bounded and treated as unreachable`() {
        if (isWindows) return
        val backend = backend("exec sleep 30", timeoutMillis = 500)

        val started = System.nanoTime()
        val reachable = backend.isReachable()
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertFalse(reachable)
        assertTrue(elapsedMillis < 10_000, "the probe must not outlive its timeout, took ${elapsedMillis}ms")
    }

    @Test
    fun `a lookup that hangs is bounded and falls through to the fallback file`() {
        if (isWindows) return
        LinuxCredentialStore(file, NoOpLogger, ProcessSecretServiceBackend(executable = File(directory, "absent").path))
            .save("22BCE0001", "test-pass")
        val backend = backend("exec sleep 30", timeoutMillis = 500)

        assertEquals("test-pass", LinuxCredentialStore(file, NoOpLogger, backend).password())
    }

    @Test
    fun `nothing stored in a reachable keyring reads as no credentials`() {
        if (isWindows) return
        assertNull(LinuxCredentialStore(file, NoOpLogger, backend(reachableKeyring())).userId())
    }

    /** Behaves like `secret-tool` over an unlocked keyring, keeping one entry in a file. */
    private fun reachableKeyring(): String {
        val entry = File(directory, "keyring-entry").path
        return """
            case "${'$'}1" in
              store) cat > '$entry'; exit 0 ;;
              lookup)
                if [ "${'$'}4" = "probe" ] || [ ! -f '$entry' ]; then exit 1; fi
                cat '$entry'; exit 0 ;;
              clear) rm -f '$entry'; exit 0 ;;
            esac
            """
    }

    private fun backend(script: String, timeoutMillis: Long = 3_000): ProcessSecretServiceBackend {
        val tool = File(directory, "secret-tool")
        tool.writeText("#!/bin/sh\n" + script.trimIndent() + "\n")
        tool.setExecutable(true)
        return ProcessSecretServiceBackend(executable = tool.path, timeoutMillis = timeoutMillis)
    }
}
