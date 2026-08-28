package com.vinnovateit.latch.platform

import android.content.Context
import androidx.room.Room
import com.vinnovateit.latch.core.data.LatchDatabase

/** Mirrors desktop's DatabaseFactory.kt -- same "latch_database" name Android already used. */
fun buildDatabase(context: Context): LatchDatabase =
    Room.databaseBuilder(context.applicationContext, LatchDatabase::class.java, "latch_database")
        .build()
