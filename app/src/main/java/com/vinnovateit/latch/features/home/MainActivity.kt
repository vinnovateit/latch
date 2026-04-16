package com.vinnovateit.latch.features.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.vinnovateit.latch.common.util.formatBitsPerSecond
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.stats.StatsViewModel
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import com.vinnovateit.latch.features.wifi.manager.WiFiStatusViewModel
import com.vinnovateit.latch.ui.theme.LatchTheme

class MainActivity : ComponentActivity() {

    private val wifiStatusViewModel: WiFiStatusViewModel by viewModels()
    private lateinit var appUpdateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SettingsManager.initialize(this)

        appUpdateManager = AppUpdateManagerFactory.create(this)
        checkForAppUpdate()

        if (SettingsManager.autoLogin.value) {
            val serviceIntent = Intent(this, ForegroundService::class.java).apply {
                action = ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
            }
            startService(serviceIntent)
        }

        setContent {
            LatchTheme {
                val statsViewModel: StatsViewModel = viewModel()
                val isConnected by wifiStatusViewModel.isConnected.collectAsStateWithLifecycle()
                val liveStatus by statsViewModel.liveStatus.collectAsStateWithLifecycle()
                val connectionStatus by wifiStatusViewModel.connectionStatus.collectAsStateWithLifecycle()
                val speedUnits by SettingsManager.speedUnits.collectAsStateWithLifecycle()

                val currentSpeedBytesPerSecond =
                    liveStatus?.liveData?.lastOrNull()?.usage?.rxBytes ?: 0L
                val formattedSpeed = formatBitsPerSecond(currentSpeedBytesPerSecond, speedUnits)

                val networkSpeedString = if (isConnected && liveStatus != null) {
                    "${formattedSpeed.first} ${formattedSpeed.second}"
                } else ""

                val sessionForHomeScreen = if (isConnected && liveStatus != null) {
                    statsViewModel.sessionToShow.collectAsStateWithLifecycle().value
                } else null

                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        isConnected = isConnected,
                        networkSpeed = networkSpeedString,
                        session = sessionForHomeScreen,
                        connectionStatus = connectionStatus,
                        speedUnits
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun checkForAppUpdate() {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo

        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            val updateAvailable =
                appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            val allowed =
                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

            if (updateAvailable && allowed) {
                try {
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.FLEXIBLE,
                        this,
                        UPDATE_REQUEST_CODE
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            appUpdateManager.registerListener { state ->
                if (state.installStatus() == InstallStatus.DOWNLOADED) {
                    appUpdateManager.completeUpdate()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        wifiStatusViewModel.refreshStatus()

        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                    @Suppress("DEPRECATION")
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.FLEXIBLE,
                        this,
                        UPDATE_REQUEST_CODE
                    )
                }
            }
    }

    companion object {
        private const val UPDATE_REQUEST_CODE = 1234
    }
}
