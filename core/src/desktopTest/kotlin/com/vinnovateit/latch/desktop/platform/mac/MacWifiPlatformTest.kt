package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.desktop.platform.BoundedCommandRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * [MacWifiPlatform] on scripted readings: what each reading means to the
 * engine, and that every way of not knowing the network's name comes out as
 * "no SSID" on a platform that requires one.
 */
class MacWifiPlatformTest {
    private lateinit var directory: File
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("latch-mac-wifi-").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun platform(
        probe: MacWifiProbe,
        links: ScriptedLinks = ScriptedLinks(mapOf("en0" to "10.1.2.3"), gateway = "10.1.0.1"),
        power: RecordingPower = RecordingPower(),
    ) = MacWifiPlatform(
        logger = NoOpLogger,
        probe = probe,
        links = links,
        power = power,
        pollIntervalMillis = 10,
        enableSettle = MacWifiPlatform.EnableSettle(attempts = 1, intervalMillis = 1),
    )

    @Test
    fun `macOS never accepts an unknown SSID as a campus network`() {
        assertTrue(platform(ScriptedProbe(MacWifiReading.NoInterface)).requiresKnownSsid)
    }

    @Test
    fun `the name counts as hidden only while Location access withholds it`() {
        assertTrue(platform(ScriptedProbe(on(ssid = null, location = MacLocationAccess.Denied))).isNetworkNameHidden())
        assertTrue(platform(ScriptedProbe(on(ssid = null, location = MacLocationAccess.NotDetermined))).isNetworkNameHidden())
        assertFalse(platform(ScriptedProbe(on(ssid = null, location = MacLocationAccess.Granted))).isNetworkNameHidden())
        assertFalse(platform(ScriptedProbe(on("VIT-Hostel"))).isNetworkNameHidden())
        assertFalse(
            platform(ScriptedProbe(on(ssid = null, location = MacLocationAccess.Denied)), links = ScriptedLinks()).isNetworkNameHidden(),
            "not connected, so there is no name to hide",
        )
    }

    @Test
    fun `connections bind to the Wi-Fi address while Wi-Fi is connected`() {
        val wifi = platform(ScriptedProbe(on("VIT-Hostel")))
        assertEquals("10.1.2.3", wifi.localAddress(null)?.hostAddress)
        assertEquals("10.1.2.3", wifi.localAddress(MacNetworkHandle("en0"))?.hostAddress)

        assertNull(platform(ScriptedProbe(on("VIT-Hostel")), links = ScriptedLinks()).localAddress(null))
        assertNull(platform(ScriptedProbe(MacWifiReading.PoweredOff("en0"))).localAddress(null))
    }

    @Test
    fun `a connected network reports its actual SSID, gateway and handle`() {
        val wifi = platform(ScriptedProbe(on("VIT-Hostel")))

        assertTrue(wifi.isWifiEnabled())
        assertTrue(wifi.isConnectedToWifi())
        assertEquals("VIT-Hostel", wifi.currentSsid())
        assertEquals("10.1.0.1", wifi.gatewayIp())
        assertEquals("en0", wifi.activeHandle()?.id)
    }

    @Test
    fun `Wi-Fi off is off, with nothing connected`() {
        val wifi = platform(ScriptedProbe(MacWifiReading.PoweredOff("en0")))

        assertFalse(wifi.isWifiEnabled())
        assertFalse(wifi.isConnectedToWifi())
        assertNull(wifi.currentSsid())
        assertNull(wifi.activeHandle())
    }

    @Test
    fun `no Wi-Fi interface is a safe, disconnected state`() {
        val wifi = platform(ScriptedProbe(MacWifiReading.NoInterface))

        assertFalse(wifi.isWifiEnabled())
        assertFalse(wifi.isConnectedToWifi())
        assertNull(wifi.currentSsid())
        assertNull(wifi.wifiInterfaceName())
    }

    @Test
    fun `a hidden SSID is null whatever the reason, even when connected`() {
        MacLocationAccess.entries.forEach { access ->
            val wifi = platform(ScriptedProbe(on(ssid = null, location = access)))
            assertTrue(wifi.isConnectedToWifi(), "connected by address ($access)")
            assertNull(wifi.currentSsid(), "no name may be invented ($access)")
        }
    }

    @Test
    fun `a blank SSID is no SSID`() {
        assertNull(platform(ScriptedProbe(on("   "))).currentSsid())
    }

    @Test
    fun `an associated radio without an address is not connected and names nothing`() {
        val wifi = platform(ScriptedProbe(on("VIT-Hostel")), links = ScriptedLinks())

        assertTrue(wifi.isWifiEnabled())
        assertFalse(wifi.isConnectedToWifi())
        assertNull(wifi.currentSsid())
        assertNull(wifi.activeHandle())
    }

    @Test
    fun `a failed read is reported as not connected, never as the last good state`() {
        val probe = ScriptedProbe(on("VIT-Hostel"))
        val wifi = platform(probe)
        assertEquals("VIT-Hostel", wifi.snapshot(fresh = true).ssid)

        probe.reading = MacWifiReading.Unavailable("permission denied")

        val snap = wifi.snapshot(fresh = true)
        assertFalse(snap.connected)
        assertNull(snap.ssid)
        assertFalse(snap.enabled)
    }

    @Test
    fun `a Wi-Fi API that never answers times out into an unknown state`() {
        val neverAnswers = CountDownLatch(1)
        val bounded = TimeBoundedProbe(
            delegate = { neverAnswers.await(); on("VIT-Hostel") },
            timeoutMillis = 100,
            threadName = "test-hung-probe",
        )
        val wifi = platform(bounded)

        try {
            val started = System.nanoTime()
            assertNull(wifi.currentSsid())
            assertFalse(wifi.isConnectedToWifi())
            assertIs<MacWifiReading.Unavailable>(wifi.snapshot(fresh = true).reading)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "reads must stay bounded while the API hangs")
        } finally {
            neverAnswers.countDown()
        }
    }

    @Test
    fun `a networksetup that hangs times out into an unknown state`() {
        if (isWindows) return
        val hung = File(directory, "networksetup").apply {
            writeText("#!/bin/sh\nexec sleep 60\n")
            setExecutable(true)
        }
        val wifi = platform(NetworksetupWifiProbe(BoundedCommandRunner(timeoutMillis = 200), hung.path))

        assertIs<MacWifiReading.Unavailable>(wifi.snapshot(fresh = true).reading)
        assertFalse(wifi.isConnectedToWifi())
        assertNull(wifi.currentSsid())
    }

    @Test
    fun `the networksetup fallback finds the radio but can never name the network`() {
        if (isWindows) return
        val tool = File(directory, "networksetup").apply {
            writeText(
                """
                #!/bin/sh
                case "${'$'}1" in
                  -listallhardwareports) printf 'Hardware Port: Ethernet\nDevice: en1\n\nHardware Port: Wi-Fi\nDevice: en0\n' ;;
                  -getairportpower) echo "Wi-Fi Power (${'$'}2): On" ;;
                  *) exit 4 ;;
                esac
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        val wifi = platform(NetworksetupWifiProbe(BoundedCommandRunner(timeoutMillis = 5_000), tool.path))

        val reading = wifi.snapshot(fresh = true).reading
        assertEquals(on(ssid = null, location = MacLocationAccess.Unknown, iface = "en0"), reading)
        assertTrue(wifi.isConnectedToWifi())
        assertNull(wifi.currentSsid())
    }

    @Test
    fun `the fingerprint changes when the network changes, named or not`() {
        val probe = ScriptedProbe(on("VIT-Hostel"))
        val links = ScriptedLinks(mapOf("en0" to "10.1.2.3"))
        val wifi = platform(probe, links)
        val vit = wifi.snapshot(fresh = true).key

        probe.reading = on("CafeNet")
        val cafe = wifi.snapshot(fresh = true).key
        assertNotEquals(vit, cafe)

        // Both names hidden: the new address still tells the networks apart.
        probe.reading = on(ssid = null, location = MacLocationAccess.Denied)
        val hiddenA = wifi.snapshot(fresh = true).key
        links.addresses = mapOf("en0" to "192.168.1.20")
        val hiddenB = wifi.snapshot(fresh = true).key
        assertNotEquals(hiddenA, hiddenB)

        probe.reading = MacWifiReading.PoweredOff("en0")
        assertNull(wifi.snapshot(fresh = true).key, "disconnected has no fingerprint")
    }

    /** Answers [readings] in order, one per read, then keeps answering the last. */
    private class SequenceProbe(private vararg val readings: MacWifiReading) : MacWifiProbe {
        private var next = 0
        @Synchronized override fun read(): MacWifiReading = readings[minOf(next++, readings.size - 1)]
    }

    @Test
    fun `a switch between networks is reported as lost, then available`() = runBlocking {
        val wifi = platform(SequenceProbe(on("VIT-Hostel"), on("VIT-Hostel"), on("CafeNet")))

        val events = withTimeout(5_000) { wifi.events.take(2).toList() }

        assertEquals(listOf(WifiEvent.Lost(MacNetworkHandle("en0")), WifiEvent.Available(MacNetworkHandle("en0"))), events)
    }

    @Test
    fun `a disconnect and a reconnect are both observed`() = runBlocking {
        val wifi = platform(
            SequenceProbe(on("VIT-Hostel"), MacWifiReading.PoweredOff("en0"), MacWifiReading.PoweredOff("en0"), on("VIT-Hostel")),
        )

        val events = withTimeout(5_000) { wifi.events.take(2).toList() }

        assertEquals(listOf(WifiEvent.Lost(MacNetworkHandle("en0")), WifiEvent.Available(MacNetworkHandle("en0"))), events)
    }

    @Test
    fun `Wi-Fi is switched on only when it is positively off`() {
        val off = RecordingPower()
        platform(ScriptedProbe(MacWifiReading.PoweredOff("en0")), power = off).enableWifi()
        assertEquals(listOf("en0"), off.requests)

        // Unreadable is not "off": the radio is never toggled blind.
        val unknown = RecordingPower()
        assertFalse(platform(ScriptedProbe(MacWifiReading.Unavailable("timeout")), power = unknown).enableWifi())
        assertEquals(emptyList(), unknown.requests)

        val already = RecordingPower()
        assertTrue(platform(ScriptedProbe(on("VIT-Hostel")), power = already).enableWifi())
        assertEquals(emptyList(), already.requests)
    }
}
