package com.vinnovateit.latch.desktop.platform.mac

import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Just enough of the Objective-C runtime to message Apple's frameworks from
 * the JVM: look up a class, send a message, read an NSString.
 *
 * objc_msgSend is called through JNA as an ordinary, non-variadic C function
 * with the exact argument types of each message, which is how the arm64 and
 * x86_64 ABIs both expect it to be called. Every message used here takes only
 * object/selector/integer arguments and returns an object, a BOOL or an
 * integer -- never a struct or a float, which would need objc_msgSend_stret
 * and friends.
 *
 * Loading this object loads libobjc, so it must only ever be touched on macOS.
 */
internal object ObjCRuntime {

    private val objc: NativeLibrary = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib")
    private val msgSend: Function = objc.getFunction("objc_msgSend")
    private val getClass: Function = objc.getFunction("objc_getClass")
    private val registerSelector: Function = objc.getFunction("sel_registerName")
    private val poolPush: Function = objc.getFunction("objc_autoreleasePoolPush")
    private val poolPop: Function = objc.getFunction("objc_autoreleasePoolPop")

    /** Loads a system framework so its classes register with the runtime. */
    fun loadFramework(name: String): NativeLibrary =
        NativeLibrary.getInstance("/System/Library/Frameworks/$name.framework/$name")

    fun cls(name: String): Pointer? = getClass.invokePointer(arrayOf(name))

    private fun sel(name: String): Pointer =
        registerSelector.invokePointer(arrayOf(name)) ?: error("sel_registerName($name) returned NULL")

    /** A message returning an object (or nil). */
    fun send(receiver: Pointer?, selector: String, vararg args: Any?): Pointer? {
        if (receiver == null) return null
        return msgSend.invokePointer(arrayOf(receiver, sel(selector), *args))
    }

    /** A message returning BOOL; only its low byte is meaningful on either ABI. */
    fun sendBool(receiver: Pointer?, selector: String, vararg args: Any?): Boolean {
        if (receiver == null) return false
        val result = msgSend.invoke(Byte::class.javaObjectType, arrayOf(receiver, sel(selector), *args)) as Byte
        return result.toInt() != 0
    }

    /** A message returning NSInteger/NSUInteger or a 64-bit enum. */
    fun sendLong(receiver: Pointer?, selector: String, vararg args: Any?): Long {
        if (receiver == null) return 0
        return msgSend.invokeLong(arrayOf(receiver, sel(selector), *args))
    }

    /** A message returning a 32-bit integer or enum (e.g. CLAuthorizationStatus). */
    fun sendInt(receiver: Pointer?, selector: String, vararg args: Any?): Int {
        if (receiver == null) return 0
        return msgSend.invokeInt(arrayOf(receiver, sel(selector), *args))
    }

    fun respondsTo(receiver: Pointer?, selector: String): Boolean =
        sendBool(receiver, "respondsToSelector:", sel(selector))

    /** Copies an NSString into a Kotlin string; nil is null. */
    fun string(nsString: Pointer?): String? =
        send(nsString, "UTF8String")?.getString(0, "UTF-8")

    /**
     * Runs [block] inside an autorelease pool. The worker threads that call
     * into AppKit-adjacent frameworks here live for the whole process, so
     * without a pool every autoreleased return value would accumulate forever.
     */
    fun <T> withAutoreleasePool(block: () -> T): T {
        val token = poolPush.invokePointer(emptyArray())
        try {
            return block()
        } finally {
            poolPop.invokeVoid(arrayOf(token))
        }
    }
}
