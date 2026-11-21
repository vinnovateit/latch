package com.vinnovateit.latch.ui.platform

/**
 * Opens the OS Wi-Fi settings using whatever the target platform provides.
 *
 * [fallback] is the platform's own `SystemActions.openWifiSettings()`. On targets
 * whose system actions already try several tools it is the whole implementation;
 * on targets where they do not, the native implementation tries a sequence first
 * and only falls back as a last resort.
 */
expect fun openWifiSettings(fallback: () -> Unit)
