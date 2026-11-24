@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import cnames.structs.SDL_Window
import com.compose.sdl.ComposeWindowScope
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.delay
import sdl3.SDL_HideWindow
import sdl3.SDL_RaiseWindow
import sdl3.SDL_RestoreWindow
import sdl3.SDL_ShowWindow

/**
 * The SDL window behind the Compose window. Window() ties its lifetime to the
 * composition, so hiding must happen on the SDL handle directly: removing the
 * composable would tear the window down and end the process.
 */
internal object NativeWindowControl {
    private var handle: CPointer<SDL_Window>? = null

    fun attach(raw: COpaquePointer?) {
        handle = raw?.reinterpret()
    }

    fun detach() {
        handle = null
    }

    fun hide() {
        handle?.let { SDL_HideWindow(it) }
    }

    /** Always show, restore and raise — re-launching an already-running app must surface it. */
    fun show() {
        val window = handle ?: return
        SDL_ShowWindow(window)
        SDL_RestoreWindow(window)
        SDL_RaiseWindow(window)
    }
}

/**
 * Wires close-to-tray and the raw handle. A close request is vetoed (returns
 * false) so only the tray's Quit entry can actually end the process.
 */
@Composable
internal fun ComposeWindowScope.NativeWindowLifecycle(startHidden: Boolean) {
    LaunchedEffect(window) {
        repeat(60) {
            val raw = window.rawSdlHandles().window
            if (raw != null) {
                NativeWindowControl.attach(raw)
                if (startHidden) NativeWindowControl.hide()
                return@LaunchedEffect
            }
            delay(16)
        }
    }
    DisposableEffect(window) {
        window.setOnCloseRequest {
            NativeWindowControl.hide()
            false
        }
        onDispose {
            window.setOnCloseRequest(null)
            NativeWindowControl.detach()
        }
    }
}
