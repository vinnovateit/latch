package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.NoOpLogger
import com.vinnovateit.latch.desktop.AppPaths
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The address-change call is a Windows one. The app also runs on Linux and
 * macOS, where it must stay out of the way.
 */
class WindowsIpChangeNotifierTest {

    @Test
    fun `the native call is only used on Windows`() {
        val notifier = WindowsIpChangeNotifier(NoOpLogger)
        if (AppPaths.isWindows) return

        assertFalse(notifier.supported, "the native call does not exist off Windows")
        // Starting and stopping must be harmless there: the Wi-Fi loop waits on
        // this whatever the machine is.
        notifier.start()
        notifier.stop()
    }

    /**
     * Windows reads the event handle out of the OVERLAPPED at a fixed place.
     * The layout is the same on every 64-bit Windows, so it can be checked
     * here rather than only on a machine that runs the app.
     */
    @Test
    fun `the event handle sits where Windows looks for it`() {
        val pointerSize = Native.POINTER_SIZE
        val eventOffset = pointerSize * 2 + 8 // the two status words, then the offset pair

        val overlapped = WindowsOverlapped()
        assertEquals(eventOffset + pointerSize, overlapped.size(), "the OVERLAPPED is the size Windows expects")

        val event = Memory(1)
        overlapped.hEvent = event
        overlapped.write()

        assertEquals(
            Pointer.nativeValue(event),
            Pointer.nativeValue(overlapped.pointer.getPointer(eventOffset.toLong())),
        )
    }
}
