package com.vinnovateit.latch.cli

import java.io.File

internal interface CommandExecutor {
    fun execute(command: List<String>): OperationResult<Unit>
}

internal class ProcessCommandExecutor : CommandExecutor {
    override fun execute(command: List<String>): OperationResult<Unit> = runCatching {
        val process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            return OperationResult(error = "${command.first()} exited with code $exitCode.")
        }
        OperationResult(Unit)
    }.getOrElse { OperationResult(error = it.message ?: "Unable to update login startup.") }
}

internal class LinuxLoginStartup(configDirectory: File) : LoginStartup {
    private val entry = configDirectory.resolve("autostart/latch-cli.desktop")

    override fun enable(command: List<String>): OperationResult<Unit> = runCatching {
        require(command.isNotEmpty()) { "Daemon command is empty." }
        entry.parentFile?.mkdirs()
        entry.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=Latch CLI
            Comment=Automatic VIT Wi-Fi login
            Exec=${command.joinToString(" ", transform = ::desktopQuote)}
            Terminal=false
            X-GNOME-Autostart-enabled=true
            Categories=Network;
            """.trimIndent() + "\n",
        )
        OperationResult(Unit)
    }.getOrElse { OperationResult(error = it.message ?: "Unable to enable login startup.") }

    override fun disable(): OperationResult<Unit> = runCatching {
        if (entry.exists() && !entry.delete()) error("Unable to remove ${entry.absolutePath}.")
        OperationResult(Unit)
    }.getOrElse { OperationResult(error = it.message ?: "Unable to disable login startup.") }
}

internal class WindowsLoginStartup(
    private val executor: CommandExecutor = ProcessCommandExecutor(),
) : LoginStartup {
    override fun enable(command: List<String>): OperationResult<Unit> {
        if (command.isEmpty()) return OperationResult(error = "Daemon command is empty.")
        return executor.execute(
            listOf(
                "reg.exe", "ADD", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d",
                command.joinToString(" ", transform = ::windowsQuote), "/f",
            ),
        )
    }

    override fun disable(): OperationResult<Unit> = executor.execute(
        listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", REMOVE_RUN_VALUE_SCRIPT),
    )

    private companion object {
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val RUN_VALUE = "Latch CLI"
        const val REMOVE_RUN_VALUE_SCRIPT =
            "\$ErrorActionPreference = 'Stop'; " +
                "\$key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey(" +
                "'Software\\Microsoft\\Windows\\CurrentVersion\\Run', \$true); " +
                "if (\$null -ne \$key) { try { " +
                "if (\$null -ne \$key.GetValue('Latch CLI', \$null, " +
                "[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)) { " +
                "\$key.DeleteValue('Latch CLI', \$false) } } finally { \$key.Dispose() } }"
    }
}

private fun desktopQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

private fun windowsQuote(value: String): String = "\"${value.replace("\"", "\\\"")}\""
