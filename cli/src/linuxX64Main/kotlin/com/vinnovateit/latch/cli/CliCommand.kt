package com.vinnovateit.latch.cli

sealed interface CliCommand {
    data object Bootstrap : CliCommand
    data object Activate : CliCommand
    data object Deactivate : CliCommand
    data object DaemonProcess : CliCommand
    data object Status : CliCommand
    data object Login : CliCommand
    data object Logout : CliCommand
    data object History : CliCommand
    data object SetCredentials : CliCommand
    data object GetSettings : CliCommand
    data class SetAutoLogin(val enabled: Boolean) : CliCommand
    data class SetAllowedSsids(val values: Set<String>) : CliCommand
    data object Help : CliCommand
    data object Version : CliCommand
}

sealed interface ParseResult {
    data class Success(val command: CliCommand) : ParseResult
    data class Failure(val message: String) : ParseResult
}

private const val SETTINGS_USAGE =
    "Usage: --settings [set auto-login <on|off> | set allowed-ssids <comma,separated>]"

fun parseCommand(args: Array<String>): ParseResult {
    if (args.isEmpty()) return ParseResult.Success(CliCommand.Bootstrap)

    if (args.first() == "--settings") return parseSettings(args)

    if (args.size != 1) {
        return ParseResult.Failure("${args.first()} does not accept arguments")
    }

    val command = when (args.first()) {
        "activate" -> CliCommand.Activate
        "deactivate" -> CliCommand.Deactivate
        "--daemon-process" -> CliCommand.DaemonProcess
        "--status" -> CliCommand.Status
        "--login" -> CliCommand.Login
        "--logout" -> CliCommand.Logout
        "--history" -> CliCommand.History
        "--set-credentials" -> CliCommand.SetCredentials
        "--help" -> CliCommand.Help
        "--version" -> CliCommand.Version
        else -> return ParseResult.Failure("Unknown command: ${args.first()}")
    }
    return ParseResult.Success(command)
}

private fun parseSettings(args: Array<String>): ParseResult {
    if (args.size == 1) return ParseResult.Success(CliCommand.GetSettings)
    if (args.size != 4 || args[1] != "set") return ParseResult.Failure(SETTINGS_USAGE)

    return when (args[2]) {
        "auto-login" -> when (args[3]) {
            "on" -> ParseResult.Success(CliCommand.SetAutoLogin(enabled = true))
            "off" -> ParseResult.Success(CliCommand.SetAutoLogin(enabled = false))
            else -> ParseResult.Failure("auto-login must be on or off")
        }

        "allowed-ssids" -> parseAllowedSsids(args[3])
        else -> ParseResult.Failure("Unknown settings key: ${args[2]}")
    }
}

private fun parseAllowedSsids(rawValue: String): ParseResult {
    if (rawValue.isBlank()) {
        return ParseResult.Failure("allowed-ssids must not be empty")
    }

    val values = rawValue.split(',').map(String::trim)
    if (values.any(String::isEmpty)) {
        return ParseResult.Failure("allowed-ssids must not contain empty entries")
    }

    return ParseResult.Success(CliCommand.SetAllowedSsids(values.toCollection(linkedSetOf())))
}
