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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.wifi.background.ForegroundService
import com.vinnovateit.latch.features.wifi.manager.WiFiStatusViewModel
import com.vinnovateit.latch.ui.theme.LatchTheme

class MainActivity : ComponentActivity() {

    private val wifiStatusViewModel: WiFiStatusViewModel by viewModels()
    private lateinit var appUpdateManager: AppUpdateManager
    private var updateDownloaded = androidx.compose.runtime.mutableStateOf(false)
    private val installStateListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            updateDownloaded.value = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        SettingsManager.initialize(this)

        appUpdateManager = AppUpdateManagerFactory.create(this)
        appUpdateManager.registerListener(installStateListener)
        checkForAppUpdate()

        // Perform a silent check on launch to detect if already authenticated
        val serviceIntent = Intent(this, ForegroundService::class.java).apply {
            action = if (SettingsManager.autoLogin.value) {
                ForegroundService.ACTION_TRIGGER_LOGIN_CHECK
            } else {
                ForegroundService.ACTION_SILENT_CHECK
            }
        }
        startService(serviceIntent)

        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val hasSeenOnboarding = prefs.getBoolean("hasSeenOnboarding", false)

        val startDest = when {
            !hasSeenOnboarding -> com.vinnovateit.latch.navigation.LatchRoutes.ONBOARDING
            com.vinnovateit.latch.core.platform.android.StoredCredentials.credentialsExist(this) -> com.vinnovateit.latch.navigation.LatchRoutes.HOME
            else -> com.vinnovateit.latch.navigation.LatchRoutes.credentials(editMode = false)
        }

        setContent {
            LatchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    if (updateDownloaded.value) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { /* Force user to decide */ },
                            title = { androidx.compose.material3.Text("Update Ready") },
                            text = { androidx.compose.material3.Text("An update has been downloaded and is ready to be installed. Restart the app to apply the update.") },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    appUpdateManager.completeUpdate()
                                        .addOnSuccessListener { updateDownloaded.value = false }
                                        .addOnFailureListener { e ->
                                            e.printStackTrace()
                                            updateDownloaded.value = false
                                        }
                                }) {
                                    androidx.compose.material3.Text("Restart")
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { 
                                    updateDownloaded.value = false 
                                }) {
                                    androidx.compose.material3.Text("Later")
                                }
                            }
                        )
                    }

                    com.vinnovateit.latch.navigation.LatchNavGraph(
                        wifiStatusViewModel = wifiStatusViewModel,
                        startDestination = startDest
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
        }
    }

    override fun onResume() {
        super.onResume()
        wifiStatusViewModel.refreshStatus()

        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                // For flexible updates, if the update is downloaded but not installed,
                // notify the user to complete the update.
                if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                    updateDownloaded.value = true
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        appUpdateManager.unregisterListener(installStateListener)
    }

    companion object {
        private const val UPDATE_REQUEST_CODE = 1234
    }
}
