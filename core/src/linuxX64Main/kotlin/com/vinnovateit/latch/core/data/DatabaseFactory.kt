package com.vinnovateit.latch.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vinnovateit.latch.desktop.AppPaths
import kotlinx.coroutines.Dispatchers

/** Bundled SQLite driver for the native build; no JNA/JVM initialisation needed. */
fun buildDatabase(location: AppPaths.DatabaseLocation = AppPaths.databaseLocation): LatchDatabase {
    val builder = when (location) {
        is AppPaths.DatabaseLocation.OnDisk -> Room.databaseBuilder<LatchDatabase>(name = location.file.path)
        AppPaths.DatabaseLocation.InMemory -> Room.inMemoryDatabaseBuilder<LatchDatabase>()
    }
    return builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.Default)
        .addMigrations(MIGRATION_3_TO_4, MIGRATION_4_TO_5)
        .fallbackToDestructiveMigration(dropAllTables = false)
        .build()
}
