package com.vinnovateit.latch.cli

import kotlin.math.pow

/**
 * One label/value line in the intro panel. A null [value] is not known yet and
 * renders as a spinner, so the panel can be drawn immediately and filled in as
 * slower answers (a captive-portal probe, say) arrive.
 */
data class IntroRow(val label: String, val value: String?)

data class IntroState(
    val version: String,
    val rows: List<IntroRow> = emptyList(),
    val hint: String = "latch-cli --help for commands",
)

internal enum class IntroStyle { BRAND, SHADOW, TITLE, MUTED, PLAIN }

internal data class IntroSpan(val text: String, val style: IntroStyle = IntroStyle.PLAIN)

private const val WORDMARK = "L A T C H"
// Two columns, not four: at four the wordmark pushes the banner past the
// width of a default terminal window and the whole thing wraps.
private const val MARK_GAP = 2
private const val SPINNER = "|/-\\"

// How far each hook sits from its resting place at the start, in logo source
// units. Small on purpose: the hooks stay in frame throughout and move just
// enough to read as closing together.
private const val SEPARATION_X = 12.0
private const val SEPARATION_Y = 12.0

/**
 * Draws the intro banner: the Latch hook mark rasterized into braille, the
 * wordmark beside it, and a panel of live state underneath.
 *
 * The mark defaults to a 28 x 11 grid of half-block cells. The logo itself
 * only needs about 26 x 9 of that: any smaller and the interior counters of the
 * two hooks close up so both read as blobs. The extra ring of cells is room for
 * the hooks to sit apart at the start without being clipped, and keeping it as
 * padding rather than shrinking the logo matters because the rasterizer is
 * sensitive to sampling phase, and this alignment is the one that renders
 * cleanly.
 */
class IntroRenderer(
    private val markWidth: Int = 28,
    private val markHeight: Int = 11,
    private val panelWidth: Int = 44,
    private val labelWidth: Int = 10,
    private val separationX: Double = SEPARATION_X,
    private val separationY: Double = SEPARATION_Y,
) {
    /**
     * The mark at [progress] of its travel.
     *
     * Both hooks are whole in every frame; only their position changes. They
     * start slightly apart along the diagonal, the left hook up and to the
     * left, the right hook down and to the right, and close until they
     * interlock at [progress] 1. Nothing is ever half drawn: the mark
     * assembles by moving, not by appearing.
     */
    fun mark(progress: Double = 1.0): List<String> =
        markSpans(progress).map { row -> row.joinToString("") { it.text } }

    /** The mark as styled rows, body and inner shadows coloured apart. */
    internal fun markSpans(progress: Double = 1.0): List<List<IntroSpan>> {
        // Ease out, so the hooks decelerate into the interlock instead of
        // arriving at constant speed and stopping dead. Squared rather than
        // cubed: over the short frame budget a cubic front-loads so hard that
        // the last three frames differ by about a pixel and the close appears
        // to stall.
        val travelled = 1.0 - (1.0 - progress.coerceIn(0.0, 1.0)).pow(2)
        val rows = spansAt(travelled)
        return (window.first..window.last).map { rows[it] }
    }

    /** What a sampled point of the mark is made of. */
    private enum class Material { NONE, HOOK, SHADOW }

    private fun rowsAt(travelled: Double): List<String> =
        spansAt(travelled).map { row -> row.joinToString("") { it.text } }

    /**
     * One styled row per line of the mark, so the body and its inner shadows
     * can be coloured separately.
     */
    private fun spansAt(travelled: Double): List<List<IntroSpan>> {
        val apart = 1.0 - travelled
        // Index 0 is the lower-left hook, which starts high and to the left;
        // index 1 is the upper-right one, which starts low and to the right.
        val offsets = listOf(
            -separationX * apart to -separationY * apart,
            separationX * apart to separationY * apart,
        )

        fun material(x: Int, y: Int): Material {
            var found = Material.NONE
            // Painter's order, matching the artwork: each hook, then the
            // shadows drawn over it, and a later shape wins.
            LatchLogo.hooks.indices.forEach { index ->
                val (offsetX, offsetY) = offsets[index]
                // Sample the pixel centre; sampling the corner drops thin
                // strokes that fall between two sample points.
                val sourceX = (x + 0.5 - originX) / scale - offsetX
                val sourceY = (y + 0.5 - originY) / scale - offsetY
                if (LatchLogo.hooks[index].contains(sourceX, sourceY)) found = Material.HOOK
                LatchLogo.shadows[index].forEach { shadow ->
                    if (shadow.contains(sourceX, sourceY)) found = Material.SHADOW
                }
            }
            return found
        }

        return List(markHeight) { row ->
            val spans = mutableListOf<IntroSpan>()
            val run = StringBuilder()
            var runStyle: IntroStyle? = null

            fun flush() {
                if (run.isEmpty()) return
                spans += IntroSpan(run.toString(), runStyle ?: IntroStyle.PLAIN)
                run.clear()
            }

            repeat(markWidth) { column ->
                val upper = material(column, row * 2)
                val lower = material(column, row * 2 + 1)
                val glyph = halfBlock(upper != Material.NONE, lower != Material.NONE)
                // A cell can only carry one colour, so a cell that straddles the
                // boundary is drawn as body: keeping the outline solid matters
                // more than the exact edge of a shadow.
                val style = when {
                    upper == Material.HOOK || lower == Material.HOOK -> IntroStyle.BRAND
                    upper == Material.SHADOW || lower == Material.SHADOW -> IntroStyle.SHADOW
                    else -> IntroStyle.PLAIN
                }
                if (style != runStyle) {
                    flush()
                    runStyle = style
                }
                run.append(glyph)
            }
            flush()
            spans
        }
    }

    // The mark is fitted into the viewport plus the separation on every side,
    // so a displaced hook still has somewhere to be instead of being clipped at
    // the edge of the grid.
    private val scale: Double = minOf(
        markWidth / (LatchLogo.VIEWPORT_WIDTH + 2 * separationX),
        markHeight * 2 / (LatchLogo.VIEWPORT_HEIGHT + 2 * separationY),
    )

    /** Pixel coordinate of the logo's origin, once the field is centred. */
    private val originX: Double =
        (markWidth - (LatchLogo.VIEWPORT_WIDTH + 2 * separationX) * scale) / 2.0 + separationX * scale
    private val originY: Double =
        (markHeight * 2 - (LatchLogo.VIEWPORT_HEIGHT + 2 * separationY) * scale) / 2.0 + separationY * scale

    /**
     * Rows that carry ink at any point in the motion.
     *
     * Measured across both extremes rather than the settled mark alone: the
     * hooks are displaced vertically at the start, so a window taken from the
     * settled frame would crop them while they travel. A fixed window also
     * keeps the banner one height, which the cursor-up redraw depends on.
     */
    private val window: IntRange by lazy {
        val inked = listOf(0.0, 1.0).flatMap { travelled ->
            rowsAt(travelled).withIndex().filter { it.value.isNotBlank() }.map { it.index }
        }
        if (inked.isEmpty()) 0 until markHeight else inked.min()..inked.max()
    }

    /**
     * The whole banner. [markReveal] drives the mark assembly, [visibleRows]
     * limits how many panel rows have appeared, and [spinnerTick] animates rows
     * whose value has not arrived.
     */
    internal fun lines(
        state: IntroState,
        markReveal: Double = 1.0,
        visibleRows: Int = Int.MAX_VALUE,
        panelReveal: Double = 1.0,
        spinnerTick: Int = 0,
        trimMark: Boolean = false,
    ): List<List<IntroSpan>> {
        val output = mutableListOf<List<IntroSpan>>()
        // The mark carries blank padding rows that exist only so the hooks have
        // somewhere to sit while they travel. An animation needs them, because
        // its height has to stay put between frames; a banner printed once does
        // not, and keeping them there just opens with an empty line.
        val markRows = markSpans(markReveal).let { rows ->
            if (trimMark) {
                rows.dropWhile { it.plain().isBlank() }.dropLastWhile { it.plain().isBlank() }
            } else {
                rows
            }
        }
        val indent = markWidth + MARK_GAP

        // The wordmark only appears once the hooks have landed; showing it
        // beside an empty mark area while they are still off-screen reads as a
        // layout bug rather than an animation.
        val landed = markReveal >= 1.0
        val middle = markRows.size / 2
        markRows.forEachIndexed { index, row ->
            val spans = mutableListOf(IntroSpan(" "))
            spans += row
            val pad = " ".repeat((indent - markWidth).coerceAtLeast(1))
            if (landed) {
                when (index) {
                    middle - 1 -> spans += IntroSpan("$pad$WORDMARK", IntroStyle.TITLE)
                    middle -> spans += IntroSpan("${pad}v${state.version}", IntroStyle.MUTED)
                }
            }
            output += spans
        }

        if (state.rows.isEmpty() && state.hint.isEmpty()) return output

        output += listOf(IntroSpan(""))
        output += panel(state, visibleRows, panelReveal, spinnerTick)
        return output
    }

    private fun panel(
        state: IntroState,
        visibleRows: Int,
        panelReveal: Double,
        spinnerTick: Int,
    ): List<List<IntroSpan>> {
        val inner = panelWidth - 2
        val reveal = panelReveal.coerceIn(0.0, 1.0)
        val spacer = if (state.rows.isNotEmpty() && state.hint.isNotEmpty()) 1 else 0
        val hintLines = if (state.hint.isEmpty()) 0 else 1
        val height = state.rows.size + spacer + hintLines + 2

        // None of the panel exists yet, but it still occupies its lines so the
        // banner keeps one height for the cursor-up redraw.
        if (reveal <= 0.0) return List(height) { listOf(IntroSpan(" ".repeat(panelWidth))) }

        val drawn = (inner * reveal).toInt().coerceIn(0, inner)
        val complete = drawn == inner
        fun edge(corner: String, closing: String) = listOf(
            IntroSpan(
                corner + "\u2500".repeat(drawn) + " ".repeat(inner - drawn) + if (complete) closing else "",
                IntroStyle.MUTED,
            ),
        )

        val body = mutableListOf<List<IntroSpan>>()
        // Sides only appear once the border has closed. A half-drawn box with
        // content already sitting inside it reads as breakage, not motion.
        state.rows.forEachIndexed { index, row ->
            if (!complete || index >= visibleRows.coerceAtLeast(0)) {
                body += blankPanelLine(inner, complete)
                return@forEachIndexed
            }
            val value = row.value ?: SPINNER[spinnerTick.mod(SPINNER.length)].toString()
            val label = row.label.padEnd(labelWidth)
            body += listOf(
                IntroSpan("\u2502", IntroStyle.MUTED),
                IntroSpan("  "),
                IntroSpan(label, IntroStyle.MUTED),
                IntroSpan(value.padEnd(inner - 2 - label.length)),
                IntroSpan("\u2502", IntroStyle.MUTED),
            )
        }

        if (state.hint.isNotEmpty()) {
            if (spacer == 1) body += blankPanelLine(inner, complete)
            // The hint lands last, once every row has arrived.
            body += if (complete && visibleRows >= state.rows.size) {
                listOf(
                    IntroSpan("\u2502", IntroStyle.MUTED),
                    IntroSpan("  " + state.hint.padEnd(inner - 2), IntroStyle.MUTED),
                    IntroSpan("\u2502", IntroStyle.MUTED),
                )
            } else {
                blankPanelLine(inner, complete)
            }
        }

        return buildList {
            add(edge("\u256d", "\u256e"))
            addAll(body)
            add(edge("\u2570", "\u256f"))
        }
    }

    private fun blankPanelLine(inner: Int, sides: Boolean = true) = if (sides) {
        listOf(
            IntroSpan("\u2502", IntroStyle.MUTED),
            IntroSpan(" ".repeat(inner)),
            IntroSpan("\u2502", IntroStyle.MUTED),
        )
    } else {
        listOf(IntroSpan(" ".repeat(inner + 2)))
    }

    private fun isBlankRow(row: String) = row.all { it.code == 0x2800 }
}

/** Flattens one line to plain text, dropping styling. */
internal fun List<IntroSpan>.plain(): String = joinToString("") { it.text }.trimEnd()

/** Renders one line for a terminal, honouring colour support and NO_COLOR. */
internal fun List<IntroSpan>.render(capabilities: TerminalCapabilities): String {
    // Without colour the inner shadows would be indistinguishable from the body
    // and the mark would read flat, so carry the depth in the glyph instead:
    // a medium shade against the body's full block.
    if (!capabilities.ansi || capabilities.noColor) {
        return joinToString("") { span ->
            if (span.style == IntroStyle.SHADOW) span.text.replace('\u2588', '\u2592') else span.text
        }.trimEnd()
    }
    val trimmed = plain()
    if (trimmed.isEmpty()) return ""
    var remaining = trimmed.length
    return buildString {
        for (span in this@render) {
            if (remaining <= 0) break
            val text = if (span.text.length > remaining) span.text.take(remaining) else span.text
            remaining -= text.length
            when (span.style) {
                IntroStyle.BRAND -> append(Ansi.brandRed(capabilities)).append(text).append(Ansi.RESET)
                IntroStyle.SHADOW -> append(Ansi.shadowRed(capabilities)).append(text).append(Ansi.RESET)
                IntroStyle.TITLE -> append(Ansi.BOLD).append(text).append(Ansi.RESET)
                IntroStyle.MUTED -> append(Ansi.DIM).append(text).append(Ansi.RESET)
                IntroStyle.PLAIN -> append(text)
            }
        }
    }
}
