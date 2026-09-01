package com.vinnovateit.latch.platform

import android.content.Context
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor

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

    lateinit var foregroundController: ForegroundControllerHolder
        private set

    fun initialize(context: Context) {
        if (_engine != null) return
        val appContext = context.applicationContext

        foregroundController = ForegroundControllerHolder(appContext)
        val notifier = AndroidUserNotifier(appContext, foregroundController)
        val platform = AndroidPlatformServices(appContext, notifier)
        _platform = platform
        Platform.install(platform)
        SettingsManager.initialize(platform.settingsStore)

        val database = buildDatabase(appContext)
        val throughput = ThroughputMonitor(platform.counters)
        val sessions = SessionRepository(database.statsDao(), throughput)
        sessions.initialize()
        _sessions = sessions

        _engine = LatchEngine(platform, sessions)
    }
}
