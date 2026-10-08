package com.vinnovateit.latch.desktop.platform.windows

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

internal interface WindowsIpHelper : Library {
    companion object {
        val INSTANCE: WindowsIpHelper by lazy {
            Native.load("iphlpapi", WindowsIpHelper::class.java)
        }
    }

    fun NotifyAddrChange(handle: PointerByReference?, overlapped: Pointer?): Int
}
