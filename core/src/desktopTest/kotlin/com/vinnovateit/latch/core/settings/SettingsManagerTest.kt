package com.vinnovateit.latch.core.settings

import com.vinnovateit.latch.core.platform.InMemoryKeyValueStore
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsManagerTest {

    private lateinit var store: InMemoryKeyValueStore

    @BeforeTest
    fun setup() {
        store = InMemoryKeyValueStore()
        SettingsManager.initialize(store)
    }

    @Test
    fun `haptics is enabled by default`() {
        assertTrue(SettingsManager.hapticsEnabled.value)
    }

    @Test
    fun `setHapticsEnabled updates state and store`() {
        SettingsManager.setHapticsEnabled(false)
        assertFalse(SettingsManager.hapticsEnabled.value)
        assertFalse(store.getBoolean("haptics_enabled", true))

        SettingsManager.setHapticsEnabled(true)
        assertTrue(SettingsManager.hapticsEnabled.value)
        assertTrue(store.getBoolean("haptics_enabled", false))
    }

    @Test
    fun `clearAll resets haptics to default true`() {
        SettingsManager.setHapticsEnabled(false)
        assertFalse(SettingsManager.hapticsEnabled.value)

        SettingsManager.clearAll()
        assertTrue(SettingsManager.hapticsEnabled.value)
        assertTrue(store.getBoolean("haptics_enabled", false))
    }

    @Test
    fun `initialize loads persisted haptics setting`() {
        val populatedStore = InMemoryKeyValueStore()
        populatedStore.putBoolean("haptics_enabled", false)

        SettingsManager.initialize(populatedStore)
        assertFalse(SettingsManager.hapticsEnabled.value)
    }

    @Test
    fun `paletteStyle is TonalSpot by default`() {
        assertEquals("TonalSpot", SettingsManager.paletteStyle.value)
    }

    @Test
    fun `setPaletteStyle updates state and store`() {
        SettingsManager.setPaletteStyle("Expressive")
        assertEquals("Expressive", SettingsManager.paletteStyle.value)
        assertEquals("Expressive", store.getString("palette_style", ""))

        SettingsManager.setPaletteStyle("FruitSalad")
        assertEquals("FruitSalad", SettingsManager.paletteStyle.value)
        assertEquals("FruitSalad", store.getString("palette_style", ""))
    }

    @Test
    fun `clearAll resets paletteStyle to TonalSpot`() {
        SettingsManager.setPaletteStyle("Vibrant")
        assertEquals("Vibrant", SettingsManager.paletteStyle.value)

        SettingsManager.clearAll()
        assertEquals("TonalSpot", SettingsManager.paletteStyle.value)
        assertEquals("TonalSpot", store.getString("palette_style", ""))
    }

    @Test
    fun `clearAll resets every setting, not just the two it used to`() {
        SettingsManager.setAutoLogin(false)
        SettingsManager.setSpeedUnits("bits")
        SettingsManager.setTheme("Dark")
        SettingsManager.setUseDynamicColors(true)
        SettingsManager.setUsePureBlack(true)
        SettingsManager.setUseMonochrome(true)
        SettingsManager.setAccentColor("Pink")
        SettingsManager.setChartPalette("Ocean")
        SettingsManager.setPaletteStyle("Vibrant")
        SettingsManager.setAllowedSsids(setOf("Somewhere Else"))
        SettingsManager.setHasSeenOnboarding(true)
        SettingsManager.setHapticsEnabled(false)
        SettingsManager.setMinimizeToTray(true)

        SettingsManager.clearAll()

        // A reset that leaves most of the settings behind is worse than none:
        // callers believe they have a clean slate while the rest carries over.
        assertTrue(SettingsManager.autoLogin.value)
        assertEquals("bps", SettingsManager.speedUnits.value)
        assertEquals("System Default", SettingsManager.theme.value)
        assertFalse(SettingsManager.useDynamicColors.value)
        assertFalse(SettingsManager.usePureBlack.value)
        assertFalse(SettingsManager.useMonochrome.value)
        assertEquals("Red", SettingsManager.accentColor.value)
        assertEquals("Material Dynamic", SettingsManager.chartPalette.value)
        assertEquals("TonalSpot", SettingsManager.paletteStyle.value)
        assertFalse(SettingsManager.hasSeenOnboarding.value)
        assertEquals(setOf("VIT"), SettingsManager.allowedSsids.value)
        assertTrue(SettingsManager.hapticsEnabled.value)
        assertFalse(SettingsManager.minimizeToTray.value)

        // And the store, not only the flows.
        assertTrue(store.getBoolean("auto_login", false))
        assertEquals("System Default", store.getString("theme", ""))
        assertEquals("Red", store.getString("accent_color", ""))
        assertFalse(store.getBoolean("minimize_to_tray", true))
    }

    @Test
    fun `minimizeToTray is false by default`() {
        assertFalse(SettingsManager.minimizeToTray.value)
    }

    @Test
    fun `setMinimizeToTray updates state and store`() {
        SettingsManager.setMinimizeToTray(true)
        assertTrue(SettingsManager.minimizeToTray.value)
        assertTrue(store.getBoolean("minimize_to_tray", false))

        SettingsManager.setMinimizeToTray(false)
        assertFalse(SettingsManager.minimizeToTray.value)
        assertFalse(store.getBoolean("minimize_to_tray", true))
    }

    @Test
    fun `initialize loads persisted minimizeToTray setting`() {
        val populatedStore = InMemoryKeyValueStore()
        populatedStore.putBoolean("minimize_to_tray", true)

        SettingsManager.initialize(populatedStore)
        assertTrue(SettingsManager.minimizeToTray.value)
    }

    @Test
    fun `initialize loads persisted paletteStyle setting`() {
        val populatedStore = InMemoryKeyValueStore()
        populatedStore.putString("palette_style", "Rainbow")

        SettingsManager.initialize(populatedStore)
        assertEquals("Rainbow", SettingsManager.paletteStyle.value)
    }
}
