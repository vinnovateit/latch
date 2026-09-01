package com.vinnovateit.latch.navigation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinnovateit.latch.common.util.formatBitsPerSecond
import com.vinnovateit.latch.common.util.generateHtmlReport
import com.vinnovateit.latch.core.platform.android.StoredCredentials
import com.vinnovateit.latch.features.about.MeetTheTeamPage
import com.vinnovateit.latch.features.home.HomeScreen
import com.vinnovateit.latch.features.onboarding.CredentialsScreen
import com.vinnovateit.latch.features.onboarding.components.OnboardingScreen
import com.vinnovateit.latch.features.settings.SettingsScreen
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.features.stats.StatsScreen
import com.vinnovateit.latch.features.stats.StatsViewModel
import com.vinnovateit.latch.features.wifi.manager.WiFiStatusViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LatchRoutes {
    const val ONBOARDING = "onboarding"
    const val CREDENTIALS = "credentials/{editMode}"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val MEET_THE_TEAM = "meet_the_team"

    fun credentials(editMode: Boolean = false) = "credentials/$editMode"
}

@Composable
fun LatchNavGraph(
    navController: NavHostController = rememberNavController(),
    wifiStatusViewModel: WiFiStatusViewModel,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { -it / 3 },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { -it / 3 },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
    ) {
        // Onboarding pager
        composable(LatchRoutes.ONBOARDING) {
            val context = LocalContext.current
            OnboardingScreen(
                onComplete = {
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("hasSeenOnboarding", true).apply()
                    navController.navigate(LatchRoutes.HOME) {
                        popUpTo(LatchRoutes.ONBOARDING) { inclusive = true }
                    }
                },
                onNavigateToCredentials = {
                    navController.navigate(LatchRoutes.credentials(editMode = false))
                }
            )
        }

        // Credentials screen
        composable(LatchRoutes.CREDENTIALS) { backStackEntry ->
            val editMode = backStackEntry.arguments?.getString("editMode")?.toBoolean() ?: false
            CredentialsScreen(
                editMode = editMode,
                onCredentialsSaved = {
                    if (editMode) {
                        navController.popBackStack()
                    } else {
                        // Return to the onboarding pager to let it finish its remaining
                        // slides instead of jumping straight to Home; OnboardingScreen's
                        // own onComplete() is what marks onboarding as seen and navigates
                        // to Home once the pager itself finishes.
                        navController.popBackStack(LatchRoutes.ONBOARDING, inclusive = false)
                    }
                }
            )
        }

        // Home screen
        composable(LatchRoutes.HOME) {
            val statsViewModel: StatsViewModel = viewModel()
            val isConnected by wifiStatusViewModel.isConnected.collectAsStateWithLifecycle()
            val liveStatus by statsViewModel.liveStatus.collectAsStateWithLifecycle()
            val connectionStatus by wifiStatusViewModel.connectionStatus.collectAsStateWithLifecycle()
            val speedUnits by SettingsManager.speedUnits.collectAsStateWithLifecycle()



            val sessionForHomeScreen = if (isConnected && liveStatus != null) {
                statsViewModel.sessionToShow.collectAsStateWithLifecycle().value
            } else null

            Surface(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    isConnected = isConnected,
                    session = sessionForHomeScreen,
                    connectionStatus = connectionStatus,
                    speedUnit = speedUnits,
                    onNavigateToSettings = { navController.navigate(LatchRoutes.SETTINGS) },
                    onNavigateToStats = { navController.navigate(LatchRoutes.STATS) },
                    onNavigateToMeetTheTeam = { navController.navigate(LatchRoutes.MEET_THE_TEAM) }
                )
            }
        }

        // Settings
        composable(LatchRoutes.SETTINGS) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToCredentials = { navController.navigate(LatchRoutes.credentials(editMode = true)) }
            )
        }

        // Stats
        composable(LatchRoutes.STATS) {
            val statsViewModel: StatsViewModel = viewModel()
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current

            var pendingReportHtml: String? = null

            val createDocumentLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/html")
            ) { uri: Uri? ->
                uri?.let {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                                generateHtmlReport(
                                    sessions = statsViewModel.historyToShow.value,
                                    outputStream = outputStream,
                                    appVersion = com.vinnovateit.latch.BuildConfig.VERSION_NAME
                                )
                            }
                        } catch (_: Exception) { }
                    }
                }
            }

            StatsScreen(
                onSaveReport = {
                    val epoch = System.currentTimeMillis()
                    val appVersion = com.vinnovateit.latch.BuildConfig.VERSION_NAME
                    val fileName = "latch_report_${appVersion}_${epoch}.html"
                    createDocumentLauncher.launch(fileName)
                },
                onBackPressed = { navController.popBackStack() },
                statsViewModel = statsViewModel
            )
        }

        // Meet the Team
        composable(LatchRoutes.MEET_THE_TEAM) {
            Surface(modifier = Modifier.fillMaxSize()) {
                MeetTheTeamPage(
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
