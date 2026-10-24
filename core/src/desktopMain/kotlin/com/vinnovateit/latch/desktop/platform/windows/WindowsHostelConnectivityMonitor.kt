package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.wifi.CaptivePortalDetector
import com.vinnovateit.latch.core.wifi.ConnectivityConfig
import com.vinnovateit.latch.core.wifi.HostelAuthenticator
import com.vinnovateit.latch.core.wifi.HostelAuthResult
import com.vinnovateit.latch.core.wifi.HostelConnectivityMonitor
import com.vinnovateit.latch.core.wifi.HostelConnectivitySnapshot
import com.vinnovateit.latch.core.wifi.HostelConnectivityState
import com.vinnovateit.latch.core.wifi.InternetProbeResult
import com.vinnovateit.latch.core.wifi.isVitCampusSsid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Windows network monitor and captive portal recovery coordinator.
 */
class WindowsHostelConnectivityMonitor(
    private val queryProvider: () -> WindowsNetworkInfo,
    private val probeProvider: suspend (NetworkHandle?) -> InternetProbeResult,
    private val authenticator: HostelAuthenticator,
    private val activeHandleProvider: () -> NetworkHandle? = { null },
    private val logger: Logger,
    private val config: ConnectivityConfig = ConnectivityConfig(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val enableNativeIpHelper: Boolean = true,
) : HostelConnectivityMonitor {

    private companion object {
        const val TAG = "WindowsConnectivityMonitor"
    }

    private val _state = MutableStateFlow(
        HostelConnectivitySnapshot(
            state = HostelConnectivityState.OFFLINE,
            connectivityLevel = WindowsConnectivityLevel.None.name,
        )
    )
    override val state: StateFlow<HostelConnectivitySnapshot> = _state.asStateFlow()

    private val authMutex = Mutex()
    private var eventListenerJob: Job? = null
    private var healthCheckJob: Job? = null
    private var debounceJob: Job? = null

    private var currentIdentity: WindowsNetworkIdentity? = null
    private var consecutiveSlowCount = 0
    private var consecutiveFailCount = 0
    private var lastAuthAttemptTime = 0L
    private var lastAuthSuccessTime = 0L
    private var lastAuthIdentity: WindowsNetworkIdentity? = null

    @Volatile
    private var isStopped = false
    @Volatile
    private var isStarted = false

    override fun start() {
        if (isStarted) return
        isStarted = true
        isStopped = false
        logger.d(TAG, "Starting WindowsHostelConnectivityMonitor.")

        startEventListener()
        startHealthMonitoring()

        scope.launch {
            evaluateNow()
        }
    }

    override fun stop() {
        if (!isStarted && isStopped) return
        isStarted = false
        isStopped = true
        logger.d(TAG, "Stopping WindowsHostelConnectivityMonitor.")
        debounceJob?.cancel()
        eventListenerJob?.cancel()
        healthCheckJob?.cancel()
        scope.coroutineContext.cancelChildren()
    }

    private fun updateState(
        newState: HostelConnectivityState,
        info: WindowsNetworkInfo? = null,
        probe: InternetProbeResult? = null,
        error: String? = null,
    ) {
        val current = _state.value
        val updated = current.copy(
            state = newState,
            ssid = info?.ssid ?: current.ssid,
            connectivityLevel = info?.connectivityLevel?.name ?: current.connectivityLevel,
            latencyMs = probe?.latencyMs ?: current.latencyMs,
            lastSuccessfulProbe = if (probe?.reachable == true && !probe.captivePortalSuspected) {
                probe.timestamp
            } else current.lastSuccessfulProbe,
            lastAuthAttempt = if (newState == HostelConnectivityState.AUTHENTICATING) {
                System.currentTimeMillis()
            } else current.lastAuthAttempt,
            lastSuccessfulAuth = if (newState == HostelConnectivityState.CONNECTED && current.state == HostelConnectivityState.VERIFYING) {
                System.currentTimeMillis()
            } else current.lastSuccessfulAuth,
            retryCount = if (newState == HostelConnectivityState.CONNECTED) 0 else current.retryCount,
            failureCount = if (newState == HostelConnectivityState.CONNECTED) 0 else consecutiveFailCount,
            error = error ?: if (newState == HostelConnectivityState.CONNECTED) null else current.error,
        )
        if (current.state != newState) {
            logger.d(TAG, "State transition: ${current.state} -> $newState (SSID: ${updated.ssid}, Level: ${updated.connectivityLevel})")
        }
        _state.value = updated
    }

    private fun startEventListener() {
        eventListenerJob?.cancel()
        eventListenerJob = scope.launch {
            if (enableNativeIpHelper) {
                launch(Dispatchers.IO) {
                    while (isActive && isStarted) {
                        try {
                            val res = WindowsIpHelper.INSTANCE.NotifyAddrChange(null, null)
                            if (res == 0) {
                                logger.d(TAG, "Native NotifyAddrChange triggered network event.")
                                scheduleDebouncedEvaluation()
                            } else {
                                delay(3000)
                            }
                        } catch (e: Throwable) {
                            logger.w(TAG, "NotifyAddrChange encountered error, falling back to periodic poll: ${e.message}")
                            delay(5000)
                        }
                    }
                }
            }

            var lastSnapshot = withContext(Dispatchers.IO) { queryProvider() }
            while (isActive && isStarted) {
                delay(config.degradedIntervalMs)
                val current = withContext(Dispatchers.IO) { queryProvider() }
                if (current != lastSnapshot) {
                    logger.d(TAG, "Periodic poll detected network state change.")
                    lastSnapshot = current
                    scheduleDebouncedEvaluation()
                }
            }
        }
    }

    private fun scheduleDebouncedEvaluation() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(config.debounceMs)
            evaluateNow()
        }
    }

    override suspend fun evaluateNow() {
        val info = withContext(Dispatchers.IO) { queryProvider() }
        val newIdentity = WindowsNetworkIdentity(
            ssid = info.ssid,
            adapterName = info.adapterName,
            profileName = info.profileName,
        )

        val networkChanged = currentIdentity != null && !newIdentity.isSameNetwork(currentIdentity)
        if (networkChanged) {
            logger.d(TAG, "Network identity changed: old=$currentIdentity, new=$newIdentity")
            consecutiveSlowCount = 0
            consecutiveFailCount = 0
            lastAuthIdentity = null
        }
        currentIdentity = newIdentity

        if (!info.adapterUp || info.adapterName == null) {
            updateState(HostelConnectivityState.OFFLINE, info)
            return
        }

        if (_state.value.state == HostelConnectivityState.OFFLINE) {
            updateState(HostelConnectivityState.WIFI_CONNECTED, info)
        }

        updateState(HostelConnectivityState.CHECKING, info)
        val handle = activeHandleProvider()

        val probe = probeProvider(handle)
        logger.d(TAG, "Connectivity probe result: reachable=${probe.reachable}, captive=${probe.captivePortalSuspected}, latency=${probe.latencyMs}ms, code=${probe.statusCode}")

        val isCampus = isVitCampusSsid(info.ssid)

        if (probe.reachable && !probe.captivePortalSuspected && probe.statusCode == 204) {
            if (isCampus) {
                consecutiveSlowCount = 0
                consecutiveFailCount = 0
                updateState(HostelConnectivityState.CONNECTED, info, probe)
            } else {
                updateState(HostelConnectivityState.CONNECTED, info, probe)
            }
            return
        }

        val captiveDetected = probe.captivePortalSuspected ||
            info.connectivityLevel == WindowsConnectivityLevel.ConstrainedInternetAccess

        if (captiveDetected) {
            if (!isCampus) {
                logger.w(TAG, "Captive portal detected but network SSID '${info.ssid}' is not a campus network.")
                updateState(HostelConnectivityState.FAILED, info, probe, error = "Not a campus network")
                return
            }
            logger.d(TAG, "Captive portal detected on campus Wi-Fi. Preparing authentication.")
            updateState(HostelConnectivityState.CAPTIVE_PORTAL, info, probe)
            authenticateAndRecover(newIdentity, handle)
            return
        }

        consecutiveFailCount++
        if (consecutiveFailCount >= config.maxConsecutiveFailures) {
            updateState(HostelConnectivityState.FAILED, info, probe, error = probe.error ?: "Internet unreachable")
        } else {
            updateState(HostelConnectivityState.DEGRADED, info, probe, error = probe.error)
        }
    }

    private suspend fun authenticateAndRecover(identity: WindowsNetworkIdentity, handle: NetworkHandle?) {
        val now = System.currentTimeMillis()
        if (lastAuthIdentity?.isSameNetwork(identity) == true && (now - lastAuthAttemptTime) < config.authCooldownMs) {
            val remainingSec = (config.authCooldownMs - (now - lastAuthAttemptTime)) / 1000
            logger.d(TAG, "Authentication skipped: cooldown active (${remainingSec}s remaining).")
            return
        }

        authMutex.withLock {
            if (isStopped || !currentCoroutineContext().isActive) return@withLock
            val currentNow = System.currentTimeMillis()
            if (lastAuthIdentity?.isSameNetwork(identity) == true && (currentNow - lastAuthAttemptTime) < config.authCooldownMs) {
                logger.d(TAG, "Authentication skipped under lock due to cooldown.")
                return@withLock
            }

            lastAuthAttemptTime = currentNow
            lastAuthIdentity = identity

            updateState(HostelConnectivityState.AUTHENTICATING)
            logger.d(TAG, "Executing hostel authentication...")

            val authResult = try {
                authenticator.authenticate(handle)
            } catch (e: Throwable) {
                logger.e(TAG, "Authentication threw exception: ${e.message}", e)
                HostelAuthResult.Failure(e.message ?: "Unknown error")
            }

            val postAuthInfo = withContext(Dispatchers.IO) { queryProvider() }
            val postAuthIdentity = WindowsNetworkIdentity(postAuthInfo.ssid, postAuthInfo.adapterName, postAuthInfo.profileName)
            if (!postAuthIdentity.isSameNetwork(identity)) {
                logger.w(TAG, "Network changed during authentication! Discarding result and re-evaluating.")
                evaluateNow()
                return@withLock
            }

            when (authResult) {
                is HostelAuthResult.Failure -> {
                    consecutiveFailCount++
                    logger.w(TAG, "Hostel authentication failed: ${authResult.reason}")
                    updateState(HostelConnectivityState.FAILED, postAuthInfo, error = authResult.reason)
                }

                is HostelAuthResult.SkippedCooldown -> {
                    logger.d(TAG, "Authentication was skipped by authenticator cooldown.")
                }

                is HostelAuthResult.Success -> {
                    lastAuthSuccessTime = System.currentTimeMillis()
                    logger.d(TAG, "Hostel credentials accepted. Verifying Internet access...")
                    verifyInternetRestoration(identity, handle)
                }
            }
        }
    }

    private suspend fun verifyInternetRestoration(expectedIdentity: WindowsNetworkIdentity, handle: NetworkHandle?) {
        updateState(HostelConnectivityState.VERIFYING)

        var verified = false
        var retry = 0
        while (retry < config.maxRevalidateRetries && !verified && currentCoroutineContext().isActive && !isStopped) {
            delay(config.revalidateDelayMs * (retry + 1))

            val currentInfo = withContext(Dispatchers.IO) { queryProvider() }
            val currentIdent = WindowsNetworkIdentity(currentInfo.ssid, currentInfo.adapterName, currentInfo.profileName)
            if (!currentIdent.isSameNetwork(expectedIdentity)) {
                logger.w(TAG, "Network changed during post-auth verification.")
                evaluateNow()
                return
            }

            val probe = probeProvider(handle)
            logger.d(TAG, "Post-auth verification probe (attempt ${retry + 1}/${config.maxRevalidateRetries}): reachable=${probe.reachable}, code=${probe.statusCode}")

            if (probe.reachable && !probe.captivePortalSuspected && probe.statusCode == 204) {
                verified = true
                consecutiveSlowCount = 0
                consecutiveFailCount = 0
                logger.d(TAG, "Internet restoration verified! Connection restored.")
                updateState(HostelConnectivityState.CONNECTED, currentInfo, probe)
                return
            }
            retry++
        }

        if (!verified) {
            consecutiveFailCount++
            logger.w(TAG, "Post-authentication verification failed after ${config.maxRevalidateRetries} attempts.")
            val finalInfo = withContext(Dispatchers.IO) { queryProvider() }
            updateState(HostelConnectivityState.FAILED, finalInfo, error = "Internet verification failed after authentication")
        }
    }

    private fun startHealthMonitoring() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive && isStarted) {
                val currentState = _state.value.state
                val pollInterval = when (currentState) {
                    HostelConnectivityState.CONNECTED -> config.connectedIntervalMs
                    HostelConnectivityState.DEGRADED -> config.degradedIntervalMs
                    HostelConnectivityState.RECOVERING -> config.degradedIntervalMs
                    else -> config.connectedIntervalMs
                }
                delay(pollInterval)
                if (!isActive || !isStarted) break

                val info = withContext(Dispatchers.IO) { queryProvider() }
                val handle = activeHandleProvider()
                val probe = probeProvider(handle)

                when {
                    probe.reachable && !probe.captivePortalSuspected && probe.statusCode == 204 -> {
                        if (probe.latencyMs >= config.latencyCriticalMs || probe.latencyMs >= config.latencyWarningMs) {
                            consecutiveSlowCount++
                            logger.w(TAG, "Probe latency elevated (${probe.latencyMs}ms, strike $consecutiveSlowCount).")
                            if (consecutiveSlowCount >= 2 && currentState == HostelConnectivityState.CONNECTED) {
                                updateState(HostelConnectivityState.DEGRADED, info, probe)
                            }
                        } else {
                            if (consecutiveSlowCount > 0 || currentState == HostelConnectivityState.DEGRADED) {
                                logger.d(TAG, "Connection recovered to healthy latency (${probe.latencyMs}ms).")
                            }
                            consecutiveSlowCount = 0
                            consecutiveFailCount = 0
                            if (currentState != HostelConnectivityState.CONNECTED) {
                                updateState(HostelConnectivityState.CONNECTED, info, probe)
                            }
                        }
                    }

                    probe.captivePortalSuspected || info.connectivityLevel == WindowsConnectivityLevel.ConstrainedInternetAccess -> {
                        logger.w(TAG, "Captive portal re-detected during health monitoring.")
                        if (isVitCampusSsid(info.ssid)) {
                            updateState(HostelConnectivityState.CAPTIVE_PORTAL, info, probe)
                            currentIdentity?.let { authenticateAndRecover(it, handle) }
                        }
                    }

                    else -> {
                        consecutiveFailCount++
                        logger.w(TAG, "Health check probe failed ($consecutiveFailCount/${config.maxConsecutiveFailures}).")
                        if (consecutiveFailCount >= config.maxConsecutiveFailures) {
                            if (currentState != HostelConnectivityState.FAILED) {
                                updateState(HostelConnectivityState.RECOVERING, info, probe, error = probe.error ?: "Connection failed")
                            }
                            val recheckedInfo = withContext(Dispatchers.IO) { queryProvider() }
                            if (recheckedInfo.connectivityLevel == WindowsConnectivityLevel.ConstrainedInternetAccess && isVitCampusSsid(recheckedInfo.ssid)) {
                                updateState(HostelConnectivityState.CAPTIVE_PORTAL, recheckedInfo, probe)
                                currentIdentity?.let { authenticateAndRecover(it, handle) }
                            }
                        } else if (currentState == HostelConnectivityState.CONNECTED) {
                            updateState(HostelConnectivityState.DEGRADED, info, probe, error = probe.error)
                        }
                    }
                }
            }
        }
    }
}
