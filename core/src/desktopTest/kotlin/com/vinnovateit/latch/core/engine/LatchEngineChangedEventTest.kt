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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private const val PRONTO_REDIRECT = "http://phc.prontonetworks.com/cgi-bin/authlogin?URI=http://example.com"

/**
 * A platform that says the network changed under it -- Windows seeing a
 * captive portal appear -- has to reach the same place as any other check:
 * the engine probes, and the credentials go out only through its own gate.
 */
class LatchEngineChangedEventTest {

    @Test
    fun `a changed network is re-checked and logged into`() =
        withEngine(autoLogin = true) { engine, network, events ->
            events.send(WifiEvent.Available(ChangedHandle))
            awaitTrue("the first check") { engine.isLatched.value }
            val baseline = network.logins.size

            network.endSession()
            events.send(WifiEvent.Changed(ChangedHandle))

            awaitTrue("the re-check") { network.logins.size == baseline + 1 }
            assertTrue(engine.isLatched.value, "the engine should be latched again")
        }

    @Test
    fun `a changed network gets no credentials when auto-login is off`() =
        withEngine(autoLogin = false) { engine, network, events ->
            events.send(WifiEvent.Available(ChangedHandle))
            delay(500)
            val baseline = network.logins.size

            network.endSession()
            events.send(WifiEvent.Changed(ChangedHandle))
            delay(500)

            assertEquals(baseline, network.logins.size, "auto-login is off, so nothing is sent")
        }

    private fun withEngine(
        autoLogin: Boolean,
        block: suspend (LatchEngine, ChangedNetwork, Channel<WifiEvent>) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-changed-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(autoLogin)
        val network = ChangedNetwork()
        val events = Channel<WifiEvent>(Channel.UNLIMITED)
        val engine = LatchEngine(
            platform = ChangedPlatform(ChangedWifi(events), network),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(ChangedNoCounters)),
        )
        try {
            engine.start()
            block(engine, network, events)
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            events.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }

    private suspend fun awaitTrue(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting: $what", e)
        }
    }
}

/**
 * A network that answers the probe with a Pronto redirect until a login is
 * accepted, the way a campus portal does.
 */
private class ChangedNetwork : HttpTransport {
    val logins = CopyOnWriteArrayList<String>()
    private val loggedIn = AtomicBoolean(false)

    /** The portal ends the session, as it does when a laptop wakes up. */
    fun endSession() = loggedIn.set(false)

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val isProbe = url.toString().contains("generate_204")
        val isLogin = url.path.contains("authlogin")
        return object : HttpURLConnection(url) {
            private var answer: Pair<Int, String>? = null

            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false

            override fun getResponseCode(): Int = respond().first

            override fun getHeaderField(name: String?): String? {
                if (!name.equals("Location", ignoreCase = true) || respond().first != 302) return null
                return PRONTO_REDIRECT
            }

            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(respond().second.toByteArray())
            override fun getErrorStream(): InputStream = ByteArrayInputStream(respond().second.toByteArray())

            private fun respond(): Pair<Int, String> = answer ?: when {
                isProbe -> if (loggedIn.get()) 204 to "" else 302 to ""
                isLogin -> {
                    logins += url.host
                    loggedIn.set(true)
                    200 to "<html>access granted</html>"
                }
                else -> 200 to ""
            }.also { answer = it }
        }
    }
}

private object ChangedHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private class ChangedWifi(private val eventChannel: Channel<WifiEvent>) : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = "VIT-Hostel"
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle = ChangedHandle
    override val events: Flow<WifiEvent> get() = eventChannel.receiveAsFlow()
}

private object ChangedNoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class ChangedPlatform(
    override val wifi: WifiPlatform,
    override val httpTransport: HttpTransport,
) : PlatformServices {
    override val logger: Logger = NoOpLogger
    override val buildInfo: BuildInfo = object : BuildInfo {
        override val versionName: String = "test"
        override val isDebug: Boolean = true
        override val isInstalled: Boolean = false
    }
    override val capabilities: PlatformCapabilities = object : PlatformCapabilities {
        override val supportsAutostart: Boolean = false
    }
    override val settingsStore: KeyValueStore = InMemoryKeyValueStore()
    override val credentials: CredentialStore = object : CredentialStore {
        override fun save(userId: String, password: String) = Result.success(Unit)
        override fun userId(): String = "00AAA0000"
        override fun password(): String = "not-a-real-password"
        override fun exists(): Boolean = true
        override fun clear(): Result<Unit> = Result.success(Unit)
    }
    override val counters: ByteCounterSource = ChangedNoCounters
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
