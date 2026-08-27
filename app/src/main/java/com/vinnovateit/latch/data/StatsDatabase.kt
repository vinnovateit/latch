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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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

@Dao
interface StatsDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSession(session: Session): Long

  @Update
  suspend fun updateSession(session: Session)

  @Query("SELECT * FROM sessions ORDER BY startTime DESC")
  fun getAllSessions(): Flow<List<Session>>

  @Query("DELETE FROM sessions")
  suspend fun clearAllSessions()
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

@Database(entities = [Session::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class LatchDatabase : RoomDatabase() {

  abstract fun statsDao(): StatsDao

  companion object {
    @Volatile
    private var INSTANCE: LatchDatabase? = null

    // daily_usage was a Room entity with five DAO methods and zero callers.
    // Dropping the table rather than the whole database: sessions is real
    // user history and must survive this upgrade untouched.
    private val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS daily_usage")
      }
    }

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
          .addMigrations(MIGRATION_2_3)
          .build()
        INSTANCE = instance
        instance
      }
    }
  }
}
