package com.vinnovateit.latch.desktop.platform.windows

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.desktop.AppPaths
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel

/**
 * iphlpapi's NotifyAddrChange: the OS saying that an address or a route moved.
 *
 * The Wi-Fi event loop waits on this as well as on its own interval, so a
 * network that changes is noticed in about a second rather than in five.
 *
 * The notification is asynchronous and cancellable. The wait is armed with an
 * event of our own, [CancelIPChangeNotify] takes the notification back when the
 * app stops listening, and the handle NotifyAddrChange hands back is left
 * alone -- the documentation says not to close it, because Windows shares it.
 * Off Windows there is no such call, and the notifier stays quiet.
 */
internal class WindowsIpChangeNotifier(private val logger: Logger) {

    private companion object {
        const val TAG = "WindowsIpChangeNotifier"
        /** How long one wait lasts before the loop looks at the stop flag again. */
        const val WAIT_SLICE_MS = 1_000
        /** How long to wait for the listener thread to finish on [stop]. */
        const val JOIN_TIMEOUT_MS = 2_000L
        const val RETRY_DELAY_MS = 1_000L
        /** What NotifyAddrChange returns when it has taken the request. */
        const val ERROR_IO_PENDING = 997
        const val WAIT_OBJECT_0 = 0
    }

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    /** Whether this machine has the OS call at all. */
    val supported: Boolean = AppPaths.isWindows

    /**
     * Starts listening. The returned channel receives once per change the OS
     * reports; it is closed when the notifier stops.
     */
    fun start(): Channel<Unit> {
        if (!supported) {
            logger.d(TAG, "Not Windows; no address-change notifications.")
            return wakeups
        }
        if (!running.compareAndSet(false, true)) return wakeups
        thread = Thread({ listen() }, "latch-ip-change").apply {
            isDaemon = true
            start()
        }
        return wakeups
    }

    /** Stops listening and releases the OS handles. Safe to call twice. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(JOIN_TIMEOUT_MS)
        thread = null
    }

    private fun listen() {
        val ipHelper = ipHelperApi()
        val kernel = kernel32Api()
        if (ipHelper == null || kernel == null) {
            logger.w(TAG, "No address-change API on this machine; falling back to polling.")
            close()
            return
        }

        // An event of our own: the notification signals it, and it is the
        // only handle this closes.
        val event = kernel.CreateEvent(null, 0, 0, null)
        if (event == null) {
            logger.w(TAG, "Could not create the change event; falling back to polling.")
            close()
            return
        }

        val overlapped = WindowsOverlapped()
        overlapped.hEvent = event
        overlapped.write()

        try {
            var failures = 0
            while (running.get()) {
                val handle = PointerByReference()
                val result = ipHelper.NotifyAddrChange(handle, overlapped.pointer)
                if (result != ERROR_IO_PENDING) {
                    // The poll loop carries on regardless, so this is a
                    // fallback rather than a failure.
                    if (failures++ == 0) {
                        logger.w(TAG, "NotifyAddrChange did not take the request ($result); falling back to polling.")
                    }
                    Thread.sleep(RETRY_DELAY_MS)
                    // A request that is still open would refuse a new one.
                    ipHelper.CancelIPChangeNotify(overlapped.pointer)
                    continue
                }
                awaitChange(kernel, event)
            }
        } catch (e: Throwable) {
            logger.w(TAG, "Address-change listener stopped: ${e.message}")
        } finally {
            runCatching { ipHelper.CancelIPChangeNotify(overlapped.pointer) }
            runCatching { kernel.CloseHandle(event) }
            close()
        }
    }

    /** Waits for the OS to report a change, in slices, so a stop is noticed. */
    private fun awaitChange(kernel: Kernel32Api, event: Pointer) {
        while (running.get()) {
            val waited = kernel.WaitForSingleObject(event, WAIT_SLICE_MS)
            if (waited == WAIT_OBJECT_0) {
                wakeups.trySend(Unit)
                return
            }
        }
    }

    private fun close() {
        wakeups.close()
    }

    private fun ipHelperApi(): IpHelperApi? = runCatching {
        Native.load("iphlpapi.dll", IpHelperApi::class.java)
    }.getOrNull()

    private fun kernel32Api(): Kernel32Api? = runCatching {
        Native.load("kernel32.dll", Kernel32Api::class.java)
    }.getOrNull()
}

/** The part of iphlpapi this uses. */
internal interface IpHelperApi : Library {
    /**
     * Registers for address changes. Returns [WindowsIpChangeNotifier]'s
     * ERROR_IO_PENDING when it has taken the request; the handle it writes is
     * Windows' own and must not be closed.
     */
    fun NotifyAddrChange(handle: PointerByReference?, overlapped: Pointer?): Int

    /** Gives the registration back, taking the OVERLAPPED it was made with. */
    fun CancelIPChangeNotify(notifyOverlapped: Pointer?): Int
}

/** The part of kernel32 this uses. */
internal interface Kernel32Api : Library {
    fun CreateEvent(attributes: Pointer?, manualReset: Int, initialState: Int, name: String?): Pointer?
    fun WaitForSingleObject(handle: Pointer?, milliseconds: Int): Int
    fun CloseHandle(handle: Pointer?): Int
}

/** The OVERLAPPED structure NotifyAddrChange and CancelIPChangeNotify share. */
@Structure.FieldOrder("internalField", "internalHigh", "offset", "offsetHigh", "hEvent")
internal class WindowsOverlapped : Structure() {
    @JvmField var internalField: Pointer? = null
    @JvmField var internalHigh: Pointer? = null
    @JvmField var offset: Int = 0
    @JvmField var offsetHigh: Int = 0
    @JvmField var hEvent: Pointer? = null
}
