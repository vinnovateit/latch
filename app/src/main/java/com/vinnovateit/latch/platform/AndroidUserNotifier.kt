package com.vinnovateit.latch.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.platform.UserNotifier

/**
 * The one thing this can't do with just a Context: the initial startForeground()
 * call and detaching the foreground state are both Service-instance-scoped.
 * Everything else -- updating the already-showing notification's content,
 * posting a transient one -- is a plain NotificationManager call.
 */
interface ForegroundController {
    /** Tapping the ongoing notification should hide it; the Service owns that action. */
    fun ongoingNotificationTapIntent(): PendingIntent
    fun detachForeground()
}

class AndroidUserNotifier(
    private val context: Context,
    private val foregroundController: ForegroundController,
) : UserNotifier {

    companion object {
        const val ONGOING_CHANNEL_ID = "WIFI_LOGIN_CHANNEL"
        const val ONGOING_NOTIFICATION_ID = 1
        private const val TRANSIENT_CHANNEL_ID = "latch_ui_notifications"
        private const val TRANSIENT_NOTIFICATION_ID = 2002
    }

    private val notificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun showOngoing(title: String, text: String) {
        notificationManager.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL_ID, context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(context, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_latch)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(foregroundController.ongoingNotificationTapIntent())
            .build()
        notificationManager.notify(ONGOING_NOTIFICATION_ID, notification)
    }

    override fun hideOngoing() {
        foregroundController.detachForeground()
        notificationManager.cancel(ONGOING_NOTIFICATION_ID)
    }

    override fun notifyTransient(title: String, text: String, isError: Boolean) {
        notificationManager.createNotificationChannel(
            NotificationChannel(TRANSIENT_CHANNEL_ID, "Status Notifications", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows temporary status messages like Connected/Disconnected"
            }
        )
        val notification = NotificationCompat.Builder(context, TRANSIENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(TRANSIENT_NOTIFICATION_ID, notification)
    }
}
