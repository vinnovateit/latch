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

/**
 * BundledSQLiteDriver and setQueryCoroutineContext are both mandatory for Room
 * KMP. The bundled driver ships its own native library which it extracts at
 * runtime, which is why the jpackage module list does not need java.sql.
 */
fun buildDatabase(): LatchDatabase =
    Room.databaseBuilder<LatchDatabase>(name = AppPaths.databaseFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_TO_3)
        .build()
