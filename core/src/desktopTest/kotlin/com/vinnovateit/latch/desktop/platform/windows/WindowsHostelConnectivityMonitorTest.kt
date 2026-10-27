package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.wifi.ConnectivityConfig
import com.vinnovateit.latch.core.wifi.HostelAuthenticator
import com.vinnovateit.latch.core.wifi.HostelAuthResult
import com.vinnovateit.latch.core.wifi.HostelConnectivityState
import com.vinnovateit.latch.core.wifi.InternetProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeLogger : Logger {
    val messages = mutableListOf<String>()
    override fun d(tag: String, message: String) { messages.add("D: $message") }
    override fun w(tag: String, message: String) { messages.add("W: $message") }
    override fun e(tag: String, message: String, throwable: Throwable?) { messages.add("E: $message") }
}

class WindowsHostelConnectivityMonitorTest {

    private fun testConfig() = ConnectivityConfig(
        latencyWarningMs = 1500L,
        latencyCriticalMs = 5000L,
        probeTimeoutMs = 1000L,
        debounceMs = 10L,
        connectedIntervalMs = 50L,
        degradedIntervalMs = 30L,
        authCooldownMs = 200L,
        maxRevalidateRetries = 2,
        revalidateDelayMs = 10L,
        maxConsecutiveFailures = 3,
    )

    @Test
    fun test01_applicationStartsWhileOffline() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = null,
                    adapterUp = false,
                    radioOn = true,
                    ssid = null,
                    gateway = null,
                    connectivityLevel = WindowsConnectivityLevel.None,
                )
            },
            probeProvider = { InternetProbeResult(reachable = false, captivePortalSuspected = false, latencyMs = 10) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.OFFLINE, monitor.state.value.state)
    }

    @Test
    fun test02_applicationStartsConnectedToWifi() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT2.4G",
                    gateway = "192.168.1.1",
                    connectivityLevel = WindowsConnectivityLevel.InternetAccess,
                )
            },
            probeProvider = { InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 50, statusCode = 204) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test03_applicationStartsWhileCaptive() = runBlocking {
        var authCalls = 0
        var postAuth = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = if (postAuth) WindowsConnectivityLevel.InternetAccess else WindowsConnectivityLevel.ConstrainedInternetAccess,
                )
            },
            probeProvider = {
                if (postAuth) {
                    InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 60, statusCode = 204)
                } else {
                    InternetProbeResult(reachable = true, captivePortalSuspected = true, latencyMs = 100, statusCode = 302, redirectDetected = true)
                }
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    authCalls++
                    postAuth = true
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(1, authCalls)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test04_wifiConnects() = runBlocking {
        var adapterUp = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = if (adapterUp) "Wi-Fi" else null,
                    adapterUp = adapterUp,
                    radioOn = true,
                    ssid = if (adapterUp) "VIT5G" else null,
                    gateway = if (adapterUp) "10.0.0.1" else null,
                    connectivityLevel = if (adapterUp) WindowsConnectivityLevel.InternetAccess else WindowsConnectivityLevel.None,
                )
            },
            probeProvider = {
                InternetProbeResult(reachable = adapterUp, captivePortalSuspected = false, latencyMs = 40, statusCode = if (adapterUp) 204 else null)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.OFFLINE, monitor.state.value.state)

        adapterUp = true
        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test05_wifiDisconnects() = runBlocking {
        var adapterUp = true
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = if (adapterUp) "Wi-Fi" else null,
                    adapterUp = adapterUp,
                    radioOn = true,
                    ssid = if (adapterUp) "VIT5G" else null,
                    gateway = if (adapterUp) "10.0.0.1" else null,
                    connectivityLevel = if (adapterUp) WindowsConnectivityLevel.InternetAccess else WindowsConnectivityLevel.None,
                )
            },
            probeProvider = {
                InternetProbeResult(reachable = adapterUp, captivePortalSuspected = false, latencyMs = 40, statusCode = if (adapterUp) 204 else null)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)

        adapterUp = false
        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.OFFLINE, monitor.state.value.state)
    }

    @Test
    fun test06_wifiChangesToNewNetwork() = runBlocking {
        var currentSsid = "VIT5G"
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = currentSsid,
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.InternetAccess,
                )
            },
            probeProvider = { InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 30, statusCode = 204) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
        assertEquals("VIT5G", monitor.state.value.ssid)

        currentSsid = "VIT2.4G"
        monitor.evaluateNow()
        assertEquals("VIT2.4G", monitor.state.value.ssid)
    }

    @Test
    fun test07_sameWifiRemainsConnectedButInternetDisappears() = runBlocking {
        var internetWorking = true
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = if (internetWorking) WindowsConnectivityLevel.InternetAccess else WindowsConnectivityLevel.LocalAccess,
                )
            },
            probeProvider = {
                if (internetWorking) {
                    InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 40, statusCode = 204)
                } else {
                    InternetProbeResult(reachable = false, captivePortalSuspected = false, latencyMs = 500, error = "Timeout")
                }
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)

        internetWorking = false
        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.DEGRADED, monitor.state.value.state)
    }

    @Test
    fun test08_windowsReportsLocalAccess() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "HomeWifi",
                    gateway = "192.168.1.1",
                    connectivityLevel = WindowsConnectivityLevel.LocalAccess,
                )
            },
            probeProvider = { InternetProbeResult(reachable = false, captivePortalSuspected = true, latencyMs = 200) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.FAILED, monitor.state.value.state)
    }

    @Test
    fun test09_windowsReportsConstrainedInternetAccess() = runBlocking {
        var authenticated = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.ConstrainedInternetAccess,
                )
            },
            probeProvider = {
                if (authenticated) {
                    InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 40, statusCode = 204)
                } else {
                    InternetProbeResult(reachable = true, captivePortalSuspected = true, latencyMs = 50, statusCode = 302)
                }
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    authenticated = true
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertTrue(authenticated)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test10_windowsReportsInternetAccess() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.InternetAccess,
                )
            },
            probeProvider = { InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 30, statusCode = 204) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test11_captivePortalIsDetected() = runBlocking {
        var captiveTriggered = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.ConstrainedInternetAccess,
                )
            },
            probeProvider = {
                InternetProbeResult(reachable = true, captivePortalSuspected = true, latencyMs = 80, statusCode = 200, redirectDetected = true)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    captiveTriggered = true
                    return HostelAuthResult.Failure("Stop here")
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertTrue(captiveTriggered)
    }

    @Test
    fun test12_hostelAuthSucceeds() = runBlocking {
        var verifiedOnline = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.ConstrainedInternetAccess,
                )
            },
            probeProvider = {
                if (verifiedOnline) {
                    InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 30, statusCode = 204)
                } else {
                    InternetProbeResult(reachable = true, captivePortalSuspected = true, latencyMs = 40, statusCode = 302)
                }
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    verifiedOnline = true
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test13_hostelAuthFails() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.ConstrainedInternetAccess,
                )
            },
            probeProvider = {
                InternetProbeResult(reachable = true, captivePortalSuspected = true, latencyMs = 40, statusCode = 302)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    return HostelAuthResult.Failure("Invalid credentials")
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.FAILED, monitor.state.value.state)
    }

    @Test
    fun test14_authSucceedsButInternetVerificationFails() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.ConstrainedInternetAccess,
                )
            },
            probeProvider = {
                InternetProbeResult(reachable = true, captivePortalSuspected = true, latencyMs = 40, statusCode = 302)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.FAILED, monitor.state.value.state)
    }

    @Test
    fun test15_internetBecomesAvailableAfterAuth() = runBlocking {
        var attempts = 0
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.ConstrainedInternetAccess,
                )
            },
            probeProvider = {
                attempts++
                if (attempts > 2) {
                    InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 40, statusCode = 204)
                } else {
                    InternetProbeResult(reachable = true, captivePortalSuspected = true, latencyMs = 50, statusCode = 302)
                }
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test16_oneSlowProbeDoesNotDegrade() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo(
                    adapterName = "Wi-Fi",
                    adapterUp = true,
                    radioOn = true,
                    ssid = "VIT5G",
                    gateway = "10.0.0.1",
                    connectivityLevel = WindowsConnectivityLevel.InternetAccess,
                )
            },
            probeProvider = {
                InternetProbeResult(reachable = true, captivePortalSuspected = false, latencyMs = 2000L, statusCode = 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
    }

    @Test
    fun test17_twoConsecutiveSlowProbesTransitionToDegraded() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = {
                InternetProbeResult(true, false, 2000L, 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig().copy(connectedIntervalMs = 20L),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        delay(100L)
        assertEquals(HostelConnectivityState.DEGRADED, monitor.state.value.state)
        monitor.stop()
    }

    @Test
    fun test18_threeConsecutiveFailedProbesRecover() = runBlocking {
        var fail = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", if (fail) WindowsConnectivityLevel.LocalAccess else WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = {
                if (fail) InternetProbeResult(false, false, 500, error = "Timeout")
                else InternetProbeResult(true, false, 30, 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig().copy(connectedIntervalMs = 20L),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        delay(30L)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)

        fail = true
        delay(120L)
        assertTrue(monitor.state.value.state in listOf(HostelConnectivityState.RECOVERING, HostelConnectivityState.DEGRADED, HostelConnectivityState.CAPTIVE_PORTAL, HostelConnectivityState.CONNECTED))
        monitor.stop()
    }

    @Test
    fun test19_temporaryInternetOutage() = runBlocking {
        var fail = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = {
                if (fail) InternetProbeResult(false, false, 500, error = "Temporary drop")
                else InternetProbeResult(true, false, 30, 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig().copy(connectedIntervalMs = 20L, degradedIntervalMs = 20L),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        delay(30L)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)

        fail = true
        delay(40L)
        assertEquals(HostelConnectivityState.DEGRADED, monitor.state.value.state)

        fail = false
        delay(40L)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
        monitor.stop()
    }

    @Test
    fun test20_internetRecoversWithoutAuthentication() = runBlocking {
        var authCalls = 0
        var fail = true
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = {
                if (fail) InternetProbeResult(false, false, 500, error = "Timeout")
                else InternetProbeResult(true, false, 40, 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    authCalls++
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        delay(30L)

        fail = false
        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
        assertEquals(0, authCalls)
        monitor.stop()
    }

    @Test
    fun test21_captivePortalReappearsAfterPreviouslyConnected() = runBlocking {
        var authCalls = 0
        var captive = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", if (captive) WindowsConnectivityLevel.ConstrainedInternetAccess else WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = {
                if (captive) InternetProbeResult(true, true, 50, 302)
                else InternetProbeResult(true, false, 40, 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    authCalls++
                    captive = false
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig().copy(connectedIntervalMs = 20L),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        delay(30L)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)

        captive = true
        delay(80L)
        assertEquals(1, authCalls)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
        monitor.stop()
    }

    @Test
    fun test22_multipleRapidNetworkEventsDebounced() = runBlocking {
        var evalCount = 0
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                evalCount++
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = { InternetProbeResult(true, false, 40, 204) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        val j1 = launch { monitor.evaluateNow() }
        val j2 = launch { monitor.evaluateNow() }
        val j3 = launch { monitor.evaluateNow() }
        j1.join()
        j2.join()
        j3.join()
        assertTrue(evalCount <= 6)
    }

    @Test
    fun test23_multipleAuthenticationTriggersSimultaneous() = runBlocking {
        var authCount = 0
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.ConstrainedInternetAccess)
            },
            probeProvider = { InternetProbeResult(true, true, 50, 302) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    authCount++
                    delay(50)
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        val j1 = launch { monitor.evaluateNow() }
        val j2 = launch { monitor.evaluateNow() }
        j1.join()
        j2.join()
        assertEquals(1, authCount)
    }

    @Test
    fun test24_authenticationCannotRunConcurrently() = runBlocking {
        var concurrent = false
        var active = false
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.ConstrainedInternetAccess)
            },
            probeProvider = { InternetProbeResult(true, true, 50, 302) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    if (active) concurrent = true
                    active = true
                    delay(50)
                    active = false
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        val j1 = launch { monitor.evaluateNow() }
        val j2 = launch { monitor.evaluateNow() }
        j1.join()
        j2.join()
        assertFalse(concurrent)
    }

    @Test
    fun test25_authenticationCooldownPreventsRepeatedAttempts() = runBlocking {
        var attempts = 0
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.ConstrainedInternetAccess)
            },
            probeProvider = { InternetProbeResult(true, true, 50, 302) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    attempts++
                    return HostelAuthResult.Failure("Failed")
                }
            },
            logger = FakeLogger(),
            config = testConfig().copy(authCooldownMs = 10_000L),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(1, attempts)

        monitor.evaluateNow()
        assertEquals(1, attempts)
    }

    @Test
    fun test26_networkChangesWhileAuthIsRunning() = runBlocking {
        var currentSsid = "VIT5G"
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, currentSsid, "10.0.0.1", WindowsConnectivityLevel.ConstrainedInternetAccess)
            },
            probeProvider = {
                InternetProbeResult(true, currentSsid == "VIT5G", 50, if (currentSsid == "VIT5G") 302 else 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    currentSsid = "VIT2.4G"
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals("VIT2.4G", monitor.state.value.ssid)
    }

    @Test
    fun test27_monitorStoppedWhileAuthIsRunning() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.ConstrainedInternetAccess)
            },
            probeProvider = { InternetProbeResult(true, true, 50, 302) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    delay(50)
                    return HostelAuthResult.Success
                }
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        delay(10)
        monitor.stop()
    }

    @Test
    fun test28_monitorRestartsWithoutDuplicateListeners() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = { InternetProbeResult(true, false, 40, 204) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        monitor.stop()
        monitor.start()
        delay(30)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
        monitor.stop()
    }

    @Test
    fun test29_exponentialBackoffVerification() = runBlocking {
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.ConstrainedInternetAccess)
            },
            probeProvider = {
                InternetProbeResult(true, true, 40, 302)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig().copy(revalidateDelayMs = 10L, maxRevalidateRetries = 2),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        assertEquals(HostelConnectivityState.FAILED, monitor.state.value.state)
    }

    @Test
    fun test30_successfulRecoveryResetsFailureCounters() = runBlocking {
        var fail = true
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", if (fail) WindowsConnectivityLevel.LocalAccess else WindowsConnectivityLevel.InternetAccess)
            },
            probeProvider = {
                if (fail) InternetProbeResult(false, false, 500, error = "Drop")
                else InternetProbeResult(true, false, 40, 204)
            },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?) = HostelAuthResult.Success
            },
            logger = FakeLogger(),
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.start()
        delay(20)

        fail = false
        monitor.evaluateNow()
        assertEquals(0, monitor.state.value.failureCount)
        assertEquals(HostelConnectivityState.CONNECTED, monitor.state.value.state)
        monitor.stop()
    }

    @Test
    fun test31_credentialsNeverWrittenToLogs() = runBlocking {
        val fakeLogger = FakeLogger()
        val monitor = WindowsHostelConnectivityMonitor(
            queryProvider = {
                WindowsNetworkInfo("Wi-Fi", true, true, "VIT5G", "10.0.0.1", WindowsConnectivityLevel.ConstrainedInternetAccess)
            },
            probeProvider = { InternetProbeResult(true, true, 40, 302) },
            authenticator = object : HostelAuthenticator {
                override suspend fun authenticate(networkHandle: NetworkHandle?): HostelAuthResult {
                    return HostelAuthResult.Failure("Auth failed")
                }
            },
            logger = fakeLogger,
            config = testConfig(),
            scope = this,
            enableNativeIpHelper = false,
        )

        monitor.evaluateNow()
        val allLogs = fakeLogger.messages.joinToString("\n")
        assertFalse(allLogs.contains("password", ignoreCase = true))
        assertFalse(allLogs.contains("secret", ignoreCase = true))
    }
}
