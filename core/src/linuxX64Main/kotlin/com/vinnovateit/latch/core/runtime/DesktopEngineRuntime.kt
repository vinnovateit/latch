package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.data.LatchDatabase
import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.mp.ioDispatcher
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.core.portal.PortalHistoryClient
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.desktop.platform.NativePlatformServices
import kotlin.concurrent.AtomicInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val ENGINE_SHUTDOWN_TIMEOUT_MS = 5_000L

class DesktopEngineRuntime private constructor(
    val platform: NativePlatformServices,
    val database: LatchDatabase,
    val sessions: SessionRepository,
    val engine: LatchEngine,
) {
    private val started = AtomicInt(0)
    private val closed = AtomicInt(0)
    val isClosed: Boolean get() = closed.value == 1

    // Startup history sync is a blocking 14s curl; keep it off the Default pool
    // that also runs the derived-stats recompute.
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun start() {
        check(closed.value == 0) { "Runtime is closed." }
        if (started.compareAndSet(0, 1)) engine.start()
    }

    suspend fun close() {
        if (!closed.compareAndSet(0, 1)) return
        if (started.value == 1) engine.submitAndAwait(LatchCommand.Shutdown, ENGINE_SHUTDOWN_TIMEOUT_MS)
        backgroundScope.cancel()
        platform.close()
        sessions.close()
        platform.settingsStore.flush()
        database.close()
    }

    companion object {
        suspend fun create(
            notifier: UserNotifier,
            echoLogsToStdout: Boolean,
            syncHistoryOnStart: Boolean = false,
        ): DesktopEngineRuntime {
            val platform = NativePlatformServices(echoLogsToStdout, notifier)
            Platform.install(platform)
            SettingsManager.initialize(platform.settingsStore)
            applyAutostartDefault(platform)
            val database = buildDatabase()
            val portalClient = PortalHistoryClient(platform.httpTransport)
            val sessions = SessionRepository(database.statsDao(), ThroughputMonitor(platform.counters), portalClient = portalClient)
            sessions.initialize()
            val runtime = DesktopEngineRuntime(
                platform = platform,
                database = database,
                sessions = sessions,
                engine = LatchEngine(platform, sessions),
            )
            if (syncHistoryOnStart) {
                val userId = platform.credentials.userId()
                val password = platform.credentials.password()
                if (!userId.isNullOrBlank() && !password.isNullOrBlank()) {
                    runtime.backgroundScope.launch { sessions.syncPortalHistory(userId, password) }
                }
            }
            return runtime
        }
    }
}

/** Turn start-at-login on the first time an installed build runs, never re-enabling it after the user turns it off. */
private fun applyAutostartDefault(platform: NativePlatformServices) {
    if (!platform.capabilities.supportsAutostart || SettingsManager.autostartDefaultApplied) return
    platform.systemActions.setAutostart(true)
    if (platform.systemActions.isAutostartEnabled()) {
        SettingsManager.autostartDefaultApplied = true
        platform.logger.d("DesktopEngineRuntime", "Enabled start-at-login by default.")
    }
}
