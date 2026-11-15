package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.core.data.PortalSessionEntity
import com.vinnovateit.latch.core.data.Session
import com.vinnovateit.latch.core.data.StatsDao
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.platform.ByteCounts
import com.vinnovateit.latch.core.platform.ByteCounterSource
import com.vinnovateit.latch.core.stats.ThroughputMonitor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

class SessionRepositoryShutdownTest {
    @Test
    fun `awaited stop finishes active session before returning`() = runBlocking {
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
        assertTrue(repository.liveStatus.value != null)
        delay(25)
        repository.stopSessionAndAwait()

        assertTrue(repository.liveStatus.value == null)
    }

    @Test
    fun `close stops the database collectors`() = runBlocking {
        val dao = RecordingStatsDao()
        val repository = SessionRepository(dao, ThroughputMonitor(ZeroCounters))
        repository.initialize()

        dao.portalSessions.value = listOf(portalEntity(loginTime = 1L))
        delay(50)
        assertEquals(1, repository.portalHistory.value.size)

        // The runtime closes the repository before the database, so anything
        // still collecting here would read a closed database.
        repository.close()
        dao.portalSessions.value = listOf(portalEntity(loginTime = 1L), portalEntity(loginTime = 2L))
        delay(50)

        assertEquals(1, repository.portalHistory.value.size, "collectors should be cancelled by close()")
    }

    @Test
    fun `closing waits for a query that is already running`() = runBlocking {
        val queryStarted = CompletableDeferred<Unit>()
        val queryFinished = AtomicBoolean(false)
        val dao = object : StatsDao by RecordingStatsDao() {
            override fun getAllPortalSessions(): Flow<List<PortalSessionEntity>> = flow {
                queryStarted.complete(Unit)
                // Stands in for a query inside SQLite, which cancelling cannot stop.
                Thread.sleep(300)
                queryFinished.set(true)
                emit(emptyList())
            }
        }
        val repository = SessionRepository(dao, ThroughputMonitor(ZeroCounters))
        repository.initialize()
        queryStarted.await()

        repository.closeAndAwait()

        // The database is closed right after this; closing it under the query
        // crashed the test JVM in SQLite's btreeBeginTrans.
        assertTrue(queryFinished.get(), "closeAndAwait returned while a query was still running")
    }

    private fun portalEntity(loginTime: Long) = PortalSessionEntity(
        location = "VIT-Vellore",
        macAddress = "9e:85:16:b5:22:eb",
        loginTime = loginTime,
        logoutTime = loginTime + 1_000L,
        durationFormatted = "1 sec",
        durationMillis = 1_000L,
        uploadBytes = 1L,
        downloadBytes = 2L,
        totalBytes = 3L,
    )
}

private object ZeroCounters : ByteCounterSource {
    override fun sample(): ByteCounts = ByteCounts(0, 0)
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

    val portalSessions = MutableStateFlow<List<PortalSessionEntity>>(emptyList())

    override suspend fun insertAllPortalSessions(sessions: List<PortalSessionEntity>) {
        portalSessions.value = portalSessions.value + sessions
    }

    override fun getAllPortalSessions(): Flow<List<PortalSessionEntity>> = portalSessions

    override suspend fun clearAllPortalSessions() {
        portalSessions.value = emptyList()
    }
}
