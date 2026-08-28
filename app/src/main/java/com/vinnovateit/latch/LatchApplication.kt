package com.vinnovateit.latch

import android.app.Application
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.platform.LatchAppGraph

class LatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionRepository.initialize(this)
        // Not yet consumed anywhere -- ForegroundService/LatchTileService/
        // LatchWidget/MainActivity still run their own pre-migration logic.
        // Builds the shared engine graph so later phases can wire into it
        // incrementally instead of all at once.
        LatchAppGraph.initialize(this)
    }
}
