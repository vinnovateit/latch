package com.vinnovateit.latch.desktop.platform.windows

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsWifiFieldsTest {

    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    @Test
    fun `fields are read from their own positions`() {
        val fields = parseWifiResult("RESULT|${b64("Wi-Fi")}|1|1|${b64("G-VIT 5")}|10.0.0.1|wlan")

        assertEquals("Wi-Fi", fields.adapterName)
        assertEquals(true, fields.adapterUp)
        assertEquals(true, fields.radioOn)
        assertEquals("G-VIT 5", fields.ssid)
        assertEquals("10.0.0.1", fields.gateway)
        assertEquals("wlan", fields.ssidSource)
    }

    @Test
    fun `a separator inside a Wi-Fi name stays in the name`() {
        val fields = parseWifiResult("RESULT|${b64("Wi-Fi")}|1|1|${b64("Cafe|8.8.8.8|wlan")}|192.168.1.1|wlan")

        assertEquals("Cafe|8.8.8.8|wlan", fields.ssid)
        assertEquals("192.168.1.1", fields.gateway, "the network name must not choose the gateway")
    }

    @Test
    fun `non-ASCII names survive`() {
        assertEquals("Café", parseWifiResult("RESULT|${b64("Wi-Fi")}|1|1|${b64("Café")}||wlan").ssid)
    }

    @Test
    fun `empty and undecodable fields are unknown`() {
        val fields = parseWifiResult("RESULT||0||!!not-base64!!||")

        assertNull(fields.adapterName)
        assertEquals(false, fields.adapterUp)
        assertNull(fields.radioOn)
        assertNull(fields.ssid)
        assertNull(fields.gateway)
        assertNull(fields.ssidSource)
    }

    @Test
    fun `the connectivity level and the profile kind are read from their own positions`() {
        val fields = parseWifiResult(
            "RESULT|${b64("Wi-Fi")}|1|1|${b64("G-VIT 5")}|10.0.0.1|wlan|ConstrainedInternetAccess|1"
        )

        assertEquals(WindowsConnectivityLevel.ConstrainedInternetAccess, fields.connectivityLevel)
        assertEquals(true, fields.internetProfileIsWlan)
    }

    @Test
    fun `a missing connectivity level is unknown`() {
        val fields = parseWifiResult("RESULT|${b64("Wi-Fi")}|1|1|${b64("G-VIT 5")}|10.0.0.1|wlan")

        assertEquals(WindowsConnectivityLevel.Unknown, fields.connectivityLevel)
        assertEquals(false, fields.internetProfileIsWlan)
    }

    @Test
    fun `the level of another link is not the Wi-Fi's own`() {
        assertEquals(
            WindowsConnectivityLevel.ConstrainedInternetAccess,
            connectivityLevelOf(WindowsConnectivityLevel.ConstrainedInternetAccess, internetProfileIsWlan = true),
        )
        assertEquals(
            WindowsConnectivityLevel.Unknown,
            connectivityLevelOf(WindowsConnectivityLevel.ConstrainedInternetAccess, internetProfileIsWlan = false),
            "a docked machine's Ethernet level says nothing about the Wi-Fi",
        )
    }

    @Test
    fun `only the move into a captive portal is worth reporting`() {
        val now = 1_000_000L

        assertTrue(
            shouldReportConnectivityChange(
                previous = WindowsConnectivityLevel.InternetAccess,
                current = WindowsConnectivityLevel.ConstrainedInternetAccess,
                lastReportedAt = 0L,
                now = now,
            ),
            "Windows seeing a captive portal is worth a check",
        )
        assertFalse(
            shouldReportConnectivityChange(
                previous = WindowsConnectivityLevel.ConstrainedInternetAccess,
                current = WindowsConnectivityLevel.ConstrainedInternetAccess,
                lastReportedAt = 0L,
                now = now,
            ),
            "a level that stays put is not news",
        )
        assertFalse(
            shouldReportConnectivityChange(
                previous = WindowsConnectivityLevel.InternetAccess,
                current = WindowsConnectivityLevel.LocalAccess,
                lastReportedAt = 0L,
                now = now,
            ),
            "only a captive portal is reported",
        )
    }

    @Test
    fun `a captive portal is not reported again inside the cooldown`() {
        val now = 1_000_000L

        assertFalse(
            shouldReportConnectivityChange(
                previous = WindowsConnectivityLevel.InternetAccess,
                current = WindowsConnectivityLevel.ConstrainedInternetAccess,
                lastReportedAt = now - CHANGED_EVENT_COOLDOWN_MS + 1,
                now = now,
            ),
            "a flapping level must not turn into a burst of checks",
        )
        assertTrue(
            shouldReportConnectivityChange(
                previous = WindowsConnectivityLevel.InternetAccess,
                current = WindowsConnectivityLevel.ConstrainedInternetAccess,
                lastReportedAt = now - CHANGED_EVENT_COOLDOWN_MS,
                now = now,
            ),
        )
    }
}
