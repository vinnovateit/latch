package com.vinnovateit.latch

import android.app.Application
import com.vinnovateit.latch.domain.model.SessionRepository
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.wifi.manager.ConnectionStatusManager

class LatchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsManager.initialize(this)
        ConnectionStatusManager.initialize(this)
        SessionRepository.initialize(this)
    }
}
