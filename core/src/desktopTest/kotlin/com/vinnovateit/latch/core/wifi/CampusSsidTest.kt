package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.InMemoryKeyValueStore
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.core.settings.SettingsManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    fun `every real campus network name qualifies`() {
        listOf("G-VIT", "G-VIT 5", "E-VIT", "VIT5", "VIT2.4", "VIT2.4G", "VIT5G", "vit-hostel", "\"G-VIT\"").forEach {
            assertTrue(isVitCampusSsid(it, unknownIsCampus = false), it)
        }
    }

    @Test
    fun `a longer word containing VIT does not qualify`() {
        listOf("INVITE", "ACTIVITY", "VITAMIN", "Gravity Cafe", "Levitate5").forEach {
            assertFalse(isVitCampusSsid(it, unknownIsCampus = false), it)
        }
    }

    @Test
    fun `custom allowed networks match as whole names too`() {
        SettingsManager.setAllowedSsids(setOf("VIT", "Hostel-Net", "  "))
        assertTrue(isVitCampusSsid("Hostel-Net 5G", unknownIsCampus = false))
        assertFalse(isVitCampusSsid("MyHostel-Network", unknownIsCampus = false))
        assertFalse(isVitCampusSsid("CafeNet", unknownIsCampus = false), "a blank entry must not match everything")
    }

    @Test
    fun `only a named campus Wi-Fi network is known to be campus`() {
        assertTrue(isKnownCampusNetwork(wifi(ssid = "G-VIT")))
        assertFalse(isKnownCampusNetwork(wifi(ssid = null)), "an unreadable name is never enough")
        assertFalse(isKnownCampusNetwork(wifi(ssid = "CafeNet")))
        assertFalse(isKnownCampusNetwork(wifi(ssid = "G-VIT", connected = false)))
    }

    private fun wifi(ssid: String?, connected: Boolean = true) = object : WifiPlatform {
        override fun isWifiEnabled() = true
        override fun isConnectedToWifi() = connected
        override fun currentSsid() = ssid
        override fun gatewayIp(): String? = null
        override fun activeHandle(): NetworkHandle? =
            if (connected) object : NetworkHandle { override val id = "wlan0" } else null
        override val events: Flow<WifiEvent> = emptyFlow()
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
