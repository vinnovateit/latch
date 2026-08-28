package com.vinnovateit.latch.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vinnovateit.latch.features.home.MainActivity

/**
 * The engine (and its notifier) live at Application scope so MainActivity's
 * cold-launch check works before any Service exists -- but hideOngoing()'s
 * detachForeground() is only meaningful while a real ForegroundService is
 * alive. This holder lets AndroidUserNotifier be built once, at Application
 * scope, while the actual Service instance registers/unregisters itself as
 * it starts and stops.
 */
class ForegroundControllerHolder(private val context: Context) : ForegroundController {
    @Volatile
    var delegate: ForegroundController? = null

    override fun ongoingNotificationTapIntent(): PendingIntent {
        delegate?.let { return it.ongoingNotificationTapIntent() }
        // No Service running to hide -- e.g. a stray notify() before one has
        // started. Falls back to just opening the app.
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun detachForeground() {
        delegate?.detachForeground()
    }
}
