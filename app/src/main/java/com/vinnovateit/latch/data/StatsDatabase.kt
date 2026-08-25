package com.vinnovateit.latch.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class Session(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val startTime: Date,
  val endTime: Date,
  val rxBytes: Long,
  val txBytes: Long,
  val maxRxBps: Long,
  val maxTxBps: Long
)

@Entity(tableName = "daily_usage")
data class DailyUsage(
  @PrimaryKey
  val date: Date,
  val totalRxBytes: Long,
  val totalTxBytes: Long,
)

@Dao
interface StatsDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSession(session: Session): Long

  @Update
  suspend fun updateSession(session: Session)

  @Query("SELECT * FROM sessions ORDER BY startTime DESC")
  fun getAllSessions(): Flow<List<Session>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertDailyUsage(dailyUsage: DailyUsage)

  @Update
  suspend fun updateDailyUsage(dailyUsage: DailyUsage)

  @Query("SELECT * FROM daily_usage ORDER BY date ASC")
  fun getDailyUsage(): Flow<List<DailyUsage>>

  @Query("SELECT * FROM daily_usage WHERE date = :day")
  suspend fun getUsageForDay(day: Date): DailyUsage?

  @Query("DELETE FROM sessions")
  suspend fun clearAllSessions()

  @Query("DELETE FROM daily_usage")
  suspend fun clearAllDailyUsage()
}

class Converters {
  @TypeConverter
  fun fromTimestamp(value: Long?): Date? {
    return value?.let { Date(it) }
  }

  @TypeConverter
  fun dateToTimestamp(date: Date?): Long? {
    return date?.time
  }
}

@Database(entities = [Session::class, DailyUsage::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class LatchDatabase : RoomDatabase() {

  abstract fun statsDao(): StatsDao

  companion object {
    @Volatile
    private var INSTANCE: LatchDatabase? = null

    fun getDatabase(context: Context): LatchDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          LatchDatabase::class.java,
          "latch_database"
        )
          // No destructive fallback: a future version bump without a real Migration
          // should fail loudly during development instead of silently wiping every
          // user's session history on update.
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
