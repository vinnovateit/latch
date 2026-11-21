package com.vinnovateit.latch.ui.chrome

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Bridge for system/window keyboard back (Backspace, Alt+Left) into the
 * application's navigation state. The window chrome owns the key event but not
 * the destination state, so the app publishes a lambda here when a secondary
 * screen is mounted. Mirrors [AppMenuHost]'s role for the overflow menu.
 */
@Stable
class LatchBackHost {
    var onBack: (() -> Unit)? by mutableStateOf(null)
        private set

    fun publish(handler: (() -> Unit)?) { onBack = handler }
}
