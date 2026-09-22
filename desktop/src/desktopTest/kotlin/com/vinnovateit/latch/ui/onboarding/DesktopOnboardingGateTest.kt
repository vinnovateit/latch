package com.vinnovateit.latch.ui.onboarding

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The desktop onboarding used to enforce "credentials are required" only on the
 * final slide, by disabling the finish button. Nothing stopped the user walking
 * straight past the credentials slide, so they landed on "You're Ready!" -- a
 * page whose own copy says setup is complete -- in front of a greyed-out
 * checkmark that silently swallowed every click, with nothing saying why.
 *
 * The gate belongs on the credentials slide, where the "Set Up Credentials"
 * button is in reach. These tests pin that down.
 */
class DesktopOnboardingGateTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var pagerState: PagerState
    private var completions = 0

    private fun launchOnboarding(hasCredentials: Boolean) =
        launchOnboarding(mutableStateOf(hasCredentials))

    private fun launchOnboarding(hasCredentials: MutableState<Boolean>) {
        compose.setContent {
            pagerState = rememberPagerState(initialPage = 0, pageCount = { PAGE_COUNT })
            DesktopOnboardingScreen(
                hasCredentials = hasCredentials.value,
                onComplete = { completions++ },
                onNavigateToCredentials = {},
                pagerState = pagerState,
            )
        }
    }

    /**
     * Taps the forward control, whichever affordance it currently wears. Never
     * taps FINISH, so a test that is supposed to be stuck cannot accidentally
     * complete onboarding. A no-op if the control is missing entirely.
     */
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

    @Test
    fun `onboarding cannot advance past the credentials slide without credentials`() {
        launchOnboarding(hasCredentials = false)

        repeat(CREDENTIALS_PAGE) { tapNext() }
        assertEquals(CREDENTIALS_PAGE, pagerState.currentPage, "expected to reach the credentials slide")

        repeat(EXTRA_TAPS) { tapNext() }

        assertEquals(
            CREDENTIALS_PAGE,
            pagerState.currentPage,
            "Onboarding advanced past the credentials slide with no credentials stored. " +
                "That strands the user on the final slide behind a dead finish button.",
        )
    }

    @Test
    fun `a blocked forward button never looks like a working one`() {
        launchOnboarding(hasCredentials = false)

        repeat(CREDENTIALS_PAGE) { tapNext() }
        assertEquals(CREDENTIALS_PAGE, pagerState.currentPage, "expected to reach the credentials slide")

        // The reported bug was a greyed checkmark that read as a live finish
        // control. Blocked must be its own affordance, not a dimmed arrow/check.
        compose.onNodeWithContentDescription(BLOCKED).assertIsDisplayed()
        compose.onAllNodesWithContentDescription(NEXT).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(FINISH).assertCountEquals(0)
    }

    @Test
    fun `the pager cannot be swiped past the credentials slide`() {
        val credentials = mutableStateOf(false)
        launchOnboarding(credentials)

        repeat(CREDENTIALS_PAGE) { tapNext() }
        assertEquals(CREDENTIALS_PAGE, pagerState.currentPage, "expected to reach the credentials slide")

        // Gating only the button would leave the swipe as a second way around
        // it. userScrollEnabled=false strips the pager's scroll action, so there
        // is no gesture target at all while the gate holds.
        compose.onAllNodes(hasScrollAction()).assertCountEquals(0)

        credentials.value = true
        compose.waitForIdle()

        assertTrue(
            compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty(),
            "the pager should become swipeable again once credentials exist",
        )
    }

    @Test
    fun `the gate releases as soon as credentials are configured`() {
        val credentials = mutableStateOf(false)
        launchOnboarding(credentials)

        repeat(CREDENTIALS_PAGE + EXTRA_TAPS) { tapNext() }
        assertEquals(CREDENTIALS_PAGE, pagerState.currentPage, "expected to be held at the credentials slide")

        credentials.value = true
        compose.waitForIdle()

        repeat(LAST_PAGE - CREDENTIALS_PAGE) { tapNext() }
        assertEquals(LAST_PAGE, pagerState.currentPage, "expected to reach the final slide once credentials exist")
    }

    @Test
    fun `the finish button completes onboarding exactly once`() {
        launchOnboarding(hasCredentials = true)

        repeat(LAST_PAGE) { tapNext() }
        assertEquals(LAST_PAGE, pagerState.currentPage, "expected to reach the final slide")

        compose.onNodeWithContentDescription(FINISH).performClick()
        compose.waitForIdle()

        assertEquals(1, completions, "the final slide must complete onboarding on a single tap")
    }

    private companion object {
        const val PAGE_COUNT = 6
        const val CREDENTIALS_PAGE = 3
        const val LAST_PAGE = PAGE_COUNT - 1
        const val EXTRA_TAPS = 3
        const val NEXT = "Next"
        const val FINISH = "Finish"
        const val BLOCKED = "Blocked"
    }
}
