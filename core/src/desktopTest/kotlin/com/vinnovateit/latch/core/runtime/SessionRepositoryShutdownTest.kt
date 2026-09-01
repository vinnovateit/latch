package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.data.Session
import com.vinnovateit.latch.core.data.StatsDao
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class SessionRepositoryShutdownTest {
    @Test
    fun `awaited stop persists active session before returning`() = runBlocking {
        val dao = RecordingStatsDao()
        var bytes = 0L
        val counters = object : ByteCounterSource {
            override fun sample(): ByteCounts {
                bytes += 2_048
                return ByteCounts(bytes, 0)
            }
        }
        var clock = 0L
        val monitor = ThroughputMonitor(counters, intervalMs = 1) { clock += 1; clock }
        val repository = SessionRepository(dao, monitor)

        repository.startSession()
        delay(25)
        repository.stopSessionAndAwait()

        assertTrue(dao.inserted.isNotEmpty())
        assertTrue(dao.inserted.single().rxBytes >= 1_024)
    }
}

private class RecordingStatsDao : StatsDao {
    val inserted = mutableListOf<Session>()
    private val sessions = MutableStateFlow<List<Session>>(emptyList())

    override suspend fun insertSession(session: Session): Long {
        inserted += session
        sessions.value += session
        return inserted.size.toLong()
    }

    override fun getAllSessions(): Flow<List<Session>> = sessions

    override suspend fun clearAllSessions() {
        sessions.value = emptyList()
    }
}
