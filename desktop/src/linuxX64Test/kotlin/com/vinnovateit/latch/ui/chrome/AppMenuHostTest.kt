package com.vinnovateit.latch.ui.chrome

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The app-menu bridge still used by the native window shell, without the JVM Compose test runner. */
class AppMenuHostTest {

    private fun actions(showSettings: Boolean) = AppMenuActions(
        showSettings = showSettings,
        onOpenSettings = {},
        onHowItWorks = {},
        onOpenAbout = {},
    )

    @Test
    fun `the host starts with no menu`() {
        assertNull(AppMenuHost().actions)
    }

    @Test
    fun `publish makes the actions available and withdrawing removes them`() {
        val host = AppMenuHost()
        host.publish(actions(showSettings = true))

        assertNotNull(host.actions)
        assertTrue(host.actions!!.showSettings)

        host.publish(null)
        assertNull(host.actions, "a withdrawn menu must not keep stale entries")
    }

    @Test
    fun `republish replaces the menu so a dead Settings entry disappears`() {
        val host = AppMenuHost()
        host.publish(actions(showSettings = true))
        host.publish(actions(showSettings = false))

        assertNotNull(host.actions)
        assertFalse(host.actions!!.showSettings)
    }
}
