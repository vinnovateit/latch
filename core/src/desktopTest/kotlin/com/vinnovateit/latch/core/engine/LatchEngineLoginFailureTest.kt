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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * Regression test: failed login must not enter revalidation.
 *
 * The pre-KMP ForegroundService checked LoginResult and only called
 * checkNetworkAndAct(revalidating=true) on Success. The KMP extraction
 * initially ignored the result, causing a futile 6-second revalidation
 * loop after every failed login.
 */
class LatchEngineLoginFailureTest {

    @Test
    fun `failed login posts LoginFailed and does not reenter login`() = withEngine(
        portalResponse = 302,
        loginResponse = 500,
    ) { engine, transport ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)

        assertEquals(false, engine.isLatched.value, "session should not be latched after failed login")
        assertTrue(transport.probed, "portal probe was never sent")
        assertEquals(
            1,
            transport.loginAttempts,
            "login should be attempted once, not ${transport.loginAttempts} (revalidation loop detected)",
        )
        val status = engine.status.value
        assertTrue(
            status is ConnectionStatus.Failed,
            "expected Failed status, got $status",
        )
    }

    @Test
    fun `successful login latches the session`() = withEngine(
        portalResponse = 302,
        loginResponse = 200,
    ) { engine, transport ->
        engine.submitAndAwait(LatchCommand.CheckAndLogin, 10_000)

        assertTrue(transport.loginAttempts >= 1, "login should have been attempted")
        assertTrue(engine.isLatched.value, "session should be latched after successful login")
    }

    private fun withEngine(
        portalResponse: Int,
        loginResponse: Int,
        block: suspend (LatchEngine, LoginFailTransport) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("latch-loginfail-").toFile()
        val previous = System.getProperty("latch.dataDir")
        System.setProperty("latch.dataDir", directory.absolutePath)
        val database = buildDatabase()
        SettingsManager.initialize(InMemoryKeyValueStore())
        SettingsManager.setAutoLogin(true)
        val transport = LoginFailTransport(portalResponse, loginResponse)
        val engine = LatchEngine(
            platform = LoginFailPlatform(LoginFailStaticWifi, transport),
            sessions = SessionRepository(database.statsDao(), ThroughputMonitor(LoginFailNoCounters)),
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

private class LoginFailTransport(
    private val portalResponse: Int,
    private val loginResponse: Int,
) : HttpTransport {
    @Volatile var probed = false
    @Volatile var loginAttempts = 0
    private val loggedIn = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        val urlStr = url.toString()
        return object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int {
                if (urlStr.contains("generate_204")) {
                    probed = true
                    if (loggedIn.get()) return 204
                    return portalResponse
                }
                if (urlStr.contains("authlogin")) {
                    loginAttempts++
                    if (loginResponse == 200) loggedIn.set(true)
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

private object LoginFailStaticHandle : NetworkHandle {
    override val id: String = "wlan0"
}

private object LoginFailStaticWifi : WifiPlatform {
    override fun isWifiEnabled(): Boolean = true
    override fun isConnectedToWifi(): Boolean = true
    override fun currentSsid(): String = "G-VIT 5"
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle = LoginFailStaticHandle
    override val events: Flow<WifiEvent> = emptyFlow()
}

private object LoginFailNoCounters : ByteCounterSource {
    override fun sample(): ByteCounts? = null
}

private class LoginFailPlatform(
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
    override val counters: ByteCounterSource = LoginFailNoCounters
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
