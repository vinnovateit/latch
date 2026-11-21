package com.vinnovateit.latch.cli

import kotlinx.coroutines.delay

/** Columns the full banner needs before it is worth drawing. */
internal const val INTRO_MIN_WIDTH = 46

// Short on purpose: the hooks only move a little, and more frames spend the
// budget on movement too small to see.
/** Widest the panel is allowed to get, and the narrowest it is worth drawing. */
private const val PANEL_MAX_WIDTH = 44
private const val PANEL_MIN_WIDTH = 32

internal fun panelWidthFor(width: Int): Int =
    (width - 2).coerceIn(PANEL_MIN_WIDTH, PANEL_MAX_WIDTH)

private const val MARK_FRAMES = 7
private const val PANEL_FRAMES = 4

/**
 * Terminal width in columns.
 *
 * COLUMNS is checked first but is rarely any use: most shells keep it as a
 * shell variable rather than exporting it, so a child JVM never sees it. The
 * real answer comes from asking the controlling terminal directly, and only if
 * that fails do we assume the conventional 80. Getting this wrong is what makes
 * the banner wrap and smear as it redraws.
 */
internal fun terminalWidth(
    environment: Map<String, String> = CliEnv.all(),
    probe: () -> Int? = ::terminalWidthFromIoctl,
): Int = environment["COLUMNS"]?.toIntOrNull()?.takeIf { it > 0 }
    ?: probe()?.takeIf { it > 0 }
    ?: 80

/**
 * The one-line form, for terminals too narrow to hold the banner.
 */
internal fun compactIntro(state: IntroState): List<List<IntroSpan>> = buildList {
    add(listOf(IntroSpan("Latch", IntroStyle.BRAND), IntroSpan(" v${state.version}", IntroStyle.MUTED)))
    state.rows.forEach { row ->
        add(
            listOf(
                IntroSpan("  ${row.label.padEnd(10)}", IntroStyle.MUTED),
                IntroSpan(row.value ?: "unknown"),
            ),
        )
    }
    if (state.hint.isNotEmpty()) add(listOf(IntroSpan("  ${state.hint}", IntroStyle.MUTED)))
}

/**
 * Draws the intro. Non-interactive output gets nothing, a terminal without ANSI
 * gets the settled banner printed once, and everything else gets the mark
 * closing shut followed by the panel drawing itself.
 */
suspend fun showIntro(
    terminal: TerminalIO,
    capabilities: TerminalCapabilities,
    state: IntroState,
    width: Int = terminalWidth(),
    // The panel follows the terminal rather than sitting at a fixed width, so a
    // window between the banner minimum and the panel's natural size gets a
    // narrower box instead of a wrapped one.
    renderer: IntroRenderer = IntroRenderer(panelWidth = panelWidthFor(width)),
    frameDelayMillis: Long = 45,
) {
    if (!capabilities.interactive) return

    // Too narrow to hold the banner, or a console that cannot encode the
    // glyphs it is drawn from. Either way the mark cannot be shown.
    if (width < INTRO_MIN_WIDTH || !capabilities.unicode) {
        compactIntro(state).forEach { terminal.println(it.render(capabilities)) }
        return
    }

    if (!capabilities.ansi || capabilities.noColor) {
        // Printed once, so drop the padding the animation needs.
        renderer.lines(state, trimMark = true).forEach { terminal.println(it.render(capabilities)) }
        return
    }

    val settled = renderer.lines(state)

    val height = settled.size
    var painted = false
    fun paint(frame: List<List<IntroSpan>>) {
        check(frame.size == height) { "intro frame changed height: ${frame.size} != $height" }
        if (painted) terminal.print(Ansi.cursorUp(height))
        painted = true
        // Clear to end of line on every row, or a shorter frame leaves the tail
        // of the previous one behind.
        terminal.print(frame.joinToString("\n", postfix = "\n") { it.render(capabilities) + Ansi.CLEAR_LINE })
    }

    terminal.print(Ansi.HIDE_CURSOR)
    try {
        // Starts at zero: both hooks are on canvas from the first frame, sitting
        // apart along the diagonal, and the animation closes the gap.
        repeat(MARK_FRAMES + 1) { index ->
            paint(
                renderer.lines(
                    state,
                    markReveal = index.toDouble() / MARK_FRAMES,
                    visibleRows = 0,
                    panelReveal = 0.0,
                ),
            )
            delay(frameDelayMillis)
        }
        repeat(PANEL_FRAMES) { index ->
            paint(renderer.lines(state, visibleRows = 0, panelReveal = (index + 1).toDouble() / PANEL_FRAMES))
            delay(frameDelayMillis)
        }
        state.rows.indices.forEach { index ->
            paint(renderer.lines(state, visibleRows = index + 1))
            delay(frameDelayMillis)
        }
        paint(settled)

        // The mark is padded top and bottom so the hooks have somewhere to
        // travel, which leaves a blank row above and below once they have
        // landed. The animation needed a fixed height; the banner left on
        // screen does not, so collapse onto the trimmed version and wipe what
        // the taller frames left behind.
        val trimmed = renderer.lines(state, trimMark = true)
        if (trimmed.size < height) {
            terminal.print(Ansi.cursorUp(height))
            terminal.print(
                trimmed.joinToString("\n", postfix = "\n") { it.render(capabilities) + Ansi.CLEAR_LINE },
            )
            terminal.print(Ansi.CLEAR_BELOW)
        }
    } finally {
        terminal.print(Ansi.RESET + Ansi.SHOW_CURSOR)
    }
}
