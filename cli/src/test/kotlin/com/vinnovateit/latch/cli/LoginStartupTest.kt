package com.vinnovateit.latch.cli

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginStartupTest {
    @Test
    fun `linux startup writes and removes a distinct XDG desktop entry`() {
        val directory = createTempDirectory("latch-cli-autostart-").toFile()
        val startup = LinuxLoginStartup(directory)
        val command = listOf("/opt/Latch CLI/latch-cli", "--daemon-process")

        assertEquals(OperationResult(Unit), startup.enable(command))

        val entry = directory.resolve("autostart/latch-cli.desktop")
        assertTrue(entry.isFile)
        assertTrue(entry.readText().contains("Name=Latch CLI"))
        assertTrue(entry.readText().contains("Exec=\"/opt/Latch CLI/latch-cli\" \"--daemon-process\""))
        assertTrue(entry.readText().contains("Terminal=false"))

        assertEquals(OperationResult(Unit), startup.disable())
        assertFalse(entry.exists())
        directory.deleteRecursively()
    }

    @Test
    fun `windows startup uses a distinct per-user Run value`() {
        val executor = RecordingCommandExecutor()
        val startup = WindowsLoginStartup(executor)

        assertEquals(
            OperationResult(Unit),
            startup.enable(listOf("C:\\Program Files\\Latch CLI\\latch-cli.exe", "--daemon-process")),
        )
        assertEquals(OperationResult(Unit), startup.disable())

        assertEquals(
            listOf(
                "reg.exe", "ADD", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "Latch CLI", "/t", "REG_SZ", "/d",
                "\"C:\\Program Files\\Latch CLI\\latch-cli.exe\" \"--daemon-process\"", "/f",
            ),
            executor.commands[0],
        )
        assertEquals(
            listOf(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "${'$'}ErrorActionPreference = 'Stop'; " +
                    "${'$'}key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey(" +
                    "'Software\\Microsoft\\Windows\\CurrentVersion\\Run', ${'$'}true); " +
                    "if (${'$'}null -ne ${'$'}key) { try { " +
                    "if (${'$'}null -ne ${'$'}key.GetValue('Latch CLI', ${'$'}null, " +
                    "[Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)) { " +
                    "${'$'}key.DeleteValue('Latch CLI', ${'$'}false) } } finally { ${'$'}key.Dispose() } }",
            ),
            executor.commands[1],
        )
    }

    @Test
    fun `windows startup propagates registry removal failures`() {
        val executor = RecordingCommandExecutor(
            result = OperationResult(error = "Registry access was denied."),
        )

        val result = WindowsLoginStartup(executor).disable()

        assertEquals(OperationResult<Unit>(error = "Registry access was denied."), result)
    }
}

private class RecordingCommandExecutor(
    private val result: OperationResult<Unit> = OperationResult(Unit),
) : CommandExecutor {
    val commands = mutableListOf<List<String>>()

    override fun execute(command: List<String>): OperationResult<Unit> {
        commands += command
        return result
    }
}
