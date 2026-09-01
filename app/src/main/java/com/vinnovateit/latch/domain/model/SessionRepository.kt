package com.vinnovateit.latch.domain.model

import android.app.Application
import com.vinnovateit.latch.platform.LatchAppGraph
import kotlinx.coroutines.flow.StateFlow

/**
 * Compatibility delegate forwarding to core SessionRepository.
 */
object SessionRepository {
    val liveStatus: StateFlow<LiveConnectionStatus?>
        get() = LatchAppGraph.sessions.liveStatus

    val lastSession: StateFlow<SessionSummary?>
        get() = LatchAppGraph.sessions.lastSession

    val sessionSummaries: StateFlow<List<SessionSummary>>
        get() = LatchAppGraph.sessions.sessionSummaries

    fun initialize(context: Application) {
        LatchAppGraph.initialize(context)
    }

    fun clearHistory() {
        LatchAppGraph.sessions.clearHistory()
    }

    fun clearAllSessions() {
        LatchAppGraph.sessions.clearHistory()
    }
}
