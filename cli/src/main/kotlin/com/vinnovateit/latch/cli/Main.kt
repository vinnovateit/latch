package com.vinnovateit.latch.cli

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** How long a one-shot command waits for the engine to reach Success/Failed. */
private const val COMMAND_TIMEOUT_MS = 20_000L

/** Prints to stdout instead of a tray -- there is no tray in a terminal. */
private class ConsoleNotifier : UserNotifier {
    override fun showOngoing(title: String, text: String) {
        // High-frequency (every 2s); the tray tooltip's job. Not worth a log
        // line per tick in a terminal.
    }

    override fun notifyTransient(title: String, text: String, isError: Boolean) {
        println("[$title] $text")
    }

    override fun hideOngoing() {
        // No tray icon to clear in a terminal.
    }
}

private fun usage(): Nothing {
    println(
        """
        Usage: latch [command]

          (no command)  Run in the foreground: connect, monitor, and print state changes.
          --status      Print the current connection status and exit.
          --login       Attempt to log in once and exit.
          --logout      Log out and exit.
        """.trimIndent()
    )
    kotlin.system.exitProcess(1)
}

fun main(args: Array<String>) = runBlocking {
    val platform = DesktopPlatformServices(echoLogsToStdout = true, notifier = ConsoleNotifier())
    Platform.install(platform)
    SettingsManager.initialize(platform.settingsStore)

    val database = buildDatabase()
    val throughput = ThroughputMonitor(platform.counters)
    val sessions = SessionRepository(database.statsDao(), throughput)
    sessions.initialize()

    val engine = LatchEngine(platform, sessions)
    // Idempotent: also starts the command-processing loop that submit() feeds,
    // needed even for the one-shot commands below, not just the daemon case.
    engine.start()

    when (args.firstOrNull()) {
        null -> {
            var wasLatched = false
            engine.isLatched.collect { latched ->
                if (latched != wasLatched) {
                    println(if (latched) "Latched onto Wi-Fi." else "No longer latched.")
                    wasLatched = latched
                }
            }
        }

        "--status" -> awaitResult(engine) { it.submit(LatchCommand.SilentCheck) }
        "--login" -> awaitResult(engine) { it.submit(LatchCommand.CheckAndLogin) }
        "--logout" -> awaitResult(engine) { it.submit(LatchCommand.Logout) }
        else -> usage()
    }

    database.close()
}

/**
 * submit() only enqueues onto a channel the engine's own coroutine drains;
 * reading status.value right after would just show whatever it was before.
 * Waits for a terminal Success/Failed instead, or reports a timeout -- which
 * is the real outcome when there's no Wi-Fi to act on at all (SilentCheck and
 * CheckAndLogin both return early without posting a status in that case).
 */
private suspend fun awaitResult(engine: LatchEngine, submit: (LatchEngine) -> Unit) {
    submit(engine)
    val result = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
        engine.status.first { it is ConnectionStatus.Success || it is ConnectionStatus.Failed }
    }
    println(result?.let { "status: $it" } ?: "no result after ${COMMAND_TIMEOUT_MS / 1000}s (is Wi-Fi connected?)")
}
