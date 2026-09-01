package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.data.LatchDatabase
import com.vinnovateit.latch.core.data.StatsDao
import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.desktop.platform.DesktopPlatformServices
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val COMMAND_TIMEOUT_MS = 20_000L
private const val SHUTDOWN_TIMEOUT_MS = 5_000L

internal suspend fun createLocalCliBackend(terminal: TerminalIO): CliBackend {
    val platform = DesktopPlatformServices(
        echoLogsToStdout = true,
        notifier = ConsoleNotifier(terminal),
    )
    Platform.install(platform)
    SettingsManager.initialize(platform.settingsStore)

    val database = buildDatabase()
    val dao = database.statsDao()
    val sessions = SessionRepository(dao, ThroughputMonitor(platform.counters))
    sessions.initialize()

    val engine = LatchEngine(platform, sessions)
    engine.start()
    return LocalCliBackend(terminal, platform, database, dao, engine)
}

private class LocalCliBackend(
    private val terminal: TerminalIO,
    private val platform: DesktopPlatformServices,
    private val database: LatchDatabase,
    private val dao: StatsDao,
    private val engine: LatchEngine,
) : CliBackend {
    override suspend fun status(): OperationResult<CliStatus> {
        if (!engine.submitAndAwait(LatchCommand.SilentCheck, COMMAND_TIMEOUT_MS)) {
            return OperationResult(error = "Status check timed out.")
        }
        val status = engine.status.value
        val connection = when (status) {
            ConnectionStatus.Idle -> if (platform.wifi.isConnectedToWifi()) "connected" else "disconnected"
            ConnectionStatus.Success -> "online"
            is ConnectionStatus.Connecting -> "connecting:${status.step.name.toKebabCase()}"
            is ConnectionStatus.Failed -> "failed:${status.reason.name.toKebabCase()}"
        }
        return OperationResult(
            CliStatus(
                owner = "cli",
                connection = connection,
                ssid = platform.wifi.currentSsid(),
                latched = engine.isLatched.value,
            ),
        )
    }

    override suspend fun login(): OperationResult<Unit> = runCommand(LatchCommand.CheckAndLogin, "Login")

    override suspend fun logout(): OperationResult<Unit> = runCommand(LatchCommand.Logout, "Logout")

    override suspend fun history(): OperationResult<List<CliSession>> = OperationResult(
        dao.getAllSessions().first().map { session ->
            CliSession(
                start = session.startTime,
                end = session.endTime,
                rx = session.rxBytes,
                tx = session.txBytes,
                maxRx = session.maxRxBps,
                maxTx = session.maxTxBps,
            )
        },
    )

    override suspend fun settings(): OperationResult<CliSettings> = OperationResult(
        CliSettings(
            autoLogin = SettingsManager.autoLogin.value,
            allowedSsids = SettingsManager.allowedSsids.value,
        ),
    )

    override suspend fun setAutoLogin(enabled: Boolean): OperationResult<Unit> {
        SettingsManager.setAutoLogin(enabled)
        return OperationResult(Unit)
    }

    override suspend fun setAllowedSsids(values: Set<String>): OperationResult<Unit> {
        SettingsManager.setAllowedSsids(values)
        return OperationResult(Unit)
    }

    override suspend fun setCredentials(userId: String, password: CharArray): OperationResult<Unit> {
        platform.credentials.save(userId, password.concatToString())
        return OperationResult(Unit)
    }

    override suspend fun runDaemon(): OperationResult<Unit> {
        var wasLatched = engine.isLatched.value
        engine.isLatched.collect { latched ->
            if (latched != wasLatched) {
                terminal.println(if (latched) "Latched onto Wi-Fi." else "No longer latched.")
                wasLatched = latched
            }
        }
        return OperationResult(Unit)
    }

    override fun close() {
        runBlocking { engine.submitAndAwait(LatchCommand.Shutdown, SHUTDOWN_TIMEOUT_MS) }
        database.close()
    }

    private suspend fun runCommand(command: LatchCommand, label: String): OperationResult<Unit> {
        if (!engine.submitAndAwait(command, COMMAND_TIMEOUT_MS)) {
            return OperationResult(error = "$label timed out.")
        }
        return when (val status = engine.status.value) {
            is ConnectionStatus.Failed -> OperationResult(error = "$label failed: ${status.reason.name.toKebabCase()}")
            else -> OperationResult(Unit)
        }
    }
}

private class ConsoleNotifier(private val terminal: TerminalIO) : UserNotifier {
    override fun showOngoing(title: String, text: String) = Unit

    override fun notifyTransient(title: String, text: String, isError: Boolean) {
        terminal.println("[$title] $text")
    }

    override fun hideOngoing() = Unit
}

private fun String.toKebabCase(): String =
    fold(StringBuilder()) { result, character ->
        if (character.isUpperCase() && result.isNotEmpty()) result.append('-')
        result.append(character.lowercaseChar())
    }.toString()
