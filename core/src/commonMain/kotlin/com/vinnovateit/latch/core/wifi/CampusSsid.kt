package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.settings.SettingsManager

/**
 * True unless the SSID is readable and readably *not* a campus network.
 *
 * Shared by the engine's login gate and by the read-only probe a CLI one-shot
 * uses when it owns the runtime, so both decide "campus network" identically.
 *
 * [unknownIsCampus] is how an unreadable SSID is judged. It stays true where a
 * null SSID has always meant "don't block" (see WifiPlatform.requiresKnownSsid);
 * a platform that cannot vouch for the network passes false, and then only a
 * positively identified campus SSID qualifies.
 */
fun isVitCampusSsid(ssid: String?, unknownIsCampus: Boolean = true): Boolean {
    val clean = ssid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return unknownIsCampus
    return clean.contains("VIT", ignoreCase = true) ||
        SettingsManager.allowedSsids.value.any { clean.contains(it, ignoreCase = true) }
}
