package com.vinnovateit.latch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import com.vinnovateit.latch.ui.onboarding.FakeCredentialStore
import com.vinnovateit.latch.ui.onboarding.FakePlatformServices
import com.vinnovateit.latch.ui.onboarding.FakeStatsDao
import com.vinnovateit.latch.ui.onboarding.InMemoryKeyValueStore
import com.vinnovateit.latch.ui.onboarding.ZeroByteCounters
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Back from the main shell's Stats and Settings, through the real [LatchRoot].
 *
 * The back arrow used to be dropped whenever the navigation rail was showing
 * (900 dp and wider), leaving wide desktop windows with no Back on either
 * screen. It now always returns to Home. The nested and gated screens keep
 * their own rules: Session History returns to Stats, and first-run credential
 * setup offers no way around it.
 */
class LatchRootBackNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private val appMenu = AppMenuHost()

    private fun launchRoot(
        width: Dp,
        credentials: FakeCredentialStore = FakeCredentialStore(TEST_REG_NO to TEST_PASSWORD),
        hasSeenOnboarding: Boolean = true,
    ) {
        val settingsStore = InMemoryKeyValueStore()
        val platform = FakePlatformServices(credentials, settingsStore)
        Platform.install(platform)
        SettingsManager.initialize(settingsStore)
        SettingsManager.setHasSeenOnboarding(hasSeenOnboarding)
        val sessions = SessionRepository(
            statsDao = FakeStatsDao(),
            throughput = ThroughputMonitor(ZeroByteCounters),
            activeHandle = { null },
        )
        val engine = LatchEngine(platform, sessions)
        compose.setContent {
            Box(Modifier.requiredSize(width, HEIGHT)) {
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
                    appMenu = appMenu,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun backCount() = compose.onAllNodesWithContentDescription(BACK).fetchSemanticsNodes().size

    private fun tapBack() {
        assertEquals(1, backCount(), "expected exactly one Back control")
        compose.onNodeWithContentDescription(BACK).performClick()
        compose.waitForIdle()
    }

    private fun assertOnHome() {
        compose.onNodeWithContentDescription(HOME_MARKER).assertExists()
        assertEquals(0, backCount(), "Home has nowhere to go back to")
    }

    private fun assertOnStats() = compose.onNodeWithContentDescription(STATS_MARKER).assertExists()

    private fun assertOnSettings() = compose.onNodeWithText(SETTINGS_MARKER).assertExists()

    /** Through the rail, which is only there at [WIDE]. */
    private fun openFromRail(label: String) {
        compose.onNodeWithContentDescription(label, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    // --- wide: the rail is showing ------------------------------------------

    @Test
    fun `wide Stats offers Back and it returns Home`() {
        launchRoot(WIDE)
        openFromRail("Stats")
        assertOnStats()

        tapBack()

        assertOnHome()
    }

    @Test
    fun `wide Settings offers Back and it returns Home`() {
        launchRoot(WIDE)
        openFromRail("Settings")
        assertOnSettings()

        tapBack()

        assertOnHome()
    }

    // --- narrow: no rail, reached from Home and the app menu ----------------

    @Test
    fun `narrow Stats still offers Back to Home`() {
        launchRoot(NARROW)
        compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(HOME_STATS_ENTRY).performClick()
        compose.waitForIdle()
        assertOnStats()

        tapBack()

        assertOnHome()
    }

    @Test
    fun `narrow Settings still offers Back to Home`() {
        launchRoot(NARROW)
        compose.runOnIdle { appMenu.actions!!.onOpenSettings() }
        compose.waitForIdle()
        assertOnSettings()

        tapBack()

        assertOnHome()
    }

    // --- nested and gated screens keep their own back rules -----------------

    @Test
    fun `Session History goes back to Stats, not Home`() {
        launchRoot(WIDE)
        openFromRail("Stats")
        compose.onNodeWithContentDescription(STATS_MARKER).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SESSION_HISTORY).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SESSION_HISTORY).assertExists()

        tapBack()

        assertOnStats()
        compose.onNodeWithText(SESSION_HISTORY).assertDoesNotExist()
        compose.onNodeWithContentDescription(HOME_MARKER).assertDoesNotExist()
    }

    @Test
    fun `editing credentials from Settings goes back to Settings`() {
        launchRoot(WIDE)
        openFromRail("Settings")
        compose.onAllNodesWithText(UPDATE_CREDENTIALS)[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SETTINGS_MARKER).assertDoesNotExist()

        tapBack()

        assertOnSettings()
    }

    @Test
    fun `mandatory credential setup has no Back and no way into the shell`() {
        launchRoot(WIDE, credentials = FakeCredentialStore())

        compose.onNodeWithText(SAVE_CREDENTIALS).assertExists()
        assertEquals(0, backCount(), "first-run credential setup must not be skippable")
        compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(HOME_MARKER).assertDoesNotExist()
        assertNull(appMenu.actions, "no app menu while credentials are required")
    }

    @Test
    fun `onboarding offers no Back into the shell`() {
        launchRoot(WIDE, credentials = FakeCredentialStore(), hasSeenOnboarding = false)

        assertEquals(0, backCount(), "onboarding must not offer a way around itself")
        compose.onNodeWithContentDescription("Stats", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(HOME_MARKER).assertDoesNotExist()
        assertNull(appMenu.actions, "no app menu during onboarding")
    }

    private companion object {
        /** Above LatchRoot's 900 dp rail breakpoint, and inside the 1024x768 test window. */
        val WIDE = 1000.dp
        val NARROW = 700.dp
        val HEIGHT = 760.dp

        const val BACK = "Back"
        const val HOME_MARKER = "Connect"
        const val STATS_MARKER = "More options"
        const val SETTINGS_MARKER = "Auto-login"
        const val HOME_STATS_ENTRY = "STATS"
        const val SESSION_HISTORY = "Session History"
        const val UPDATE_CREDENTIALS = "Update Credentials"
        const val SAVE_CREDENTIALS = "Save Credentials"

        // Synthetic, matching the local format validator only. Not a real account.
        const val TEST_REG_NO = "00AAA0000"
        const val TEST_PASSWORD = "not-a-real-password"
    }
}
