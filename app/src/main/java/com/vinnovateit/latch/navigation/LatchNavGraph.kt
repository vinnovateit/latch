package com.vinnovateit.latch.navigation

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vinnovateit.latch.common.util.generateHtmlReport
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
import androidx.core.content.edit

object LatchRoutes {
    const val ONBOARDING = "onboarding"
    const val CREDENTIALS = "credentials/{editMode}"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val STATS = "stats"
    const val MEET_THE_TEAM = "meet_the_team"

    fun credentials(editMode: Boolean = false) = "credentials/$editMode"
}

// -------------------------------------------------------------------------------------------------
// CRITICAL ARCHITECTURE INVARIANT (DO NOT MODIFY / REWORK / SIMPLIFY):
// The user explicitly mandates this exact predictive slide back architecture with background rendering
// and Animatable gesture tracking. NavHost transitions must remain slideInHorizontally for forward
// navigation and EnterTransition.None / ExitTransition.None for pop transitions to prevent duplicate
// animations or frame glitches. Never replace this with scale/fade animations or simplify it.
// -------------------------------------------------------------------------------------------------
@Composable
fun LatchNavGraph(
    navController: NavHostController = rememberNavController(),
    wifiStatusViewModel: WiFiStatusViewModel,
    startDestination: String
) {
    val homeContent: @Composable () -> Unit = {
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

    val settingsContent: @Composable () -> Unit = {
        Surface(modifier = Modifier.fillMaxSize()) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToCredentials = { navController.navigate(LatchRoutes.credentials(editMode = true)) }
            )
        }
    }

    val onboardingContent: @Composable () -> Unit = {
        val context = LocalContext.current
        Surface(modifier = Modifier.fillMaxSize()) {
            OnboardingScreen(
                onComplete = {
                    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit { putBoolean("hasSeenOnboarding", true) }
                    navController.navigate(LatchRoutes.HOME) {
                        popUpTo(LatchRoutes.ONBOARDING) { inclusive = true }
                    }
                },
                onNavigateToCredentials = {
                    navController.navigate(LatchRoutes.credentials(editMode = false))
                }
            )
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth / 3 },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        },
        popEnterTransition = {
            EnterTransition.None
        },
        popExitTransition = {
            ExitTransition.None
        }
    ) {
        // Onboarding pager
        composable(LatchRoutes.ONBOARDING) {
            onboardingContent()
        }

        // Credentials screen
        composable(LatchRoutes.CREDENTIALS) { backStackEntry ->
            val editMode = backStackEntry.arguments?.getString("editMode")?.toBoolean() ?: false
            PredictiveSlideBackContainer(
                onBackPressed = {
                    if (editMode) {
                        navController.popBackStack()
                    } else {
                        navController.popBackStack(LatchRoutes.ONBOARDING, inclusive = false)
                    }
                },
                backgroundContent = if (editMode) settingsContent else onboardingContent
            ) { triggerBack ->
                CredentialsScreen(
                    editMode = editMode,
                    onCredentialsSaved = {
                        triggerBack()
                    },
                    onBackClick = triggerBack
                )
            }
        }

        // Home screen
        composable(LatchRoutes.HOME) {
            homeContent()
        }

        // Settings
        composable(LatchRoutes.SETTINGS) {
            PredictiveSlideBackContainer(
                onBackPressed = { navController.popBackStack() },
                backgroundContent = homeContent
            ) { triggerBack ->
                SettingsScreen(
                    onBackClick = triggerBack,
                    onNavigateToCredentials = { navController.navigate(LatchRoutes.credentials(editMode = true)) }
                )
            }
        }

        // Stats
        composable(LatchRoutes.STATS) {
            val statsViewModel: StatsViewModel = viewModel()
            val coroutineScope = rememberCoroutineScope()
            val context = LocalContext.current

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

            PredictiveSlideBackContainer(
                onBackPressed = { navController.popBackStack() },
                backgroundContent = homeContent
            ) { triggerBack ->
                StatsScreen(
                    onSaveReport = {
                        val epoch = System.currentTimeMillis()
                        val appVersion = com.vinnovateit.latch.BuildConfig.VERSION_NAME
                        val fileName = "latch_report_${appVersion}_${epoch}.html"
                        createDocumentLauncher.launch(fileName)
                    },
                    onBackPressed = triggerBack,
                    statsViewModel = statsViewModel
                )
            }
        }

        // Meet the Team
        composable(LatchRoutes.MEET_THE_TEAM) {
            PredictiveSlideBackContainer(
                onBackPressed = { navController.popBackStack() },
                backgroundContent = homeContent
            ) { triggerBack ->
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeetTheTeamPage(
                        onBackClick = triggerBack
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// CRITICAL ARCHITECTURE INVARIANT (DO NOT MODIFY / REWORK / SIMPLIFY):
// Handles native Android predictive back gestures and programmatic back button clicks.
// 1. Predictive gesture: Tracks gesture progress in real-time via `progress.snapTo(event.progress)`.
// 2. Commit on release: Smoothly completes the remaining slide to 1.0f with `progress.animateTo(1f)`
//    before calling `onBackPressed()` to prevent visual jerks or screen flashes.
// 3. Parallax background: Renders `backgroundContent` (HomeScreen) behind the active screen shifted
//    by `(progress - 1f) * (screenWidth / 3f)` with a subtle depth scrim.
// 4. Cancellation: Springs back to 0.0f cleanly on gesture abort.
// Never simplify, delete, or rework this implementation.
// -------------------------------------------------------------------------------------------------
@Composable
private fun PredictiveSlideBackContainer(
    onBackPressed: () -> Unit,
    backgroundContent: (@Composable () -> Unit)? = null,
    content: @Composable (triggerBack: () -> Unit) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var isPredictive by remember { androidx.compose.runtime.mutableStateOf(false) }

    val performAnimatedBack: () -> Unit = {
        coroutineScope.launch {
            isPredictive = true
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            onBackPressed()
        }
    }

    PredictiveBackHandler(enabled = true) { backEvent ->
        isPredictive = true
        try {
            backEvent.collect { event ->
                progress.snapTo(event.progress)
            }
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            onBackPressed()
        } catch (_: Exception) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            isPredictive = false
        }
    }

    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { screenWidth.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isPredictive && backgroundContent != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = (progress.value - 1f) * (screenWidthPx / 3f)
                    }
            ) {
                backgroundContent()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(
                            alpha = (1f - progress.value) * 0.25f
                        )
                    )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (isPredictive) {
                        translationX = progress.value * screenWidthPx
                    }
                }
        ) {
            content(performAnimatedBack)
        }
    }
}
