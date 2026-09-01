package com.vinnovateit.latch.cli

import java.awt.geom.Area
import java.awt.geom.Path2D
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.delay

private const val SPLASH_WIDTH = 56
private const val SPLASH_HEIGHT = 20
private const val DOT_WIDTH = SPLASH_WIDTH * 2
private const val DOT_HEIGHT = SPLASH_HEIGHT * 4
private const val FRAME_COUNT = 10
private const val BRAND_RED_TRUE_COLOR = "\u001b[38;2;192;18;33m"
private const val BRAND_RED_BASIC = "\u001b[31m"
private const val BACKGROUND_COLOR = "\u001b[2;37m"
private const val RESET = "\u001b[0m"

data class SplashCapabilities(
    val interactive: Boolean,
    val ansi: Boolean,
    val trueColor: Boolean,
    val noColor: Boolean,
)

fun detectSplashCapabilities(
    terminal: TerminalIO,
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name", ""),
): SplashCapabilities {
    val interactive = terminal.interactive
    val noColor = environment.containsKey("NO_COLOR")
    val term = environment["TERM"].orEmpty()
    val windows = osName.startsWith("Windows", ignoreCase = true)
    val windowsAnsi = environment.containsKey("WT_SESSION") ||
        environment.containsKey("ANSICON") ||
        environment["ConEmuANSI"].equals("ON", ignoreCase = true) ||
        term.contains("xterm", ignoreCase = true)
    val ansi = interactive && !noColor && !term.equals("dumb", ignoreCase = true) && (!windows || windowsAnsi)
    val colorTerm = environment["COLORTERM"].orEmpty()
    val trueColor = ansi && (
        colorTerm.contains("truecolor", ignoreCase = true) ||
            colorTerm.contains("24bit", ignoreCase = true) ||
            environment.containsKey("WT_SESSION")
        )
    return SplashCapabilities(interactive, ansi, trueColor, noColor)
}

class SplashRenderer(private val seed: Long = 0L) {
    private val logo = Area().apply {
        LOGO_PATHS.forEach { add(Area(parsePath(it))) }
    }

    fun frame(progress: Double, capabilities: SplashCapabilities): List<String> {
        val amount = progress.coerceIn(0.0, 1.0)
        return List(SPLASH_HEIGHT) { row -> renderRow(row, amount, capabilities) }
    }

    private fun renderRow(row: Int, progress: Double, capabilities: SplashCapabilities): String = buildString {
        var activeColor = ""
        repeat(SPLASH_WIDTH) { column ->
            val cell = brailleCell(column, row, progress)
            if (capabilities.ansi && !capabilities.noColor && cell.bits != 0) {
                val color = when {
                    cell.logo && capabilities.trueColor -> BRAND_RED_TRUE_COLOR
                    cell.logo -> BRAND_RED_BASIC
                    else -> BACKGROUND_COLOR
                }
                if (color != activeColor) {
                    append(color)
                    activeColor = color
                }
            }
            appendCodePoint(0x2800 + cell.bits)
        }
        if (activeColor.isNotEmpty()) append(RESET)
    }

    private fun brailleCell(column: Int, row: Int, progress: Double): BrailleCell {
        var bits = 0
        var containsLogo = false
        DOTS.forEach { dot ->
            val x = column * 2 + dot.x
            val y = row * 4 + dot.y
            val logoDot = isLogoDot(x, y, progress)
            val backgroundDot = isBackgroundDot(x, y, progress)
            if (logoDot || backgroundDot) bits = bits or dot.bit
            containsLogo = containsLogo || logoDot
        }
        return BrailleCell(bits, containsLogo)
    }

    private fun isLogoDot(x: Int, y: Int, progress: Double): Boolean {
        val scale = 0.43
        val left = (DOT_WIDTH - 191.0 * scale) / 2.0
        val top = (DOT_HEIGHT - 139.4 * scale) / 2.0
        val sourceX = (x - left) / scale
        val sourceY = (y - top) / scale
        val reveal = (sourceX / 191.0) * 0.8 + 0.1
        return progress >= reveal && logo.contains(sourceX, sourceY)
    }

    private fun isBackgroundDot(x: Int, y: Int, progress: Double): Boolean {
        val centerX = DOT_WIDTH / 2.0
        val centerY = DOT_HEIGHT / 2.0
        val radius = hypot((x - centerX) / centerX, (y - centerY) / centerY)
        val radialReveal = progress * 1.65
        if (radius > radialReveal) return false

        val phase = ((seed xor (seed ushr 32)) and 0xffff).toDouble() / 0xffff * 2.0 * PI
        val diagonal = (x * 3L + y * 5L + seed).mod(17L) < 2L
        val wave = sin(x * 0.19 + y * 0.13 + phase + progress * PI * 2.0) > 0.82
        return diagonal || wave
    }
}

suspend fun showSplash(
    terminal: TerminalIO,
    capabilities: SplashCapabilities,
    frameDelayMillis: Long = 60,
) {
    if (!capabilities.interactive) return

    val renderer = SplashRenderer()
    if (!capabilities.ansi || capabilities.noColor) {
        terminal.println(renderer.frame(1.0, capabilities).joinToString("\n"))
        return
    }

    terminal.print("\u001b[?25l")
    try {
        repeat(FRAME_COUNT) { index ->
            if (index > 0) terminal.print("\u001b[${SPLASH_HEIGHT}A")
            val progress = (index + 1).toDouble() / FRAME_COUNT
            terminal.print(renderer.frame(progress, capabilities).joinToString("\n", postfix = "\n"))
            if (frameDelayMillis > 0) delay(frameDelayMillis)
        }
    } finally {
        terminal.print("$RESET\u001b[?25h\n")
    }
}

private data class BrailleCell(val bits: Int, val logo: Boolean)
private data class BrailleDot(val x: Int, val y: Int, val bit: Int)

private val DOTS = listOf(
    BrailleDot(0, 0, 0x01),
    BrailleDot(0, 1, 0x02),
    BrailleDot(0, 2, 0x04),
    BrailleDot(1, 0, 0x08),
    BrailleDot(1, 1, 0x10),
    BrailleDot(1, 2, 0x20),
    BrailleDot(0, 3, 0x40),
    BrailleDot(1, 3, 0x80),
)

private fun parsePath(data: String): Path2D.Double {
    val tokens = PATH_TOKEN.findAll(data).map(MatchResult::value).toList()
    val path = Path2D.Double(Path2D.WIND_NON_ZERO)
    var index = 0
    var command = ' '
    while (index < tokens.size) {
        val token = tokens[index]
        if (token.length == 1 && token[0].isLetter()) {
            command = token[0]
            index++
            if (command == 'Z') path.closePath()
            continue
        }
        fun coordinate(): Double = tokens[index++].toDouble()
        when (command) {
            'M' -> {
                path.moveTo(coordinate(), coordinate())
                command = 'L'
            }
            'L' -> path.lineTo(coordinate(), coordinate())
            'C' -> path.curveTo(
                coordinate(), coordinate(),
                coordinate(), coordinate(),
                coordinate(), coordinate(),
            )
            else -> error("Unsupported logo path command: $command")
        }
    }
    return path
}

private val PATH_TOKEN = Regex("[MLCZ]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)")

// Vendored from app/src/main/res/drawable/ic_latch.xml (191 x 139.4 viewport).
private val LOGO_PATHS = listOf(
    "M69.54,92.49L88.83,110.95L69.07,129.18C62.04,135.36 53.07,135.48 45.07,132.96C42.3,132.09 39.88,130.39 37.78,128.38C26.66,117.72 21.93,112.41 12.83,101.92C9.57,98.15 6.42,94.21 4.26,89.71C-0.43,79.92 -1.02,70.66 1.37,56.2C2.31,50.52 4.14,44.99 7.26,40.16C12.92,31.39 21.31,22.15 36.22,7.06C37.88,5.38 39.76,3.9 41.89,2.88C51.42,-1.67 57.69,-0.61 68.13,4.53L112.1,46.9C113.12,47.88 113.96,49.05 114.51,50.36C120.19,63.79 118.22,70.78 113.64,81.9C97.53,66.53 72.25,43.68 72.25,43.68C69.62,42.1 54.96,37.46 47.2,45.92C39.44,54.38 32.58,77.36 49.2,90.49C55.53,95.45 60.1,95.56 69.54,92.49Z",
    "M121.46,46.91L102.17,28.45L121.93,10.22C130.19,2.95 141.13,4.06 150.03,7.98C164.43,21.62 168.85,26.72 180.26,39.9L180.71,40.42C182.29,42.23 183.75,44.15 184.94,46.24C191.56,57.95 192.39,68.01 189.2,85.64C188.47,89.68 187.17,93.62 185.05,97.14C179.65,106.11 171.69,115.14 157.03,130.05C153.88,133.25 150.36,136.19 146.17,137.78C140.34,139.98 135.56,139.86 129.76,137.84C125.06,136.2 121.04,133.11 117.46,129.65L78.9,92.49C77.88,91.51 77.04,90.34 76.49,89.04C70.81,75.61 72.78,68.61 77.36,57.49C93.47,72.86 118.75,95.71 118.75,95.71C121.38,97.29 136.04,101.94 143.8,93.47C151.56,85.01 158.42,62.04 141.8,48.91C135.47,43.95 130.9,43.83 121.46,46.91Z",
)
