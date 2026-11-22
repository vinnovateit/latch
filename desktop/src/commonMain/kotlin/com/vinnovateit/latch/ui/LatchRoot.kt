package com.vinnovateit.latch.ui

import kotlinx.coroutines.launch

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
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
import com.vinnovateit.latch.desktop.LatchMark
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.theme.modernizFontFamily
import com.vinnovateit.latch.ui.chrome.AppMenuActions
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import com.vinnovateit.latch.ui.chrome.LatchBackHost
import com.vinnovateit.latch.ui.components.HowItWorksDialog
import com.vinnovateit.latch.ui.navigation.LatchDestination
import com.vinnovateit.latch.ui.onboarding.DesktopOnboardingScreen
import com.vinnovateit.latch.ui.screens.AboutScreen
import com.vinnovateit.latch.ui.screens.CredentialsScreen
import com.vinnovateit.latch.ui.screens.HomeScreen
import com.vinnovateit.latch.ui.screens.SettingsScreen
import com.vinnovateit.latch.ui.screens.StatsScreen
import com.vinnovateit.latch.ui.screens.UpdateScreen
import com.vinnovateit.latch.ui.theme.LatchTheme

/**
 * The whole window: theme, credential gate, and the navigation shell.
 *
 * The OS draws the window frame; the only in-app chrome is the hamburger at the
 * top left, which opens the navigation drawer (Home / Stats / Settings / About
 * plus How it works). Secondary screens keep a back arrow.
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
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Visibility rule: the hamburger exists only while
                                // this shell is mounted, so onboarding, credential
                                // setup, About and the update screen never show it.
                                DisposableEffect(backHost, destination) {
                                    backHost?.publish(
                                        if (destination != LatchDestination.Home) {
                                            { destination = LatchDestination.Home }
                                        } else null,
                                    )
                                    onDispose { backHost?.publish(null) }
                                }

                                DisposableEffect(appMenu, destination) {
                                    appMenu?.publish(
                                        AppMenuActions(
                                            showSettings = destination != LatchDestination.Settings,
                                            showMenu = true,
                                            onOpenSettings = { destination = LatchDestination.Settings },
                                            onHowItWorks = { showHowItWorks = true },
                                            onOpenAbout = { destination = LatchDestination.About },
                                        ),
                                    )
                                    onDispose { appMenu?.publish(null) }
                                }

                                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                                val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }
                                ModalNavigationDrawer(
                                    drawerState = drawerState,
                                    drawerContent = {
                                        LatchDrawerContent(
                                            selected = destination,
                                            onSelect = { destination = it },
                                            onHowItWorks = { showHowItWorks = true },
                                            isLatched = isLatchedForDrawer,
                                            onClose = closeDrawer,
                                        )
                                    },
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // The only chrome in the app: the system draws
                                        // the window frame, this opens the nav drawer.
                                        Box(
                                            modifier = Modifier.height(56.dp).padding(start = 4.dp),
                                            contentAlignment = Alignment.CenterStart,
                                        ) {
                                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                                Icon(LatchIcons.Menu, contentDescription = "Open navigation menu")
                                            }
                                        }

                                        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                            ) { current ->
                                                val back: (() -> Unit)? = { destination = LatchDestination.Home }

                                                when (current) {
                                                    LatchDestination.Home -> HomeScreen(
                                                        controller = controller,
                                                        sessions = sessions,
                                                        platform = platform,
                                                        onOpenStats = { destination = LatchDestination.Stats },
                                                        showStatusBadge = true,
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
 * The drawer reproduces the former wide navigation rail exactly: same branded
 * header, same status badge, same destinations in the same order and icons.
 */
@Composable
private fun LatchDrawerContent(
    selected: LatchDestination,
    onSelect: (LatchDestination) -> Unit,
    onHowItWorks: () -> Unit,
    isLatched: Boolean,
    onClose: () -> Unit,
) {
    ModalDrawerSheet {
        // Branded header: the Latch mark + LATCH wordmark in the display font.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                modifier = Modifier
                    .padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(LatchDestination.Home); onClose() },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = LatchMark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = "LATCH",
                    fontFamily = modernizFontFamily(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            // Connection status sits under the wordmark, as on the rail.
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

        LatchDestination.entries.forEach { entry ->
            NavigationDrawerItem(
                label = { Text(entry.label) },
                selected = entry == selected,
                icon = { Icon(entry.icon, contentDescription = null) },
                onClick = { onSelect(entry); onClose() },
            )
        }
        NavigationDrawerItem(
            label = { Text("How it works") },
            selected = false,
            icon = { Icon(LatchIcons.Help, contentDescription = null) },
            onClick = { onHowItWorks(); onClose() },
        )
    }
}
