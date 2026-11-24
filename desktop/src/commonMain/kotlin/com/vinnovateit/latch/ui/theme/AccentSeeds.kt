package com.vinnovateit.latch.ui.theme

import com.vinnovateit.latch.core.mp.formatUs

import androidx.compose.ui.graphics.Color

/**
 * The six accent seed colours the whole scheme is generated from.
 *
 * In the Android app this list is duplicated three times (Theme.kt and twice in
 * SettingsActivity.kt). Consolidated here so the picker swatches and the
 * generated scheme can never disagree.
 *
 * Names are the persisted setting values, so they must not change.
 */
internal object AccentSeeds {
    val Red = Color(0xFFC01221)
    val Blue = Color(0xFF005AC1)
    val Green = Color(0xFF0F5223)
    val Purple = Color(0xFF7D00B8)
    val Pink = Color(0xFFD81B60)
    val Yellow = Color(0xFFF5B300)

    /**
     * Display name -> seed, in the order the settings picker shows them.
     *
     * Only three presets plus the custom swatch -- Purple/Pink/Yellow are still
     * valid values [forName] resolves (so a setting saved before this change
     * still applies correctly), just not offered as swatches here.
     */
    val ordered: List<Pair<String, Color>> = listOf(
        "Red" to Red,
        "Blue" to Blue,
        "Green" to Green,
        "Purple" to Purple,
        "Yellow" to Yellow,
    )

    /**
     * Red is the default for any unrecognised value, matching Android.
     *
     * Desktop additionally accepts a "#RRGGBB" hex string here -- the custom
     * colour picker persists its pick directly as that string rather than adding
     * a second setting key, since a hex value can never collide with a preset
     * name and round-trips through [toHexString] without any extra state.
     */
    fun forName(name: String): Color = when (name) {
        "Blue" -> Blue
        "Green" -> Green
        "Purple" -> Purple
        "Pink" -> Pink
        "Yellow" -> Yellow
        else -> parseHexOrNull(name) ?: Red
    }

    /** Parses "#RRGGBB" (or "RRGGBB"), or null if [value] isn't a valid hex colour. */
    fun parseHexOrNull(value: String): Color? {
        val hex = value.removePrefix("#")
        if (hex.length != 6) return null
        val rgb = hex.toIntOrNull(16) ?: return null
        return Color(0xFF000000.toInt() or rgb)
    }

    /** Inverse of [parseHexOrNull]: "#RRGGBB", uppercase. */
    fun Color.toHexString(): String {
        val r = (red * 255).toInt().coerceIn(0, 255)
        val g = (green * 255).toInt().coerceIn(0, 255)
        val b = (blue * 255).toInt().coerceIn(0, 255)
        return formatUs("#%02X%02X%02X", r, g, b)
    }
}
