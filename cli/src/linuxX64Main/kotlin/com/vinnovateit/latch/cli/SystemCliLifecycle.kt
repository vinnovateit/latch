package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.mp.JFile as File
import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.INSTANCE_PROTOCOL_VERSION
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.InstanceResponse
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommand
import com.vinnovateit.latch.core.runtime.RuntimeCommandService

internal fun createSystemCliLifecycle(): CliLifecycle {
    val command = resolveDaemonCommand()
    command.error?.let { return FailedCliLifecycle(it) }
    val resolvedCommand = command.value ?: return FailedCliLifecycle("Unable to resolve the Latch CLI executable.")
    val daemonCommand = if (CliAppPaths.isWindows) windowsHiddenCommand(resolvedCommand) else resolvedCommand
    val startup = when {
        CliAppPaths.isWindows -> WindowsLoginStartup()
        CliAppPaths.isLinux -> LinuxLoginStartup(linuxConfigDirectory())
        else -> return FailedCliLifecycle("Persistent CLI background mode is supported on Windows and Linux.")
    }
    return PersistentCliLifecycle(
        startup = startup,
        launcher = SystemDaemonLauncher(CliAppPaths.logsDir.resolve("latch-cli-daemon.log")),
        owners = CoordinatedRuntimeOwnerControl(CliAppPaths.dataDir),
        daemonCommand = daemonCommand,
    )
}

internal fun windowsHiddenCommand(command: List<String>): List<String> = listOf(
    "powershell.exe",
    "-NoProfile",
    "-NonInteractive",
    "-WindowStyle",
    "Hidden",
    "-Command",
    command.joinToString(" ") { "'${it.replace("'", "''")}'" }.let { "& $it" },
)

/**
 * A native executable is self-contained, so the daemon is just this binary with
 * --daemon-process. [classPath] is kept only so existing callers keep compiling.
 */
internal fun resolveDaemonCommand(
    processCommand: String? = currentExecutable(),
    @Suppress("UNUSED_PARAMETER") classPath: String = "",
): OperationResult<List<String>> {
    val executable = processCommand?.takeIf(String::isNotBlank)
        ?: return OperationResult(error = "Unable to determine the current Latch CLI executable.")
    return OperationResult(listOf(executable, "--daemon-process"))
}

private fun linuxConfigDirectory(): File {
    val path = CliEnv.xdgConfigHome?.takeIf(String::isNotBlank)
        ?: (CliEnv.userHome + "/.config")
    return File(path)
}

private class FailedCliLifecycle(private val message: String) : CliLifecycle {
    override suspend fun activate() = OperationResult<Unit>(error = message)
    override suspend fun deactivate() = OperationResult<Unit>(error = message)
}

internal class SystemDaemonLauncher(private val logFile: File) : DaemonLauncher {
    override fun launch(command: List<String>): OperationResult<Unit> = runCatching {
        logFile.parentFile?.mkdirs()
        launchDetached(command, logFile)
        OperationResult(Unit)
    }.getOrElse { OperationResult(error = it.message ?: "Unable to start Latch in the background.") }
}

internal fun sanitizeDaemonEnvironment(environment: MutableMap<String, String>) {
    environment.remove("_JPACKAGE_LAUNCHER")
}

internal class CoordinatedRuntimeOwnerControl(
    private val dataDir: File,
) : RuntimeOwnerControl {
    override suspend fun activeOwner(): OperationResult<OwnerKind?> = when (val acquired = inspect()) {
        is AcquireResult.Owner -> {
            acquired.coordinator.close()
            OperationResult(null)
        }
        is AcquireResult.Existing -> {
            val response = acquired.client.send(RuntimeCommand.PING)
            if (response.ok) OperationResult(acquired.metadata.ownerKind)
            else OperationResult(error = response.message.ifBlank { response.code })
        }
        is AcquireResult.Failure -> OperationResult(error = acquired.message)
    }

    override suspend fun stopCliDaemon(): OperationResult<Unit> = when (val acquired = inspect()) {
        is AcquireResult.Owner -> {
            acquired.coordinator.close()
            OperationResult(Unit)
        }
        is AcquireResult.Existing -> {
            if (acquired.metadata.ownerKind != OwnerKind.CLI_DAEMON) {
                OperationResult(Unit)
            } else {
                val response = acquired.client.send(RuntimeCommand.DEACTIVATE)
                if (response.ok) OperationResult(Unit)
                else OperationResult(error = response.message.ifBlank { response.code })
            }
        }
        is AcquireResult.Failure -> OperationResult(error = acquired.message)
    }

    private fun inspect(): AcquireResult = InstanceCoordinator.tryAcquire(
        dataDir = dataDir,
        ownerKind = OwnerKind.CLI_ONESHOT,
    ) { request ->
        InstanceResponse(
            requestId = request.requestId,
            ok = false,
            code = "OWNER_CHANGED",
            message = "Temporary lifecycle probe cannot serve commands.",
            data = mapOf("protocol" to INSTANCE_PROTOCOL_VERSION.toString()),
        )
    }
}
