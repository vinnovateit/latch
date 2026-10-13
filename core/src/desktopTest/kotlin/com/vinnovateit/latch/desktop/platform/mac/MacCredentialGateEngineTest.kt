package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.BuildInfo
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.CredentialStore
import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.InMemoryKeyValueStore
import com.vinnovateit.latch.core.platform.KeyValueStore
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.core.platform.PlatformCapabilities
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.core.wifi.probeCampusNetwork
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The macOS credential gate, end to end: the real [LatchEngine] driving the
 * real [MacWifiPlatform], on a network that answers with a captive portal --
 * the one situation in which the engine would otherwise POST the stored
 * credentials. Only the Wi-Fi readings and the HTTP layer are scripted.
 *
 * Every case where the network cannot be positively named as VIT must end
 * with zero credential requests.
 */
class MacCredentialGateEngineTest {

    private val captivePortal = PortalTransport(online = false)

    @Test
    fun `on a known VIT SSID the portal login proceeds`() = withEngine(on("VIT-Hostel")) { engine, _ ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000)

        assertEquals(1, captivePortal.credentialPosts.get(), "exactly one login on VIT Wi-Fi")
        assertTrue(engine.isLatched.value)
    }

    @Test
    fun `on a known non-VIT SSID credentials are never sent`() = assertRefused(on("CafeNet"))

    @Test
    fun `when the SSID lookup returns nothing credentials are never sent`() =
        assertRefused(on(ssid = null, location = MacLocationAccess.Granted))

    @Test
    fun `when Location permission is denied credentials are never sent`() =
        assertRefused(on(ssid = null, location = MacLocationAccess.Denied))

    @Test
    fun `when Location permission is undecided or restricted credentials are never sent`() {
        assertRefused(on(ssid = null, location = MacLocationAccess.NotDetermined))
        assertRefused(on(ssid = null, location = MacLocationAccess.Restricted))
    }

    @Test
    fun `with Wi-Fi off credentials are never sent`() =
        assertRefused(MacWifiReading.PoweredOff("en0"), expected = ConnectionStatus.Reason.WifiOff)

    @Test
    fun `with no Wi-Fi interface there is no crash and no credential request`() =
        assertRefused(MacWifiReading.NoInterface, expected = ConnectionStatus.Reason.WifiOff)

    @Test
    fun `when the Wi-Fi read fails credentials are never sent`() =
        assertRefused(MacWifiReading.Unavailable("networksetup exit 10"), expected = ConnectionStatus.Reason.WifiOff)

    @Test
    fun `when the Wi-Fi API times out there is no crash and no credential request`() {
        val neverAnswers = CountDownLatch(1)
        try {
            val hung = TimeBoundedProbe({ neverAnswers.await(); on("VIT-Hostel") }, timeoutMillis = 100, threadName = "test-hung")
            withEngine(probe = hung) { engine, _ ->
                assertTrue(engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000), "the engine must not hang")
                assertEquals(0, captivePortal.credentialPosts.get())
                assertFalse(engine.isLatched.value)
            }
        } finally {
            neverAnswers.countDown()
        }
    }

    @Test
    fun `a hidden SSID with working internet is not latched as VIT`() {
        val online = PortalTransport(online = true)
        withEngine(on(ssid = null, location = MacLocationAccess.Denied), transport = online) { engine, wifi ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000)

            assertFalse(engine.isLatched.value, "real internet on an unnamed network is not 'latched'")
            assertFalse(
                probeCampusNetwork(wifi, online, NoOpLogger).latched,
                "the read-only probe (CLI status, logout) must agree",
            )
            assertEquals(0, online.credentialPosts.get())
        }
    }

    @Test
    fun `moving from VIT to a home network tears the session down without logging in again`() =
        withEngine(on("VIT-Hostel")) { engine, wifi ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000)
            assertTrue(engine.isLatched.value)
            val onVit = wifi.snapshot(fresh = true).key
            val probesOnVit = captivePortal.probes.get()

            // A different network, behind its own captive portal.
            captivePortal.joinNewNetwork()
            (wifi.probeUnderTest as ScriptedProbe).reading = on("Home-5G")
            wifi.linksUnderTest.addresses = mapOf("en0" to "192.168.1.20")

            awaitTrue("session torn down after leaving VIT") { !engine.isLatched.value }
            assertNotEquals(onVit, wifi.snapshot(fresh = true).key, "the network change must change the fingerprint")
            awaitTrue("the home network's portal was evaluated") { captivePortal.probes.get() > probesOnVit }
            // A user-initiated attempt on the home network is refused too.
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000)
            assertEquals(1, captivePortal.credentialPosts.get(), "no credentials on the home network")
            assertFalse(engine.isLatched.value)
        }

    @Test
    fun `moving from a home network to VIT logs in normally`() =
        withEngine(on("Home-5G")) { engine, wifi ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000)
            assertEquals(0, captivePortal.credentialPosts.get())

            (wifi.probeUnderTest as ScriptedProbe).reading = on("VIT-Hostel")
            wifi.linksUnderTest.addresses = mapOf("en0" to "10.20.30.40")

            awaitTrue("logged in after joining VIT") { engine.isLatched.value }
            assertEquals(1, captivePortal.credentialPosts.get())
        }

    // --- harness -------------------------------------------------------------

    private fun assertRefused(
        reading: MacWifiReading,
        expected: ConnectionStatus.Reason = ConnectionStatus.Reason.NotTargetNetwork,
    ) = withEngine(reading) { engine, _ ->
        val before = captivePortal.credentialPosts.get()
        assertTrue(engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000), "the engine must not hang")

        assertEquals(before, captivePortal.credentialPosts.get(), "credentials were sent for $reading")
        assertFalse(engine.isLatched.value)
        val status = engine.status.value
        assertTrue(
            status is ConnectionStatus.Failed && status.reason == expected,
            "expected Failed($expected) for $reading, got $status",
        )
    }

    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting: $what", e)
        }
    }

    private class GateWifi(
        val probeUnderTest: MacWifiProbe,
        val linksUnderTest: ScriptedLinks,
        delegate: MacWifiPlatform,
    ) : WifiPlatform by delegate {
        private val mac = delegate
        fun snapshot(fresh: Boolean) = mac.snapshot(fresh)
    }

    private fun withEngine(
        reading: MacWifiReading? = null,
        probe: MacWifiProbe = ScriptedProbe(reading ?: MacWifiReading.NoInterface),
        transport: PortalTransport = captivePortal,
        block: suspend (LatchEngine, GateWifi) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-mac-gate-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val links = ScriptedLinks(mapOf("en0" to "10.20.30.40"), gateway = "10.20.0.1")
        val mac = MacWifiPlatform(
            logger = NoOpLogger,
            probe = probe,
            links = links,
            power = RecordingPower(accept = false),
            pollIntervalMillis = 20,
        )
        val wifi = GateWifi(probe, links, mac)
        val engine = LatchEngine(
            platform = GatePlatform(mac, transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(NoCounters)),
        )
        try {
            engine.start()
            block(engine, wifi)
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }
}

/**
 * A captive portal ([online] false: every probe is redirected until a login
 * succeeds) or an open network ([online] true). Counts credential POSTs.
 */
private class PortalTransport(private val online: Boolean) : HttpTransport {
    val credentialPosts = AtomicInteger()
    val probes = AtomicInteger()
    private val loggedIn = AtomicBoolean(false)

    /** A different network: its portal has not seen a login yet. */
    fun joinNewNetwork() = loggedIn.set(false)

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val target = url.toString()
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = when {
                target.contains("generate_204") -> {
                    probes.incrementAndGet()
                    if (online || loggedIn.get()) 204 else 302
                }
                target.contains("authlogin") -> {
                    credentialPosts.incrementAndGet()
                    loggedIn.set(true)
                    200
                }
                else -> 200
            }
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream("<html>ok</html>".toByteArray())
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        }
    }
}

private object NoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class GatePlatform(
    override val wifi: WifiPlatform,
    override val httpTransport: HttpTransport,
) : PlatformServices {
    override val logger: Logger = NoOpLogger
    override val buildInfo: BuildInfo = object : BuildInfo {
        override val versionName = "test"
        override val isDebug = true
        override val isInstalled = false
    }
    override val capabilities: PlatformCapabilities = object : PlatformCapabilities {
        override val supportsAutostart = false
    }
    override val settingsStore: KeyValueStore = InMemoryKeyValueStore()
    override val credentials: CredentialStore = object : CredentialStore {
        override fun save(userId: String, password: String) = Result.success(Unit)
        override fun userId(): String = "00AAA0000"
        override fun password(): String = "not-a-real-password"
        override fun exists(): Boolean = true
        override fun clear() = Result.success(Unit)
    }
    override val counters: ByteCounterSource = NoCounters
    override val notifier: UserNotifier = object : UserNotifier {
        override fun notifyTransient(title: String, text: String, isError: Boolean) = Unit
    }
    override val systemActions: SystemActions = object : SystemActions {
        override fun openWifiSettings() = Unit
        override fun openUrl(url: String) = Unit
        override fun setAutostart(enabled: Boolean) = Unit
        override fun isAutostartEnabled(): Boolean = false
    }
}
