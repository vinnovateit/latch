package com.vinnovateit.latch

import android.app.Application
import com.vinnovateit.latch.platform.LatchAppGraph

class LatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LatchAppGraph.initialize(this)
    }
}
