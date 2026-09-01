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
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress

enum class LatchCommand { CheckAndLogin, SilentCheck, Logout, Shutdown }

/** The only engine API the UI knows about. Replaces Android's Intent control plane. */
interface LatchController {
    val status: StateFlow<ConnectionStatus>
    val isLatched: StateFlow<Boolean>
    fun submit(command: LatchCommand)

    /**
     * Like [submit], but suspends until [command] has actually finished
     * processing (not until some StateFlow happens to already satisfy a
     * predicate -- that races the command itself). Returns false on timeout.
     */
    suspend fun submitAndAwait(command: LatchCommand, timeoutMs: Long): Boolean
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
 *  - reportNetworkConnectivity(). Behind wifi.reportConnectivity(), a no-op here.
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

    /** Wraps a command with an optional per-invocation completion signal for [submitAndAwait]. */
    private data class QueuedCommand(
        val command: LatchCommand,
        val done: CompletableDeferred<Unit>? = null,
    )

    private val commands = Channel<QueuedCommand>(Channel.UNLIMITED)
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

    private fun unlatch() {
        _isLatched.value = false
        sessions.stopSession()
    }

    override fun submit(command: LatchCommand) {
        commands.trySend(QueuedCommand(command))
    }

    override suspend fun submitAndAwait(command: LatchCommand, timeoutMs: Long): Boolean {
        val done = CompletableDeferred<Unit>()
        commands.trySend(QueuedCommand(command, done))
        return withTimeoutOrNull(timeoutMs) { done.await() } != null
    }

    /** Idempotent -- the desktop engine is created once but may be re-started. */
    fun start() {
        if (started) return
        started = true

        scope.launch {
            platform.wifi.events.collect { event -> onWifiEvent(event) }
        }
        scope.launch {
            for (queued in commands) {
                try {
                    handle(queued.command)
                } finally {
                    queued.done?.complete(Unit)
                }
            }
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
                unlatch()
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
                    unlatch()
                    ConnectionStatusManager.postStatus(
                        ConnectionStatus.Failed(ConnectionStatus.Reason.WifiOff)
                    )
                    return
                }
                val handle = currentHandle ?: platform.wifi.activeHandle()
                if (handle == null) {
                    unlatch()
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

            // Serialized against checkAndActExclusive so a logout can't run
            // concurrently with a fresh login on the Wi-Fi-event coroutine and
            // clobber it -- the two run on separate coroutines sharing this pool.
            LatchCommand.Logout -> loginGate.withLock { logoutNow() }

            LatchCommand.Shutdown -> {
                healthCheckJob?.cancel()
                unlatch()
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
        val currentSsid = platform.wifi.currentSsid()
        val currentGateway = platform.wifi.gatewayIp()
        logger.d(TAG, "[ConnectAnalysis] === Connection Probe Started (revalidating=$revalidating, retry=$retry) ===")
        logger.d(TAG, "[ConnectAnalysis] Step 1/4: Network Info: SSID='$currentSsid', Gateway='$currentGateway'")

        ConnectionStatusManager.postStatus(
            ConnectionStatus.Connecting(ConnectionStatus.Step.CheckingInternet)
        )

        val code = portal.checkPortalStatus(handle)
        logger.d(TAG, "[ConnectAnalysis] Step 2/4: Portal Probe Response Code: $code (204 = Direct Internet, 200/302 = Captive Portal, -1 = Network Error)")

        if (code == 204) {
            val ssid = platform.wifi.currentSsid()
            if (!isVitCampusSsid(ssid)) {
                logger.d(TAG, "[ConnectAnalysis] Network has 204 internet but SSID '$ssid' is not a VIT campus network; not latching.")
                unlatch()
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.NotTargetNetwork)
                )
                return
            }
            logger.d(TAG, "[ConnectAnalysis] Network has real internet (HTTP 204). Starting session.")
            platform.wifi.reportConnectivity(handle, ok = true)
            ConnectionStatusManager.postStatus(ConnectionStatus.Success)
            _isLatched.value = true
            sessions.startSession()
            startHealthCheck(handle)
            return
        }

        // DNS resolution failing outright (as opposed to resolving but the
        // portal not answering) usually means Private DNS is blocking
        // captive-portal detection entirely -- retrying the same probe would
        // just fail the same way, so this is reported distinctly rather than
        // falling into the generic login-failure path below.
        if (code == CaptivePortalDetector.DNS_RESOLUTION_FAILED && !revalidating) {
            logger.w(TAG, "[ConnectAnalysis] Portal host DNS resolution failed.")
            unlatch()
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Failed(ConnectionStatus.Reason.DnsResolutionFailed)
            )
            return
        }

        // Just logged in: give the portal a moment to open the gates rather than
        // looping forever.
        if (revalidating) {
            if (retry < MAX_REVALIDATE_RETRIES) {
                logger.d(TAG, "[ConnectAnalysis] Waiting for network gates to open (attempt ${retry + 1}/$MAX_REVALIDATE_RETRIES)")
                delay(REVALIDATE_DELAY_MS)
                checkAndAct(handle, revalidating = true, retry = retry + 1, silent = silent)
            } else {
                logger.w(TAG, "[ConnectAnalysis] Network never granted internet after successful login.")
                unlatch()
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.NetworkTimeoutAfterLogin)
                )
            }
            return
        }

        if (silent || !SettingsManager.autoLogin.value) {
            logger.d(TAG, "[ConnectAnalysis] Silent check or auto-login disabled; skipping login attempt.")
            unlatch()
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Failed(ConnectionStatus.Reason.LoginFailed)
            )
            return
        }

        if (!isTargetNetwork()) {
            logger.w(TAG, "[ConnectAnalysis] Captive portal present but network target verification failed.")
            unlatch()
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Failed(ConnectionStatus.Reason.NotTargetNetwork)
            )
            return
        }

        logger.d(TAG, "[ConnectAnalysis] Captive portal detected on a verified network. Proceeding to login.")
        handleCaptivePortal(handle)
    }

    /**
     * Gate before any credential-bearing request.
     */
    private suspend fun isTargetNetwork(): Boolean = withContext(Dispatchers.IO) {
        val ssid = platform.wifi.currentSsid()
        logger.d(TAG, "[ConnectAnalysis] Step 3/4: Target Network Verification: SSID='$ssid'")
        if (!isVitCampusSsid(ssid)) {
            logger.w(TAG, "[ConnectAnalysis] SSID '$ssid' failed VIT campus match; refusing login.")
            return@withContext false
        }
        if (ssid == null) {
            logger.w(TAG, "[ConnectAnalysis] SSID unreadable; checking portal host resolution alone.")
        }
        
        var resolves = runCatching { InetAddress.getByName(PORTAL_HOST) }.isSuccess
        if (!resolves) {
            logger.w(TAG, "[ConnectAnalysis] Portal host '$PORTAL_HOST' DNS failed on 1st try. Retrying after 300ms...")
            delay(300)
            resolves = runCatching { InetAddress.getByName(PORTAL_HOST) }.isSuccess
        }

        if (!resolves) {
            val gatewayIp = platform.wifi.gatewayIp()
            if (gatewayIp != null) {
                logger.d(TAG, "[ConnectAnalysis] Checking fallback gateway IP reachable: $gatewayIp")
                resolves = runCatching { InetAddress.getByName(gatewayIp) }.isSuccess
            }
        }

        if (!resolves) {
            logger.w(TAG, "[ConnectAnalysis] Target Verification FAILED: Portal host '$PORTAL_HOST' unresolvable.")
        } else {
            logger.d(TAG, "[ConnectAnalysis] Target Verification PASSED: Network verified successfully.")
        }
        resolves
    }

    /**
     * True unless the SSID is readable and readably *not* a campus network.
     */
    private fun isVitCampusSsid(ssid: String?): Boolean {
        val clean = ssid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return true
        return VIT_SSID_PATTERN.containsMatchIn(clean) ||
            clean.contains("VIT", ignoreCase = true) ||
            clean.endsWith("-VIT", ignoreCase = true) ||
            SettingsManager.allowedSsids.value.any { clean.contains(it, ignoreCase = true) }
    }

    private suspend fun handleCaptivePortal(handle: NetworkHandle) {
        logger.d(TAG, "[ConnectAnalysis] Step 4/4: Authenticating with Captive Portal...")
        ConnectionStatusManager.postStatus(
            ConnectionStatus.Connecting(ConnectionStatus.Step.Authenticating)
        )
        platform.wifi.bindProcess(handle)
        try {
            val user = platform.credentials.userId()
            val pass = platform.credentials.password()
            if (user == null || pass == null) {
                logger.w(TAG, "[ConnectAnalysis] Auth Failed: Missing saved credentials.")
                unlatch()
                ConnectionStatusManager.postStatus(
                    ConnectionStatus.Failed(ConnectionStatus.Reason.NoCredentials)
                )
                return
            }
            logger.d(TAG, "[ConnectAnalysis] Attempting Portal Login 1 (HTTP)...")
            var result = login.attemptLogin(
                userId = user,
                password = pass,
                handle = handle,
                useAlternate = false,
                fallbackIp = platform.wifi.gatewayIp(),
            )
            if (result is LoginResult.Failure) {
                logger.w(TAG, "[ConnectAnalysis] Attempt 1 (HTTP) Failed; Retrying Attempt 2 (HTTPS) in 1s...")
                delay(1000)
                result = login.attemptLogin(
                    userId = user,
                    password = pass,
                    handle = handle,
                    useAlternate = true,
                    fallbackIp = platform.wifi.gatewayIp(),
                )
            }
            when (result) {
                is LoginResult.Success -> {
                    logger.d(TAG, "[ConnectAnalysis] Auth SUCCESS! Revalidating network access...")
                    checkAndAct(handle, revalidating = true)
                }

                is LoginResult.Failure -> {
                    // Pronto sometimes serves a login response our success-string
                    // match doesn't recognize even though the portal already
                    // granted the session server-side -- a manual "press connect
                    // again" right after a reported failure routinely succeeds
                    // instantly because of this. One cheap extra probe here
                    // automates that instead of making the user do it by hand.
                    // Real failures (bad credentials, portal down) still surface
                    // immediately below since this doesn't retry.
                    logger.w(TAG, "[ConnectAnalysis] Auth FAILED on both HTTP & HTTPS attempts; re-probing in case the portal already granted access...")
                    if (portal.checkPortalStatus(handle) == 204) {
                        logger.d(TAG, "[ConnectAnalysis] Portal already granted access despite failed login detection. Treating as success.")
                        checkAndAct(handle, revalidating = true)
                    } else {
                        unlatch()
                        ConnectionStatusManager.postStatus(
                            ConnectionStatus.Failed(ConnectionStatus.Reason.LoginFailed)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "[ConnectAnalysis] Exception during handleCaptivePortal: ${e.message}", e)
            unlatch()
            ConnectionStatusManager.postStatus(
                ConnectionStatus.Failed(ConnectionStatus.Reason.LoginFailed)
            )
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
            unlatch()
            // Tell Android this network no longer has a live session now,
            // rather than waiting for its own NetworkMonitor to notice on
            // its own schedule -- regardless of whether the portal's own
            // logout request itself succeeded, the app is no longer relying
            // on this network being latched.
            //
            // Deliberately unconditional on `ok`: this is a hint asking
            // Android to re-validate the network, not an acknowledgment that
            // logout succeeded, so it's correct to fire it either way -- if
            // the portal's own logout call failed, that's still surfaced
            // separately below via ConnectionStatus.Reason.LogoutFailed.
            if (handle != null) platform.wifi.reportConnectivity(handle, ok = false)
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
                    unlatch()
                    checkAndActExclusive(handle, revalidating = false)
                }
            }
        }
    }
}
