package com.vinnovateit.latch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.domain.SessionRepository
import com.vinnovateit.latch.core.engine.LatchController
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.desktop.LatchMark
import com.vinnovateit.latch.ui.components.LatchHomeTopBar
import com.vinnovateit.latch.ui.components.WindowControlButtons
import com.vinnovateit.latch.ui.navigation.LatchDestination
import com.vinnovateit.latch.ui.screens.AboutScreen
import com.vinnovateit.latch.ui.screens.CredentialsScreen
import com.vinnovateit.latch.ui.screens.HomeScreen
import com.vinnovateit.latch.ui.screens.SettingsScreen
import com.vinnovateit.latch.ui.screens.StatsScreen
import com.vinnovateit.latch.ui.screens.UpdateScreen
import com.vinnovateit.latch.ui.theme.LatchTheme

/** Below this width the app uses the Android-style overflow menu instead of a rail. */
private val RailBreakpoint = 900.dp

/**
 * The whole window: theme, credential gate, and the three-screen shell.
 *
 * Navigation adapts rather than picking one idiom and forcing it. A wide window
 * gets a persistent navigation rail, which is what a desktop user expects and
 * what makes Stats and Settings one click away; a narrow one falls back to the
 * Android app's own pattern, an overflow menu in the top bar with a back arrow
 * on the secondary screens. Neither mode leaves a dead affordance on screen.
 */
@Composable
fun LatchRoot(
    controller: LatchController,
    sessions: SessionRepository,
    platform: PlatformServices,
    updateState: UpdateState,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallUpdate: (String) -> Unit,
    onDismissUpdate: () -> Unit,
) {
    LatchTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var hasCredentials by remember { mutableStateOf(platform.credentials.exists()) }
            var editingCredentials by remember { mutableStateOf(false) }
            var showAbout by remember { mutableStateOf(false) }
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
                !hasCredentials || editingCredentials -> "Credentials"
                showAbout -> "About"
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
                        "Credentials" -> {
                            CredentialsScreen(
                                initialRegNo = platform.credentials.userId().orEmpty(),
                                initialPassword = platform.credentials.password().orEmpty(),
                                onSave = { userId, password ->
                                    platform.credentials.save(userId, password)
                                    hasCredentials = true
                                    editingCredentials = false
                                },
                                onCancel = if (hasCredentials) {
                                    { editingCredentials = false }
                                } else {
                                    null
                                },
                            )
                        }

                        "About" -> {
                            AboutScreen(
                                platform = platform,
                                onBack = { showAbout = false },
                                updateState = updateState,
                                onCheckForUpdates = onCheckForUpdates,
                                onDownloadUpdate = onDownloadUpdate,
                                onCancelDownload = onCancelDownload,
                                onInstallUpdate = onInstallUpdate,
                                onDismissUpdate = onDismissUpdate,
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

                                Row(modifier = Modifier.fillMaxSize()) {
                                    if (railVisible) {
                                        LatchNavigationRail(
                                            selected = destination,
                                            onSelect = { destination = it },
                                        )
                                    }

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
                                        val back: (() -> Unit)? = if (railVisible) {
                                            null
                                        } else {
                                            { destination = LatchDestination.Home }
                                        }

                                        when (current) {
                                            LatchDestination.Home -> HomeScreen(
                                                controller = controller,
                                                sessions = sessions,
                                                platform = platform,
                                                onOpenStats = { destination = LatchDestination.Stats },
                                                onOpenSettings = { destination = LatchDestination.Settings },
                                                onOpenAbout = { showAbout = true },
                                                showNavigationMenuItems = !railVisible,
                                            )

                                            LatchDestination.Stats -> StatsScreen(
                                                sessions = sessions,
                                                onBack = back,
                                                onClearHistory = { sessions.clearHistory() },
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

                // Window control buttons static overlay: always pinned to TopEnd
                WindowControlButtons(
                    onMinimize = onMinimize,
                    onClose = onClose,
                    modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd),
                )
            }
        }
    }
}

@Composable
private fun LatchNavigationRail(
    selected: LatchDestination,
    onSelect: (LatchDestination) -> Unit,
) {
    NavigationRail(
        containerColor = Color.Transparent,
        header = {
            Box(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                Icon(
                    imageVector = LatchMark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(34.dp),
                )
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        LatchDestination.entries.forEach { entry ->
            NavigationRailItem(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                icon = { Icon(entry.icon, contentDescription = entry.label) },
                label = { Text(entry.label) },
            )
        }
    }
}
