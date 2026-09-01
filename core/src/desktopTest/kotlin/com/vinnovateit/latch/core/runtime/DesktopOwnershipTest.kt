package com.vinnovateit.latch.core.runtime

import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DesktopOwnershipTest {
    @Test
    fun `second desktop activates the existing desktop`() = runBlocking {
        val directory = createTempDirectory("latch-desktop-existing-").toFile()
        var activated = false
        val owner = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.DESKTOP) { request ->
                if (request.command == RuntimeCommand.ACTIVATE_UI) activated = true
                InstanceResponse(request.requestId, true, "OK")
            },
        )
        try {
            val claim = claimDesktopOwnership(directory, timeoutMillis = 500) { echo(it) }

            assertIs<DesktopOwnership.ActivatedExisting>(claim)
            assertTrue(activated)
        } finally {
            owner.coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `desktop takes ownership from cli daemon`() = runBlocking {
        val directory = createTempDirectory("latch-desktop-takeover-").toFile()
        lateinit var cliOwner: InstanceCoordinator
        val acquired = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_DAEMON) { request ->
                if (request.command == RuntimeCommand.TAKE_OVER) {
                    thread(isDaemon = true) {
                        Thread.sleep(20)
                        cliOwner.close()
                    }
                    InstanceResponse(request.requestId, true, "OK")
                } else {
                    echo(request)
                }
            },
        )
        cliOwner = acquired.coordinator
        try {
            val claim = claimDesktopOwnership(directory, timeoutMillis = 1_000, retryDelayMillis = 10) { echo(it) }

            val desktop = assertIs<DesktopOwnership.Owner>(claim)
            desktop.coordinator.close()
        } finally {
            cliOwner.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed daemon takeover is reported`() = runBlocking {
        val directory = createTempDirectory("latch-desktop-refused-").toFile()
        val owner = assertIs<AcquireResult.Owner>(
            InstanceCoordinator.tryAcquire(directory, OwnerKind.CLI_DAEMON) { request ->
                InstanceResponse(request.requestId, false, "OWNER_CHANGED", "refused")
            },
        )
        try {
            val claim = claimDesktopOwnership(directory, timeoutMillis = 500) { echo(it) }

            val failure = assertIs<DesktopOwnership.Failure>(claim)
            assertEquals("refused", failure.message)
        } finally {
            owner.coordinator.close()
            directory.deleteRecursively()
        }
    }

    private fun echo(request: InstanceRequest) = InstanceResponse(request.requestId, true, "OK")
}
