@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.data

import androidx.room.RoomDatabaseConstructor
import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.mp.JFileWriter
import com.vinnovateit.latch.core.mp.useOutput
import com.vinnovateit.latch.desktop.AppPaths
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.cinterop.refTo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Room and bundled SQLite publish no mingwX64 klib. The Windows native build
 * keeps session history in a JSON file next to the other app data. The DAO
 * contract is identical to Room's, so the engine and stats code are shared.
 */
fun buildDatabase(location: AppPaths.DatabaseLocation = AppPaths.databaseLocation): LatchDatabase =
    FileBackedLatchDatabase()

@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
actual object LatchDatabaseConstructor : RoomDatabaseConstructor<LatchDatabase> {
    actual override fun initialize(): LatchDatabase = FileBackedLatchDatabase()
}

@Serializable
private data class SessionRow(
    val id: Int = 0,
    val startTime: Long = 0,
    val endTime: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val maxRxBps: Long = 0,
    val maxTxBps: Long = 0,
) {
    fun toModel() = Session(id, startTime, endTime, rxBytes, txBytes, maxRxBps, maxTxBps)
    companion object {
        fun from(s: Session) = SessionRow(s.id, s.startTime, s.endTime, s.rxBytes, s.txBytes, s.maxRxBps, s.maxTxBps)
    }
}

@Serializable
private data class PortalRow(
    val id: Int = 0,
    val location: String = "",
    val macAddress: String = "",
    val loginTime: Long = 0,
    val logoutTime: Long = 0,
    val durationFormatted: String = "",
    val durationMillis: Long = 0,
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    val totalBytes: Long = 0,
    val isManual: Boolean = false,
) {
    fun toModel() = PortalSessionEntity(
        id, location, macAddress, loginTime, logoutTime, durationFormatted,
        durationMillis, uploadBytes, downloadBytes, totalBytes, isManual,
    )
    companion object {
        fun from(s: PortalSessionEntity) = PortalRow(
            s.id, s.location, s.macAddress, s.loginTime, s.logoutTime, s.durationFormatted,
            s.durationMillis, s.uploadBytes, s.downloadBytes, s.totalBytes, s.isManual,
        )
    }
}

@Serializable
private data class HistoryFile(
    val sessions: List<SessionRow> = emptyList(),
    val portalSessions: List<PortalRow> = emptyList(),
)

private class FileBackedLatchDatabase : LatchDatabase() {
    private val dao = FileBackedStatsDao()
    override fun statsDao(): StatsDao = dao
    override fun close() {}
}

private class FileBackedStatsDao : StatsDao {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val file: JFile = JFile(AppPaths.dataDir, "history.json")

    private var nextId = 1

    private val sessions = MutableStateFlow<List<Session>>(emptyList())
    private val portalSessions = MutableStateFlow<List<PortalSessionEntity>>(emptyList())

    init {
        runCatching {
            if (file.exists()) {
                val text = readText(file)
                if (text.isNotBlank()) {
                    val stored = json.decodeFromString<HistoryFile>(text)
                    sessions.value = stored.sessions.map(SessionRow::toModel)
                    portalSessions.value = stored.portalSessions.map(PortalRow::toModel)
                    nextId = (sessions.value.maxOfOrNull { it.id } ?: 0) + 1
                }
            }
        }
    }

    override suspend fun insertSession(session: Session): Long {
        val id = if (session.id != 0) session.id else nextId++
        if (id >= nextId) nextId = id + 1
        sessions.value = sessions.value.filterNot { it.id == id } + session.copy(id = id)
        persist()
        return id.toLong()
    }

    override fun getAllSessions(): Flow<List<Session>> = sessions.asStateFlow()

    override suspend fun clearAllSessions() {
        sessions.value = emptyList()
        persist()
    }

    override suspend fun insertAllPortalSessions(items: List<PortalSessionEntity>) {
        val added = items.map { item ->
            val id = if (item.id != 0) item.id else nextId++
            if (id >= nextId) nextId = id + 1
            item.copy(id = id)
        }
        portalSessions.value = portalSessions.value.filterNot { it.id in added.map { a -> a.id } } + added
        persist()
    }

    override fun getAllPortalSessions(): Flow<List<PortalSessionEntity>> = portalSessions.asStateFlow()

    override suspend fun clearAllPortalSessions() {
        portalSessions.value = emptyList()
        persist()
    }

    private fun persist() {
        val payload = HistoryFile(
            sessions.value.map(SessionRow::from),
            portalSessions.value.map(PortalRow::from),
        )
        file.parentFile?.mkdirs()
        val tmp = JFile(file.path + ".tmp")
        JFileWriter(tmp, false).useOutput { it.write(json.encodeToString(payload).encodeToByteArray()) }
        tmp.renameTo(file)
    }

    private fun readText(f: JFile): String {
        val handle = platform.posix.fopen(f.path, "rb") ?: return ""
        return try {
            val buffer = ByteArray(8192)
            val out = StringBuilder()
            while (true) {
                val n = platform.posix.fread(buffer.refTo(0), 1u, buffer.size.toULong(), handle).toInt()
                if (n <= 0) break
                out.append(buffer.copyOf(n).decodeToString())
            }
            out.toString()
        } finally {
            platform.posix.fclose(handle)
        }
    }
}
