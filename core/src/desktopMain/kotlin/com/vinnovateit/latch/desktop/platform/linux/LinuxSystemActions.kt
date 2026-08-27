package com.vinnovateit.latch.desktop.platform.linux

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.core.platform.SystemActions
import com.vinnovateit.latch.desktop.platform.InstalledBuild
import java.awt.Desktop
import java.io.File
import java.net.URI

/**
 * Linux implementation of system actions (opening settings, opening URLs, XDG autostart).
 */
class LinuxSystemActions(private val logger: Logger) : SystemActions {

    private companion object {
        const val TAG = "LinuxSystemActions"
        const val DESKTOP_FILE_NAME = "latch.desktop"
    }

    private val autostartFile: File
        get() {
            val configDir = System.getenv("XDG_CONFIG_HOME")
                ?: (System.getProperty("user.home") + "/.config")
            return File(configDir, "autostart/$DESKTOP_FILE_NAME")
        }

    override fun openWifiSettings() {
        val commands = listOf(
            arrayOf("dbus-send", "--session", "--dest=org.gnome.Shell", "--type=method_call", "/org/gnome/Shell", "org.gnome.Shell.ShowSystemMenu"),
            arrayOf("gnome-control-center", "wifi"),
            arrayOf("nm-connection-editor"),
            arrayOf("kcmshell6", "kcm_networkmanagement"),
            arrayOf("kcmshell5", "kcm_networkmanagement"),
        )

        for (cmd in commands) {
            if (tryExec(cmd)) return
        }
        logger.w(TAG, "Could not open Linux Wi-Fi settings GUI via standard desktop tools.")
    }

    private fun tryExec(cmd: Array<String>): Boolean = try {
        ProcessBuilder(*cmd).start()
        true
    } catch (e: Throwable) {
        false
    }

    override fun openUrl(url: String) {
        if (tryExec(arrayOf("xdg-open", url))) return
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
