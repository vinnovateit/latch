package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.NoOpLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Runs the Wi-Fi query's real PowerShell output line on made-up adapter and
 * network names, through the same runner the app uses, and parses what it
 * prints. [WindowsWifiFieldsTest] covers the parser alone; this covers the
 * PowerShell encoding feeding it.
 */
class WindowsWifiScriptTest {

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    /** A PowerShell single-quoted literal. PowerShell also reads curly single quotes as quotes. */
    private fun literal(value: String) =
        "'" + value.replace(Regex("['‘’‚‛]")) { it.value + it.value } + "'"

    private fun query(name: String, ssid: String, gateway: String = "192.168.1.1"): WindowsWifiFields {
        val script = listOf(
            "\$name = ${literal(name)}",
            "\$up = '1'",
            "\$radio = '1'",
            "\$ssid = ${literal(ssid)}",
            "\$gw = ${literal(gateway)}",
            "\$src = 'wlan'",
            PS_WIFI_RESULT_LINE,
        ).joinToString("\n")
        val line = assertNotNull(runPowerShellForResult(script, NoOpLogger), "PowerShell printed no RESULT line")
        return parseWifiResult(line)
    }

    @Test
    fun `a separator in the network name stays in the name`() {
        if (!isWindows) return
        val fields = query(name = "Wi-Fi 2", ssid = "Cafe|8.8.8.8|wlan")

        assertEquals("Wi-Fi 2", fields.adapterName)
        assertEquals(true, fields.adapterUp)
        assertEquals(true, fields.radioOn)
        assertEquals("Cafe|8.8.8.8|wlan", fields.ssid)
        assertEquals("192.168.1.1", fields.gateway, "the network name must not choose the gateway")
        assertEquals("wlan", fields.ssidSource)
    }

    @Test
    fun `non-ASCII and quoted names come through unchanged`() {
        if (!isWindows) return
        assertEquals("Café ☕ 日本", query(name = "Wi-Fi", ssid = "Café ☕ 日本").ssid)
        assertEquals("Krish's iPhone", query(name = "Wi-Fi", ssid = "Krish's iPhone").ssid)
        assertEquals("WLAN | Intel®", query(name = "WLAN | Intel®", ssid = "VIT").adapterName)
    }

    @Test
    fun `an empty network name is unknown`() {
        if (!isWindows) return
        val fields = query(name = "Wi-Fi", ssid = "", gateway = "")

        assertEquals("Wi-Fi", fields.adapterName)
        assertNull(fields.ssid)
        assertNull(fields.gateway)
    }
}
