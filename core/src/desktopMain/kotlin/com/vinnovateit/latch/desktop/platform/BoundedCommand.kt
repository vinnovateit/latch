package com.vinnovateit.latch.desktop.platform

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What running an external command came to. Never "maybe": each case is explicit. */
internal sealed interface CommandOutcome {
    /** The command could not be started at all (not installed, not executable). */
    data class Unavailable(val reason: String) : CommandOutcome

    /** Still running at the deadline; it was killed and its output is not trusted. */
    data object TimedOut : CommandOutcome

    data class Failed(val exitCode: Int, val stdout: String, val stderr: String) : CommandOutcome

    /** Exit 0. [stdout] may be empty -- a success with nothing to say is not a failure. */
    data class Succeeded(val stdout: String, val stderr: String) : CommandOutcome
}

/** The trimmed output of a successful command that printed something, else null. */
internal val CommandOutcome.nonEmptyOutput: String?
    get() = (this as? CommandOutcome.Succeeded)?.stdout?.takeIf { it.isNotEmpty() }

/**
 * Runs a command with a hard deadline.
 *
 * stdout and stderr are drained on their own threads from the moment the
 * process starts, so a child that writes more than a pipe buffer holds can
 * never stall waiting for a reader, and the deadline applies while it runs --
 * not after its output has been read to the end, which a hung child never
 * reaches. A child still running at the deadline is killed.
 *
 * Output beyond [maxOutputBytes] per stream is read and discarded rather than
 * buffered, so a runaway command cannot exhaust the heap either.
 */
internal class BoundedCommandRunner(
    private val timeoutMillis: Long = 5_000,
    private val maxOutputBytes: Int = 256 * 1024,
) {

    fun run(command: List<String>, input: String? = null): CommandOutcome {
        val process = try {
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            return CommandOutcome.Unavailable(e.message ?: "could not start ${command.firstOrNull()}")
        } catch (e: SecurityException) {
            return CommandOutcome.Unavailable(e.message ?: "not permitted to start ${command.firstOrNull()}")
        }

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val readers = listOf(
            thread(isDaemon = true, name = "bounded-command-stdout") { drain(process.inputStream, stdout) },
            thread(isDaemon = true, name = "bounded-command-stderr") { drain(process.errorStream, stderr) },
        )
        val writer = thread(isDaemon = true, name = "bounded-command-stdin") {
            try {
                process.outputStream.use { out -> input?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
            } catch (_: IOException) {
                // Exited without reading its input; the exit status says why.
            }
        }

        val finished = try {
            process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            return CommandOutcome.TimedOut
        }
        if (!finished) {
            process.destroyForcibly()
            // Reaped so no zombie outlives the call; bounded in case the kill is slow.
            runCatching { process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS) }
            return CommandOutcome.TimedOut
        }

        // The process is gone, but a grandchild it started may still hold the
        // pipes open; the readers are only waited on briefly for the same reason.
        writer.join(READER_GRACE_MS)
        readers.forEach { it.join(READER_GRACE_MS) }
        val out = synchronized(stdout) { stdout.toString(Charsets.UTF_8) }.trim()
        val err = synchronized(stderr) { stderr.toString(Charsets.UTF_8) }.trim()
        val code = process.exitValue()
        return if (code == 0) CommandOutcome.Succeeded(out, err) else CommandOutcome.Failed(code, out, err)
    }

    private fun drain(stream: InputStream, sink: ByteArrayOutputStream) {
        try {
            stream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(sink) {
                        val room = maxOutputBytes - sink.size()
                        if (room > 0) sink.write(buffer, 0, minOf(n, room))
                    }
                }
            }
        } catch (_: IOException) {
            // Stream closed under us (the process was killed); keep what was read.
        }
    }

    private companion object {
        const val KILL_GRACE_MS = 1_000L
        const val READER_GRACE_MS = 1_000L
    }
}
