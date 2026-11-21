@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.compose.sdl.Window
import com.compose.sdl.nativeComposeApp
import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.DesktopEngineRuntime
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommandService
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.desktop.platform.NativeNotifier
import com.vinnovateit.latch.desktop.platform.NativeTray
import com.vinnovateit.latch.ui.LatchRoot
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

private const val START_WIDTH = 420
private const val START_HEIGHT = 700

fun main(args: Array<String>) {
    val serviceReady = CompletableDeferred<RuntimeCommandService>()
    var requestShow: (() -> Unit)? = null
    val handler: suspend (com.vinnovateit.latch.core.runtime.InstanceRequest) -> com.vinnovateit.latch.core.runtime.InstanceResponse =
        { request -> serviceReady.await().execute(request) }

    val ownership = runBlocking {
        InstanceCoordinator.tryAcquire(JFile(AppPaths.dataDir.path), OwnerKind.DESKTOP, handler)
    }

    val (runtime, coordinator) = when (ownership) {
        is AcquireResult.Owner -> ownership.coordinator to ownership.coordinator
        is AcquireResult.Existing -> {
            // Another Latch instance owns the runtime; ask it to show its window.
            runBlocking { ownership.client.send(com.vinnovateit.latch.core.runtime.RuntimeCommand.ACTIVATE_UI) }
            return
        }
        is AcquireResult.Failure -> {
            println("Unable to start Latch: ${ownership.message}")
            return
        }
    }

    val notifier = NativeNotifier()
    val app = try {
        runBlocking {
            val created = DesktopEngineRuntime.create(notifier, echoLogsToStdout = true, syncHistoryOnStart = true)
            created.start()
            serviceReady.complete(
                RuntimeCommandService(OwnerKind.DESKTOP, created, onActivateUi = { requestShow?.invoke() }),
            )
            created
        }
    } catch (error: Throwable) {
        serviceReady.completeExceptionally(error)
        coordinator.close()
        throw error
    }

    try {
        nativeComposeApp {
            val appScope = this
            // Tray-only launch: never create the SDL window (and its taskbar
            // icon) at startup. "Open Latch" from the tray or ACTIVATE_UI flips
            // this to true, which composes the Window on the next frame.
            val windowVisible = remember { mutableStateOf(false) }
            val appMenu = remember { AppMenuHost() }
            val tray = remember { NativeTray() }

            requestShow = { windowVisible.value = true }

            val status by app.engine.status.collectAsState()
            val isLatched by app.engine.isLatched.collectAsState()
            val shouldDisconnect = isLatched || status is ConnectionStatus.Connecting
            val latestShouldDisconnect = rememberUpdatedState(shouldDisconnect)

            val ongoingTooltip = remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                notifier.onOngoing = { title, text ->
                    ongoingTooltip.value = if (text.isBlank()) title else "$title\n$text"
                }
                notifier.onHideOngoing = { ongoingTooltip.value = null }
            }
            DisposableEffect(Unit) {
                onDispose {
                    notifier.onOngoing = null
                    notifier.onHideOngoing = null
                }
            }

            val statusText = when {
                isLatched -> "Status: Connected"
                status is ConnectionStatus.Connecting -> "Status: Connecting…"
                status is ConnectionStatus.Failed -> "Status: Disconnected"
                else -> "Status: Idle"
            }
            val tooltip = ongoingTooltip.value ?: when {
                isLatched -> "Latch — Connected"
                status is ConnectionStatus.Connecting -> "Latch — Connecting…"
                else -> "Latch — Disconnected"
            }

            LaunchedEffect(tooltip, statusText, shouldDisconnect) {
                tray.setTooltip(tooltip)
                tray.setStatus(statusText, shouldDisconnect)
            }

            DisposableEffect(Unit) {
                val trayReady = tray.start(
                    tooltip = tooltip,
                    showDisconnect = shouldDisconnect,
                    statusText = statusText,
                    onOpen = { windowVisible.value = true },
                    onToggleConnect = {
                        if (latestShouldDisconnect.value) app.engine.submit(LatchCommand.Logout)
                        else app.engine.submit(LatchCommand.CheckAndLogin)
                    },
                    onExit = { appScope.exitApplication() },
                )
                // No tray on this host (e.g. missing libappindicator): show the
                // window instead of leaving an unreachable hidden process.
                if (!trayReady) windowVisible.value = true
                onDispose { tray.destroy() }
            }

            if (windowVisible.value) {
                Window(
                    title = "Latch",
                    width = START_WIDTH,
                    height = START_HEIGHT,
                    undecorated = true,
                    resizable = true,
                    onCloseRequest = { windowVisible.value = false },
                ) {
                    LatchWindow(onCloseRequest = { windowVisible.value = false }, appMenu = appMenu) {
                        LatchRoot(
                            controller = app.engine,
                            sessions = app.sessions,
                            platform = app.platform,
                            updateState = UpdateState.Idle,
                            onCheckForUpdates = {},
                            onDownloadUpdate = {},
                            onCancelDownload = {},
                            onInstallUpdate = {},
                            onDismissUpdate = {},
                            appMenu = appMenu,
                        )
                    }
                }
            }
        }
    } finally {
        runBlocking { app.close() }
        coordinator.close()
    }
}
