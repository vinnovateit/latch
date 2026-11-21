@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.compose.sdl.Window
import com.compose.sdl.nativeComposeApp
import com.vinnovateit.latch.core.mp.JFile
import com.vinnovateit.latch.core.runtime.AcquireResult
import com.vinnovateit.latch.core.runtime.DesktopEngineRuntime
import com.vinnovateit.latch.core.runtime.InstanceCoordinator
import com.vinnovateit.latch.core.runtime.OwnerKind
import com.vinnovateit.latch.core.runtime.RuntimeCommandService
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.desktop.platform.NativeNotifier
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

    val app = try {
        runBlocking {
            val created = DesktopEngineRuntime.create(NativeNotifier(), echoLogsToStdout = true, syncHistoryOnStart = true)
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
            val windowVisible = remember { mutableStateOf("--hidden" !in args) }
            val appMenu = remember { AppMenuHost() }

            requestShow = { windowVisible.value = true }

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
