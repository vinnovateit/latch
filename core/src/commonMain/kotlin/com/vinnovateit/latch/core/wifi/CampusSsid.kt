package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.platform.WifiPlatform
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
    return namesNetwork(clean, "VIT") || SettingsManager.allowedSsids.value.any { namesNetwork(clean, it) }
}

/**
 * Whether [entry] appears in [ssid] as a name of its own.
 *
 * A letter or digit before it, or a letter after it, makes it part of a longer
 * word: INVITE, ACTIVITY, VITAMIN. Digits after it are a band suffix, as in
 * VIT5 and VIT2.4G, and punctuation or a space around it is a separator, as in
 * G-VIT 5.
 */
private fun namesNetwork(ssid: String, entry: String): Boolean {
    val name = entry.trim()
    if (name.isEmpty()) return false
    return Regex("(?<![A-Z0-9])${Regex.escape(name)}(?![A-Z])", RegexOption.IGNORE_CASE).containsMatchIn(ssid)
}

/**
 * Whether this is positively a campus Wi-Fi network, for requests outside the
 * engine's login that carry the saved password.
 *
 * Stricter than the login gate on purpose: an unreadable name never qualifies,
 * on any platform, because these requests are optional and have no portal
 * redirect to confirm the network with.
 */
fun isKnownCampusNetwork(wifi: WifiPlatform): Boolean =
    wifi.activeHandle() != null && isVitCampusSsid(wifi.currentSsid(), unknownIsCampus = false)
