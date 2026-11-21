package com.vinnovateit.latch.ui.platform

/** The JVM platform's SystemActions already try several tools; keep its behaviour. */
actual fun openWifiSettings(fallback: () -> Unit) = fallback()
