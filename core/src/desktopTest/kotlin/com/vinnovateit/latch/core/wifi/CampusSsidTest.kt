package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.InMemoryKeyValueStore
import com.vinnovateit.latch.core.settings.SettingsManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The campus-SSID rule on its own, independent of DNS: the engine tests that
 * exercise it end to end can only tell a fail-open gate apart where the
 * portal host resolves, so the rule is pinned here as well.
 */
class CampusSsidTest {

    @BeforeTest
    fun setUp() {
        SettingsManager.initialize(InMemoryKeyValueStore())
    }

    @Test
    fun `a readable campus SSID qualifies either way`() {
        assertTrue(isVitCampusSsid("VIT-Hostel"))
        assertTrue(isVitCampusSsid("VIT-Hostel", unknownIsCampus = false))
    }

    @Test
    fun `a readable non-campus SSID never qualifies`() {
        assertFalse(isVitCampusSsid("CafeNet"))
        assertFalse(isVitCampusSsid("CafeNet", unknownIsCampus = false))
    }

    @Test
    fun `an unknown SSID keeps its historical meaning by default`() {
        assertTrue(isVitCampusSsid(null), "Android, Windows and Linux are unchanged")
        assertTrue(isVitCampusSsid("  "))
    }

    @Test
    fun `an unknown SSID never qualifies where the platform requires a known one`() {
        assertFalse(isVitCampusSsid(null, unknownIsCampus = false))
        assertFalse(isVitCampusSsid("", unknownIsCampus = false))
        assertFalse(isVitCampusSsid("  ", unknownIsCampus = false))
        assertFalse(isVitCampusSsid("\"\"", unknownIsCampus = false))
    }

    @Test
    fun `working internet on an unnamed network is latched only where unknown is allowed`() {
        assertTrue(CampusNetworkState(connected = true, online = true, ssid = null).latched)
        assertFalse(CampusNetworkState(connected = true, online = true, ssid = null, requiresKnownSsid = true).latched)
        assertTrue(CampusNetworkState(connected = true, online = true, ssid = "VIT-Hostel", requiresKnownSsid = true).latched)
    }
}
