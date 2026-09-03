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
    lifecycle: CliLifecycle = UnavailableCliLifecycle,
    backendFactory: suspend (CliCommand) -> CliBackend,
): Int = when (val parsed = parseCommand(args)) {
    is ParseResult.Success -> CliRunner(
        terminal,
        { backendFactory(parsed.command) },
        version,
        lifecycle = lifecycle,
    ).run(parsed.command)
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
    private val lifecycle: CliLifecycle = UnavailableCliLifecycle,
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

            CliCommand.Activate -> return activate()
            CliCommand.Deactivate -> return deactivate()
            CliCommand.Bootstrap -> return bootstrap()

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
        CliCommand.Bootstrap -> error("Handled before backend creation")
        CliCommand.DaemonProcess -> report(backend.runDaemon())
        CliCommand.Activate, CliCommand.Deactivate -> error("Handled before backend creation")
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

    private suspend fun bootstrap(): Int {
        splash(terminal)
        val backend = try {
            backendFactory()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return fail(error.message ?: "Unable to initialize Latch.")
        }

        val shouldActivate = try {
            val setup = backend.isSetup()
            setup.error?.let { return fail(it) }
            val configured = setup.value ?: return fail("The operation returned no setup status.")
            if (configured) {
                terminal.println(CliOutput.help)
                return EXIT_SUCCESS
            }

            terminal.println("Welcome to Latch.")
            val credentials = promptForCredentials(terminal).getOrElse {
                return fail(it.message ?: "Invalid credentials.")
            }
            try {
                val saved = backend.setCredentials(credentials.userId, credentials.password)
                saved.error?.let { return fail(it) }
            } finally {
                credentials.password.fill('\u0000')
            }
            true
        } finally {
            backend.close()
        }

        return if (shouldActivate) activate() else EXIT_SUCCESS
    }

    private suspend fun activate(): Int = report(
        lifecycle.activate(),
        successMessage = "Latch is running in the background and will start when you log in.",
    )

    private suspend fun deactivate(): Int = report(
        lifecycle.deactivate(),
        successMessage = "Latch background daemon stopped and login startup disabled.",
    )

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
