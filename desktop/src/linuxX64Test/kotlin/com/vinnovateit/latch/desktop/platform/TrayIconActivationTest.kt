@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.posix.ESRCH
import platform.posix.errno
import platform.posix.getenv
import platform.posix.kill
import platform.posix.usleep

class TrayIconActivationTest {

    @Test
    fun `stop is safe when never started`() {
        val activation = TrayIconActivation {}
        activation.stop()
        assertEquals(-1, activation.activeChildPid)
    }

    @Test
    fun `start spawns dbus-monitor directly and stop reaps child completely`() {
        val activation = TrayIconActivation {}
        activation.start()
        try {
            if (getenv("DBUS_SESSION_BUS_ADDRESS") != null) {
                var pid = -1
                repeat(50) {
                    pid = activation.activeChildPid
                    if (pid > 0) return@repeat
                    usleep(20_000u)
                }
                if (pid > 0) {
                    assertEquals(0, kill(pid, 0), "dbus-monitor child should be alive while running")
                    activation.stop()
                    assertEquals(-1, activation.activeChildPid)
                    assertTrue(kill(pid, 0) != 0 && errno == ESRCH, "dbus-monitor child pid=$pid should be reaped cleanly")
                    activation.stop()
                    return
                }
            }
            activation.stop()
            assertEquals(-1, activation.activeChildPid)
        } finally {
            activation.stop()
        }
    }
}
