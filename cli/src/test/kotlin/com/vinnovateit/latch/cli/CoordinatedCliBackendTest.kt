package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.InstanceRequest
import com.vinnovateit.latch.core.runtime.InstanceResponse
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommand
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class CoordinatedCliBackendTest {
    @Test
    fun `one-shot command forwards to active cli daemon`() = runBlocking {
        val directory = createTempDirectory("latch-cli-forward-").toFile()
        val daemon = createCoordinatedCliBackend(CliCommand.DaemonProcess, SilentTerminal, directory)
        try {
            val oneShot = createCoordinatedCliBackend(CliCommand.Status, SilentTerminal, directory)
            try {
                val status = oneShot.status()
                assertEquals("cli-daemon", status.value?.owner)
            } finally {
                oneShot.close()
            }
        } finally {
            daemon.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `desktop takeover stops daemon and releases ownership`() = runBlocking {
        val directory = createTempDirectory("latch-cli-takeover-").toFile()
        val daemon = createCoordinatedCliBackend(CliCommand.DaemonProcess, SilentTerminal, directory)
        val daemonRun = async(Dispatchers.Default) { daemon.runDaemon() }
        try {
            val existing = assertIs<AcquireResult.Existing>(
                InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo),
            )

            val response = existing.client.send(RuntimeCommand.TAKE_OVER)

            assertTrue(response.ok)
            assertEquals(OperationResult(Unit), withTimeout(2_000) { daemonRun.await() })
            daemon.close()

            val desktop = assertIs<AcquireResult.Owner>(
                InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP, ::echo),
            )
            desktop.coordinator.close()
        } finally {
            daemonRun.cancel()
            daemon.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `authenticated deactivate stops daemon and releases ownership`() = runBlocking {
        val directory = createTempDirectory("latch-cli-deactivate-").toFile()
        val daemon = createCoordinatedCliBackend(CliCommand.DaemonProcess, SilentTerminal, directory)
        val daemonRun = async(Dispatchers.Default) { daemon.runDaemon() }
        try {
            val existing = assertIs<AcquireResult.Existing>(
                InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_ONESHOT, ::echo),
            )

            val response = existing.client.send(RuntimeCommand.DEACTIVATE)

            assertTrue(response.ok)
            assertEquals(OperationResult(Unit), withTimeout(2_000) { daemonRun.await() })
            daemon.close()
            val next = assertIs<AcquireResult.Owner>(
                InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_ONESHOT, ::echo),
            )
            next.coordinator.close()
        } finally {
            daemonRun.cancel()
            daemon.close()
            directory.deleteRecursively()
        }
    }

    private suspend fun echo(request: InstanceRequest) = InstanceResponse(
        requestId = request.requestId,
        ok = true,
        code = "OK",
    )
}

private object SilentTerminal : TerminalIO {
    override val interactive = false
    override fun print(text: String) = Unit
    override fun println(text: String) = Unit
    override fun readLine(prompt: String): String? = null
    override fun readSecret(prompt: String): CharArray? = null
}
