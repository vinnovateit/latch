@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop.platform

import kotlinx.cinterop.refTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.posix.fread
import platform.posix.getpid
import platform.posix.pclose
import platform.posix.popen

/**
 * Watches for a click on the AppIndicator icon itself.
 *
 * SDL 3.4 has no tray-icon click callback, but a click sends the
 * `org.kde.StatusNotifierItem.Activate` method call over the session bus.
 * This is a best-effort bridge: if there is no session bus or no monitor
 * tool, the menu entries still work.
 */
internal class TrayIconActivation(private val onActivate: () -> Unit) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        if (platform.posix.getenv("DBUS_SESSION_BUS_ADDRESS") == null) return
        val ownPath = "sdl_appindicator_${getpid()}_"
        job = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            val pipe = popen(
                "dbus-monitor --session \"interface='org.kde.StatusNotifierItem'\" 2>/dev/null",
                "r",
            ) ?: return@launch
            val buffer = ByteArray(1024)
            var pending = ""
            try {
                while (isActive) {
                    val read = fread(buffer.refTo(0), 1u, buffer.size.toULong(), pipe).toInt()
                    if (read <= 0) break
                    pending += buffer.copyOf(read).decodeToString()
                    var newline = pending.indexOf('\n')
                    while (newline >= 0) {
                        val line = pending.substring(0, newline)
                        pending = pending.substring(newline + 1)
                        if (line.contains("interface=org.kde.StatusNotifierItem") &&
                            (line.contains("member=Activate") ||
                                line.contains("member=SecondaryActivate")) &&
                            line.contains(ownPath)
                        ) onActivate()
                        newline = pending.indexOf('\n')
                    }
                }
            } finally {
                pclose(pipe)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
