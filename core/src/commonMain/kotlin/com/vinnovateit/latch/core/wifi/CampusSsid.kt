package com.vinnovateit.latch.core.wifi

import com.vinnovateit.latch.core.settings.SettingsManager

// Campus networks come in two shapes: the hostel/block form
// "<letter>-VIT" (optionally with a trailing band suffix Windows
// appends, e.g. "G-VIT 5") and the academic-block form "VIT<band>"
// ("VIT5G", "VIT2.4G"). Anchored to the start of the SSID so an
// unrelated network that merely contains "VIT" somewhere in its name
// does not match.
private val VIT_SSID_PATTERN = Regex("^(?:[A-Za-z]-)?VIT", RegexOption.IGNORE_CASE)

/**
 * True unless the SSID is readable and readably *not* a campus network.
 *
 * Shared by the engine's login gate and by the read-only probe a CLI one-shot
 * uses when it owns the runtime, so both decide "campus network" identically.
 */
fun isVitCampusSsid(ssid: String?): Boolean {
    val clean = ssid?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return true
    return VIT_SSID_PATTERN.containsMatchIn(clean) ||
        clean.contains("VIT", ignoreCase = true) ||
        clean.endsWith("-VIT", ignoreCase = true) ||
        SettingsManager.allowedSsids.value.any { clean.contains(it, ignoreCase = true) }
}
