package com.vinnovateit.latch.cli

import java.time.Instant

internal object CliOutput {
    val help: String =
        """
        Usage: latch-cli [command]

          (no command)                              Onboard this machine, or show help when configured.
          activate                                  Start Latch in the background and at login.
          deactivate                                Stop the CLI daemon and disable login startup.
          --status                                  Show the current connection status.
          --login                                   Attempt one login.
          --logout                                  Log out once.
          --history                                 List recorded sessions, newest first.
          --set-credentials                         Prompt for and save credentials.
          --settings                                Show CLI settings.
          --settings set auto-login <on|off>        Enable or disable automatic login.
          --settings set allowed-ssids <ssid,...>   Replace the allowed SSID list.
          --help                                    Show this help.
          --version                                 Show the installed version.
        """.trimIndent()

    fun status(value: CliStatus): String = buildString {
        appendLine("owner: ${value.owner}")
        appendLine("connection: ${value.connection}")
        appendLine("ssid: ${value.ssid ?: "none"}")
        appendLine("latched: ${if (value.latched) "yes" else "no"}")
    }

    fun history(values: List<CliSession>): String {
        if (values.isEmpty()) return "No sessions.\n"

        return buildString {
            appendLine("start\tend\trx-bytes\ttx-bytes\tmax-rx-bps\tmax-tx-bps")
            values.sortedByDescending(CliSession::start).forEach { session ->
                append(Instant.ofEpochMilli(session.start))
                append('\t')
                append(Instant.ofEpochMilli(session.end))
                append('\t')
                append(session.rx)
                append('\t')
                append(session.tx)
                append('\t')
                append(session.maxRx)
                append('\t')
                appendLine(session.maxTx)
            }
        }
    }

    fun settings(value: CliSettings): String = buildString {
        appendLine("auto-login: ${if (value.autoLogin) "on" else "off"}")
        appendLine("allowed-ssids: ${value.allowedSsids.sorted().joinToString(",")}")
    }
}
