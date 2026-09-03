package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.DesktopEngineRuntime
import com.vinnovateit.latch.core.runtime.INSTANCE_PROTOCOL_VERSION
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.InstanceRequest
import com.vinnovateit.latch.core.runtime.InstanceResponse
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommand
import com.vinnovateit.latch.core.runtime.RuntimeCommandService
import com.vinnovateit.latch.core.settings.SettingsManager
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
    val ownerKind = if (command == CliCommand.DaemonProcess) OwnerKind.CLI_DAEMON else OwnerKind.CLI_ONESHOT
    val serviceReady = CompletableDeferred<RuntimeCommandService>()
    val acquired = InstanceCoordinator.tryAcquire(dataDir, ownerKind) { request ->
        serviceReady.await().execute(request)
    }

    return when (acquired) {
        is AcquireResult.Existing -> {
            val remote = RemoteCliBackend(acquired.client)
            if (command == CliCommand.DaemonProcess) ExistingDaemonBackend(remote, terminal, acquired.metadata.ownerKind)
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
            onDeactivate = {
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
        // Mirror LatchApp.start(): probe once on startup so a network that is
        // already authenticated is recognised without waiting for the next
        // Wi-Fi event, which otherwise leaves `--status` reporting "latched: no"
        // for a freshly activated daemon.
        if (ownerKind == OwnerKind.CLI_DAEMON) {
            runtime.engine.submit(
                if (SettingsManager.autoLogin.value) LatchCommand.CheckAndLogin
                else LatchCommand.SilentCheck,
            )
        }
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
    private val shutdownHook = Thread(
        { close(fromShutdownHook = true) },
        "LatchCliShutdown",
    ).also(Runtime.getRuntime()::addShutdownHook)

    override suspend fun runDaemon(): OperationResult<Unit> {
        check(ownerKind == OwnerKind.CLI_DAEMON)
        stopSignal.await()
        return OperationResult(Unit)
    }

    override fun close() = close(fromShutdownHook = false)

    private fun close(fromShutdownHook: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        if (!fromShutdownHook) runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
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
