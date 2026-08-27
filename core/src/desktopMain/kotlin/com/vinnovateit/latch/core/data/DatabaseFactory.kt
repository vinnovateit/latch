package com.vinnovateit.latch.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.vinnovateit.latch.desktop.AppPaths
import kotlinx.coroutines.Dispatchers

/**
 * BundledSQLiteDriver and setQueryCoroutineContext are both mandatory for Room
 * KMP. The bundled driver ships its own native library which it extracts at
 * runtime, which is why the jpackage module list does not need java.sql.
 */
fun buildDatabase(): LatchDatabase =
    Room.databaseBuilder<LatchDatabase>(name = AppPaths.databaseFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
