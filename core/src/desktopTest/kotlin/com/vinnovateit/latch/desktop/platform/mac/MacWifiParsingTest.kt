package com.vinnovateit.latch.desktop.platform.mac

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The text formats the networksetup fallback reads. Fixtures are the shape
 * `networksetup -listallhardwareports` prints, including its trailing VLAN
 * section; the fallback can never report an SSID, so these only decide which
 * device is the Wi-Fi radio and whether it is on.
 */
class MacWifiParsingTest {

    private val laptop = """

        Hardware Port: Ethernet Adapter (en4)
        Device: en4
        Ethernet Address: 1e:00:00:00:00:04

        Hardware Port: Wi-Fi
        Device: en0
        Ethernet Address: 3c:00:00:00:00:00

        Hardware Port: Thunderbolt Bridge
        Device: bridge0
        Ethernet Address: 36:00:00:00:00:00

        VLAN Configurations
        ===================
    """.trimIndent()

    @Test
    fun `the Wi-Fi port is found among other ports`() {
        assertEquals(listOf("en0"), parseWifiDevices(laptop))
    }

    @Test
    fun `the older AirPort name is a Wi-Fi port`() {
        val older = """
            Hardware Port: Ethernet
            Device: en0
            Ethernet Address: 00:00:00:00:00:01

            Hardware Port: AirPort
            Device: en1
            Ethernet Address: 00:00:00:00:00:02
        """.trimIndent()

        assertEquals(listOf("en1"), parseWifiDevices(older))
    }

    @Test
    fun `Ethernet-only hardware has no Wi-Fi device`() {
        val desktop = """
            Hardware Port: Ethernet
            Device: en0
            Ethernet Address: 00:00:00:00:00:01

            Hardware Port: Thunderbolt Ethernet Slot 1
            Device: en1
            Ethernet Address: 00:00:00:00:00:02

            Hardware Port: USB 10/100/1000 LAN
            Device: en5
            Ethernet Address: 00:00:00:00:00:03
        """.trimIndent()

        assertEquals(emptyList(), parseWifiDevices(desktop))
    }

    @Test
    fun `a Wi-Fi port name is matched whole, not as a substring`() {
        val lookalikes = """
            Hardware Port: Wi-Fi Ethernet Dongle
            Device: en7
            Ethernet Address: 00:00:00:00:00:07

            Hardware Port: AirPort Express Bridge
            Device: en8
            Ethernet Address: 00:00:00:00:00:08
        """.trimIndent()

        assertEquals(emptyList(), parseWifiDevices(lookalikes))
    }

    @Test
    fun `a Wi-Fi port without a device, or with a malformed one, yields nothing`() {
        val broken = """
            Hardware Port: Wi-Fi
            Ethernet Address: 00:00:00:00:00:00

            Hardware Port: Wi-Fi
            Device: en0; rm -rf /
        """.trimIndent()

        assertEquals(emptyList(), parseWifiDevices(broken))
    }

    @Test
    fun `a device line never leaks from a neighbouring Ethernet block`() {
        // The line-number parsing this replaces read "the line after Wi-Fi",
        // whatever block it belonged to.
        val wifiBlockWithoutDevice = """
            Hardware Port: Wi-Fi

            Hardware Port: Ethernet
            Device: en0
        """.trimIndent()

        assertEquals(emptyList(), parseWifiDevices(wifiBlockWithoutDevice))
    }

    @Test
    fun `empty and unrelated output yields nothing`() {
        assertEquals(emptyList(), parseWifiDevices(""))
        assertEquals(emptyList(), parseWifiDevices("** Error: unable to list hardware ports"))
    }

    @Test
    fun `airport power is read only from the documented forms`() {
        assertEquals(true, parseAirportPower("Wi-Fi Power (en0): On"))
        assertEquals(false, parseAirportPower("Wi-Fi Power (en0): Off"))
        assertEquals(true, parseAirportPower("AirPort Power (en1): On\n"))
        assertNull(parseAirportPower("en5 is not a Wi-Fi interface.\n** Error: Error obtaining wireless information."))
        assertNull(parseAirportPower(""))
    }

    @Test
    fun `the router is accepted only as a bare IPv4 address`() {
        assertEquals("10.0.0.1", parseRouter("10.0.0.1\n"))
        assertNull(parseRouter(""))
        assertNull(parseRouter("get option failed"))
        assertNull(parseRouter("10.0.0.256"))
    }
}
