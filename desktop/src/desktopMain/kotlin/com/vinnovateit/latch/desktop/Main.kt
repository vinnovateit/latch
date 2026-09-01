package com.vinnovateit.latch.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import com.sun.jna.WString
import com.sun.jna.platform.win32.Shell32
import com.vinnovateit.latch.core.engine.LatchCommand
import com.vinnovateit.latch.core.runtime.DesktopOwnership
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommandService
import com.vinnovateit.latch.core.runtime.claimDesktopOwnership
import com.vinnovateit.latch.ui.LatchRoot
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val APP_DISPLAY_NAME = "LATCH by VinnovateIT"

private fun configureWindowsAppUserModelId() {
    if (!System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
    runCatching {
        Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(APP_DISPLAY_NAME))
    }
}

fun main(args: Array<String>) {
    var onActivateWindow: (() -> Unit)? = null
    val pendingActivation = AtomicBoolean(false)
    val serviceReady = CompletableDeferred<RuntimeCommandService>()
    val ownership = runBlocking {
        claimDesktopOwnership(AppPaths.dataDir) { request ->
            serviceReady.await().execute(request)
        }
    }
    val coordinator = when (ownership) {
        is DesktopOwnership.Owner -> ownership.coordinator
        DesktopOwnership.ActivatedExisting -> return
        is DesktopOwnership.Failure -> {
            System.err.println("Unable to start Latch: ${ownership.message}")
            kotlin.system.exitProcess(1)
        }
    }

    val startHidden = "--hidden" in args
    val app = try {
        LatchApp.create(echoLogsToStdout = System.console() != null || !startHidden)
    } catch (error: Exception) {
        serviceReady.completeExceptionally(error)
        coordinator.close()
        throw error
    }
    serviceReady.complete(
        RuntimeCommandService(
            ownerKind = OwnerKind.DESKTOP,
            runtime = app.runtime,
            onActivateUi = {
                EventQueue.invokeLater {
                    onActivateWindow?.invoke() ?: pendingActivation.set(true)
                }
            },
        ),
    )
    app.start()
    configureWindowsAppUserModelId()

    try {
        application {
            var windowVisible by remember { mutableStateOf(!startHidden) }
            var restoreTrigger by remember { mutableStateOf(0) }

            val openLatch: () -> Unit = {
                windowVisible = true
                restoreTrigger++
            }
            onActivateWindow = openLatch
            if (pendingActivation.getAndSet(false)) openLatch()

            val trayState = rememberTrayState()
            val isLatched by app.engine.isLatched.collectAsState()
            val status by app.engine.status.collectAsState()
            val tooltip by app.notifier.tooltip.collectAsState()
            val updateState by app.updater.state.collectAsState()

            LaunchedEffect(trayState) { app.notifier.trayState = trayState }

            val isLinux = remember { System.getProperty("os.name").contains("Linux", ignoreCase = true) }
            val useLinuxTray = remember {
                isLinux && com.vinnovateit.latch.desktop.platform.linux.LinuxAppIndicatorTray.isSupported()
            }

            val toggleConnect: () -> Unit = {
                val currentStatus = app.engine.status.value
                val latched = app.engine.isLatched.value
                if (latched || currentStatus is com.vinnovateit.latch.core.wifi.ConnectionStatus.Connecting) {
                    app.engine.submit(LatchCommand.Logout)
                } else {
                    app.engine.submit(LatchCommand.CheckAndLogin)
                }
            }

            LaunchedEffect(isLatched, status) {
                val shouldShowDisconnect = isLatched || status is com.vinnovateit.latch.core.wifi.ConnectionStatus.Connecting
                if (useLinuxTray) {
                    com.vinnovateit.latch.desktop.platform.linux.LinuxAppIndicatorTray.init(
                        isLatched = isLatched,
                        onOpenLatch = openLatch,
                        onToggleConnect = toggleConnect,
                        onExitLatch = {
                            com.vinnovateit.latch.desktop.platform.linux.LinuxAppIndicatorTray.stop()
                            app.shutdown()
                            coordinator.close()
                            kotlin.system.exitProcess(0)
                        },
                    )
                    com.vinnovateit.latch.desktop.platform.linux.LinuxAppIndicatorTray.updateStatus(shouldShowDisconnect)
                } else if (isLinux) {
                    kotlinx.coroutines.delay(200)
                    runCatching { patchLinuxTrayIconAlpha(isLatched, openLatch) }
                }
            }

            if (!useLinuxTray) {
                Tray(
                    state = trayState,
                    icon = remember(isLatched) { LatchIcon.forTray(latched = isLatched) },
                    tooltip = tooltip,
                    onAction = openLatch,
                    menu = {
                        Item("Open Latch", onClick = openLatch)
                        Separator()
                        if (isLatched || status is com.vinnovateit.latch.core.wifi.ConnectionStatus.Connecting) {
                            Item("Disconnect", onClick = toggleConnect)
                        } else {
                            Item("Connect", onClick = toggleConnect)
                        }
                        Separator()
                        Item("Exit Latch", onClick = {
                            app.shutdown()
                            coordinator.close()
                            exitApplication()
                        })
                    },
                )
            }

            LatchWindow(
                visible = windowVisible,
                restoreTrigger = restoreTrigger,
                onCloseRequest = { windowVisible = false },
            ) { onMinimize, onClose ->
                val scope = rememberCoroutineScope()
                Surface(modifier = Modifier.fillMaxSize()) {
                    LatchRoot(
                        controller = app.engine,
                        sessions = app.sessions,
                        platform = app.platform,
                        updateState = updateState,
                        onMinimize = onMinimize,
                        onClose = onClose,
                        onCheckForUpdates = { scope.launch { app.updater.check(force = true) } },
                        onDownloadUpdate = { app.downloadUpdate() },
                        onCancelDownload = { app.cancelUpdateDownload() },
                        // Leave only if the installer really started; on a failure
                        // installAndExit has an error for the user to read, which
                        // exiting unconditionally would take down with the process.
                        onInstallUpdate = { path ->
                            if (app.updater.installAndExit(path)) exitApplication()
                        },
                        onDismissUpdate = { app.updater.dismissUpdate() },
                    )
                }
            }
        }
    } finally {
        app.shutdown()
        coordinator.close()
    }
}