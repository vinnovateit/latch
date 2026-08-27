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
 * Session history.
 *
 * Differences from the Android schema, both deliberate:
 *
 *  - Timestamps are `Long` epoch millis rather than `java.util.Date`, so no
 *    @TypeConverter is needed. Room stored Date as INTEGER anyway, so the column
 *    definition is identical -- and every read site immediately called .time on
 *    the Date regardless.
 *  - The `daily_usage` table is gone. It had five DAO methods and zero callers.
 *    This is a fresh desktop database so there is no migration to write.
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

@Database(entities = [Session::class], version = 1, exportSchema = false)
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
