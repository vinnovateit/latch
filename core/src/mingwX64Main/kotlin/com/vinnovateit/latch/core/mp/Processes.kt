@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.core.mp

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreateProcessW
import platform.windows.GetExitCodeProcess
import platform.windows.INFINITE
import platform.windows.PROCESS_INFORMATION
import platform.windows.STARTUPINFOW
import platform.windows.WCHARVar
import platform.windows.WaitForSingleObject

/**
 * Runs a command line without the console window that platform.posix.system()
 * opens on Windows. Needed for the netsh/curl/powershell calls the native app
 * makes continuously: without CREATE_NO_WINDOW every poll flashes a black
 * cmd window on screen.
 */
fun runHidden(command: String, wait: Boolean = true): Int = memScoped {
    val cmdLine = allocArray<WCHARVar>(command.length + 1)
    for (i in command.indices) cmdLine[i] = command[i].code.toUShort()
    cmdLine[command.length] = 0u

    val startup = alloc<STARTUPINFOW>()
    startup.cb = sizeOf<STARTUPINFOW>().toUInt()
    val info = alloc<PROCESS_INFORMATION>()

    val created = CreateProcessW(
        null,
        cmdLine,
        null,
        null,
        0,
        CREATE_NO_WINDOW.toUInt(),
        null,
        null,
        startup.ptr,
        info.ptr,
    )
    if (created == 0) return@memScoped -1

    if (wait) WaitForSingleObject(info.hProcess, INFINITE)
    val exitCode = alloc<UIntVar>()
    GetExitCodeProcess(info.hProcess, exitCode.ptr)
    CloseHandle(info.hThread)
    CloseHandle(info.hProcess)
    exitCode.value.toInt()
}
