package com.vinnovateit.latch.core.engine

import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.core.domain.SessionRepository
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
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val PRONTO_REDIRECT = "http://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com"
private const val CAFE_REDIRECT = "http://login.cafe.example/portal"
private const val GATEWAY = "10.0.0.1"

/**
 * The checks that decide whether a captive portal gets the stored credentials,
 * and how the login gets through when the probe cannot.
 */
class LatchEnginePortalGateTest {

    @Test
    fun `a captive portal that does not point at Pronto gets no credentials`() =
        withEngine(PortalScript(probeRedirect = CAFE_REDIRECT), ssid = null) { engine, portal ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)

            assertEquals(emptyList(), portal.logins, "credentials went to a non-VIT portal")
            assertFailed(engine, ConnectionStatus.Reason.NotTargetNetwork)
        }

    @Test
    fun `a network whose name contains VIT behind another portal gets no credentials`() =
        withEngine(PortalScript(probeRedirect = CAFE_REDIRECT), ssid = "Vitality Gym") { engine, portal ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)

            assertEquals(emptyList(), portal.logins)
            assertFailed(engine, ConnectionStatus.Reason.NotTargetNetwork)
        }

    @Test
    fun `a probe blocked by DNS logs in through a gateway that fronts Pronto`() =
        withEngine(PortalScript(probeDnsBlocked = true, gatewayRedirect = PRONTO_REDIRECT, portalHostResolves = false)) { engine, portal ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000)

            assertEquals(listOf(GATEWAY), portal.logins, "the login should fall back to the gateway")
            assertTrue(engine.isLatched.value)
        }

    @Test
    fun `a probe blocked by DNS without a Pronto gateway reports the DNS failure`() =
        withEngine(PortalScript(probeDnsBlocked = true, gatewayRedirect = CAFE_REDIRECT)) { engine, portal ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)

            assertEquals(emptyList(), portal.logins)
            assertFailed(engine, ConnectionStatus.Reason.DnsResolutionFailed)
        }

    @Test
    fun `a refused password is sent once`() =
        withEngine(PortalScript(portalLogin = 200 to "<html>Invalid password</html>")) { engine, portal ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)

            assertEquals(listOf("phc.prontonetworks.com"), portal.logins, "a refusal must not be retried")
            assertFailed(engine, ConnectionStatus.Reason.LoginFailed)
        }

    @Test
    fun `an unrecognised portal answer is retried once through the gateway`() =
        withEngine(PortalScript(portalLogin = 500 to "error")) { engine, portal ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 15_000)

            assertEquals(listOf("phc.prontonetworks.com", GATEWAY), portal.logins)
            assertTrue(engine.isLatched.value)
        }

    @Test
    fun `after a sleep a Pronto redirect logs in again without waiting for three failures`() =
        assertProbesBeforeRelogin(sleep = true, expected = 2)

    @Test
    fun `without a sleep a Pronto redirect still waits for three failed health checks`() =
        assertProbesBeforeRelogin(sleep = false, expected = 4)

    /**
     * Latches, ends the session at the portal, then counts the redirected
     * probes until the next login: the health checks plus the probe of the
     * re-login itself.
     */
    private fun assertProbesBeforeRelogin(sleep: Boolean, expected: Int) {
        val clockOffset = AtomicLong(0)
        val script = PortalScript()
        withEngine(script, healthCheckIntervalMs = 150, wallClock = { System.currentTimeMillis() + clockOffset.get() }) { engine, portal ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
            assertTrue(engine.isLatched.value)

            portal.endSession()
            if (sleep) clockOffset.addAndGet(60_000)
            awaitTrue("re-login") { portal.logins.size == 2 }

            assertEquals(expected, portal.redirectsBeforeSecondLogin.get())
        }
    }

    private fun assertFailed(engine: LatchEngine, reason: ConnectionStatus.Reason) {
        assertFalse(engine.isLatched.value)
        val status = engine.status.value
        assertTrue(status is ConnectionStatus.Failed && status.reason == reason, "expected Failed($reason), got $status")
    }

    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting: $what", e)
        }
    }

    private fun withEngine(
        script: PortalScript,
        ssid: String? = "VIT-Hostel",
        healthCheckIntervalMs: Long = 60_000,
        wallClock: () -> Long = System::currentTimeMillis,
        block: suspend (LatchEngine, PortalScript) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-portal-gate-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val engine = LatchEngine(
            platform = GatePlatform(GateWifi(ssid), script),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(GateNoCounters)),
            healthCheckIntervalMs = healthCheckIntervalMs,
            wallClock = wallClock,
        )
        try {
            engine.start()
            block(engine, script)
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }
}

/**
 * A scripted network. The probe is redirected to [probeRedirect] (or fails DNS)
 * until a login is accepted. The gateway answers with [gatewayRedirect]. A
 * login to the portal host gets [portalLogin], one to the gateway gets 200.
 * [logins] lists the host of every credential POST.
 */
private class PortalScript(
    private val probeRedirect: String = PRONTO_REDIRECT,
    private val probeDnsBlocked: Boolean = false,
    private val gatewayRedirect: String = PRONTO_REDIRECT,
    private val portalHostResolves: Boolean = true,
    private val portalLogin: Pair<Int, String> = 200 to "<html>access granted</html>",
) : HttpTransport {
    val logins = CopyOnWriteArrayList<String>()
    val redirectsBeforeSecondLogin = AtomicInteger()
    private val loggedIn = AtomicBoolean(false)

    /** The portal ends the session, as it does while a laptop sleeps. */
    fun endSession() = loggedIn.set(false)

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val host = url.host
        val isProbe = url.toString().contains("generate_204")
        val isLogin = url.path.contains("authlogin")
        val isGatewayRoot = host == GATEWAY && url.path.trimEnd('/').isEmpty()
        return object : HttpURLConnection(url) {
            private var answer: Pair<Int, String>? = null

            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false

            override fun getResponseCode(): Int = respond().first

            override fun getHeaderField(name: String?): String? {
                if (!name.equals("Location", ignoreCase = true) || respond().first != 302) return null
                return if (isGatewayRoot) gatewayRedirect else probeRedirect
            }

            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(respond().second.toByteArray())
            override fun getErrorStream(): InputStream = ByteArrayInputStream(respond().second.toByteArray())

            private fun respond(): Pair<Int, String> = answer ?: when {
                isProbe -> when {
                    loggedIn.get() -> 204 to ""
                    probeDnsBlocked -> throw UnknownHostException("clients3.google.com")
                    else -> {
                        if (logins.size == 1) redirectsBeforeSecondLogin.incrementAndGet()
                        302 to ""
                    }
                }
                isGatewayRoot -> 302 to ""
                isLogin -> {
                    if (host != GATEWAY && !portalHostResolves) throw UnknownHostException(host)
                    logins += host
                    val reply = if (host == GATEWAY) 200 to "<html>access granted</html>" else portalLogin
                    if (reply.first == 200 && !reply.second.contains("Invalid")) loggedIn.set(true)
                    reply
                }
                else -> 200 to ""
            }.also { answer = it }
        }
    }
}

private object GateHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private class GateWifi(private val ssid: String?) : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String? = ssid
    override fun gatewayIp(): String = GATEWAY
    override fun activeHandle(): NetworkHandle = GateHandle
    override val events: Flow<WifiEvent> = emptyFlow()
}

private object GateNoCounters : ByteCounterSource {
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
    override val counters: ByteCounterSource = GateNoCounters
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
