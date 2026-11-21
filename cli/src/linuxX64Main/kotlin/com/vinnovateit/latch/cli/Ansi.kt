package com.vinnovateit.latch.cli

/**
 * The escape sequences the splash and the intro banner share, so the two cannot
 * drift to different reds.
 */
internal object Ansi {
    const val BRAND_RED_TRUE_COLOR = "\u001b[38;2;192;18;33m"
    const val BRAND_RED_BASIC = "\u001b[31m"
    // The artwork's inner-shadow fill, #670002.
    const val SHADOW_RED_TRUE_COLOR = "\u001b[38;2;103;0;2m"
    // No dark red in the 8-colour palette, so dim the red instead.
    const val SHADOW_RED_BASIC = "\u001b[2;31m"
    const val DIM = "\u001b[2;37m"
    const val BOLD = "\u001b[1m"
    const val RESET = "\u001b[0m"

    const val HIDE_CURSOR = "\u001b[?25l"
    const val SHOW_CURSOR = "\u001b[?25h"
    const val CLEAR_LINE = "\u001b[K"
    const val CLEAR_BELOW = "\u001b[J"

    fun cursorUp(lines: Int) = "\u001b[${lines}A"

    fun brandRed(capabilities: TerminalCapabilities) =
        if (capabilities.trueColor) BRAND_RED_TRUE_COLOR else BRAND_RED_BASIC

    fun shadowRed(capabilities: TerminalCapabilities) =
        if (capabilities.trueColor) SHADOW_RED_TRUE_COLOR else SHADOW_RED_BASIC
}
