@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import com.compose.sdl.Window
import com.compose.sdl.nativeComposeApp
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.runtime.DesktopEngineRuntime
import com.vinnovateit.latch.core.runtime.DesktopOwnership
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommandService
import com.vinnovateit.latch.core.runtime.claimDesktopOwnership
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.core.wifi.ConnectionStatus
import com.vinnovateit.latch.desktop.platform.NativeLogger
import com.vinnovateit.latch.desktop.platform.NativeNotifier
import com.vinnovateit.latch.desktop.platform.NativeTray
import com.vinnovateit.latch.desktop.platform.TrayIconActivation
import com.vinnovateit.latch.ui.LatchRoot
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import com.vinnovateit.latch.ui.chrome.LatchBackHost
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.AtomicInt
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.exit
import platform.posix.sigaction
import platform.posix.sigemptyset
import sdl3.SDL_HINT_NO_SIGNAL_HANDLERS
import sdl3.SDL_SetHint

private const val START_WIDTH = 420
private const val START_HEIGHT = 700

private val signalExitRequested = AtomicInt(0)

private val terminationSignalHandler = staticCFunction<Int, Unit> { _ ->
    signalExitRequested.value = 1
}

private fun installTerminationSignalHandlers() {
    signalExitRequested.value = 0
    SDL_SetHint(SDL_HINT_NO_SIGNAL_HANDLERS, "1")
    memScoped {
        val action = alloc<sigaction>()
        sigemptyset(action.sa_mask.ptr)
        action.__sigaction_handler.sa_handler = terminationSignalHandler
        action.sa_flags = 0
        sigaction(SIGTERM, action.ptr, null)
        sigaction(SIGINT, action.ptr, null)
    }
}

fun main(args: Array<String>) {
    installTerminationSignalHandlers()
    val startHidden = "--hidden" in args
    val startupLogger = NativeLogger(echoToStdout = !startHidden)
    val serviceReady = CompletableDeferred<RuntimeCommandService>()
    var requestShow: (() -> Unit)? = null
    val handler: suspend (com.vinnovateit.latch.core.runtime.InstanceRequest) -> com.vinnovateit.latch.core.runtime.InstanceResponse =
        { request -> serviceReady.await().execute(request) }

    val ownership = runBlocking {
        claimDesktopOwnership(JFile(AppPaths.dataDir.path), handler = handler)
    }

    val coordinator = when (ownership) {
        is DesktopOwnership.Owner -> ownership.coordinator
        is DesktopOwnership.ActivatedExisting -> {
            startupLogger.d("Main", "Activated existing Latch desktop instance.")
            return
        }
        is DesktopOwnership.Failure -> {
            val message = "Unable to start Latch: ${ownership.message}"
            startupLogger.e("Main", message)
            println(message)
            exit(1)
            return
        }
    }

    val notifier = NativeNotifier()
    val app = try {
        runBlocking {
            val created = DesktopEngineRuntime.create(notifier, echoLogsToStdout = !startHidden, syncHistoryOnStart = true)
            created.start()
            created.engine.submit(
                if (SettingsManager.autoLogin.value) LatchCommand.CheckAndLogin else LatchCommand.SilentCheck,
            )
            serviceReady.complete(
                RuntimeCommandService(OwnerKind.DESKTOP, created, onActivateUi = { requestShow?.invoke() }),
            )
            created
        }
    } catch (error: Throwable) {
        startupLogger.e("Main", "Failed to start Latch runtime: ${error.message}", error)
        serviceReady.completeExceptionally(error)
        coordinator.close()
        exit(1)
        throw error
    }

    try {
        if (signalExitRequested.value == 0) nativeComposeApp {
            val appScope = this
            LaunchedEffect(Unit) {
                while (isActive) {
                    if (signalExitRequested.value != 0) {
                        appScope.exitApplication()
                        break
                    }
                    delay(50)
                }
            }
            // Manual launch (desktop entry / terminal) opens the window.
            // Only autostart / launch-at-login passes --hidden to stay in the
            // tray with no taskbar icon.
            val showRequest = remember { mutableIntStateOf(0) }
            val appMenu = remember { AppMenuHost() }
            val backHost = remember { LatchBackHost() }
            val tray = remember { NativeTray() }

            // ACTIVATE_UI arrives on the instance listener's background thread.
            // Bump a snapshot counter there; do the SDL show on the main thread.
            requestShow = { showRequest.intValue++ }
            LaunchedEffect(showRequest.intValue) {
                if (showRequest.intValue > 0) NativeWindowControl.show()
            }

            val status by app.engine.status.collectAsState()
            val isLatched by app.engine.isLatched.collectAsState()
            val shouldDisconnect = isLatched
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
                    onOpen = { requestShow() },
                    onToggleConnect = {
                        if (latestShouldDisconnect.value) {
                            SettingsManager.setAutoLogin(false)
                            app.engine.submit(LatchCommand.Logout)
                        } else {
                            SettingsManager.setAutoLogin(true)
                            app.engine.submit(LatchCommand.CheckAndLogin)
                        }
                    },
                    onExit = { appScope.exitApplication() },
                )
                // No tray on this host (e.g. missing libappindicator): show the
                // window instead of leaving an unreachable hidden process.
                val activation = if (trayReady) {
                    TrayIconActivation { requestShow() }.also { it.start() }
                } else null
                if (!trayReady && startHidden) requestShow()
                onDispose {
                    activation?.stop()
                    tray.destroy()
                }
            }

            // The window lives for the whole process; closing hides it to the
            // tray (NativeWindowLifecycle vetoes the close), and only the tray's
            // Quit entry ends the app.
            Window(
                title = "Latch",
                width = START_WIDTH,
                height = START_HEIGHT,
                undecorated = false,
                resizable = true,
                onCloseRequest = {},
                onKeyEvent = { event ->
                    val isBack = event.key == Key.Backspace ||
                        (event.isAltPressed && event.key == Key.DirectionLeft)
                    if (isBack && event.type == KeyEventType.KeyDown) {
                        backHost.onBack?.invoke()
                        true
                    } else false
                },
            ) {
                NativeWindowLifecycle(startHidden = startHidden)
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
                    backHost = backHost,
                )
            }
        }
    } finally {
        runBlocking { app.close() }
        coordinator.close()
    }
    exit(0)
}
