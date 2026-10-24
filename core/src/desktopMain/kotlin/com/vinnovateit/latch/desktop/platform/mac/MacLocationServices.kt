package com.vinnovateit.latch.desktop.platform.mac

import com.sun.jna.Callback
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.vinnovateit.latch.core.platform.Logger

/**
 * Location Services authorization, which macOS 14 and later require before
 * CoreWLAN will report the connected network's name.
 *
 * Only ever asks; never assumes. The prompt names the app from its bundle and
 * shows the NSLocationUsageDescription in its Info.plist, so it can only work
 * from the packaged Latch.app -- a bare `java` process (Gradle run, the CLI)
 * is never granted access and keeps reading the SSID as hidden.
 */
internal object MacLocationServices {

    private const val TAG = "MacLocationServices"

    // CLAuthorizationStatus.
    private const val NOT_DETERMINED = 0
    private const val RESTRICTED = 1
    private const val DENIED = 2
    private const val AUTHORIZED_ALWAYS = 3
    private const val AUTHORIZED_WHEN_IN_USE = 4

    private val coreLocation by lazy { ObjCRuntime.loadFramework("CoreLocation") }

    private fun managerClass(): Pointer? {
        coreLocation
        return ObjCRuntime.cls("CLLocationManager")
    }

    /** Never throws: an unreadable status is [MacLocationAccess.Unknown]. */
    fun access(): MacLocationAccess = runCatching {
        ObjCRuntime.withAutoreleasePool {
            val manager = managerClass() ?: return@withAutoreleasePool MacLocationAccess.Unknown
            if (!ObjCRuntime.sendBool(manager, "locationServicesEnabled")) {
                // Switched off system-wide: nothing an app can request.
                return@withAutoreleasePool MacLocationAccess.Denied
            }
            when (ObjCRuntime.sendInt(manager, "authorizationStatus")) {
                AUTHORIZED_ALWAYS, AUTHORIZED_WHEN_IN_USE -> MacLocationAccess.Granted
                NOT_DETERMINED -> MacLocationAccess.NotDetermined
                DENIED -> MacLocationAccess.Denied
                RESTRICTED -> MacLocationAccess.Restricted
                else -> MacLocationAccess.Unknown
            }
        }
    }.getOrDefault(MacLocationAccess.Unknown)

    private fun interface DispatchFunction : Callback {
        fun invoke(context: Pointer?)
    }

    // Held for the life of the process: the callback must outlive the
    // asynchronous dispatch, and releasing the manager would withdraw a
    // prompt that is still on screen.
    @Volatile private var pendingRequest: DispatchFunction? = null
    @Volatile private var manager: Pointer? = null

    /**
     * Shows the system prompt if the user has not decided yet. Asynchronous:
     * the answer arrives later and is picked up by the next [access] read.
     *
     * CLLocationManager must be created and asked on the main thread, which on
     * macOS is the AppKit thread the Java launcher parks in a run loop -- not
     * the AWT event thread -- so the request goes through the main dispatch
     * queue. Requests at most once per process.
     */
    @Synchronized
    fun requestAccessIfUndetermined(logger: Logger) {
        if (pendingRequest != null) return
        if (access() != MacLocationAccess.NotDetermined) return
        val request = DispatchFunction {
            runCatching {
                ObjCRuntime.withAutoreleasePool {
                    val created = ObjCRuntime.send(ObjCRuntime.send(managerClass(), "alloc"), "init")
                    manager = created
                    if (ObjCRuntime.respondsTo(created, "requestWhenInUseAuthorization")) {
                        ObjCRuntime.send(created, "requestWhenInUseAuthorization")
                    } else {
                        ObjCRuntime.send(created, "requestAlwaysAuthorization")
                    }
                }
            }.onFailure { logger.e(TAG, "Location permission request failed", it) }
        }
        try {
            val system = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
            val mainQueue = system.getGlobalVariableAddress("_dispatch_main_q")
            system.getFunction("dispatch_async_f").invokeVoid(arrayOf(mainQueue, null, request))
            pendingRequest = request
            logger.d(TAG, "Asked macOS for Location access, needed to read the Wi-Fi network name.")
        } catch (e: Throwable) {
            logger.e(TAG, "Could not ask for Location access", e)
        }
    }
}
