package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.platform.HttpTransport
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.platform.WifiPlatform
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

class LiveNetworkProbeTest {
    @Test
    fun `reports latched when a campus network already has internet`() = runBlocking {
        val snapshot = probe(ssid = "G-VIT 5", responseCode = 204)

        assertEquals(RuntimeSnapshot("online", "G-VIT 5", true), snapshot)
    }

    @Test
    fun `reports not latched behind the captive portal`() = runBlocking {
        val snapshot = probe(ssid = "G-VIT 5", responseCode = 302)

        assertEquals(RuntimeSnapshot("connected", "G-VIT 5", false), snapshot)
    }

    @Test
    fun `internet on a non-campus network is online but not latched`() = runBlocking {
        val snapshot = probe(ssid = "Airport Free WiFi", responseCode = 204)

        assertEquals(RuntimeSnapshot("online", "Airport Free WiFi", false), snapshot)
    }

    @Test
    fun `does not probe when Wi-Fi is disconnected`() = runBlocking {
        var probed = false
        val snapshot = probeRuntimeSnapshot(
            wifi = FakeWifi(ssid = null, connected = false),
            transport = StubTransport(204) { probed = true },
            logger = NoOpLogger,
        )

        assertEquals(RuntimeSnapshot("disconnected", null, false), snapshot)
        assertEquals(false, probed)
    }

    private suspend fun probe(ssid: String?, responseCode: Int): RuntimeSnapshot = probeRuntimeSnapshot(
        wifi = FakeWifi(ssid = ssid, connected = true),
        transport = StubTransport(responseCode),
        logger = NoOpLogger,
    )
}

private class StubTransport(
    private val code: Int,
    private val onOpen: () -> Unit = {},
) : HttpTransport {
    override fun open(url: URL, handle: NetworkHandle?): HttpURLConnection {
        onOpen()
        return StubConnection(url, code)
    }
}

private class FakeWifi(
    private val ssid: String?,
    private val connected: Boolean,
) : WifiPlatform {
    override fun isWifiEnabled(): Boolean = connected
    override fun isConnectedToWifi(): Boolean = connected
    override fun currentSsid(): String? = ssid
    override fun gatewayIp(): String? = null
    override fun activeHandle(): NetworkHandle? = if (connected) FakeHandle else null
    override val events: Flow<WifiEvent> = emptyFlow()
}

private object FakeHandle : NetworkHandle {
    override val id: String = "wlan0"
}

/** Answers the portal probe without a socket: no network in unit tests. */
private class StubConnection(url: URL, private val code: Int) : HttpURLConnection(url) {
    override fun connect() = Unit
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun getResponseCode(): Int = code
}
