package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.runtime.OwnerKind
import kotlinx.coroutines.delay

internal interface LoginStartup {
    fun enable(command: List<String>): OperationResult<Unit>
    fun disable(): OperationResult<Unit>
}

internal interface DaemonLauncher {
    fun launch(command: List<String>): OperationResult<Unit>
}

internal interface RuntimeOwnerControl {
    suspend fun activeOwner(): OperationResult<OwnerKind?>
    suspend fun stopCliDaemon(): OperationResult<Unit>
}

internal class PersistentCliLifecycle(
    private val startup: LoginStartup,
    private val launcher: DaemonLauncher,
    private val owners: RuntimeOwnerControl,
    private val daemonCommand: List<String>,
    private val retryDelayMillis: Long = 100,
    private val attempts: Int = 300,
) : CliLifecycle {
    override suspend fun activate(): OperationResult<Unit> {
        startup.enable(daemonCommand).error?.let { return OperationResult(error = it) }
        var launchedForCurrentVacancy = false
        repeat(attempts) {
            val owner = owners.activeOwner()
            if (owner.error == null) {
                when (owner.value) {
                    OwnerKind.DESKTOP, OwnerKind.CLI_DAEMON -> return OperationResult(Unit)
                    OwnerKind.CLI_ONESHOT -> launchedForCurrentVacancy = false
                    null -> if (!launchedForCurrentVacancy) {
                        launcher.launch(daemonCommand).error?.let {
                            startup.disable()
                            return OperationResult(error = it)
                        }
                        launchedForCurrentVacancy = true
                    }
                }
            }
            if (retryDelayMillis > 0) delay(retryDelayMillis)
        }

        startup.disable()
        return OperationResult(error = "Latch background daemon did not become ready.")
    }

    override suspend fun deactivate(): OperationResult<Unit> {
        startup.disable().error?.let { return OperationResult(error = it) }

        val current = owners.activeOwner()
        current.error?.let { return OperationResult(error = it) }
        if (current.value != OwnerKind.CLI_DAEMON) return OperationResult(Unit)

        owners.stopCliDaemon().error?.let { return OperationResult(error = it) }
        repeat(attempts) {
            if (retryDelayMillis > 0) delay(retryDelayMillis)
            val owner = owners.activeOwner()
            if (owner.error != null) return@repeat
            if (owner.value != OwnerKind.CLI_DAEMON) return OperationResult(Unit)
        }
        return OperationResult(error = "Latch background daemon did not stop.")
    }
}
