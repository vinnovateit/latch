package com.vinnovateit.latch.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.ui.onboarding.FakeCredentialStore
import com.vinnovateit.latch.ui.onboarding.FakePlatformServices
import com.vinnovateit.latch.ui.onboarding.InMemoryKeyValueStore
import com.vinnovateit.latch.ui.theme.LatchTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The home top bar's pill always shows the session state. Neither state dismisses itself. */
class HomeTopBarPillTest {

    @get:Rule
    val compose = createComposeRule()

    private var latched by mutableStateOf(false)

    /** Well past the 3.5s after which the pill used to dismiss itself. */
    private val longAfter = 60_000L

    @Before
    fun setUp() {
        val store = InMemoryKeyValueStore()
        Platform.install(FakePlatformServices(FakeCredentialStore(), store))
        SettingsManager.initialize(store)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LatchTheme {
                LatchHomeTopBar(isLatched = latched)
            }
        }
    }

    @Test
    fun `the disconnected pill stays`() {
        compose.mainClock.advanceTimeBy(longAfter)

        compose.onNodeWithText("DISCONNECTED").assertIsDisplayed()
    }

    @Test
    fun `the latched pill stays until the session disconnects`() {
        latched = true
        compose.mainClock.advanceTimeBy(longAfter)
        compose.onNodeWithText("LATCHED").assertIsDisplayed()

        latched = false
        compose.mainClock.advanceTimeBy(longAfter)
        compose.onNodeWithText("DISCONNECTED").assertIsDisplayed()
        compose.onNodeWithText("LATCHED").assertDoesNotExist()
    }
}
