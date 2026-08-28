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
 * Not yet wired into any real behavior: ForegroundService/LatchTileService/
 * LatchWidget/MainActivity still run their own pre-migration logic. This
 * only constructs the graph so it exists and can be built on incrementally.
 */
object LatchAppGraph {
    private var _engine: LatchEngine? = null
    val engine: LatchEngine get() = checkNotNull(_engine) { "LatchAppGraph.initialize() has not run yet" }

    lateinit var foregroundController: ForegroundControllerHolder
        private set

    fun initialize(context: Context) {
        if (_engine != null) return
        val appContext = context.applicationContext

        foregroundController = ForegroundControllerHolder(appContext)
        val notifier = AndroidUserNotifier(appContext, foregroundController)
        val platform = AndroidPlatformServices(appContext, notifier)
        Platform.install(platform)
        SettingsManager.initialize(platform.settingsStore)

        val database = buildDatabase(appContext)
        val throughput = ThroughputMonitor(platform.counters)
        val sessions = SessionRepository(database.statsDao(), throughput)
        sessions.initialize()

        _engine = LatchEngine(platform, sessions)
    }
}
