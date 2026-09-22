package com.vinnovateit.latch.ui.onboarding

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.ui.LatchRoot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * One end-to-end regression for the state handoff that produced the reported bug.
 *
 * [DesktopOnboardingGateTest] pins the gate itself with the onboarding screen in
 * isolation. What that cannot see is the seam between the screens: LatchRoot owns
 * `hasCredentials`, `editingCredentials` and the shared `onboardingPagerState`, and
 * the credentials screen is what flips the first of those. This walks the whole
 * path through the real production composables:
 *
 *     onboarding -> blocked at the credentials slide -> credentials screen
 *     -> save -> back to the SAME pager -> gate released -> finish
 *
 * Nothing here touches the network, the portal or a real credential store.
 */
class DesktopOnboardingFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val credentials = FakeCredentialStore()
    private val settingsStore = InMemoryKeyValueStore()
    private lateinit var platform: FakePlatformServices
    private lateinit var sessions: SessionRepository
    private lateinit var engine: LatchEngine

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

    private fun launchRoot() {
        compose.setContent {
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
            )
        }
    }

    /** Taps the forward control whatever affordance it wears, never FINISH. */
    private fun tapNext() {
        for (description in listOf(NEXT, BLOCKED)) {
            val nodes = compose.onAllNodesWithContentDescription(description)
            if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                nodes.onFirst().performClick()
                compose.waitForIdle()
                return
            }
        }
    }

    private fun forwardControlCount(description: String): Int =
        compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

    @Test
    fun `configuring credentials mid-onboarding releases the gate and lets setup finish`() {
        assertFalse(credentials.exists(), "the flow must start with no credentials")
        launchRoot()

        // --- walk to the credentials slide -------------------------------
        repeat(CREDENTIALS_PAGE) { tapNext() }
        compose.onNodeWithText(SET_UP_CREDENTIALS).assertExists()

        // --- the gate holds ----------------------------------------------
        repeat(EXTRA_TAPS) { tapNext() }
        compose.onNodeWithText(SET_UP_CREDENTIALS).assertExists()
        assertEquals(1, forwardControlCount(BLOCKED), "forward control should read as blocked")
        assertEquals(0, forwardControlCount(NEXT), "a blocked gate must not offer a live Next")

        // --- provision credentials ---------------------------------------
        compose.onNodeWithText(SET_UP_CREDENTIALS).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(SAVE_CREDENTIALS).assertExists()

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(TEST_REG_NO)
        fields[1].performTextInput(TEST_PASSWORD)
        compose.waitForIdle()

        compose.onNodeWithText(SAVE_CREDENTIALS).performClick()
        compose.waitForIdle()

        // --- the store saw exactly what was typed -------------------------
        assertEquals(
            listOf(TEST_REG_NO to TEST_PASSWORD),
            credentials.saves,
            "the credential store must receive exactly what onboarding collected",
        )
        assertTrue(credentials.exists(), "the store must now report credentials present")

        // --- back on the SAME pager, still on the credentials slide -------
        compose.onNodeWithText(CREDENTIALS_CONFIGURED).assertExists()

        // --- gate released -------------------------------------------------
        assertEquals(1, forwardControlCount(NEXT), "the gate should be released once credentials exist")
        assertEquals(0, forwardControlCount(BLOCKED), "nothing should still read as blocked")

        // --- finish onboarding ---------------------------------------------
        repeat(LAST_PAGE - CREDENTIALS_PAGE) { tapNext() }
        compose.onNodeWithContentDescription(FINISH).performClick()
        compose.waitForIdle()

        assertTrue(
            SettingsManager.hasSeenOnboarding.value,
            "finishing the last slide must complete onboarding",
        )
        compose.onNodeWithText(YOURE_READY).assertDoesNotExist()
    }

    private companion object {
        const val CREDENTIALS_PAGE = 3
        const val LAST_PAGE = 5
        const val EXTRA_TAPS = 3
        const val NEXT = "Next"
        const val FINISH = "Finish"
        const val BLOCKED = "Blocked"
        const val SET_UP_CREDENTIALS = "Set Up Credentials"
        const val CREDENTIALS_CONFIGURED = "Credentials Configured"
        const val SAVE_CREDENTIALS = "Save Credentials"
        const val YOURE_READY = "You're Ready!"

        // Synthetic, matching the local format validator only. Not a real account.
        const val TEST_REG_NO = "00AAA0000"
        const val TEST_PASSWORD = "not-a-real-password"
    }
}
