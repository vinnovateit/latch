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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
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

/**
 * The health check must not re-login on a single failed probe.
 *
 * A 5s timeout on congested campus Wi-Fi is an ordinary event; acting on one
 * would re-POST the credentials and flap the UI every time it happened. Only a
 * sustained failure means the portal session actually expired.
 */
class LatchEngineHealthDebounceTest {

    private val intervalMs = 200L

    @Test
    fun `one failed probe does not trigger a re-login but a sustained failure does`() = runBlocking {
        val directory = createTempDirectory("latch-debounce-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val events = Channel<WifiEvent>(Channel.UNLIMITED)
        val transport = DebounceTransport()
        val engine = LatchEngine(
            platform = DebouncePlatform(DebounceWifi(events), transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(DebounceNoCounters)),
            healthCheckIntervalMs = intervalMs,
        )
        try {
            engine.start()
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
            assertTrue(engine.isLatched.value, "session should be latched after the initial check")

            val baseline = transport.logins.get()
            transport.probeCode.set(302)

            // Long enough for one or two failed probes, well short of three.
            delay(intervalMs * 2 + 100)
            assertEquals(
                baseline,
                transport.logins.get(),
                "a transient probe failure must not re-POST the credentials",
            )

            // Long enough for the third strike to land.
            delay(intervalMs * 6)
            assertTrue(
                transport.logins.get() > baseline,
                "a sustained probe failure should trigger exactly one recovery login",
            )
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            events.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }
}

private object DebounceHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private class DebounceWifi(private val eventChannel: Channel<WifiEvent>) : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = "G-VIT 5"
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle = DebounceHandle
    override val events: Flow<WifiEvent> get() = eventChannel.receiveAsFlow()
}

private class DebounceTransport : HttpTransport {
    val probeCode = AtomicInteger(204)
    val logins = AtomicInteger(0)

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val urlStr = url.toString()
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = when {
                urlStr.contains("generate_204") -> probeCode.get()
                urlStr.contains("authlogin") -> {
                    logins.incrementAndGet()
                    200
                }
                else -> 200
            }
            override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                override fun close() { flush() }
            }
            override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        }
    }
}

private object DebounceNoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class DebouncePlatform(
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
        override fun userId(): String? = "testuser"
        override fun password(): String? = "testpass"
        override fun exists(): Boolean = true
        override fun clear() = Result.success(Unit)
    }
    override val counters: ByteCounterSource = DebounceNoCounters
    override val notifier: UserNotifier = object : UserNotifier {
        override fun showOngoing(title: String, text: String) = Unit
        override fun notifyTransient(title: String, text: String, isError: Boolean) = Unit
        override fun hideOngoing() = Unit
    }
    override val systemActions: SystemActions = object : SystemActions {
        override fun openWifiSettings() = Unit
        override fun openUrl(url: String) = Unit
        override fun setAutostart(enabled: Boolean) = Unit
        override fun isAutostartEnabled(): Boolean = false
    }
}
