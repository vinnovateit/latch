package com.vinnovateit.latch.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemCliLifecycleTest {
    @Test
    fun `daemon child does not inherit jpackage internal launcher marker`() {
        val environment = mutableMapOf("_JPACKAGE_LAUNCHER" to "0", "PATH" to "/usr/bin")

        sanitizeDaemonEnvironment(environment)

        assertEquals(mapOf("PATH" to "/usr/bin"), environment)
    }

    @Test
    fun `packaged launcher command starts hidden daemon process`() {
        assertEquals(
            OperationResult(listOf("/opt/latch-cli/bin/latch-cli", "--daemon-process")),
            resolveDaemonCommand("/opt/latch-cli/bin/latch-cli", "ignored"),
        )
    }

    @Test
    fun `development java command preserves runtime and classpath`() {
        assertEquals(
            OperationResult(
                listOf(
                    "/usr/lib/jvm/bin/java",
                    "-cp",
                    "cli.jar:core.jar",
                    "com.vinnovateit.latch.cli.MainKt",
                    "--daemon-process",
                ),
            ),
            resolveDaemonCommand("/usr/lib/jvm/bin/java", "cli.jar:core.jar"),
        )
    }

    @Test
    fun `windows daemon command launches without a visible console`() {
        assertEquals(
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-Command",
                "& 'C:\\Program Files\\Latch CLI\\latch-cli.exe' '--daemon-process'",
            ),
            windowsHiddenCommand(listOf("C:\\Program Files\\Latch CLI\\latch-cli.exe", "--daemon-process")),
        )
    }
}
