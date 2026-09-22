package com.vinnovateit.latch.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.ui.chrome.AppMenuActions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The title-bar hamburger must be a real toggle: open when closed, close when open,
 * and leave outside clicks to the popup.
 *
 * These tests drive the button with a mouse sequence (`moveTo` + `press` + `release`)
 * rather than the semantics-level `performClick`. That is deliberate: the popup is
 * non-focusable, the popup layer is dispatched before the title bar, and the toggle
 * relies on the pointer actually being over the anchor. `performClick` injects a
 * press/release with no hover/enter at all, so it does not represent a pointer and
 * would not exercise the ordering this fixes.
 *
 * Observed order for a click on the hamburger while the menu is open:
 *
 * ```text
 * onDismissRequest -> anchor Press -> anchor Release -> onClick
 * ```
 *
 * which is why the popup's dismissal is ignored while the pointer is on the anchor.
 */
class TitleBarMenuToggleTest {

    @get:Rule
    val compose = createComposeRule()

    private var settingsActions = 0
    private var aboutActions = 0

    private fun menuVisible(): Boolean =
        compose.onAllNodesWithText("About").fetchSemanticsNodes().isNotEmpty()

    private fun launchButton() {
        compose.setContent {
            // Deliberately much larger than the button and its menu so the "outside"
            // click lands clear of both.
            Box(Modifier.size(600.dp, 400.dp).testTag("outside")) {
                Box(Modifier.size(56.dp, 40.dp)) {
                    TitleBarMenuButton(
                        AppMenuActions(
                            showSettings = true,
                            onOpenSettings = { settingsActions++ },
                            onHowItWorks = {},
                            onOpenAbout = { aboutActions++ },
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun clickHamburger() {
        compose.onNodeWithContentDescription("Menu").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        compose.waitForIdle()
    }

    private fun clickOutside() {
        compose.onNodeWithTag("outside").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        compose.waitForIdle()
    }

    @Test
    fun `the hamburger closes the menu and leaves it closed`() {
        launchButton()
        assertFalse(menuVisible(), "the menu starts closed")

        clickHamburger()
        assertTrue(menuVisible(), "the first click must open the menu")

        clickHamburger()
        assertFalse(menuVisible(), "the second click must close the menu and keep it closed")

        clickHamburger()
        assertTrue(menuVisible(), "and it must open again afterwards")
    }

    @Test
    fun `a click outside dismisses the menu`() {
        launchButton()
        clickHamburger()
        assertTrue(menuVisible())

        clickOutside()

        assertFalse(menuVisible(), "an outside click must dismiss the menu")
    }

    @Test
    fun `a menu item closes the menu and fires exactly one action`() {
        launchButton()
        clickHamburger()
        assertTrue(menuVisible())

        compose.onNodeWithText("About").performMouseInput {
            moveTo(center)
            press()
            release()
        }
        compose.waitForIdle()

        assertFalse(menuVisible(), "choosing an item must close the menu")
        assertEquals(1, aboutActions, "exactly one action per item click")
        assertEquals(0, settingsActions, "the other entries must not fire")
    }

    @Test
    fun `repeated open and close cycles stay stable`() {
        launchButton()
        repeat(5) { cycle ->
            clickHamburger()
            assertTrue(menuVisible(), "cycle $cycle: opens")
            clickHamburger()
            assertFalse(menuVisible(), "cycle $cycle: closes and stays closed")
        }
    }
}
