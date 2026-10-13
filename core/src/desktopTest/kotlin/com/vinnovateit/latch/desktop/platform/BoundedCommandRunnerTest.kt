package com.vinnovateit.latch.desktop.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real [BoundedCommandRunner] against `/bin/sh` scripts. Nothing
 * here sleeps to wait for an outcome: the timeout case is a child that would
 * run for a minute, and the test only checks that the runner came back long
 * before that with the child dead.
 */
class BoundedCommandRunnerTest {
    private lateinit var directory: File
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("latch-bounded-command-").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun sh(script: String) = listOf("/bin/sh", "-c", script)

    @Test
    fun `success returns trimmed stdout and stderr separately`() {
        if (isWindows) return
        val outcome = BoundedCommandRunner().run(sh("echo '  out  '; echo err >&2"))

        assertIs<CommandOutcome.Succeeded>(outcome)
        assertEquals("out", outcome.stdout)
        assertEquals("err", outcome.stderr)
        assertEquals("out", outcome.nonEmptyOutput)
    }

    @Test
    fun `a non-zero exit is a failure carrying its code and output`() {
        if (isWindows) return
        val outcome = BoundedCommandRunner().run(sh("echo partial; echo 'no such device' >&2; exit 3"))

        assertIs<CommandOutcome.Failed>(outcome)
        assertEquals(3, outcome.exitCode)
        assertEquals("partial", outcome.stdout)
        assertEquals("no such device", outcome.stderr)
        assertNull(outcome.nonEmptyOutput, "a failed command's output is never used as a result")
    }

    @Test
    fun `an empty successful result is distinct from a failure`() {
        if (isWindows) return
        val outcome = BoundedCommandRunner().run(sh("exit 0"))

        assertIs<CommandOutcome.Succeeded>(outcome)
        assertEquals("", outcome.stdout)
        assertNull(outcome.nonEmptyOutput)
    }

    @Test
    fun `a command that cannot be started is unavailable`() {
        val outcome = BoundedCommandRunner().run(listOf(File(directory, "no-such-tool").path))

        assertIs<CommandOutcome.Unavailable>(outcome)
    }

    @Test
    fun `a hung command times out promptly and is killed`() {
        if (isWindows) return
        val pidFile = File(directory, "pid")
        val started = System.nanoTime()

        val outcome = BoundedCommandRunner(timeoutMillis = 300).run(sh("echo $$ > '${pidFile.path}'; exec sleep 60"))

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(CommandOutcome.TimedOut, outcome)
        assertTrue(elapsedMs < 10_000, "the runner must return at the deadline, not when the child exits ($elapsedMs ms)")
        val pid = pidFile.readText().trim().toLong()
        val child = ProcessHandle.of(pid)
        assertTrue(child.isEmpty || !child.get().isAlive, "the timed-out child must have been killed")
    }

    @Test
    fun `large stdout cannot deadlock the runner`() {
        if (isWindows) return
        // Far beyond any pipe buffer: a runner that waited before reading would hang here.
        val outcome = BoundedCommandRunner(timeoutMillis = 30_000, maxOutputBytes = 64 * 1024)
            .run(sh("head -c 4000000 /dev/zero | tr '\\0' a"))

        assertIs<CommandOutcome.Succeeded>(outcome)
        assertEquals(64 * 1024, outcome.stdout.length, "output past the cap is drained and discarded")
    }

    @Test
    fun `large stderr cannot deadlock the runner`() {
        if (isWindows) return
        val outcome = BoundedCommandRunner(timeoutMillis = 30_000, maxOutputBytes = 64 * 1024)
            .run(sh("head -c 4000000 /dev/zero | tr '\\0' e >&2; echo done"))

        assertIs<CommandOutcome.Succeeded>(outcome)
        assertEquals("done", outcome.stdout)
        assertEquals(64 * 1024, outcome.stderr.length)
    }

    @Test
    fun `input is delivered on stdin`() {
        if (isWindows) return
        val outcome = BoundedCommandRunner().run(sh("cat"), input = "hello")

        assertIs<CommandOutcome.Succeeded>(outcome)
        assertEquals("hello", outcome.stdout)
    }
}
