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
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking

/**
 * After an outage long enough to fail the health check, the recovery login
 * finds the network still down and gives up. Once the network answers again
 * the session must come back on its own: nothing else re-checks a network the
 * app is still connected to.
 */
class LatchEngineHealthRecoveryTest {

    private val intervalMs = 200L

    @Test
    fun `the session is restored when the network comes back after a failed recovery`() = runBlocking {
        val directory = createTempDirectory("latch-recovery-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val events = Channel<WifiEvent>(Channel.UNLIMITED)
        val transport = RecoveryTransport()
        val engine = LatchEngine(
            platform = RecoveryPlatform(RecoveryWifi(events), transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(RecoveryNoCounters)),
            healthCheckIntervalMs = intervalMs,
        )
        try {
            engine.start()
            engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
            assertTrue(engine.isLatched.value, "session should be latched after the initial check")

            // The network stops answering for longer than three health checks.
            transport.down.set(true)
            delay(intervalMs * 6)
            assertFalse(engine.isLatched.value, "a sustained outage should drop the session")

            // The network answers again, still signed in.
            transport.down.set(false)
            delay(intervalMs * 4)
            assertTrue(engine.isLatched.value, "the session should come back once the network answers")
            assertEquals(0, transport.logins.get(), "an online network needs no login")
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            events.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }
}

private object RecoveryHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private class RecoveryWifi(private val eventChannel: Channel<WifiEvent>) : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = "G-VIT 5"
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle = RecoveryHandle
    override val events: Flow<WifiEvent> get() = eventChannel.receiveAsFlow()
}

private class RecoveryTransport : HttpTransport {
    val down = AtomicBoolean(false)
    val logins = AtomicInteger(0)

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val urlStr = url.toString()
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = when {
                down.get() -> throw IOException("network unreachable")
                urlStr.contains("generate_204") -> 204
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
        override fun userId(): String? = "testuser"
        override fun password(): String? = "testpass"
        override fun exists(): Boolean = true
        override fun clear() = Result.success(Unit)
    }
    override val counters: ByteCounterSource = RecoveryNoCounters
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
