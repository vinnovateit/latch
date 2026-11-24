@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.ui.platform

import platform.posix.system

/**
 * `nm-connection-editor` alone is absent on many Linux desktops, so try the common
 * GNOME, KDE and wpa_supplicant tools in sequence.
 *
 * Each executable is probed with `command -v` first: a backgrounded command
 * (`... &`) makes [system] report success even when the binary does not exist, so
 * the return code alone cannot drive the fallback chain.
 */
private val WIFI_SETTINGS_COMMANDS = listOf(
    "nm-connection-editor" to "nm-connection-editor >/dev/null 2>&1 &",
    "gnome-control-center" to "gnome-control-center wifi >/dev/null 2>&1 &",
    "kcmshell6" to "kcmshell6 kcm_networkmanagement >/dev/null 2>&1 &",
    "kcmshell5" to "kcmshell5 kcm_networkmanagement >/dev/null 2>&1 &",
    "systemsettings" to "systemsettings >/dev/null 2>&1 &",
    "wpa_gui" to "wpa_gui >/dev/null 2>&1 &",
)

actual fun openWifiSettings(fallback: () -> Unit) {
    for ((executable, command) in WIFI_SETTINGS_COMMANDS) {
        if (system("command -v $executable >/dev/null 2>&1") != 0) continue
        system(command)
        return
    }
    fallback()
}
