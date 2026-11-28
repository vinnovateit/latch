package com.vinnovateit.latch.core.engine

import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.mp.JHttpConnection
import com.vinnovateit.latch.core.mp.JUrl
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking

/** Recovery behaviour: hostel SSID, Pronto address, weak signal and manual disconnect. */
class LatchEngineRecoveryTest {

    @Test
    fun `KvIT latches when the portal redirects to the portal address`() = withEngine(
        wifi = RecoveryWifi("KvIT"),
        transport = RecoveryTransport("http://136.233.9.110/cgi-bin/authlogin"),
    ) { engine, _, transport ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value, "KvIT should latch on a Pronto portal; status=${engine.status.value}")
        assertTrue(
            transport.logins.isNotEmpty() && transport.logins.all { it == "phc.prontonetworks.com" },
            "credentials should only go to the validated portal host",
        )
    }

    @Test
    fun `manual disconnect suppresses auto-connect until the user connects again`() = withEngine(
        wifi = RecoveryWifi("KvIT"),
        transport = RecoveryTransport("http://phc.prontonetworks.com/cgi-bin/authlogin"),
    ) { engine, events, _ ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value)

        engine.submitAndAwait(LatchCommand.Logout, 10_000)
        assertTrue(!engine.isLatched.value, "an explicit logout must unlatch")

        events.send(WifiEvent.Available(RecoveryHandle))
        delay(300)
        assertTrue(!engine.isLatched.value, "auto-connect must stay off after an explicit disconnect")

        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value, "a manual connect re-enables auto-connect")
    }

    @Test
    fun `a weak signal repairs the link without ending the session`() {
        val wifi = RecoveryWifi("KvIT").also { it.signalPercent = 20 }
        withEngine(
            wifi = wifi,
            transport = RecoveryTransport("http://phc.prontonetworks.com/cgi-bin/authlogin"),
            healthIntervalMs = 100L,
        ) { engine, _, _ ->
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
            assertTrue(engine.isLatched.value)

            delay(600)
            assertTrue(engine.isLatched.value, "a weak link must not end the session")
            assertTrue(wifi.reconnectCalls.get() > 0, "a weak signal should trigger a Wi-Fi reconnect")
        }
    }

    @Test
    fun `CheckAndLogin preempts a stalled background login and latches`() {
        val transport = RecoveryTransport(
            redirect = "http://phc.prontonetworks.com/cgi-bin/authlogin",
            stallFirstLoginUntilDisconnect = true,
        )
        withEngine(
            wifi = RecoveryWifi("KvIT"),
            transport = transport,
        ) { engine, events, _ ->
            events.send(WifiEvent.Available(RecoveryHandle))
            assertTrue(transport.firstLoginStarted.await(3, TimeUnit.SECONDS), "background login should start")

            val ok = engine.submitAndAwait(LatchCommand.CheckAndLogin, 5_000)
            assertTrue(ok, "CheckAndLogin should preempt stalled background login promptly")
            assertTrue(transport.firstLoginDisconnected.get(), "stalled login connection must be closed on cancel")
            assertTrue(engine.isLatched.value, "engine should latch after preempting stalled check")
        }
    }

    @Test
    fun `WifiEvent Available followed by Lost aborts stalled login without blocking events`() {
        val transport = RecoveryTransport(
            redirect = "http://phc.prontonetworks.com/cgi-bin/authlogin",
            stallFirstLoginUntilDisconnect = true,
        )
        withEngine(
            wifi = RecoveryWifi("KvIT"),
            transport = transport,
        ) { engine, events, _ ->
            events.send(WifiEvent.Available(RecoveryHandle))
            assertTrue(transport.firstLoginStarted.await(3, TimeUnit.SECONDS), "background login should start")

            events.send(WifiEvent.Lost(RecoveryHandle))
            delay(200)
            assertTrue(transport.firstLoginDisconnected.get(), "WifiEvent.Lost must cancel in-flight login")
            assertTrue(!engine.isLatched.value)

            events.send(WifiEvent.Available(RecoveryHandle))
            val ok = engine.submitAndAwait(LatchCommand.CheckAndLogin, 5_000)
            assertTrue(ok)
            assertTrue(engine.isLatched.value)
        }
    }

    @Test
    fun `repairConnection retries second attempt when first reconnect leaves portal offline`() {
        val wifi = RecoveryWifi("KvIT")
        val transport = RecoveryTransport(
            redirect = "http://phc.prontonetworks.com/cgi-bin/authlogin",
            probeSucceedsAfterReconnectCount = 2,
            reconnectCountProvider = { wifi.reconnectCalls.get() },
        )
        withEngine(
            wifi = wifi,
            transport = transport,
            healthIntervalMs = 80L,
        ) { engine, _, _ ->
            assertTrue(engine.submitAndAwait(LatchCommand.CheckAndLogin, 5_000))
            assertTrue(engine.isLatched.value)
            transport.failProbes = true

            val deadline = System.currentTimeMillis() + 15_000L
            while (wifi.reconnectCalls.get() < 2 && System.currentTimeMillis() < deadline) {
                assertTrue(engine.isLatched.value, "session must stay latched while intermediate repair retries")
                delay(100)
            }
            assertTrue(wifi.reconnectCalls.get() >= 2, "expected 2 reconnect attempts, saw ${wifi.reconnectCalls.get()}")
            delay(300)
            assertTrue(engine.isLatched.value, "session should recover cleanly on attempt 2")
        }
    }

    private fun withEngine(
        wifi: RecoveryWifi,
        transport: RecoveryTransport,
        healthIntervalMs: Long = 60_000L,
        block: suspend (LatchEngine, Channel<WifiEvent>, RecoveryTransport) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-recovery-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val engine = LatchEngine(
            platform = RecoveryPlatform(wifi, transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(RecoveryNoCounters)),
            healthCheckIntervalMs = healthIntervalMs,
        )
        try {
            engine.start()
            block(engine, wifi.eventChannel, transport)
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            wifi.eventChannel.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }
}

private object RecoveryHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private class RecoveryWifi(private val ssid: String) : WifiPlatform {
    val eventChannel = Channel<WifiEvent>(Channel.UNLIMITED)
    val reconnectCalls = AtomicInteger(0)
    @Volatile var signalPercent: Int? = null

    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = ssid
    override fun gatewayIp(): String? = "192.168.1.1"
    override fun activeHandle(): NetworkHandle = RecoveryHandle
    override fun signalPercent(): Int? = signalPercent
    override fun reconnect(handle: NetworkHandle?): Boolean {
        reconnectCalls.incrementAndGet()
        return true
    }
    override val events: Flow<WifiEvent> get() = eventChannel.receiveAsFlow()
}

private class RecoveryTransport(
    private val redirect: String,
    private val stallFirstLoginUntilDisconnect: Boolean = false,
    private val probeSucceedsAfterReconnectCount: Int = 0,
    private val reconnectCountProvider: () -> Int = { 0 },
) : HttpTransport {
    val logins = mutableListOf<String>()
    val firstLoginStarted = CountDownLatch(1)
    val firstLoginDisconnected = AtomicBoolean(false)
    @Volatile var failProbes: Boolean = false
    private val loggedIn = AtomicBoolean(false)
    private val loginCallSeq = AtomicInteger(0)

    override fun open(url: JUrl, handle: NetworkHandle?): JHttpConnection =
        JHttpConnection(openLegacy(URL(url.toString()), handle))

    private fun openLegacy(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val isProbe = url.toString().contains("generate_204")
        val isLogin = url.path.contains("authlogin") || url.path.contains("login")
        return object : HttpURLConnection(url) {
            private val answered = AtomicBoolean(false)
            private val disconnectLatch = CountDownLatch(1)
            private val stalledLogin = isLogin && stallFirstLoginUntilDisconnect && loginCallSeq.incrementAndGet() == 1

            override fun connect() = Unit
            override fun disconnect() {
                if (stalledLogin) firstLoginDisconnected.set(true)
                disconnectLatch.countDown()
            }
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int {
                if (stalledLogin) {
                    firstLoginStarted.countDown()
                    if (disconnectLatch.await(15, TimeUnit.SECONDS)) {
                        throw IOException("aborted by disconnect")
                    }
                }
                if (isLogin && answered.compareAndSet(false, true)) {
                    logins += url.host
                    loggedIn.set(true)
                }
                if (isProbe && failProbes &&
                    reconnectCountProvider() < probeSucceedsAfterReconnectCount
                ) {
                    return 503
                }
                return when {
                    isProbe && !loggedIn.get() -> 302
                    isProbe -> 204
                    else -> 200
                }
            }
            override fun getHeaderField(name: String?): String? =
                if (isProbe && !loggedIn.get() && name.equals("Location", ignoreCase = true)) redirect else null
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream("<html>ok</html>".toByteArray())
            override fun getErrorStream() = ByteArrayInputStream("<html>ok</html>".toByteArray())
        }
    }
}

private object RecoveryNoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class RecoveryPlatform(
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
        override fun userId(): String? = "00AAA0000"
        override fun password(): String? = "not-a-real-password"
        override fun exists(): Boolean = true
        override fun clear() = Result.success(Unit)
    }
    override val counters: ByteCounterSource = RecoveryNoCounters
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
