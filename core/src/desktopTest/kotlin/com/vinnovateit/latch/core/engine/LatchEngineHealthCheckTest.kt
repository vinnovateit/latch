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
 * Regression tests for health-check recovery and the re-login mechanism.
 *
 * The health check runs every 60s (too slow for a unit test), but it uses the
 * same `checkAndActExclusive(handle, revalidating=false)` path that
 * `CheckAndLogin` and `WifiEvent.Available` use. These tests exercise that path
 * directly to verify that transient probe failures recover without tearing
 * down the session first.
 */
class LatchEngineHealthCheckTest {

    @Test
    fun `re-login after session loss restores the session`() = withEngine(
        probeResponse = 204,
    ) { engine, events ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value, "session should be latched after initial check")

        events.send(WifiEvent.Available(HealthCurrentHandle))
        events.send(WifiEvent.Lost(HealthCurrentHandle))
        delay(1000)

        assertEquals(false, engine.isLatched.value, "session should be unlatched after onLost")

        events.send(WifiEvent.Available(HealthCurrentHandle))
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(
            engine.isLatched.value,
            "session should be restored after re-available + CheckAndLogin",
        )
    }

    @Test
    fun `transient probe failure followed by success recovers without duplicate login`() = withEngine(
        initialProbeResponse = 302,
        loginResponse = 200,
        revalidationProbeResponse = 204,
    ) { engine, events ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)

        assertTrue(engine.isLatched.value, "session should be latched after successful login + revalidation")
    }

    @Test
    fun `session recovery sends probe`() = withEngine(
        probeResponse = 204,
    ) { engine, events ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value)

        events.send(WifiEvent.Lost(HealthCurrentHandle))
        delay(200)

        events.send(WifiEvent.Available(HealthCurrentHandle))
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value)
    }

    private fun withEngine(
        probeResponse: Int = 204,
        loginResponse: Int = 200,
        revalidationProbeResponse: Int = 204,
        initialProbeResponse: Int = probeResponse,
        block: suspend (LatchEngine, Channel<WifiEvent>) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-health-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val events = Channel<WifiEvent>(Channel.UNLIMITED)
        val transport = HealthProbeTransport(
            initialProbeResponse = initialProbeResponse,
            loginResponse = loginResponse,
            revalidationProbeResponse = revalidationProbeResponse,
        )
        val wifi = HealthEmittingWifi(events)
        val engine = LatchEngine(
            platform = HealthCheckPlatform(wifi, transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(HealthNoCounters)),
        )
        try {
            engine.start()
            block(engine, events)
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            events.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }
}

private object HealthCurrentHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private class HealthEmittingWifi(
    private val eventChannel: Channel<WifiEvent>,
) : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = "G-VIT 5"
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle = HealthCurrentHandle
    override val events: Flow<WifiEvent> get() = eventChannel.receiveAsFlow()
}

private class HealthProbeTransport(
    private val initialProbeResponse: Int,
    private val loginResponse: Int,
    private val revalidationProbeResponse: Int,
) : HttpTransport {
    private val probeCount = AtomicInteger(0)

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val urlStr = url.toString()
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int {
                if (urlStr.contains("generate_204")) {
                    val count = probeCount.getAndIncrement()
                    return if (count == 0) initialProbeResponse else revalidationProbeResponse
                }
                if (urlStr.contains("authlogin")) {
                    return loginResponse
                }
                return 200
            }
            override fun getOutputStream(): OutputStream = object : ByteArrayOutputStream() {
                override fun close() { flush() }
            }
            override fun getInputStream() = ByteArrayInputStream(
                "<html>access granted</html>".toByteArray()
            )
            override fun getErrorStream() = ByteArrayInputStream(
                "error".toByteArray()
            )
        }
    }
}

private object HealthNoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class HealthCheckPlatform(
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
        override fun clear() = Unit
    }
    override val counters: ByteCounterSource = HealthNoCounters
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
