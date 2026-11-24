@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vinnovateit.latch.ui.platform

import com.vinnovateit.latch.core.mp.runHidden

/** Windows Settings Wi-Fi page; fall back to the caller's behaviour if it cannot be opened. */
actual fun openWifiSettings(fallback: () -> Unit) {
    if (runHidden("start ms-settings:wifi") != 0) fallback()
}
