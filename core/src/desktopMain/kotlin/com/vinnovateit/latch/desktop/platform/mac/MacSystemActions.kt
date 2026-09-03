package com.vinnovateit.latch.desktop.platform.mac

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.desktop.platform.InstalledBuild
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Mac implementation of system actions (opening settings(working), opening URLs(not working), XDG autostart(not working)).
 */
class MacSystemActions(private val logger: Logger) : SystemActions {

    private companion object {
        const val TAG = "MacSystemActions"
        const val DESKTOP_FILE_NAME = "latch.desktop"
    }

    private val autostartFile: File
        get() {
            val configDir = System.getenv("XDG_CONFIG_HOME")
                ?: (System.getProperty("user.home") + "/.config")
            return File(configDir, "autostart/$DESKTOP_FILE_NAME")
        }

    override fun openWifiSettings() {
        if (tryExec(arrayOf("osascript",
                "-e",
                "tell application \"System Settings\"\n    reveal pane id \"com.apple.wifi-settings-extension\"\nend tell"))) return
        logger.w(TAG, "Could not open WiFi Pane in System Settings.")
    }

    private fun tryExec(cmd: Array<String>): Boolean = try {
        ProcessBuilder(*cmd).start()
        true
    } catch (e: Throwable) {
        false
    }

    override fun openUrl(url: String) {
        if (tryExec(arrayOf("open", url))) return
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { logger.e(TAG, "Could not open URL: $url", it) }
    }

    override fun setAutostart(enabled: Boolean) {
        val exePath = InstalledBuild.path
        if (exePath == null) {
            logger.w(TAG, "Not running from an installed build; refusing to set autostart.")
            return
        }

        try {
            val file = autostartFile
            if (enabled) {
                file.parentFile?.mkdirs()
                file.writeText(
                    """
                    [Desktop Entry]
                    Type=Application
                    Name=Latch
                    Comment=Auto-login for VIT hostel Wi-Fi
                    Exec="$exePath" --hidden
                    Terminal=false
                    X-GNOME-Autostart-enabled=true
                    Categories=Network;
                    """.trimIndent() + "\n"
                )
                logger.d(TAG, "Autostart enabled -> ${file.absolutePath}")
            } else if (file.exists()) {
                file.delete()
                logger.d(TAG, "Autostart disabled")
            }
        } catch (e: Throwable) {
            logger.e(TAG, "Failed to update autostart", e)
        }
    }

    override fun isAutostartEnabled(): Boolean = autostartFile.exists()
}
