package com.vinnovateit.latch.desktop.platform.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinReg
import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.desktop.platform.InstalledBuild
import java.awt.Desktop
import java.net.URI

/**
 * Windows implementations of the OS actions Latch needs.
 */
class WindowsSystemActions(private val logger: Logger) : SystemActions {

    private companion object {
        const val TAG = "WindowsSystemActions"
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val RUN_VALUE_NAME = "Latch"
        const val EXE_NAME = "Latch.exe"
        const val SW_SHOWNORMAL = 1
        const val SHELL_EXECUTE_ERROR_MAX = 32L
    }

    override fun openWifiSettings() {
        val targets = listOf("ms-availablenetworks:", "ms-settings:network-wifi")
        for (target in targets) {
            if (shellExecute(target)) return
        }
        logger.e(TAG, "Could not open Wi-Fi settings", null)
    }

    private fun shellExecute(target: String): Boolean = runCatching {
        val code = Shell32.INSTANCE
            .ShellExecute(null, "open", target, null, null, SW_SHOWNORMAL)
            .toLong()
        if (code <= SHELL_EXECUTE_ERROR_MAX) {
            logger.w(TAG, "ShellExecute('$target') failed with code $code")
            false
        } else {
            true
        }
    }.getOrElse {
        logger.e(TAG, "ShellExecute('$target') threw", it)
        false
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { logger.e(TAG, "Could not open URL: $url", it) }
    }

    override fun setAutostart(enabled: Boolean) {
        val exe = appExePath()
        if (exe == null) {
            logger.w(TAG, "Not running from an installed $EXE_NAME; refusing to set autostart.")
            return
        }
        try {
            if (enabled) {
                Advapi32Util.registrySetStringValue(
                    WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE_NAME, "\"$exe\" --hidden",
                )
                logger.d(TAG, "Autostart enabled -> $exe")
            } else if (isAutostartEnabled()) {
                Advapi32Util.registryDeleteValue(
                    WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE_NAME,
                )
                logger.d(TAG, "Autostart disabled")
            }
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to update autostart", e)
        }
    }

    override fun isAutostartEnabled(): Boolean = try {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_VALUE_NAME)
    } catch (e: Throwable) {
        logger.e(TAG, "Failed to read autostart state", e)
        false
    }

    private fun appExePath(): String? {
        val exe = InstalledBuild.path
        if (exe == null) {
            logger.w(TAG, "Not running from an installed $EXE_NAME; refusing to set autostart.")
        }
        return exe
    }
}
