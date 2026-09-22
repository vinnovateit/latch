package com.vinnovateit.latch.ui.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.ui.LatchRoot
import com.vinnovateit.latch.ui.onboarding.FakeCredentialStore
import com.vinnovateit.latch.ui.onboarding.FakePlatformServices
import com.vinnovateit.latch.ui.onboarding.FakeStatsDao
import com.vinnovateit.latch.ui.onboarding.InMemoryKeyValueStore
import com.vinnovateit.latch.ui.onboarding.ZeroByteCounters
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The window chrome renders the application menu, but the application decides
 * what it contains and whether it exists at all. That contract is [AppMenuHost],
 * and this pins its behaviour.
 *
 * [com.vinnovateit.latch.desktop.LatchWindow] itself is not exercised here: it
 * builds a real AWT window through `androidx.compose.ui.window.Window`, which
 * needs an `ApplicationScope` and a display, so it cannot be rendered by
 * `createComposeRule`. What is testable, and what actually carries the risk, is
 * everything below it: what the application publishes, and when.
 */
class AppMenuContractTest {

    @get:Rule
    val compose = createComposeRule()

    private val credentials = FakeCredentialStore()
    private val settingsStore = InMemoryKeyValueStore()
    private lateinit var platform: FakePlatformServices
    private lateinit var sessions: SessionRepository
    private lateinit var engine: LatchEngine
    private val host = AppMenuHost()

    @Before
    fun setUp() {
        platform = FakePlatformServices(credentials, settingsStore)
        Platform.install(platform)
        SettingsManager.initialize(settingsStore)
        sessions = SessionRepository(
            statsDao = FakeStatsDao(),
            throughput = ThroughputMonitor(ZeroByteCounters),
            activeHandle = { null },
        )
        engine = LatchEngine(platform, sessions)
    }

    /** [width] decides whether the nav rail is showing, which the Settings item keys off. */
    private fun launchRoot(width: Int = NARROW) {
        compose.setContent {
            Box(Modifier.size(width.dp, 800.dp)) {
                LatchRoot(
                    controller = engine,
                    sessions = sessions,
                    platform = platform,
                    updateState = UpdateState.Idle,
                    onCheckForUpdates = {},
                    onDownloadUpdate = {},
                    onCancelDownload = {},
                    onInstallUpdate = {},
                    onDismissUpdate = {},
                    appMenu = host,
                )
            }
        }
    }

    private fun reachMainShell() {
        credentials.save("00AAA0000", "not-a-real-password")
        SettingsManager.setHasSeenOnboarding(true)
    }

    @Test
    fun `no menu is offered during first-run onboarding`() {
        launchRoot()
        assertNull(host.actions, "onboarding must not expose Settings, About or How it works")
    }

    @Test
    fun `no menu is offered during credential setup`() {
        SettingsManager.setHasSeenOnboarding(true)
        launchRoot()
        assertNull(host.actions, "the credentials gate must not expose the app menu")
    }

    @Test
    fun `the menu appears once the main shell is mounted`() {
        reachMainShell()
        launchRoot()
        assertNotNull(host.actions, "the main shell should publish an app menu")
    }

    @Test
    fun `the How it works action shows the dialog`() {
        reachMainShell()
        launchRoot()

        compose.runOnUiThread { host.actions!!.onHowItWorks() }
        compose.waitForIdle()

        compose.onNodeWithText("How Latch works").assertExists()
    }

    @Test
    fun `the About action opens About and withdraws the menu`() {
        reachMainShell()
        launchRoot()

        compose.runOnUiThread { host.actions!!.onOpenAbout() }
        compose.waitForIdle()

        compose.onNodeWithText("About").assertExists()
        assertNull(host.actions, "About is not the main shell, so the menu should be withdrawn")
    }

    @Test
    fun `Settings is offered on narrow layouts and drops once it is the destination`() {
        reachMainShell()
        launchRoot(width = NARROW)

        assertTrue(host.actions!!.showSettings, "no rail at this width, so the menu must offer Settings")

        compose.runOnUiThread { host.actions!!.onOpenSettings() }
        compose.waitForIdle()

        assertNotNull(host.actions, "Settings is part of the main shell, so the menu stays")
        assertFalse(
            host.actions!!.showSettings,
            "offering Settings while already on Settings is a dead entry",
        )
    }

    @Test
    fun `Settings is not duplicated in the menu when the rail provides it`() {
        reachMainShell()
        launchRoot(width = WIDE)

        assertNotNull(host.actions)
        assertFalse(host.actions!!.showSettings, "the rail already offers Settings at this width")
    }

    private companion object {
        /** Either side of LatchRoot's 900dp rail breakpoint. */
        const val NARROW = 400
        const val WIDE = 1100
    }
}
