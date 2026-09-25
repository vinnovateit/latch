package com.vinnovateit.latch.core.data

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vinnovateit.latch.desktop.AppPaths
import kotlinx.coroutines.Dispatchers

// No-op except for the version number: LatchDatabase jumped straight from 1
// to 3 to align with Android's on-disk version once it adopts this shared
// class (see StatsDatabase.kt). Any desktop install that already persisted
// a version-1 database needs this path registered, or Room refuses to open
// it and the app crashes on launch instead of just continuing to work.
private val MIGRATION_1_TO_3 = object : Migration(1, 3) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) = Unit
}

interface SystemSqliteInitializer : com.sun.jna.Library {
    fun sqlite3_initialize(): Int

    companion object {
        private val CANDIDATES = listOf("libsqlite3.so.0", "sqlite3", "sqlite3.so.0", "libsqlite3.so", "sqlite3.dll", "libsqlite3.dylib")

        fun init() {
            for (name in CANDIDATES) {
                try {
                    val lib = com.sun.jna.Native.load(name, SystemSqliteInitializer::class.java)
                    lib.sqlite3_initialize()
                    return
                } catch (_: Throwable) {
                }
            }
        }
    }
}


/**
 * BundledSQLiteDriver and setQueryCoroutineContext are both mandatory for Room
 * KMP. The bundled driver ships its own native library which it extracts at
 * runtime, which is why the jpackage module list does not need java.sql.
 *
 * [location] is AppPaths.databaseLocation, the only place that decides which
 * database may be opened; this never chooses a path of its own.
 */
fun buildDatabase(location: AppPaths.DatabaseLocation = AppPaths.databaseLocation): LatchDatabase {
    SystemSqliteInitializer.init()
    val builder = when (location) {
        is AppPaths.DatabaseLocation.OnDisk -> Room.databaseBuilder<LatchDatabase>(name = location.file.absolutePath)
        AppPaths.DatabaseLocation.InMemory -> Room.inMemoryDatabaseBuilder<LatchDatabase>()
    }
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_TO_3, MIGRATION_3_TO_4, MIGRATION_4_TO_5)
        .fallbackToDestructiveMigration(dropAllTables = false)
        .build()
}


