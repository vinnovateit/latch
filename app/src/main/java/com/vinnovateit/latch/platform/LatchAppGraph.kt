package com.vinnovateit.latch.platform

import android.content.Context
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.CompositeLogger
import com.vinnovateit.latch.core.platform.LatchFileLogger
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.platform.android.AndroidPlatformServices
import com.vinnovateit.latch.core.platform.android.buildDatabase
import com.vinnovateit.latch.core.portal.PortalHistoryClient
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Composition root, Android equivalent of desktop's LatchApp.create().
 *
 * Lives at Application scope (built once, from LatchApplication.onCreate())
 * so it's available before any Activity/Service exists -- MainActivity's
 * cold-launch check needs a working engine immediately, not after a Service
 * happens to start first.
 *
 * ForegroundService drives this engine now. LatchTileService/LatchWidget/
 * MainActivity still read Android's own ConnectionStatusManager/
 * SessionRepository, kept in sync by ForegroundService's bridge
 * (EngineStatusBridge.kt) until they're repointed directly at this engine.
 */
object LatchAppGraph {
    private var _engine: LatchEngine? = null
    val engine: LatchEngine get() = checkNotNull(_engine) { "LatchAppGraph.initialize() has not run yet" }

    private var _platform: AndroidPlatformServices? = null
    val platform: AndroidPlatformServices get() = checkNotNull(_platform) { "LatchAppGraph.initialize() has not run yet" }

    private var _sessions: SessionRepository? = null
    val sessions: SessionRepository get() = checkNotNull(_sessions) { "LatchAppGraph.initialize() has not run yet" }

    var fileLogger: LatchFileLogger? = null
        private set

    lateinit var foregroundController: ForegroundControllerHolder
        private set

    // Single application-lifetime scope shared by initialize() launchers and triggerHistorySync().
    // Avoids spawning an orphan CoroutineScope on every sync call.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun initialize(context: Context) {
        if (_engine != null) return
        val appContext = context.applicationContext

        foregroundController = ForegroundControllerHolder(appContext)
        val notifier = AndroidUserNotifier(appContext, foregroundController)
        val platform = AndroidPlatformServices(appContext, notifier)
        _platform = platform
        Platform.install(platform)
        SettingsManager.initialize(platform.settingsStore)

        val log = LatchFileLogger(com.vinnovateit.latch.core.mp.JFile(appContext.filesDir.path, "latch_verbose.log"))
        fileLogger = log
        val compositeLogger = CompositeLogger(listOf(platform.logger, log))

        val database = buildDatabase(appContext)
        val throughput = ThroughputMonitor(platform.counters)
        val portalClient = PortalHistoryClient(platform.httpTransport, logger = compositeLogger)
        val sessions = SessionRepository(
            statsDao = database.statsDao(),
            throughput = throughput,
            portalClient = portalClient,
            logger = compositeLogger,
        )
        sessions.initialize()
        _sessions = sessions

        _engine = LatchEngine(platform, sessions)

        triggerHistorySync()

        appScope.launch {
            SettingsManager.settingsChanged.collect {
                appContext.sendBroadcast(android.content.Intent("com.vinnovateit.latch.ACTION_SETTINGS_CHANGED"))
            }
        }
        appScope.launch {
            var initial = true
            SettingsManager.autoLogin.collect { enabled ->
                if (initial) {
                    initial = false
                    return@collect
                }
                if (enabled) {
                    appContext.startService(android.content.Intent(appContext, com.vinnovateit.latch.features.wifi.background.ForegroundService::class.java))
                } else if (sessions.liveStatus.value != null) {
                    appContext.startService(
                        android.content.Intent(appContext, com.vinnovateit.latch.features.wifi.background.ForegroundService::class.java).apply {
                            action = com.vinnovateit.latch.features.wifi.background.ForegroundService.ACTION_TRIGGER_LOGOUT
                        }
                    )
                }
            }
        }
    }

    /**
     * Fire-and-forget history sync. Reads credentials from the platform store,
     * so callers do not duplicate that logic. Safe to call any time -- the
     * repository's atomic slot prevents overlapping syncs.
     */
    fun triggerHistorySync(force: Boolean = false) {
        val creds = platform.credentials
        if (!creds.exists()) return
        val userId = creds.userId()?.takeIf { it.isNotBlank() } ?: return
        val password = creds.password()?.takeIf { it.isNotBlank() } ?: return
        appScope.launch(Dispatchers.IO) {
            try { sessions.syncPortalHistory(userId, password, force = force) } catch (_: Exception) { }
        }
    }
}
