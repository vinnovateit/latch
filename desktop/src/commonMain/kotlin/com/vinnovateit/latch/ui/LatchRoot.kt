package com.vinnovateit.latch.ui

import kotlinx.coroutines.launch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.Surface
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchEngine
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.chrome.AppMenuActions
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import com.vinnovateit.latch.ui.chrome.LatchBackHost
import com.vinnovateit.latch.ui.components.HowItWorksDialog
import com.vinnovateit.latch.ui.components.LatchStatusPill
import com.vinnovateit.latch.ui.navigation.LatchDestination
import com.vinnovateit.latch.ui.onboarding.DesktopOnboardingScreen
import com.vinnovateit.latch.ui.screens.AboutScreen
import com.vinnovateit.latch.ui.screens.CredentialsScreen
import com.vinnovateit.latch.ui.screens.HomeScreen
import com.vinnovateit.latch.ui.screens.SettingsScreen
import com.vinnovateit.latch.ui.screens.StatsScreen
import com.vinnovateit.latch.ui.screens.UpdateScreen
import com.vinnovateit.latch.ui.theme.LatchTheme

/** Below this width the rail cannot fit, so navigation moves into a drawer. */
private val RailBreakpoint = 900.dp
private val WideRailExpandedWidth = 900.dp

/**
 * The whole window: theme, credential gate, and the navigation shell.
 *
 * A wide window keeps the persistent wide navigation rail. Below [RailBreakpoint]
 * the rail is replaced by a hamburger that opens the very same rail in a drawer.
 */
@Composable
fun LatchRoot(
    controller: LatchEngine,
    sessions: SessionRepository,
    platform: PlatformServices,
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallUpdate: (String) -> Unit,
    onDismissUpdate: () -> Unit,
    /**
     * Window chrome to publish the application menu to. Null when there is no
     * chrome, which is how tests and previews render this screen.
     */
    appMenu: AppMenuHost? = null,
    backHost: LatchBackHost? = null,
) {
    LatchTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var hasCredentials by remember { mutableStateOf(platform.credentials.exists()) }
            val isLatchedForDrawer by controller.isLatched.collectAsStateWithLifecycle()
            val hasSeenOnboarding by SettingsManager.hasSeenOnboarding.collectAsStateWithLifecycle()
            val onboardingPagerState = rememberPagerState(initialPage = 0, pageCount = { 6 })
            var editingCredentials by remember { mutableStateOf(false) }
            var validatingCredentials by remember { mutableStateOf(false) }
            var credentialValidationError by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            var showHowItWorks by remember { mutableStateOf(false) }
            var destination by remember { mutableStateOf(LatchDestination.Home) }

            // Surfaced the moment an update is found rather than left for
            // someone to stumble on in Settings.
            var showUpdateScreen by remember { mutableStateOf(false) }
            var lastDownloadedPath by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(updateState) {
                showUpdateScreen = when (updateState) {
                    is UpdateState.UpdateAvailable,
                    is UpdateState.Downloading,
                    is UpdateState.Downloaded,
                    -> true
                    is UpdateState.Error -> showUpdateScreen
                    else -> false
                }

                if (updateState is UpdateState.UpdateAvailable || updateState is UpdateState.Downloading) {
                    lastDownloadedPath = null
                }

                val downloaded = updateState as? UpdateState.Downloaded
                if (downloaded != null) {
                    lastDownloadedPath = downloaded.filePath
                    onInstallUpdate(downloaded.filePath)
                }
            }

            val currentRootScreen = when {
                editingCredentials -> "Credentials"
                !hasSeenOnboarding -> "Onboarding"
                !hasCredentials -> "Credentials"
                showUpdateScreen -> "Update"
                else -> "Main"
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = currentRootScreen,
                    transitionSpec = {
                        if (targetState != "Main") {
                            // Forward takeover transition: slide in from right to left
                            (androidx.compose.animation.slideInHorizontally(
                                initialOffsetX = { fullWidth -> fullWidth },
                                animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            ) + fadeIn(tween(110))) togetherWith (
                                androidx.compose.animation.slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> -fullWidth / 3 },
                                    animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                ) + fadeOut(tween(90))
                            )
                        } else {
                            // Return to main transition: slide out to right
                            (androidx.compose.animation.slideInHorizontally(
                                initialOffsetX = { fullWidth -> -fullWidth / 3 },
                                animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            ) + fadeIn(tween(110))) togetherWith (
                                androidx.compose.animation.slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                ) + fadeOut(tween(90))
                            )
                        }
                    },
                    label = "LatchRootScreen",
                    modifier = Modifier.fillMaxSize(),
                ) { rootScreen ->
                    when (rootScreen) {
                        "Onboarding" -> {
                            DesktopOnboardingScreen(
                                hasCredentials = hasCredentials,
                                onComplete = {
                                    SettingsManager.setHasSeenOnboarding(true)
                                },
                                onNavigateToCredentials = {
                                    editingCredentials = true
                                },
                                pagerState = onboardingPagerState,
                            )
                        }

                        "Credentials" -> {
                            CredentialsScreen(
                                initialRegNo = platform.credentials.userId().orEmpty(),
                                initialPassword = platform.credentials.password().orEmpty(),
                                isBusy = validatingCredentials,
                                validationError = credentialValidationError,
                                onSave = { userId, password ->
                                    // Validate against the publicly reachable account
                                    // service before storing. This never uses the
                                    // captive-portal login path.
                                    credentialValidationError = null
                                    validatingCredentials = true
                                    scope.launch {
                                        val result = kotlinx.coroutines.withContext(com.vinnovateit.latch.core.mp.ioDispatcher) {
                                            com.vinnovateit.latch.core.portal.PortalHistoryClient(platform.httpTransport)
                                                .validateCredentials(userId, password)
                                        }
                                        validatingCredentials = false
                                        when {
                                            result.isFailure -> credentialValidationError =
                                                "Could not verify credentials: ${result.exceptionOrNull()?.message ?: "network error"}"
                                            result.getOrNull() == false -> credentialValidationError =
                                                "Incorrect registration number or password"
                                            else -> platform.credentials.save(userId, password).fold(
                                                onSuccess = {
                                                    hasCredentials = true
                                                    editingCredentials = false
                                                    // Populate stats immediately after credentials are
                                                    // accepted (original PR #148 behaviour).
                                                    scope.launch {
                                                        sessions.syncPortalHistory(userId, password, force = true)
                                                    }
                                                },
                                                onFailure = {
                                                    platform.notifier.notifyTransient(
                                                        "Latch",
                                                        "Unable to save credentials securely.",
                                                        isError = true,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                },
                                onCancel = if (hasCredentials || !hasSeenOnboarding) {
                                    { editingCredentials = false }
                                } else {
                                    null
                                },
                            )
                        }

                        "Update" -> {
                            UpdateScreen(
                                state = updateState,
                                onDownload = onDownloadUpdate,
                                onCancelDownload = onCancelDownload,
                                onRetry = {
                                    val path = lastDownloadedPath
                                    if (path != null) onInstallUpdate(path) else onDownloadUpdate()
                                },
                                onSkip = onDismissUpdate,
                            )
                        }

                        else -> {
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val railVisible = maxWidth >= RailBreakpoint
                                val railExpanded = maxWidth >= WideRailExpandedWidth

                                // Back handling only exists for secondary screens.
                                DisposableEffect(backHost, destination) {
                                    backHost?.publish(
                                        if (destination != LatchDestination.Home) {
                                            { destination = LatchDestination.Home }
                                        } else null,
                                    )
                                    onDispose { backHost?.publish(null) }
                                }

                                // The window chrome only carries a menu when the
                                // rail is not providing navigation itself.
                                DisposableEffect(appMenu, railVisible, destination) {
                                    appMenu?.publish(
                                        AppMenuActions(
                                            showSettings = !railVisible &&
                                                destination != LatchDestination.Settings,
                                            showMenu = !railVisible,
                                            onOpenSettings = { destination = LatchDestination.Settings },
                                            onHowItWorks = { showHowItWorks = true },
                                            onOpenAbout = { destination = LatchDestination.About },
                                        ),
                                    )
                                    onDispose { appMenu?.publish(null) }
                                }

                                val screenContent: @Composable () -> Unit = {
                                    AnimatedContent(
                                        targetState = destination,
                                        transitionSpec = {
                                            if (targetState.ordinal > initialState.ordinal) {
                                                // Slide in from right when opening a deeper section
                                                (androidx.compose.animation.slideInHorizontally(
                                                    initialOffsetX = { fullWidth -> fullWidth },
                                                    animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                                ) + fadeIn(tween(100))) togetherWith (
                                                    androidx.compose.animation.slideOutHorizontally(
                                                        targetOffsetX = { fullWidth -> -fullWidth / 3 },
                                                        animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                                    ) + fadeOut(tween(90))
                                                )
                                            } else {
                                                // Slide in from left when returning to previous section
                                                (androidx.compose.animation.slideInHorizontally(
                                                    initialOffsetX = { fullWidth -> -fullWidth / 3 },
                                                    animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                                ) + fadeIn(tween(100))) togetherWith (
                                                    androidx.compose.animation.slideOutHorizontally(
                                                        targetOffsetX = { fullWidth -> fullWidth },
                                                        animationSpec = tween(150, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                                                    ) + fadeOut(tween(90))
                                                )
                                            }
                                        },
                                        label = "LatchDestination",
                                        modifier = Modifier.fillMaxSize(),
                                    ) { current ->
                                        // The compact row's menu replaces the back button.
                                        val back: (() -> Unit)? = null

                                        when (current) {
                                            LatchDestination.Home -> HomeScreen(
                                                controller = controller,
                                                sessions = sessions,
                                                platform = platform,
                                                onOpenStats = { destination = LatchDestination.Stats },
                                                // The compact row already shows the status pill.
                                                showStatusBadge = false,
                                            )

                                            LatchDestination.Stats -> StatsScreen(
                                                sessions = sessions,
                                                platform = platform,
                                                onBack = back,
                                                onClearHistory = { sessions.clearHistory() },
                                            )

                                            LatchDestination.About -> AboutScreen(
                                                platform = platform,
                                                onBack = back,
                                                updateState = updateState,
                                                onCheckForUpdates = onCheckForUpdates,
                                                onDownloadUpdate = onDownloadUpdate,
                                                onCancelDownload = onCancelDownload,
                                                onInstallUpdate = onInstallUpdate,
                                                onDismissUpdate = onDismissUpdate,
                                            )

                                            LatchDestination.Settings -> SettingsScreen(
                                                platform = platform,
                                                updateState = updateState,
                                                onBack = back,
                                                onNavigateToCredentials = { editingCredentials = true },
                                                onClearStats = { sessions.clearHistory() },
                                                onCheckForUpdates = onCheckForUpdates,
                                                onDownloadUpdate = onDownloadUpdate,
                                                onCancelDownload = onCancelDownload,
                                                onInstallUpdate = onInstallUpdate,
                                                onDismissUpdate = onDismissUpdate,
                                            )
                                        }
                                    }
                                }

                                if (railVisible) {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        LatchNavigationRail(
                                            selected = destination,
                                            onSelect = { destination = it },
                                            onHowItWorks = { showHowItWorks = true },
                                            isLatched = isLatchedForDrawer,
                                            expanded = railExpanded,
                                        )
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                            screenContent()
                                        }
                                    }
                                } else {
                                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                                    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
                                    ModalNavigationDrawer(
                                        drawerState = drawerState,
                                        drawerContent = {
                                            LatchRailDrawerContent(
                                                selected = destination,
                                                onSelect = { destination = it },
                                                onHowItWorks = { showHowItWorks = true },
                                                isLatched = isLatchedForDrawer,
                                                onClose = closeDrawer,
                                            )
                                        },
                                    ) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(56.dp)
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .padding(start = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                                    Icon(LatchIcons.Menu, contentDescription = "Open navigation menu")
                                                }
                                                if (destination == LatchDestination.Home) {
                                                    Spacer(Modifier.size(4.dp))
                                                    LatchStatusPill(isLatched = isLatchedForDrawer)
                                                }
                                            }
                                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                                screenContent()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showHowItWorks) {
                    HowItWorksDialog(onDismiss = { showHowItWorks = false })
                }
            }
        }
    }
}

/**
 * The drawer is the exact same WideNavigationRail the wide layout used —
 * same header, same status badge, same item padding and content-sized items.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LatchRailDrawerContent(
    selected: LatchDestination,
    onSelect: (LatchDestination) -> Unit,
    onHowItWorks: () -> Unit,
    isLatched: Boolean,
    onClose: () -> Unit,
) {
    val railState = rememberWideNavigationRailState(
        initialValue = WideNavigationRailValue.Expanded,
    )
    WideNavigationRail(
        state = railState,
        header = {
            // Branded header: the Latch mark + LATCH wordmark in the display
            // font. Not a FAB — the rail's Home item handles navigation.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // App identity is in the OS title bar now; the rail only carries status.
                // Connection status, as on the rail.
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (isLatched) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = if (isLatched) "LATCHED" else "DISCONNECTED",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLatched) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontFamily = com.vinnovateit.latch.ui.theme.satoshiFontFamily(
                            androidx.compose.ui.text.font.FontWeight.Bold,
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) {
        LatchDestination.entries.forEach { entry ->
            WideNavigationRailItem(
                selected = entry == selected,
                onClick = { onSelect(entry); onClose() },
                icon = { Icon(entry.icon, contentDescription = null) },
                label = { Text(entry.label) },
                railExpanded = true,
                iconPosition = NavigationItemIconPosition.Start,
            )
        }
        WideNavigationRailItem(
            selected = false,
            onClick = { onHowItWorks(); onClose() },
            icon = { Icon(LatchIcons.Help, contentDescription = null) },
            label = { Text("How it works") },
            railExpanded = true,
            iconPosition = NavigationItemIconPosition.Start,
        )
    }
}

/** The persistent wide navigation rail used when there is room for it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LatchNavigationRail(
    selected: LatchDestination,
    onSelect: (LatchDestination) -> Unit,
    onHowItWorks: () -> Unit,
    isLatched: Boolean,
    expanded: Boolean = true,
) {
    val railState = rememberWideNavigationRailState(
        initialValue = if (expanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
    )
    LaunchedEffect(expanded) {
        if (expanded) railState.expand() else railState.collapse()
    }

    WideNavigationRail(
        state = railState,
        header = {
            // Branded header: the Latch mark + LATCH wordmark in the display
            // font. Not a FAB — the rail's Home item handles navigation.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // App identity is in the OS title bar now; the rail only carries status.
                // Connection status, as before.
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (isLatched) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = if (isLatched) "LATCHED" else "DISCONNECTED",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isLatched) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontFamily = com.vinnovateit.latch.ui.theme.satoshiFontFamily(
                            androidx.compose.ui.text.font.FontWeight.Bold,
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) {
        LatchDestination.entries.forEach { entry ->
            WideNavigationRailItem(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = null) },
                label = { Text(entry.label) },
                railExpanded = railState.currentValue == WideNavigationRailValue.Expanded,
                iconPosition = NavigationItemIconPosition.Start,
            )
        }
        WideNavigationRailItem(
            selected = false,
            onClick = onHowItWorks,
            icon = { Icon(LatchIcons.Help, contentDescription = null) },
            label = { Text("How it works") },
            railExpanded = railState.currentValue == WideNavigationRailValue.Expanded,
            iconPosition = NavigationItemIconPosition.Start,
        )
    }
}
