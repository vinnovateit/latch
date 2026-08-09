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

            if (!hasCredentials || editingCredentials) {
                CredentialsScreen(
                    onSave = { userId, password ->
                        platform.credentials.save(userId, password)
                        hasCredentials = true
                        editingCredentials = false
                    },
                    // Nothing to go back to on first run. When re-entered from
                    // Settings the old credentials are left untouched until a new
                    // pair is actually saved, so cancelling is safe.
                    onCancel = if (hasCredentials) {
                        { editingCredentials = false }
                    } else {
                        null
                    },
                )
                return@Surface
            }

            // Reference material, not a nav destination -- takes over the whole
            // window the same way CredentialsScreen does above, rather than
            // living in LatchDestination/the rail.
            if (showAbout) {
                AboutScreen(
                    platform = platform,
                    onBack = { showAbout = false },
                )
                return@Surface
            }

            // Updates are mandatory: the moment one is found the window is
            // taken over and there is no way back to the app until it installs.
            // Only the states worth interrupting for take over; a background
            // check that is still Checking, came back UpToDate, or failed
            // silently (Error while nothing was already showing -- see the
            // Error branch below) leaves the user on whatever screen they were
            // on. Note the gate is the *window*, not the app: the tray menu's
            // Connect/Disconnect keep working, so a user whose update cannot
            // complete is never locked out of getting online.
            var showUpdateScreen by remember { mutableStateOf(false) }

            // Remembered so a failed install can be retried directly instead of
            // re-downloading -- the Error state itself only carries a message,
            // not the path the failure happened on.
            var lastDownloadedPath by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(updateState) {
                showUpdateScreen = when (updateState) {
                    is UpdateState.UpdateAvailable,
                    is UpdateState.Downloading,
                    is UpdateState.Downloaded,
                    -> true
                    // A download/install failure should stay on screen so the
                    // error and Retry button are visible; a failed background
                    // check must not pop the takeover up out of nowhere.
                    is UpdateState.Error -> showUpdateScreen
                    else -> false
                }

                // A fresh download invalidates whatever path was remembered
                // from a previous cycle -- GithubUpdater deletes the file on
                // failure, so retrying against a stale path here would hand
                // msiexec a file that no longer exists.
                if (updateState is UpdateState.UpdateAvailable || updateState is UpdateState.Downloading) {
                    lastDownloadedPath = null
                }

                // Downloading is automatic for the same reason installing is:
                // with the update mandatory there is nothing to decide, and a
                // "Download" button would just be a gate the user has to click
                // through. This moves straight to Downloading, so it cannot
                // re-enter and loop.
                if (updateState is UpdateState.UpdateAvailable) {
                    onDownloadUpdate()
                }

                // Installing is automatic once the file is on disk -- there is
                // nothing left for the user to decide at this point, and the
                // MSI silently installing (see GithubUpdater.installAndExit) is
                // the whole point of getting here. onInstallUpdate exits the
                // process on success; a failure surfaces as UpdateState.Error
                // and the Downloaded->Error case above keeps the takeover up.
                val downloaded = updateState as? UpdateState.Downloaded
                if (downloaded != null) {
                    lastDownloadedPath = downloaded.filePath
                    onInstallUpdate(downloaded.filePath)
                }
            }

            if (showUpdateScreen) {
                UpdateScreen(
                    state = updateState,
                    // Retrying after a download failure means fetching again;
                    // after an install failure the file is already on disk, so
                    // retrying means handing that same path to msiexec again.
                    onRetry = {
                        val path = lastDownloadedPath
                        if (path != null) onInstallUpdate(path) else onDownloadUpdate()
                    },
                )
                return@Surface
            }

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
                            fadeIn(tween(220)) togetherWith fadeOut(tween(160))
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
