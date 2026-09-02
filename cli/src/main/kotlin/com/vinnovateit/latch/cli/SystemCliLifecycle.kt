package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.INSTANCE_PROTOCOL_VERSION
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.InstanceResponse
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommand
import com.vinnovateit.latch.desktop.AppPaths
import java.io.File

internal fun createSystemCliLifecycle(): CliLifecycle {
    val command = resolveDaemonCommand()
    command.error?.let { return FailedCliLifecycle(it) }
    val resolvedCommand = command.value ?: return FailedCliLifecycle("Unable to resolve the Latch CLI executable.")
    val daemonCommand = if (AppPaths.isWindows) windowsHiddenCommand(resolvedCommand) else resolvedCommand
    val startup = when {
        AppPaths.isWindows -> WindowsLoginStartup()
        AppPaths.isLinux -> LinuxLoginStartup(linuxConfigDirectory())
        else -> return FailedCliLifecycle("Persistent CLI background mode is supported on Windows and Linux.")
    }
    return PersistentCliLifecycle(
        startup = startup,
        launcher = SystemDaemonLauncher(File(AppPaths.logsDir, "latch-cli-daemon.log")),
        owners = CoordinatedRuntimeOwnerControl(AppPaths.dataDir),
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

internal fun resolveDaemonCommand(
    processCommand: String? = ProcessHandle.current().info().command().orElse(null),
    classPath: String = System.getProperty("java.class.path").orEmpty(),
): OperationResult<List<String>> {
    val executable = processCommand?.takeIf(String::isNotBlank)
        ?: return OperationResult(error = "Unable to determine the current Latch CLI executable.")
    val name = File(executable).nameWithoutExtension
    val command = if (name.equals("java", ignoreCase = true) || name.equals("javaw", ignoreCase = true)) {
        if (classPath.isBlank()) return OperationResult(error = "Unable to determine the Latch CLI classpath.")
        listOf(executable, "-cp", classPath, "com.vinnovateit.latch.cli.MainKt", "--daemon-process")
    } else {
        listOf(executable, "--daemon-process")
    }
    return OperationResult(command)
}

private fun linuxConfigDirectory(): File {
    val path = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)
        ?: File(System.getProperty("user.home"), ".config").absolutePath
    return File(path)
}

private class FailedCliLifecycle(private val message: String) : CliLifecycle {
    override suspend fun activate() = OperationResult<Unit>(error = message)
    override suspend fun deactivate() = OperationResult<Unit>(error = message)
}

internal class SystemDaemonLauncher(private val logFile: File) : DaemonLauncher {
    override fun launch(command: List<String>): OperationResult<Unit> = runCatching {
        logFile.parentFile?.mkdirs()
        val processBuilder = ProcessBuilder(command)
        sanitizeDaemonEnvironment(processBuilder.environment())
        processBuilder.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        OperationResult(Unit)
    }.getOrElse { OperationResult(error = it.message ?: "Unable to start Latch in the background.") }

    private companion object {
        val NULL_INPUT: File = if (AppPaths.isWindows) File("NUL") else File("/dev/null")
    }
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
