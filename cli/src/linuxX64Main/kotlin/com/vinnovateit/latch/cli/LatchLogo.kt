package com.vinnovateit.latch.cli

/**
 * Half-block glyphs. One character cell is one pixel wide and two tall, and a
 * terminal cell is roughly twice as tall as it is wide, so those pixels come out
 * square and strokes render solid rather than dotted.
 */
internal fun halfBlock(upper: Boolean, lower: Boolean): Char = when {
    upper && lower -> '\u2588'
    upper -> '\u2580'
    lower -> '\u2584'
    else -> ' '
}

/**
 * A filled logo shape. Bezier curves are flattened once at construction; point
 * membership uses the non-zero winding rule, matching the Path2D/Area raster
 * this replaced. Keeps `contains(x, y)` so IntroRenderer needs no changes.
 */
internal class LogoShape(data: String) {
    private val contours: List<List<Point>> = parse(data)

    fun contains(x: Double, y: Double): Boolean {
        var winding = 0
        for (contour in contours) {
            var i = 0
            while (i < contour.size) {
                val a = contour[i]
                val b = contour[(i + 1) % contour.size]
                if (a.y <= y) {
                    if (b.y > y && (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y) > 0) winding++
                } else if (b.y <= y && (b.x - a.x) * (y - a.y) - (x - a.x) * (b.y - a.y) < 0) {
                    winding--
                }
                i++
            }
        }
        return winding != 0
    }

    private data class Point(val x: Double, val y: Double)

    companion object {
        private const val CURVE_STEPS = 24
        private val PATH_TOKEN = Regex("[MLCZ]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)")

        private fun parse(data: String): List<List<Point>> {
            val tokens = PATH_TOKEN.findAll(data).map(MatchResult::value).toList()
            val contours = mutableListOf<List<Point>>()
            var current = mutableListOf<Point>()
            var index = 0
            var command = ' '
            fun coordinate(): Double = tokens[index++].toDouble()
            while (index < tokens.size) {
                val token = tokens[index]
                if (token.length == 1 && token[0].isLetter()) {
                    command = token[0]
                    index++
                    if (command == 'Z') {
                        if (current.size >= 3) contours += current.toList()
                        current = mutableListOf()
                    }
                    continue
                }
                when (command) {
                    'M' -> {
                        current = mutableListOf(Point(coordinate(), coordinate()))
                        command = 'L'
                    }
                    'L' -> current += Point(coordinate(), coordinate())
                    'C' -> {
                        val p0 = current.last()
                        val p1 = Point(coordinate(), coordinate())
                        val p2 = Point(coordinate(), coordinate())
                        val p3 = Point(coordinate(), coordinate())
                        for (step in 1..CURVE_STEPS) {
                            current += bezier(p0, p1, p2, p3, step.toDouble() / CURVE_STEPS)
                        }
                    }
                    else -> error("Unsupported logo path command: $command")
                }
            }
            if (current.size >= 3) contours += current.toList()
            return contours
        }

        private fun bezier(p0: Point, p1: Point, p2: Point, p3: Point, t: Double): Point {
            val u = 1 - t
            fun axis(a: Double, b: Double, c: Double, d: Double) =
                u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
            return Point(
                axis(p0.x, p1.x, p2.x, p3.x),
                axis(p0.y, p1.y, p2.y, p3.y),
            )
        }
    }
}

/** The Latch hook mark, split so an animation can move each hook independently. */
internal object LatchLogo {
    const val VIEWPORT_WIDTH = 191.0
    const val VIEWPORT_HEIGHT = 139.4

    val hooks: List<LogoShape> by lazy { HOOK_PATHS.map(::LogoShape) }
    val shadows: List<List<LogoShape>> by lazy {
        SHADOW_PATHS.map { group -> group.map(::LogoShape) }
    }

    // Vendored from app/src/main/res/drawable/ic_latch.xml (191 x 139.4
    // viewport). Lower-left hook first; paint order is body then its shadows.
    private val HOOK_PATHS = listOf(
        "M69.54,92.49L88.83,110.95L69.07,129.18C62.04,135.36 53.07,135.48 45.07,132.96C42.3,132.09 39.88,130.39 37.78,128.38C26.66,117.72 21.93,112.41 12.83,101.92C9.57,98.15 6.42,94.21 4.26,89.71C-0.43,79.92 -1.02,70.66 1.37,56.2C2.31,50.52 4.14,44.99 7.26,40.16C12.92,31.39 21.31,22.15 36.22,7.06C37.88,5.38 39.76,3.9 41.89,2.88C51.42,-1.67 57.69,-0.61 68.13,4.53L112.1,46.9C113.12,47.88 113.96,49.05 114.51,50.36C120.19,63.79 118.22,70.78 113.64,81.9C97.53,66.53 72.25,43.68 72.25,43.68C69.62,42.1 54.96,37.46 47.2,45.92C39.44,54.38 32.58,77.36 49.2,90.49C55.53,95.45 60.1,95.56 69.54,92.49Z",
        "M121.46,46.91L102.17,28.45L121.93,10.22C130.19,2.95 141.13,4.06 150.03,7.98C164.43,21.62 168.85,26.72 180.26,39.9L180.71,40.42C182.29,42.23 183.75,44.15 184.94,46.24C191.56,57.95 192.39,68.01 189.2,85.64C188.47,89.68 187.17,93.62 185.05,97.14C179.65,106.11 171.69,115.14 157.03,130.05C153.88,133.25 150.36,136.19 146.17,137.78C140.34,139.98 135.56,139.86 129.76,137.84C125.06,136.2 121.04,133.11 117.46,129.65L78.9,92.49C77.88,91.51 77.04,90.34 76.49,89.04C70.81,75.61 72.78,68.61 77.36,57.49C93.47,72.86 118.75,95.71 118.75,95.71C121.38,97.29 136.04,101.94 143.8,93.47C151.56,85.01 158.42,62.04 141.8,48.91C135.47,43.95 130.9,43.83 121.46,46.91Z",
    )

    private val SHADOW_PATHS = listOf(
        listOf(
            "M58.21,106.54C68.43,112.86 74.92,113.48 86.55,108.66L78.2,100.6C73.39,95.96 69.57,92.3 69.49,92.51C62.88,94.73 59.83,94.73 54.45,93.13C50.2,91.42 48.22,89.89 45.27,86.31L41.98,82.08L44.1,86.67C47.64,95.16 50.56,99.56 58.21,106.54Z",
            "M60.09,41.16C50.8,42.3 46.96,44.31 42.33,53.63C44.24,47.69 45.87,44.39 49.98,38.57C57.74,27.9 62.19,24.44 70.32,23.87C80.86,22.61 86.64,24.58 96.78,31.99L113.24,47.98C119.36,59.31 119.96,67.8 113.63,81.95L80.43,51.15C72.72,43.74 68.2,41.31 60.09,41.16Z",
        ),
        listOf(
            "M104.52,30.74L121.4,46.99C128.01,44.77 131.36,44.8 136.74,46.4C140.99,48.12 142.78,49.51 145.73,53.08L149.02,57.31L146.9,52.73C143.36,44.23 140.44,39.83 132.79,32.85C122.57,26.53 116.15,25.91 104.52,30.74Z",
            "M130.89,98.38C140.18,97.24 144.02,94.99 148.64,85.68C146.73,91.62 145.11,94.91 141,100.73C133.24,111.4 128.79,114.87 120.65,115.43C110.12,116.69 104.33,114.72 94.2,107.32L77.73,91.33C70.75,80.47 71.39,71.39 77.15,57.34L110.67,88.28C118.38,95.7 122.77,98.23 130.89,98.38Z",
        ),
    )
}
