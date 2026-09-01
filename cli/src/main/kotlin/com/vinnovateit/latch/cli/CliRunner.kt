package com.vinnovateit.latch.cli

import com.vinnovateit.latch.core.LatchCore
import kotlinx.coroutines.CancellationException

const val EXIT_SUCCESS = 0
const val EXIT_OPERATIONAL_ERROR = 1
const val EXIT_USAGE_ERROR = 2

suspend fun runCli(
    args: Array<String>,
    terminal: TerminalIO,
    version: String = LatchCore.VERSION,
    backendFactory: suspend () -> CliBackend,
): Int = when (val parsed = parseCommand(args)) {
    is ParseResult.Success -> CliRunner(terminal, backendFactory, version).run(parsed.command)
    is ParseResult.Failure -> {
        terminal.println("error: ${parsed.message}")
        terminal.println(CliOutput.help)
        EXIT_USAGE_ERROR
    }
}

class CliRunner(
    private val terminal: TerminalIO,
    private val backendFactory: suspend () -> CliBackend,
    private val version: String = LatchCore.VERSION,
    private val splash: suspend (TerminalIO) -> Unit = { output ->
        showSplash(output, detectSplashCapabilities(output))
    },
) {
    suspend fun run(command: CliCommand): Int {
        when (command) {
            CliCommand.Help -> {
                terminal.println(CliOutput.help)
                return EXIT_SUCCESS
            }

            CliCommand.Version -> {
                terminal.println("latch-cli $version")
                return EXIT_SUCCESS
            }

            else -> Unit
        }

        val backend = try {
            backendFactory()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return fail(error.message ?: "Unable to initialize Latch.")
        }

        return try {
            runWithBackend(command, backend)
        } finally {
            backend.close()
        }
    }

    private suspend fun runWithBackend(command: CliCommand, backend: CliBackend): Int = when (command) {
        CliCommand.Daemon -> {
            splash(terminal)
            report(backend.runDaemon())
        }
        CliCommand.Status -> report(backend.status(), CliOutput::status)
        CliCommand.Login -> report(backend.login(), successMessage = "Login completed.")
        CliCommand.Logout -> report(backend.logout(), successMessage = "Logout completed.")
        CliCommand.History -> report(backend.history(), CliOutput::history)
        CliCommand.GetSettings -> report(backend.settings(), CliOutput::settings)
        is CliCommand.SetAutoLogin -> report(
            backend.setAutoLogin(command.enabled),
            successMessage = "auto-login: ${if (command.enabled) "on" else "off"}",
        )

        is CliCommand.SetAllowedSsids -> report(
            backend.setAllowedSsids(command.values),
            successMessage = "allowed-ssids: ${command.values.sorted().joinToString(",")}",
        )

        CliCommand.SetCredentials -> setCredentials(backend)
        CliCommand.Help, CliCommand.Version -> error("Handled before backend creation")
    }

    private suspend fun setCredentials(backend: CliBackend): Int {
        val credentials = promptForCredentials(terminal).getOrElse { return fail(it.message ?: "Invalid credentials.") }
        return try {
            report(backend.setCredentials(credentials.userId, credentials.password), successMessage = "Credentials saved.")
        } finally {
            credentials.password.fill('\u0000')
        }
    }

    private fun report(result: OperationResult<Unit>, successMessage: String? = null): Int {
        result.error?.let { return fail(it) }
        successMessage?.let(terminal::println)
        return EXIT_SUCCESS
    }

    private fun <T> report(result: OperationResult<T>, render: (T) -> String): Int {
        result.error?.let { return fail(it) }
        val value = result.value ?: return fail("The operation returned no result.")
        terminal.print(render(value))
        return EXIT_SUCCESS
    }

    private fun fail(message: String): Int {
        terminal.println("error: $message")
        return EXIT_OPERATIONAL_ERROR
    }
}
