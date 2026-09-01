package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.DesktopEngineRuntime
import com.vinnovateit.latch.core.runtime.INSTANCE_PROTOCOL_VERSION
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.InstanceRequest
import com.vinnovateit.latch.core.runtime.InstanceResponse
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommand
import com.vinnovateit.latch.core.runtime.RuntimeCommandService
import com.vinnovateit.latch.desktop.AppPaths
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

internal suspend fun createCoordinatedCliBackend(
    command: CliCommand,
    terminal: TerminalIO,
    dataDir: File = AppPaths.dataDir,
): CliBackend {
    val ownerKind = if (command == CliCommand.Daemon) OwnerKind.CLI_DAEMON else OwnerKind.CLI_ONESHOT
    val serviceReady = CompletableDeferred<RuntimeCommandService>()
    val acquired = InstanceCoordinator.tryAcquire(dataDir, ownerKind) { request ->
        serviceReady.await().execute(request)
    }

    return when (acquired) {
        is AcquireResult.Existing -> {
            val remote = RemoteCliBackend(acquired.client)
            if (command == CliCommand.Daemon) ExistingDaemonBackend(remote, terminal, acquired.metadata.ownerKind)
            else remote
        }
        is AcquireResult.Failure -> error(acquired.message)
        is AcquireResult.Owner -> createOwnerBackend(ownerKind, terminal, acquired.coordinator, serviceReady)
    }
}

private suspend fun createOwnerBackend(
    ownerKind: OwnerKind,
    terminal: TerminalIO,
    coordinator: InstanceCoordinator,
    serviceReady: CompletableDeferred<RuntimeCommandService>,
): CliBackend {
    return try {
        val runtime = DesktopEngineRuntime.create(
            ConsoleNotifier(terminal),
            echoLogsToStdout = ownerKind == OwnerKind.CLI_DAEMON,
        )
        val stopSignal = CompletableDeferred<Unit>()
        val service = RuntimeCommandService(
            ownerKind = ownerKind,
            runtime = runtime,
            onTakeOver = {
                if (ownerKind == OwnerKind.CLI_DAEMON) {
                    stopSignal.complete(Unit)
                    true
                } else {
                    false
                }
            },
        )
        serviceReady.complete(service)
        runtime.start()
        OwnedCliBackend(ownerKind, runtime, coordinator, service, stopSignal)
    } catch (error: Exception) {
        serviceReady.completeExceptionally(error)
        coordinator.close()
        throw error
    }
}

private class OwnedCliBackend(
    private val ownerKind: OwnerKind,
    private val runtime: DesktopEngineRuntime,
    private val coordinator: InstanceCoordinator,
    service: RuntimeCommandService,
    private val stopSignal: CompletableDeferred<Unit>,
) : CliBackend by ProtocolCliBackend({ command, arguments ->
    service.execute(
        InstanceRequest(
            version = INSTANCE_PROTOCOL_VERSION,
            token = "local-owner",
            requestId = UUID.randomUUID().toString(),
            command = command,
            arguments = arguments,
        ),
    )
}) {
    private val closed = AtomicBoolean(false)

    override suspend fun runDaemon(): OperationResult<Unit> {
        check(ownerKind == OwnerKind.CLI_DAEMON)
        stopSignal.await()
        return OperationResult(Unit)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking { runtime.close() }
        coordinator.close()
    }
}

private class ExistingDaemonBackend(
    delegate: CliBackend,
    private val terminal: TerminalIO,
    private val activeOwner: OwnerKind,
) : CliBackend by delegate {
    override suspend fun runDaemon(): OperationResult<Unit> {
        terminal.println("Latch is already running as ${activeOwner.name.lowercase().replace('_', '-')}.")
        return OperationResult(Unit)
    }
}

private class ConsoleNotifier(private val terminal: TerminalIO) : com.vinnovateit.latch.core.platform.UserNotifier {
    override fun showOngoing(title: String, text: String) = Unit
    override fun notifyTransient(title: String, text: String, isError: Boolean) {
        terminal.println("[$title] $text")
    }
    override fun hideOngoing() = Unit
}
