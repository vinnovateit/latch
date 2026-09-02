package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.OwnerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PersistentCliLifecycleTest {
    @Test
    fun `activate enables login startup and launches daemon when no owner exists`() = runBlocking {
        val startup = FakeLoginStartup()
        val launcher = FakeDaemonLauncher()
        val owners = FakeOwnerControl(ArrayDeque(listOf(null, OwnerKind.CLI_DAEMON)))
        val command = listOf("/opt/latch-cli/bin/latch-cli", "--daemon-process")
        val lifecycle = PersistentCliLifecycle(startup, launcher, owners, command, retryDelayMillis = 0, attempts = 2)

        assertEquals(OperationResult(Unit), lifecycle.activate())

        assertEquals(command, startup.enabledCommand)
        assertEquals(command, launcher.launchedCommand)
    }

    @Test
    fun `activate is idempotent when an owner is already running`() = runBlocking {
        val startup = FakeLoginStartup()
        val launcher = FakeDaemonLauncher()
        val owners = FakeOwnerControl(ArrayDeque(listOf(OwnerKind.DESKTOP)))
        val lifecycle = PersistentCliLifecycle(startup, launcher, owners, listOf("latch-cli", "--daemon-process"))

        assertEquals(OperationResult(Unit), lifecycle.activate())

        assertTrue(startup.enabled)
        assertFalse(launcher.launched)
    }

    @Test
    fun `activate retries transient owner metadata race while daemon starts`() = runBlocking {
        val startup = FakeLoginStartup()
        val owners = FakeOwnerControl(
            ArrayDeque(listOf(null, OwnerKind.CLI_DAEMON)),
            transientErrorsAtCalls = setOf(2),
        )
        val lifecycle = PersistentCliLifecycle(
            startup,
            FakeDaemonLauncher(),
            owners,
            listOf("latch-cli", "--daemon-process"),
            retryDelayMillis = 0,
            attempts = 3,
        )

        assertEquals(OperationResult(Unit), lifecycle.activate())
        assertFalse(startup.disabled)
    }

    @Test
    fun `activate waits out a one-shot owner and verifies a durable daemon`() = runBlocking {
        val startup = FakeLoginStartup()
        val launcher = FakeDaemonLauncher()
        val owners = FakeOwnerControl(
            ArrayDeque(listOf(OwnerKind.CLI_ONESHOT, OwnerKind.CLI_ONESHOT, null, OwnerKind.CLI_DAEMON)),
        )
        val lifecycle = PersistentCliLifecycle(
            startup,
            launcher,
            owners,
            listOf("latch-cli", "--daemon-process"),
            retryDelayMillis = 0,
            attempts = 4,
        )

        assertEquals(OperationResult(Unit), lifecycle.activate())
        assertTrue(launcher.launched)
        assertFalse(startup.disabled)
    }

    @Test
    fun `failed daemon startup rolls back login startup`() = runBlocking {
        val startup = FakeLoginStartup()
        val owners = FakeOwnerControl(ArrayDeque(listOf(null, null, null)))
        val lifecycle = PersistentCliLifecycle(
            startup,
            FakeDaemonLauncher(),
            owners,
            listOf("latch-cli", "--daemon-process"),
            retryDelayMillis = 0,
            attempts = 2,
        )

        val result = lifecycle.activate()

        assertEquals("Latch background daemon did not become ready.", result.error)
        assertTrue(startup.disabled)
    }

    @Test
    fun `deactivate disables startup and stops cli daemon`() = runBlocking {
        val startup = FakeLoginStartup()
        val owners = FakeOwnerControl(ArrayDeque(listOf(OwnerKind.CLI_DAEMON, null)))
        val lifecycle = PersistentCliLifecycle(
            startup,
            FakeDaemonLauncher(),
            owners,
            listOf("latch-cli", "--daemon-process"),
            retryDelayMillis = 0,
            attempts = 2,
        )

        assertEquals(OperationResult(Unit), lifecycle.deactivate())

        assertTrue(startup.disabled)
        assertTrue(owners.stopRequested)
    }

    @Test
    fun `deactivate leaves desktop owner running`() = runBlocking {
        val startup = FakeLoginStartup()
        val owners = FakeOwnerControl(ArrayDeque(listOf(OwnerKind.DESKTOP)))
        val lifecycle = PersistentCliLifecycle(startup, FakeDaemonLauncher(), owners, listOf("latch-cli"))

        assertEquals(OperationResult(Unit), lifecycle.deactivate())

        assertTrue(startup.disabled)
        assertFalse(owners.stopRequested)
    }

    @Test
    fun `deactivate retries transient owner metadata race while daemon exits`() = runBlocking {
        val startup = FakeLoginStartup()
        val owners = FakeOwnerControl(
            ArrayDeque(listOf(OwnerKind.CLI_DAEMON, null)),
            transientErrorsAtCalls = setOf(2),
        )
        val lifecycle = PersistentCliLifecycle(
            startup,
            FakeDaemonLauncher(),
            owners,
            listOf("latch-cli"),
            retryDelayMillis = 0,
            attempts = 2,
        )

        assertEquals(OperationResult(Unit), lifecycle.deactivate())
    }
}

private class FakeLoginStartup : LoginStartup {
    var enabled = false
    var disabled = false
    var enabledCommand: List<String>? = null

    override fun enable(command: List<String>): OperationResult<Unit> {
        enabled = true
        enabledCommand = command
        return OperationResult(Unit)
    }

    override fun disable(): OperationResult<Unit> {
        disabled = true
        return OperationResult(Unit)
    }
}

private class FakeDaemonLauncher : DaemonLauncher {
    var launched = false
    var launchedCommand: List<String>? = null

    override fun launch(command: List<String>): OperationResult<Unit> {
        launched = true
        launchedCommand = command
        return OperationResult(Unit)
    }
}

private class FakeOwnerControl(
    private val owners: ArrayDeque<OwnerKind?>,
    private val transientErrorsAtCalls: Set<Int> = emptySet(),
) : RuntimeOwnerControl {
    var stopRequested = false
    private var activeOwnerCalls = 0

    override suspend fun activeOwner(): OperationResult<OwnerKind?> {
        activeOwnerCalls++
        if (activeOwnerCalls in transientErrorsAtCalls) {
            return OperationResult(error = "Runtime lock is held but owner metadata is unavailable.")
        }
        return OperationResult(if (owners.size > 1) owners.removeFirst() else owners.firstOrNull())
    }

    override suspend fun stopCliDaemon(): OperationResult<Unit> {
        stopRequested = true
        return OperationResult(Unit)
    }
}
