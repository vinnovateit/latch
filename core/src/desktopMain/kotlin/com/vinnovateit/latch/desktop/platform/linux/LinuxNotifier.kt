package com.vinnovateit.latch.desktop.platform.linux

import com.vinnovateit.latch.core.platform.Logger
import com.vinnovateit.latch.desktop.AppPaths
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Universal Linux desktop notifications conforming to org.freedesktop.Notifications.
 *
 * Enforces:
 * 1. Single notification always: maintains [lastNotificationId] to replace notifications in place.
 * 2. Silent by default: urgency=low, suppress-sound=true.
 * 3. Zombie clearance: on launch, clears old lingering notifications across restarts.
 * 4. Multi-distro compatibility (Debian first, Ubuntu, Fedora, Arch, etc.) using notify-send, gdbus, and dbus-send fallbacks.
 */
object LinuxNotifier {

    private const val TAG = "LinuxNotifier"
    private const val APP_NAME = "Latch"
    private const val SYNC_TAG = "latch"

    private val lastNotificationId = AtomicLong(0L)
    private var logger: Logger? = null

    private val idFile: File
        get() = File(AppPaths.dataDir, "last_notification_id")

    fun start(logger: Logger) {
        this.logger = logger
        clearZombie()
    }

    private fun clearZombie() {
        try {
            if (idFile.exists()) {
                val oldId = idFile.readText().trim().toLongOrNull() ?: 0L
                if (oldId > 0L) {
                    logger?.d(TAG, "Clearing zombie notification ID: $oldId")
                    closeNotification(oldId)
                }
                idFile.delete()
            }
        } catch (e: Throwable) {
            logger?.w(TAG, "Failed to clear zombie notification: ${e.message}")
        }
    }

    fun notify(title: String, message: String, isError: Boolean = false) {
        val currentId = lastNotificationId.get()
        val icon = if (isError) "network-error" else "network-wireless"

        val newId = tryNotifySend(title, message, icon, currentId)
            ?: tryGdbus(title, message, icon, currentId)
            ?: tryDbusSend(title, message, icon, currentId)
            ?: currentId

        if (newId > 0L) {
            lastNotificationId.set(newId)
            try {
                idFile.writeText(newId.toString())
            } catch (_: Throwable) {}
        }
    }

    fun clear() {
        val id = lastNotificationId.getAndSet(0L)
        if (id > 0L) {
            closeNotification(id)
        }
        try {
            if (idFile.exists()) idFile.delete()
        } catch (_: Throwable) {}
    }

    private fun tryNotifySend(title: String, message: String, icon: String, replacesId: Long): Long? {
        return try {
            val cmd = mutableListOf(
                "notify-send",
                "-a", APP_NAME,
                "-u", "low",
                "-i", icon,
                "-h", "string:x-canonical-private-synchronous:$SYNC_TAG",
                "-h", "boolean:suppress-sound:true",
                "-p",
            )
            if (replacesId > 0L) {
                cmd.add("-r")
                cmd.add(replacesId.toString())
            }
            cmd.add(title)
            cmd.add(message)

            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                output.lines().lastOrNull()?.trim()?.toLongOrNull() ?: replacesId
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun tryGdbus(title: String, message: String, icon: String, replacesId: Long): Long? {
        return try {
            val cmd = arrayOf(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.Notify",
                APP_NAME,
                replacesId.toString(),
                icon,
                title,
                message,
                "[]",
                "{'urgency': <@y 0>, 'suppress-sound': <true>, 'x-canonical-private-synchronous': <'$SYNC_TAG'>}",
                "5000",
            )
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                Regex("""\d+""").find(output)?.value?.toLongOrNull() ?: replacesId
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun tryDbusSend(title: String, message: String, icon: String, replacesId: Long): Long? {
        return try {
            val cmd = arrayOf(
                "dbus-send", "--session", "--type=method_call", "--print-reply",
                "--dest=org.freedesktop.Notifications",
                "/org/freedesktop/Notifications",
                "org.freedesktop.Notifications.Notify",
                "string:$APP_NAME",
                "uint32:$replacesId",
                "string:$icon",
                "string:$title",
                "string:$message",
                "array:string:",
                "dict:string:variant:urgency,byte:0,suppress-sound,boolean:true,x-canonical-private-synchronous,string:$SYNC_TAG",
                "int32:5000",
            )
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                Regex("""uint32\s+(\d+)""").find(output)?.groupValues?.get(1)?.toLongOrNull() ?: replacesId
            } else null
        } catch (e: Throwable) {
            null
        }
    }

    private fun closeNotification(id: Long) {
        if (id <= 0L) return
        try {
            val cmd = arrayOf(
                "gdbus", "call", "--session",
                "--dest", "org.freedesktop.Notifications",
                "--object-path", "/org/freedesktop/Notifications",
                "--method", "org.freedesktop.Notifications.CloseNotification",
                id.toString(),
            )
            ProcessBuilder(*cmd).start().waitFor()
        } catch (_: Throwable) {
            try {
                val cmd = arrayOf(
                    "dbus-send", "--session", "--type=method_call",
                    "--dest=org.freedesktop.Notifications",
                    "/org/freedesktop/Notifications",
                    "org.freedesktop.Notifications.CloseNotification",
                    "uint32:$id",
                )
                ProcessBuilder(*cmd).start().waitFor()
            } catch (_: Throwable) {}
        }
    }
}
