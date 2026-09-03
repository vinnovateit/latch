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
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * A CLI one-shot creates the engine for the length of a single command, so it
 * reaches logout with isLatched still false. The portal logout must still be
 * sent, or `latch-cli --logout` silently does nothing whenever no daemon is
 * running.
 */
class LatchEngineLogoutTest {
    @Test
    fun `logout reaches the portal on a cold engine that is actually latched`() = withEngine(
        probeResponse = 204,
    ) { engine, transport ->
        assertTrue(engine.submitAndAwait(LatchCommand.Logout, 20_000))

        assertEquals(false, engine.isLatched.value)
        assertTrue(
            transport.requested.any { it.contains("authlogout") },
            "portal logout was never sent; requests were ${transport.requested}",
        )
    }

    @Test
    fun `logout does not touch the portal when the network is not latched`() = withEngine(
        probeResponse = 302,
    ) { engine, transport ->
        assertTrue(engine.submitAndAwait(LatchCommand.Logout, 20_000))

        assertEquals(
            emptyList(),
            transport.requested.filter { it.contains("authlogout") },
            "logged out of a portal this machine was never authenticated with",
        )
    }

    private fun withEngine(
        probeResponse: Int,
        block: suspend (LatchEngine, RecordingTransport) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-logout-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        val transport = RecordingTransport(probeResponse)
        val engine = LatchEngine(
            platform = FakePlatform(FakeWifi, transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(NoCounters)),
        )
        try {
            engine.start()
            block(engine, transport)
        } finally {
            engine.submitAndAwait(LatchCommand.Shutdown, 5_000)
            database.close()
            if (previous == null) System.clearProperty("latch.dataDir") else System.setProperty("latch.dataDir", previous)
            directory.deleteRecursively()
        }
    }
}

/** Answers the portal probe without a socket, and records what was asked for. */
private class RecordingTransport(private val probeResponse: Int) : HttpTransport {
    val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        requested += url.toString()
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int =
                if (url.toString().contains("generate_204")) probeResponse else 200
        }
    }
}

private object FakeWifi : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = "G-VIT 5"
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle = FakeHandle
    override val events: Flow<WifiEvent> = emptyFlow()
}

private object FakeHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private object NoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class FakePlatform(
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
        override val supportsDynamicColor = false
        override val supportsAutostart = false
    }
    override val settingsStore: KeyValueStore = InMemoryKeyValueStore()
    override val credentials: CredentialStore = object : CredentialStore {
        override fun save(userId: String, password: String) = Unit
        override fun userId(): String? = null
        override fun password(): String? = null
        override fun exists(): Boolean = false
        override fun clear() = Unit
    }
    override val counters: ByteCounterSource = NoCounters
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
