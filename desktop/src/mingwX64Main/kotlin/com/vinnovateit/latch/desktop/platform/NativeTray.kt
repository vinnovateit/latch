@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop.platform

import com.compose.sdl.loadComposeResourceBytes
import kotlinx.cinterop.CFunction
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
import sdl3.SDL_InsertTrayEntryAt
import sdl3.SDL_PIXELFORMAT_RGBA32
import sdl3.SDL_SetTrayEntryCallback
import sdl3.SDL_SetTrayEntryLabel
import sdl3.SDL_SetTrayTooltip
import sdl3.SDL_INIT_EVENTS
import sdl3.SDL_INIT_VIDEO
import sdl3.SDL_TRAYENTRY_BUTTON
import sdl3.SDL_TRAYENTRY_DISABLED
import sdl3.SDL_Surface
import cnames.structs.SDL_Tray
import cnames.structs.SDL_TrayEntry

/** Holds the live callbacks for the process lifetime; SDL invokes them from its event pump. */
private object TrayActions {
    @Volatile var open: (() -> Unit)? = null
    @Volatile var toggleConnect: (() -> Unit)? = null
    @Volatile var exit: (() -> Unit)? = null
}

private val openCallback = staticCFunction<COpaquePointer?, CPointer<SDL_TrayEntry>?, Unit> { _, _ ->
    TrayActions.open?.invoke()
}
private val toggleCallback = staticCFunction<COpaquePointer?, CPointer<SDL_TrayEntry>?, Unit> { _, _ ->
    TrayActions.toggleConnect?.invoke()
}
private val exitCallback = staticCFunction<COpaquePointer?, CPointer<SDL_TrayEntry>?, Unit> { _, _ ->
    TrayActions.exit?.invoke()
}

/**
 * SDL3 system tray for the native Linux desktop build.
 *
 * SDL implements this through libappindicator/GTK on Linux, so the icon appears
 * in any desktop that hosts a StatusNotifierItem (KDE, XFCE, GNOME with the
 * AppIndicator extension). SDL's Linux backend does not support tooltips, so
 * connection status is also mirrored in a disabled menu entry.
 */
class NativeTray {
    private var tray: CPointer<SDL_Tray>? = null
    private var iconSurface: CPointer<SDL_Surface>? = null
    private var connectEntry: CPointer<SDL_TrayEntry>? = null
    private var statusEntry: CPointer<SDL_TrayEntry>? = null

    @Volatile private var desiredTooltip: String = "Latch"

    /** Returns false when SDL could not create a tray, so the caller can show the window instead. */
    fun start(
        tooltip: String,
        showDisconnect: Boolean,
        statusText: String,
        onOpen: () -> Unit,
        onToggleConnect: () -> Unit,
        onExit: () -> Unit,
    ): Boolean {
        if (tray != null) return true
        desiredTooltip = tooltip
        TrayActions.open = onOpen
        TrayActions.toggleConnect = onToggleConnect
        TrayActions.exit = onExit

        // The port only initialises SDL video when a Window is created; a tray-only
        // launch never creates one, so initialise here. Idempotent if already up.
        if (!SDL_Init(SDL_INIT_VIDEO or SDL_INIT_EVENTS)) {
            println("NativeTray: SDL_Init failed: ${SDL_GetError()?.toKString()}")
            return false
        }
        val surface = createIconSurface() ?: run {
            println("NativeTray: no tray icon available: ${SDL_GetError()?.toKString()}")
            return false
        }
        iconSurface = surface
        val created = SDL_CreateTray(surface, desiredTooltip)
        if (created == null) {
            println("NativeTray: SDL_CreateTray failed: ${SDL_GetError()?.toKString()}")
            SDL_DestroySurface(surface)
            iconSurface = null
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
        SDL_SetTrayTooltip(created, desiredTooltip)
        return true
    }

    fun setTooltip(text: String) {
        desiredTooltip = text
        tray?.let { SDL_SetTrayTooltip(it, text) }
    }

    fun setStatus(text: String, showDisconnect: Boolean) {
        statusEntry?.let { SDL_SetTrayEntryLabel(it, text) }
        connectEntry?.let { SDL_SetTrayEntryLabel(it, if (showDisconnect) "Disconnect" else "Connect") }
    }

    fun destroy() {
        TrayActions.open = null
        TrayActions.toggleConnect = null
        TrayActions.exit = null
        // SDL_DestroyTray frees the menu and entries. It copies the icon surface
        // into a PNG at creation time, so we keep ownership of the surface.
        tray?.let { SDL_DestroyTray(it) }
        tray = null
        connectEntry = null
        statusEntry = null
        iconSurface?.let { SDL_DestroySurface(it) }
        iconSurface = null
    }

    /** The bundled .rgba is an 8-byte little-endian (width, height) header + RGBA pixels. */
    private fun createIconSurface(): CPointer<SDL_Surface>? {
        val bytes = loadComposeResourceBytes("icon/latch-32.rgba") ?: return null
        if (bytes.size < 8) return null
        fun le32(i: Int) = (bytes[i].toInt() and 0xff) or
            ((bytes[i + 1].toInt() and 0xff) shl 8) or
            ((bytes[i + 2].toInt() and 0xff) shl 16) or
            ((bytes[i + 3].toInt() and 0xff) shl 24)
        val width = le32(0)
        val height = le32(4)
        if (width <= 0 || height <= 0 || bytes.size < 8 + width * height * 4) return null

        val surface = SDL_CreateSurface(width, height, SDL_PIXELFORMAT_RGBA32) ?: return null
        val dst = surface.pointed.pixels ?: run {
            SDL_DestroySurface(surface)
            return null
        }
        memcpy(dst, bytes.refTo(8), (width * height * 4).toULong())
        return surface
    }
}
