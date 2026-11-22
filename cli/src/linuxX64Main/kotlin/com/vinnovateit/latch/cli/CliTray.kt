@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.cli

import cnames.structs.SDL_Tray
import cnames.structs.SDL_TrayEntry
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import kotlinx.cinterop.refTo
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlin.concurrent.Volatile
import platform.posix.memcpy
import sdl3.SDL_CreateSurface
import sdl3.SDL_CreateTray
import sdl3.SDL_CreateTrayMenu
import sdl3.SDL_DestroySurface
import sdl3.SDL_DestroyTray
import sdl3.SDL_GetError
import sdl3.SDL_Init
import sdl3.SDL_INIT_EVENTS
import sdl3.SDL_INIT_VIDEO
import sdl3.SDL_InsertTrayEntryAt
import sdl3.SDL_PIXELFORMAT_RGBA32
import sdl3.SDL_SetTrayEntryCallback
import sdl3.SDL_SetTrayEntryLabel
import sdl3.SDL_SetTrayTooltip
import sdl3.SDL_Surface
import sdl3.SDL_TRAYENTRY_BUTTON
import sdl3.SDL_TRAYENTRY_DISABLED

/** SDL invokes these callbacks from its own event pump thread. */
private object CliTrayActions {
    @Volatile var open: (() -> Unit)? = null
    @Volatile var toggleConnect: (() -> Unit)? = null
    @Volatile var exit: (() -> Unit)? = null
}

private val openCallback = staticCFunction<COpaquePointer?, CPointer<SDL_TrayEntry>?, Unit> { _, _ ->
    CliTrayActions.open?.invoke()
}
private val toggleCallback = staticCFunction<COpaquePointer?, CPointer<SDL_TrayEntry>?, Unit> { _, _ ->
    CliTrayActions.toggleConnect?.invoke()
}
private val exitCallback = staticCFunction<COpaquePointer?, CPointer<SDL_TrayEntry>?, Unit> { _, _ ->
    CliTrayActions.exit?.invoke()
}

/**
 * System tray for the CLI daemon — the same icon/status/Connect/Exit behaviour
 * as the GUI. SDL routes this through AppIndicator on Linux.
 */
internal class CliTray {
    private var tray: CPointer<SDL_Tray>? = null
    private var icon: CPointer<SDL_Surface>? = null
    private var statusEntry: CPointer<SDL_TrayEntry>? = null
    private var connectEntry: CPointer<SDL_TrayEntry>? = null

    fun start(
        tooltip: String,
        statusText: String,
        showDisconnect: Boolean,
        onOpen: () -> Unit,
        onToggleConnect: () -> Unit,
        onExit: () -> Unit,
    ): Boolean {
        if (tray != null) return true
        CliTrayActions.open = onOpen
        CliTrayActions.toggleConnect = onToggleConnect
        CliTrayActions.exit = onExit

        if (!SDL_Init(SDL_INIT_VIDEO or SDL_INIT_EVENTS)) {
            println("tray: SDL_Init failed: ${SDL_GetError()?.toKString()}")
            return false
        }
        val surface = createIcon() ?: run {
            println("tray: no icon: ${SDL_GetError()?.toKString()}")
            return false
        }
        icon = surface
        val created = SDL_CreateTray(surface, tooltip)
        if (created == null) {
            println("tray: SDL_CreateTray failed: ${SDL_GetError()?.toKString()}")
            SDL_DestroySurface(surface)
            icon = null
            return false
        }
        tray = created
        val menu = SDL_CreateTrayMenu(created) ?: return false

        SDL_InsertTrayEntryAt(menu, -1, "Open Latch", SDL_TRAYENTRY_BUTTON)?.let {
            SDL_SetTrayEntryCallback(it, openCallback, null)
        }
        statusEntry = SDL_InsertTrayEntryAt(
            menu, -1, statusText, SDL_TRAYENTRY_BUTTON or SDL_TRAYENTRY_DISABLED,
        )
        connectEntry = SDL_InsertTrayEntryAt(
            menu, -1, if (showDisconnect) "Disconnect" else "Connect", SDL_TRAYENTRY_BUTTON,
        )?.also { SDL_SetTrayEntryCallback(it, toggleCallback, null) }
        SDL_InsertTrayEntryAt(menu, -1, "Exit Latch", SDL_TRAYENTRY_BUTTON)?.let {
            SDL_SetTrayEntryCallback(it, exitCallback, null)
        }
        SDL_SetTrayTooltip(created, tooltip)
        return true
    }

    fun setStatus(text: String, showDisconnect: Boolean) {
        statusEntry?.let { SDL_SetTrayEntryLabel(it, text) }
        connectEntry?.let { SDL_SetTrayEntryLabel(it, if (showDisconnect) "Disconnect" else "Connect") }
    }

    fun destroy() {
        CliTrayActions.open = null
        CliTrayActions.toggleConnect = null
        CliTrayActions.exit = null
        tray?.let { SDL_DestroyTray(it) }
        tray = null
        statusEntry = null
        connectEntry = null
        icon?.let { SDL_DestroySurface(it) }
        icon = null
    }

    /** 32x32 solid brand-purple icon; no resource bundle in the CLI. */
    private fun createIcon(): CPointer<SDL_Surface>? {
        val size = 32
        val surface = SDL_CreateSurface(size, size, SDL_PIXELFORMAT_RGBA32) ?: return null
        val pixels = surface.pointed.pixels ?: run {
            SDL_DestroySurface(surface)
            return null
        }
        val color = ByteArray(size * size * 4) { i ->
            when (i % 4) {
                0 -> 0xA4.toByte()
                1 -> 0x50.toByte()
                2 -> 0x67.toByte()
                else -> 0xFF.toByte()
            }
        }
        memcpy(pixels, color.refTo(0), (size * size * 4).toULong())
        return surface
    }
}
