package com.vinnovateit.latch.core.engine

import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.platform.NetworkHandle
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.platform.WifiEvent
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.wifi.AutoLoginManager
import com.vinnovateit.latch.core.wifi.CaptivePortalDetector
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.core.wifi.ConnectionStatusManager
import com.vinnovateit.latch.core.wifi.LoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress

enum class LatchCommand { CheckAndLogin, SilentCheck, Logout, Shutdown }

/** The only engine API the UI knows about. Replaces Android's Intent control plane. */
interface LatchController {
    val status: StateFlow<ConnectionStatus>
    val isLatched: StateFlow<Boolean>
    fun submit(command: LatchCommand)
}

/**
 * The portal state machine, extracted from Android's ForegroundService.
 *
 * Behaviour carried over unchanged: the 3-retry x 2s revalidation after a
 * successful login, the 60s health check, and the onAvailable/onLost handling.
 *
 * Behaviour deliberately NOT carried over, because it is Android-specific and
 * porting it would cause silent failure:
 *
 *  - The 5h45m proactive stopSelf(). That worked around the Android 15
 *    foreground-service time limit. A tray daemon has no such cap, and porting
 *    it would mean monitoring silently dies mid-day with no error and no log line.
 *  - onTimeout(). Android 15 FGS callback, no analogue.
 *  - reportNetworkConnectivity(). Behind wifi.reportConnectivityOk(), a no-op here.
 *  - bindProcessToNetwork(). Behind wifi.bindProcess(), a no-op here.
 *  - stopSelf() on failure paths. The desktop daemon must stay alive and keep
 *    listening; failures post a status and return to Idle.
 */
class LatchEngine(
    private val platform: PlatformServices,
    private val sessions: SessionRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LatchController {

    private companion object {
        const val TAG = "LatchEngine"
        const val PORTAL_HOST = "phc.prontonetworks.com"
        const val HEALTH_CHECK_INTERVAL_MS = 60_000L
        const val REVALIDATE_DELAY_MS = 2000L
        const val MAX_REVALIDATE_RETRIES = 3

        // Campus networks come in two shapes: the hostel/block form
        // "<letter>-VIT" (optionally with a trailing band suffix Windows
        // appends, e.g. "G-VIT 5") and the academic-block form "VIT<band>"
        // ("VIT5G", "VIT2.4G"). Anchored to the start of the SSID so an
        // unrelated network that merely contains "VIT" somewhere in its name
        // does not match.
        val VIT_SSID_PATTERN = Regex("^(?:[A-Za-z]-)?VIT", RegexOption.IGNORE_CASE)
    }

    private val logger = platform.logger
    private val login = AutoLoginManager(platform.httpTransport, logger, platform.buildInfo)
    private val portal = CaptivePortalDetector(platform.httpTransport, logger)

    private val commands = Channel<LatchCommand>(Channel.UNLIMITED)
    private var healthCheckJob: Job? = null
    private var currentHandle: NetworkHandle? = null
    private var started = false

    /**
     * Serialises checkAndAct entry points so two flows -- e.g. the startup
     * CheckAndLogin command and the first polled Wi-Fi Available event -- cannot
     * race the portal with simultaneous credential POSTs. Pronto responds to
     * concurrent logins for one user with a read timeout or an unrecognised 200
     * page, which made the first connection after app start fail silently.
     */
    private val loginGate = Mutex()

    private val _isLatched = MutableStateFlow(false)
    override val isLatched: StateFlow<Boolean> = _isLatched.asStateFlow()

    override val status: StateFlow<ConnectionStatus> = ConnectionStatusManager.status

    override fun submit(command: LatchCommand) {
        commands.trySend(command)
    }

    /** Idempotent -- the desktop engine is created once but may be re-started. */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            platform.wifi.events.collect { event -> onWifiEvent(event) }
        }
        scope.launch {
            for (command in commands) handle(command)
        }
    }

    private suspend fun onWifiEvent(event: WifiEvent) {
        when (event) {
            is WifiEvent.Available -> {
                currentHandle = event.handle
                if (!platform.wifi.isWifiEnabled()) return
                logger.d(TAG, "Wi-Fi available: ${event.handle.id}")
                checkAndActExclusive(
                    handle = event.handle,
                    revalidating = false,
                    silent = !SettingsManager.autoLogin.value,
                )
            }

            is WifiEvent.Lost -> {
                logger.d(TAG, "Wi-Fi lost")
                currentHandle = null
                healthCheckJob?.cancel()
                _isLatched.value = false
                sessions.stopSession()
            }
        }
    }

    private suspend fun handle(command: LatchCommand) {
        when (command) {
            LatchCommand.CheckAndLogin -> {
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Connecting(ConnectionStatus.Step.Initializing)
                )
                if (!platform.wifi.isWifiEnabled()) {
                    ConnectionStatusManager.postStatus(
                        ConnectionStatus.Failed(ConnectionStatus.Reason.WifiOff)
                    )
                    return
                }
                val handle = currentHandle ?: platform.wifi.activeHandle()
                if (handle == null) {
                    ConnectionStatusManager.postStatus(
                        ConnectionStatus.Failed(ConnectionStatus.Reason.NotOnWifi)
                    )
                    return
                }
                checkAndActExclusive(handle, revalidating = false, silent = false)
            }

            LatchCommand.SilentCheck -> {
                val handle = currentHandle ?: platform.wifi.activeHandle() ?: return
                checkAndActExclusive(handle, revalidating = false, silent = true)
            }

            LatchCommand.Logout -> logoutNow()

            LatchCommand.Shutdown -> {
                healthCheckJob?.cancel()
                sessions.stopSession()
            }
        }
    }

    /**
     * checkAndAct under [loginGate].
     *
     * Only external entry points use this. The recursive revalidation call inside
     * [handleCaptivePortal] must keep using the raw checkAndAct -- it runs while
     * the lock is already held, and Mutex is not reentrant.
     */
    private suspend fun checkAndActExclusive(
        handle: NetworkHandle,
        revalidating: Boolean,
        retry: Int = 0,
        silent: Boolean = false,
    ) {
        loginGate.withLock {
            checkAndAct(handle, revalidating, retry, silent)
        }
    }

    private suspend fun checkAndAct(
        handle: NetworkHandle,
        revalidating: Boolean,
        retry: Int = 0,
        silent: Boolean = false,
    ) {
        ConnectionStatusManager.postStatus(
            ConnectionStatus.Connecting(ConnectionStatus.Step.CheckingInternet)
        )

        val code = portal.checkPortalStatus(handle)

        if (code == 204) {
            val ssid = platform.wifi.currentSsid()
            if (!isVitCampusSsid(ssid)) {
                logger.d(TAG, "Network has internet but SSID '$ssid' is not a VIT campus network; not latching.")
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.NotTargetNetwork)
                )
                return
            }
            logger.d(TAG, "Network has internet. Starting session.")
            platform.wifi.reportConnectivityOk(handle)
            ConnectionStatusManager.postStatus(ConnectionStatus.Success)
            _isLatched.value = true
            sessions.startSession()
            startHealthCheck(handle)
            return
        }

        // Just logged in: give the portal a moment to open the gates rather than
        // looping forever.
        if (revalidating) {
            if (retry < MAX_REVALIDATE_RETRIES) {
                logger.d(TAG, "Waiting for network gates to open (attempt ${retry + 1})")
                delay(REVALIDATE_DELAY_MS)
                checkAndAct(handle, revalidating = true, retry = retry + 1, silent = silent)
            } else {
                logger.w(TAG, "Network never granted internet after successful login.")
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.NetworkTimeoutAfterLogin)
                )
            }
            return
        }

        if (silent || !SettingsManager.autoLogin.value) {
            logger.d(TAG, "Silent check or auto-login disabled; not logging in.")
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Failed(ConnectionStatus.Reason.LoginFailed)
            )
            return
        }

        if (!isTargetNetwork()) {
            logger.w(TAG, "Captive portal present but this is not a known Latch network.")
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Failed(ConnectionStatus.Reason.NotTargetNetwork)
            )
            return
        }

        logger.d(TAG, "Captive portal detected on a known network. Logging in.")
        handleCaptivePortal(handle)
    }

    /**
     * Gate before any credential-bearing request.
     *
     * The Android app has no equivalent: it infers "this is the VIT portal"
     * purely from a failed generate_204 probe, then POSTs the student's userId
     * and password in cleartext. On a phone that mostly lives on campus that is
     * tolerable. On a laptop that visits cafes, airports and hotels it would leak
     * credentials to every captive portal it meets, so desktop requires both a
     * matching SSID and the portal host actually resolving on this network.
     *
     * Of the two, the DNS check is the strong signal -- phc.prontonetworks.com
     * only resolves (to a private campus address) when you are actually attached
     * to a Pronto network. The SSID match is defence in depth, checked with
     * [isVitCampusSsid].
     */
    private suspend fun isTargetNetwork(): Boolean = withContext(Dispatchers.IO) {
        val ssid = platform.wifi.currentSsid()
        if (!isVitCampusSsid(ssid)) {
            logger.w(TAG, "SSID '$ssid' is not a VIT campus network; refusing to send credentials.")
            return@withContext false
        }
        val resolves = runCatching { InetAddress.getByName(PORTAL_HOST) }.isSuccess
        if (!resolves) {
            logger.w(TAG, "Portal host does not resolve on this network; refusing to log in.")
        }
        resolves
    }

    /** True for SSIDs starting with "VIT" or "<letter>-VIT" (e.g. "G-VIT", "VIT5G"). */
    private fun isVitCampusSsid(ssid: String?): Boolean =
        ssid != null && VIT_SSID_PATTERN.containsMatchIn(ssid.trim())

    private suspend fun handleCaptivePortal(handle: NetworkHandle) {
        ConnectionStatusManager.postStatus(
            ConnectionStatus.Connecting(ConnectionStatus.Step.Authenticating)
        )
        platform.wifi.bindProcess(handle)
        try {
            val user = platform.credentials.userId()
            val pass = platform.credentials.password()
            if (user == null || pass == null) {
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.NoCredentials)
                )
                return
            }
            val result = login.attemptLogin(
                userId = user,
                password = pass,
                handle = handle,
                useAlternate = false,
                fallbackIp = platform.wifi.gatewayIp(),
            )
            when (result) {
                is LoginResult.Success -> {
                    logger.d(TAG, "Login succeeded; re-validating network.")
                    checkAndAct(handle, revalidating = true)
                }

                is LoginResult.Failure -> {
                    platform.notifier.notifyTransient(
                        title = "Login failed",
                        text = "Check your credentials.",
                        isError = true,
                    )
                    ConnectionStatusManager.postStatus(
                        ConnectionStatus.Failed(ConnectionStatus.Reason.LoginFailed)
                    )
                }
            }
        } finally {
            platform.wifi.bindProcess(null)
        }
    }

    private suspend fun logoutNow() {
        ConnectionStatusManager.postStatus(
            ConnectionStatus.Connecting(ConnectionStatus.Step.LoggingOut)
        )
        healthCheckJob?.cancel()

        val handle = currentHandle ?: platform.wifi.activeHandle()
        platform.wifi.bindProcess(handle)
        try {
            val ok = login.attemptLogout(handle, false, platform.wifi.gatewayIp())
            _isLatched.value = false
            sessions.stopSession()
            if (ok) {
                logger.d(TAG, "Logout succeeded.")
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.Disconnected)
                )
            } else {
                logger.w(TAG, "Logout failed.")
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.LogoutFailed)
                )
            }
        } finally {
            platform.wifi.bindProcess(null)
        }
    }

    private fun startHealthCheck(handle: NetworkHandle) {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            var lastTick = System.currentTimeMillis()
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)

                // delay() does not track wall-clock across OS suspend, so after a
                // lid-close the tick can be arbitrarily late. Detect that and
                // re-check immediately rather than trusting stale state.
                val now = System.currentTimeMillis()
                val drift = now - lastTick - HEALTH_CHECK_INTERVAL_MS
                lastTick = now
                if (drift > HEALTH_CHECK_INTERVAL_MS) {
                    logger.d(TAG, "Detected resume from sleep (drift ${drift}ms); re-checking.")
                }

                val code = portal.checkPortalStatus(handle)
                if (code != 204) {
                    logger.w(TAG, "Health check failed (status $code); session may have expired.")
                    _isLatched.value = false
                    checkAndActExclusive(handle, revalidating = false)
                }
            }
        }
    }
}
