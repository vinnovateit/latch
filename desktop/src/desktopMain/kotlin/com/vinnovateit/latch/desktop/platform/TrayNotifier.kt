package com.vinnovateit.latch.desktop.platform

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import com.vinnovateit.latch.core.platform.UserNotifier
import com.vinnovateit.latch.desktop.AppPaths
import com.vinnovateit.latch.desktop.platform.linux.LinuxNotifier
import com.vinnovateit.latch.desktop.platform.windows.WindowsBalloonNotifier
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Notifications for the tray app.
 *
 * The split between [showOngoing] and [notifyTransient] is load-bearing. The
 * Android service calls its notification update on every liveStatus emission,
 * i.e. every 2 seconds. Mapping that to real Windows toasts would produce a
 * notification storm, and Windows rate-limits them anyway. So:
 *
 *   showOngoing      -> tray tooltip only. Free, safe every 2s.
 *   notifyTransient  -> real balloon. State transitions ONLY.
 *
 * On Windows, [notifyTransient] routes through [WindowsBalloonNotifier] so
 * that the Latch icon appears in the balloon (NIIF_USER).
 * On Linux, [notifyTransient] routes through [LinuxNotifier] to enforce a
 * single notification, silent by default, with zombie cleanup across distros.
 */
class TrayNotifier : UserNotifier {

    private companion object {
        const val APP_DISPLAY_NAME = "LATCH by VinnovateIT"
    }

    /** Bound to the Compose Tray's tooltip. */
    val tooltip = MutableStateFlow(APP_DISPLAY_NAME)

    /** Set once the Compose tray exists; balloons are dropped before then. */
    var trayState: TrayState? = null

    override fun showOngoing(title: String, text: String) {
        tooltip.value = if (text.isBlank()) title else "$title\n$text"
    }

    override fun notifyTransient(title: String, text: String, isError: Boolean) {
        // The OS toast shell already shows the app name/icon as its own header
        // (from the AUMID) -- reusing APP_DISPLAY_NAME here as well duplicated it
        // verbatim in the balloon body, so callers' own [title] is used instead.
        if (AppPaths.isWindows) {
            WindowsBalloonNotifier.notify(title, text, isError)
        } else if (AppPaths.isLinux) {
            LinuxNotifier.notify(title, text, isError)
        } else {
            trayState?.sendNotification(
                Notification(
                    title = title,
                    message = text,
                    type = if (isError) Notification.Type.Error else Notification.Type.Info,
                )
            )
        }
    }

    override fun hideOngoing() {
        tooltip.value = APP_DISPLAY_NAME
        if (AppPaths.isLinux) {
            LinuxNotifier.clear()
        }
    }
}
