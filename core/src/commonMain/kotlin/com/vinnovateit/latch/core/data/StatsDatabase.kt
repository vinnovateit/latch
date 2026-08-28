package com.vinnovateit.latch.core.data

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import kotlinx.coroutines.flow.Flow

/**
 * Session history -- shared by desktop and Android as of version 3.
 *
 * Differences from Android's original schema, both deliberate:
 *
 *  - Timestamps are `Long` epoch millis rather than `java.util.Date`, so no
 *    @TypeConverter is needed. Room stores Date as INTEGER anyway, so the
 *    column definition is identical -- and every read site immediately
 *    called .time on the Date regardless.
 *  - The `daily_usage` table is gone. It had five DAO methods and zero
 *    callers on both platforms.
 *
 * version = 3 to match Android's on-disk version exactly (its schema
 * already went 1 -> 2 -> 3, dropping daily_usage along the way -- see
 * GitHub #70). Room refuses to open a database at a version *lower* than
 * what's stored, so this can't stay at the "fresh desktop database" version
 * 1 it started at once Android adopts this class.
 *
 * No migration is registered: verified empirically (a throwaway Room
 * database built with Android's real production entities -- Long id, Date
 * fields + TypeConverters, version 3 -- reopens cleanly under this exact
 * entity with the data intact) that the two are schema-compatible, since
 * Room's type affinity maps Date-via-TypeConverter and Int/Long primary
 * keys onto the same SQLite INTEGER column either way.
 */
@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val startTime: Long,
    val endTime: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val maxRxBps: Long,
    val maxTxBps: Long,
)

@Dao
interface StatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session): Long

    @Query("SELECT * FROM sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<Session>>

    @Query("DELETE FROM sessions")
    suspend fun clearAllSessions()
}

@Database(entities = [Session::class], version = 3, exportSchema = false)
@ConstructedBy(LatchDatabaseConstructor::class)
abstract class LatchDatabase : RoomDatabase() {
    abstract fun statsDao(): StatsDao
}

/**
 * Room KMP requirement: we declare the expect, KSP generates the actual.
 */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object LatchDatabaseConstructor : RoomDatabaseConstructor<LatchDatabase> {
    override fun initialize(): LatchDatabase
}
