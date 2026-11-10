package com.vinnovateit.latch.desktop.platform.windows

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
