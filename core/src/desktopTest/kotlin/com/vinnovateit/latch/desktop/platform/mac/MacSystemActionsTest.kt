package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.desktop.platform.BoundedCommandRunner
import com.vinnovateit.latch.desktop.platform.CommandOutcome
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Start at login as a LaunchAgent, in a temporary directory standing in for
 * ~/Library/LaunchAgents. On macOS the generated plist is also checked with
 * Apple's own `plutil`.
 */
class MacSystemActionsTest {
    private lateinit var home: File
    private lateinit var agents: File
    private val isMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
    private val installed = "/Applications/Latch.app/Contents/MacOS/Latch"

    @BeforeTest
    fun setUp() {
        home = createTempDirectory("latch-launchagents-").toFile()
        agents = File(home, "Library/LaunchAgents")
    }

    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun actions(executable: String?) = MacSystemActions(NoOpLogger, agents, { executable })

    private val plist get() = File(agents, "${MacSystemActions.LAUNCH_AGENT_LABEL}.plist")

    @Test
    fun `enabling writes a LaunchAgent that starts the installed app hidden`() {
        val actions = actions(installed)

        actions.setAutostart(true)

        assertTrue(actions.isAutostartEnabled())
        val text = plist.readText()
        assertTrue(text.contains("<string>$installed</string>"))
        assertTrue(text.contains("<string>--hidden</string>"))
        assertTrue(text.contains("<key>RunAtLoad</key>"))
        assertFalse(File(home, ".config/autostart").exists(), "no XDG autostart on macOS")
    }

    @Test
    fun `disabling removes it`() {
        val actions = actions(installed)
        actions.setAutostart(true)

        actions.setAutostart(false)

        assertFalse(actions.isAutostartEnabled())
        assertFalse(plist.exists())
    }

    @Test
    fun `a non-installed build never registers itself`() {
        actions(null).setAutostart(true)
        actions("/usr/bin/java").setAutostart(true)
        actions("/opt/homebrew/bin/latch").setAutostart(true)

        assertFalse(plist.exists(), "only an executable inside a .app bundle may be started at login")
    }

    @Test
    fun `a translocated copy is not registered`() {
        actions("/private/var/folders/xy/T/AppTranslocation/ABC/d/Latch.app/Contents/MacOS/Latch").setAutostart(true)

        assertFalse(plist.exists())
    }

    @Test
    fun `paths are escaped in the plist`() {
        val odd = "/Users/a&b/Applications/<Latch>.app/Contents/MacOS/Latch"
        val text = MacSystemActions.launchAgentPlist(odd)

        assertTrue(text.contains("/Users/a&amp;b/Applications/&lt;Latch&gt;.app/Contents/MacOS/Latch"))
        assertFalse(text.contains("a&b"))
    }

    @Test
    fun `the plist is valid for launchd on macOS`() {
        if (!isMac) return
        actions(installed).setAutostart(true)

        val lint = BoundedCommandRunner().run(listOf("/usr/bin/plutil", "-lint", plist.path))

        assertTrue(lint is CommandOutcome.Succeeded, lint.toString())
    }

    @Test
    fun `web links and the Wi-Fi pane go to open, anything else never does`() {
        val commands = mutableListOf<List<String>>()
        val actions = MacSystemActions(NoOpLogger, agents, { installed }) { commands += it; CommandOutcome.Succeeded("", "") }

        actions.openUrl("file:///Applications/Calculator.app")
        actions.openUrl("/Applications/Calculator.app")
        actions.openUrl("-a Terminal")
        assertEquals(emptyList(), commands, "a non-web target would make `open` launch something local")

        actions.openUrl("https://github.com/vinnovateit/latch/releases/tag/v1.5.0")
        actions.openWifiSettings()
        assertEquals(
            listOf(
                listOf("/usr/bin/open", "https://github.com/vinnovateit/latch/releases/tag/v1.5.0"),
                listOf("/usr/bin/open", "x-apple.systempreferences:com.apple.wifi-settings-extension"),
            ),
            commands,
        )
    }

    @Test
    fun `the Wi-Fi settings fall back to the older pane, and a failure is bounded`() {
        val commands = mutableListOf<List<String>>()
        val actions = MacSystemActions(NoOpLogger, agents, { installed }) { commands += it; CommandOutcome.TimedOut }

        actions.openWifiSettings()

        assertEquals(MacSystemActions.WIFI_SETTINGS.map { listOf("/usr/bin/open", it) }, commands)
    }
}
