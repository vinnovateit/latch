package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.data.LatchDatabase
import com.vinnovateit.latch.core.data.buildDatabase
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import com.vinnovateit.latch.desktop.platform.DesktopPlatformServices
import java.util.concurrent.atomic.AtomicBoolean

private const val ENGINE_SHUTDOWN_TIMEOUT_MS = 5_000L

class DesktopEngineRuntime private constructor(
    val platform: DesktopPlatformServices,
    val database: LatchDatabase,
    val sessions: SessionRepository,
    val engine: LatchEngine,
) {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    fun start() {
        check(!closed.get()) { "Runtime is closed." }
        if (started.compareAndSet(false, true)) engine.start()
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (started.get()) engine.submitAndAwait(LatchCommand.Shutdown, ENGINE_SHUTDOWN_TIMEOUT_MS)
        database.close()
    }

    companion object {
        suspend fun create(
            notifier: UserNotifier,
            echoLogsToStdout: Boolean,
        ): DesktopEngineRuntime {
            val platform = DesktopPlatformServices(echoLogsToStdout, notifier)
            Platform.install(platform)
            SettingsManager.initialize(platform.settingsStore)
            val database = buildDatabase()
            val sessions = SessionRepository(database.statsDao(), ThroughputMonitor(platform.counters))
            sessions.initialize()
            return DesktopEngineRuntime(
                platform = platform,
                database = database,
                sessions = sessions,
                engine = LatchEngine(platform, sessions),
            )
        }
    }
}
