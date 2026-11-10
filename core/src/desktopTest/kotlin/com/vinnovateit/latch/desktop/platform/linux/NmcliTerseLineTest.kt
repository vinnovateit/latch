package com.vinnovateit.latch.desktop.platform.linux

import kotlin.test.Test
import kotlin.test.assertEquals

class NmcliTerseLineTest {

    @Test
    fun `plain fields are split on the separator`() {
        assertEquals(listOf("yes", "G-VIT 5", "wlan0", "wifi"), parseNmcliTerseLine("yes:G-VIT 5:wlan0:wifi"))
    }

    @Test
    fun `an escaped colon stays inside its field`() {
        assertEquals(listOf("yes", "Hostel:Block A", "wlan0", "wifi"), parseNmcliTerseLine("""yes:Hostel\:Block A:wlan0:wifi"""))
    }

    @Test
    fun `an escaped backslash does not escape the separator after it`() {
        assertEquals(listOf("yes", """net\""", "wlan0"), parseNmcliTerseLine("""yes:net\\:wlan0"""))
    }

    @Test
    fun `empty fields are kept`() {
        assertEquals(listOf("no", "", "wlan0", ""), parseNmcliTerseLine("no::wlan0:"))
    }

    @Test
    fun `a dev show value keeps its colons`() {
        assertEquals("Dorm:VIT 5", nmcliDeviceShowValue("GENERAL.CONNECTION:Dorm:VIT 5"))
    }

    @Test
    fun `a dev show value keeps its backslashes`() {
        assertEquals("""Block\A""", nmcliDeviceShowValue("""GENERAL.CONNECTION:Block\A"""))
    }
}
