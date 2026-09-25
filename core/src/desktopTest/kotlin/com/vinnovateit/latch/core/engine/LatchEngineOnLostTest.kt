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
import java.net.HttpURLConnection
import java.net.URL
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
 * Regression tests for the stale-handle onLost guard.
 *
 * During Wi-Fi handoff, Android may fire onLost for the old network after the
 * replacement is already available. Without an identity check, the engine would
 * tear down a healthy session.
 */
class LatchEngineOnLostTest {

    @Test
    fun `onLost for a non-current handle does not tear down the session`() = withEngine { engine, events ->
        // Establish session via command (sets currentHandle via activeHandle()).
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value, "session should be latched after initial check")

        // Also send Available so currentHandle is set from the event path.
        events.send(WifiEvent.Available(OnLostCurrentHandle))
        delay(200)

        // Emit onLost for a DIFFERENT handle (stale handoff).
        events.send(WifiEvent.Lost(StaleHandle))
        delay(200)

        assertTrue(
            engine.isLatched.value,
            "session should remain latched after stale onLost",
        )
    }

    @Test
    fun `onLost for the current handle tears down the session`() = withEngine { engine, events ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value, "session should be latched after initial check")

        // Send Available so currentHandle is set from the event path.
        events.send(WifiEvent.Available(OnLostCurrentHandle))
        delay(200)

        // Emit onLost for the CURRENT handle (real disconnect).
        events.send(WifiEvent.Lost(OnLostCurrentHandle))
        delay(200)

        assertEquals(
            false,
            engine.isLatched.value,
            "session should be torn down after current-handle onLost",
        )
    }

    @Test
    fun `onLost with null handle tears down the session`() = withEngine { engine, events ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value, "session should be latched after initial check")

        // Send Available so currentHandle is set from the event path.
        events.send(WifiEvent.Available(OnLostCurrentHandle))
        delay(200)

        // A null handle means "unknown which network was lost" -- must not be ignored.
        events.send(WifiEvent.Lost(null))
        delay(200)

        assertEquals(
            false,
            engine.isLatched.value,
            "session should be torn down after null-handle onLost",
        )
    }

    @Test
    fun `onLost tears down a session that was latched without an Available event`() = withEngine { engine, events ->
        // The desktop pollers seed their last-seen key from the current snapshot,
        // so starting while already connected emits no Available at all and the
        // only handle the engine ever sees comes from the startup check.
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)
        assertTrue(engine.isLatched.value, "session should be latched after initial check")

        events.send(WifiEvent.Lost(OnLostCurrentHandle))
        delay(200)

        assertEquals(
            false,
            engine.isLatched.value,
            "session should be torn down even though no Available event was seen",
        )
    }

    private fun withEngine(
        block: suspend (LatchEngine, Channel<WifiEvent>) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-onlost-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val events = Channel<WifiEvent>(Channel.UNLIMITED)
        val transport = OnLostProbeTransport(204)
        val wifi = OnLostEmittingWifi(events)
        val engine = LatchEngine(
            platform = OnLostFakePlatform(wifi, transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(OnLostNoCounters)),
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

private object OnLostCurrentHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private object StaleHandle : NetworkHandle {
    override val id: String = "wlan1"
}

private class OnLostEmittingWifi(
    private val eventChannel: Channel<WifiEvent>,
) : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = "G-VIT 5"
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle = OnLostCurrentHandle
    override val events: Flow<WifiEvent> get() = eventChannel.receiveAsFlow()
}

private class OnLostProbeTransport(private val probeResponse: Int) : HttpTransport {
    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int =
                if (url.toString().contains("generate_204")) probeResponse else 200
        }
    }
}

private object OnLostNoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class OnLostFakePlatform(
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
    override val counters: ByteCounterSource = OnLostNoCounters
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
