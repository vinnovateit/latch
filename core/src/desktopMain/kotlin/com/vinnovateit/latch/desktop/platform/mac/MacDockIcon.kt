package com.vinnovateit.latch.desktop.platform.mac

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.vinnovateit.latch.core.platform.Logger

/**
 * Shows Latch in the Dock only while its window is open.
 *
 * Latch runs in the background and lives in the menu bar, so with the window
 * closed a Dock icon is just clutter. With the window open it gets one, and a
 * place in Cmd-Tab, like any other window.
 *
 * Switches NSApplication's activation policy between regular (Dock icon) and
 * accessory (menu bar only). That belongs to the main thread, which on macOS is
 * the AppKit thread the Java launcher parks in a run loop -- not the AWT event
 * thread -- so each change goes through the main dispatch queue, as in
 * [MacLocationServices].
 */
object MacDockIcon {

    private const val TAG = "MacDockIcon"

    // NSApplicationActivationPolicy.
    private const val REGULAR = 0L
    private const val ACCESSORY = 1L

    private fun interface DispatchFunction : Callback {
        fun invoke(context: Pointer?)
    }

    private val appKit by lazy { ObjCRuntime.loadFramework("AppKit") }

    private fun application(): Pointer? {
        appKit
        return ObjCRuntime.send(ObjCRuntime.cls("NSApplication"), "sharedApplication")
    }

    private fun apply(policy: Long) {
        ObjCRuntime.withAutoreleasePool {
            val app = application()
            ObjCRuntime.sendBool(app, "setActivationPolicy:", policy)
            // An accessory app that turns regular does not come forward on its
            // own; without this the new Dock icon shows next to a window that is
            // still behind the app the user was in.
            if (policy == REGULAR) ObjCRuntime.send(app, "activateIgnoringOtherApps:", 1.toByte())
        }
    }

    // Held for the life of the process: libdispatch calls them later, from the
    // main thread, so they must not be collected in between.
    private val show = DispatchFunction { runCatching { apply(REGULAR) } }
    private val hide = DispatchFunction { runCatching { apply(ACCESSORY) } }

    /** Asynchronous; never throws. Only call on macOS. */
    fun setVisible(visible: Boolean, logger: Logger) {
        try {
            val system = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
            val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
            system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, if (visible) show else hide))
        } catch (e: Throwable) {
            logger.e(TAG, "Could not ${if (visible) "show" else "hide"} the Dock icon", e)
        }
    }

    /** Whether this macOS answers the messages [setVisible] sends; for the macOS CI job. */
    internal fun isSupported(): Boolean = runCatching {
        ObjCRuntime.withAutoreleasePool {
            val app = application()
            ObjCRuntime.respondsTo(app, "setActivationPolicy:") &&
                ObjCRuntime.respondsTo(app, "activateIgnoringOtherApps:")
        }
    }.getOrDefault(false)
}
