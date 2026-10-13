package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.desktop.platform.BoundedCommandRunner
import com.vinnovateit.latch.desktop.platform.CommandOutcome
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The native seams on a real Mac: CoreWLAN, CoreLocation, `networksetup` and
 * the Keychain through Security.framework. Off macOS they return early,
 * unless LATCH_MAC_NATIVE_TESTS=1 says this run is the macOS CI job, where
 * skipping would hide a broken binding and so fails instead.
 *
 * The Keychain test writes to the login Keychain, so it runs only under that
 * same variable -- never by accident on a developer's Mac -- and uses a
 * unique, test-only service name that it always removes.
 */
class MacNativeIntegrationTest {

    private val isMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
    private val required = System.getenv("LATCH_MAC_NATIVE_TESTS") == "1"

    /** True when this test should run; fails when it is required but cannot. */
    private fun onMac(): Boolean {
        if (isMac) return true
        if (required) fail("LATCH_MAC_NATIVE_TESTS=1 but this is not macOS (${System.getProperty("os.name")})")
        return false
    }

    @Test
    fun `CoreWLAN loads and answers within its deadline`() {
        if (!onMac()) return
        val started = System.nanoTime()

        val reading = CoreWlanProbe.create(timeoutMillis = 5_000).read()

        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("CoreWLAN reading: $reading (${elapsedMs} ms)")
        assertTrue(elapsedMs < 10_000, "bounded read took $elapsedMs ms")
        if (reading is MacWifiReading.Unavailable) fail("CoreWLAN read failed on a real Mac: ${reading.reason}")
    }

    @Test
    fun `Location access is readable without prompting`() {
        if (!onMac()) return
        val access = MacLocationServices.access()
        println("Location access: $access")
        assertTrue(access in MacLocationAccess.entries)
    }

    @Test
    fun `the real platform never reports a network name macOS did not give it`() {
        if (!onMac()) return
        val wifi = MacWifiPlatform(NoOpLogger)
        val snap = wifi.snapshot(fresh = true)
        println("Mac Wi-Fi snapshot: $snap")

        assertTrue(wifi.requiresKnownSsid)
        val reading = snap.reading
        if (snap.ssid != null) {
            assertIs<MacWifiReading.PoweredOn>(reading)
            assertEquals(reading.ssid?.trim(), snap.ssid)
        }
        if (!snap.connected) assertEquals(null, wifi.currentSsid())
    }

    @Test
    fun `networksetup on this Mac never yields an Ethernet device as Wi-Fi`() {
        if (!onMac()) return
        val outcome = BoundedCommandRunner().run(listOf("/usr/sbin/networksetup", "-listallhardwareports"))
        assertIs<CommandOutcome.Succeeded>(outcome, outcome.toString())
        println(outcome.stdout)

        val wifiDevices = parseWifiDevices(outcome.stdout)
        val nonWifiDevices = outcome.stdout.split(Regex("\n\\s*\n")).mapNotNull { block ->
            val port = Regex("Hardware Port: (.*)").find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val device = Regex("Device: (.*)").find(block)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            device.takeIf { port.lowercase() !in setOf("wi-fi", "airport") }
        }
        assertTrue(wifiDevices.none { it in nonWifiDevices }, "wifi=$wifiDevices other=$nonWifiDevices")

        // Where CoreWLAN reports a radio, the fallback must agree on which one it is.
        val reading = CoreWlanProbe.create(timeoutMillis = 5_000).read()
        val coreWlanName = when (reading) {
            is MacWifiReading.PoweredOn -> reading.interfaceName
            is MacWifiReading.PoweredOff -> reading.interfaceName
            else -> null
        }
        if (coreWlanName != null) assertTrue(coreWlanName in wifiDevices, "$coreWlanName not in $wifiDevices")
    }

    @Test
    fun `the Keychain stores, replaces and removes a credential item`() {
        if (!onMac() || !required) return
        val service = "com.vinnovateit.latch.ci-test.${UUID.randomUUID()}"
        val item = SecurityFrameworkKeychainItem(service, "ci-test-account")
        try {
            assertEquals(KeychainItem.Read.Missing, item.read())

            assertEquals(ERR_SEC_SUCCESS, item.write("first".toByteArray()))
            assertContentEquals("first".toByteArray(), assertIs<KeychainItem.Read.Found>(item.read()).secret)

            assertEquals(ERR_SEC_SUCCESS, item.write("second".toByteArray()), "an existing item is updated, not duplicated")
            assertContentEquals("second".toByteArray(), assertIs<KeychainItem.Read.Found>(item.read()).secret)

            // The item is really in the Keychain, as macOS's own tool sees it.
            val listed = BoundedCommandRunner().run(listOf("/usr/bin/security", "find-generic-password", "-s", service))
            assertIs<CommandOutcome.Succeeded>(listed, listed.toString())

            val store = MacKeychainCredentialStore(item, NoOpLogger)
            assertTrue(store.save("00AAA0000", "not-a-real-password").isSuccess)
            assertEquals("not-a-real-password", MacKeychainCredentialStore(item, NoOpLogger).password())
            assertTrue(store.clear().isSuccess)

            assertEquals(KeychainItem.Read.Missing, item.read())
            assertEquals(ERR_SEC_SUCCESS, item.delete(), "deleting what is already gone is not an error")
        } finally {
            item.delete()
        }
    }
}
