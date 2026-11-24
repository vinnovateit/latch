package com.vinnovateit.latch.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.ui.theme.AppTitleColor
import kotlin.math.min

/*
 * The Latch mark, used for both the tray icon and the window/taskbar icon.
 *
 * The geometry below is vendored from composeResources/drawable/ic_latch.xml
 * rather than loaded with painterResource(). Two reasons:
 *
 *  1. That drawable fills every path with `@color/white` -- an Android resource
 *     reference. This module ships no values/colors.xml, so nothing resolves it.
 *  2. A loaded vector paints its own colours. The tray icon has to be recoloured
 *     per connection state, which a Painter over fixed vector data cannot do.
 *
 * Sizing, which matters here: Compose Desktop ignores a tray Painter's
 * intrinsicSize and rasterises at a hardcoded per-OS size -- 16x16 on Windows --
 * then hands the bitmap to java.awt.TrayIcon with setImageAutoSize(true), so a
 * HiDPI tray slot upscales that 16px bitmap rather than re-rendering the vector.
 *
 * This mark is far more detailed than the plain ring it replaced, and its thinner
 * strokes will not survive 16px. The red/grey colour swap is what actually
 * carries the connection state at tray size; the shape only has to stay
 * recognisably Latch. [intrinsicSize] below therefore only affects the window
 * icon, which is rendered from the vector at whatever size it declares.
 */

/** Source viewport of ic_latch.xml is 191x140; the mark is wider than it is tall. */
internal val MARK_PATH_DATA = listOf(
    "M69.54,92.49L88.83,110.95L69.07,129.18C62.04,135.36 53.07,135.48 45.07,132.96C42.3,132.09 39.88,130.39 37.78,128.38C26.66,117.72 21.93,112.41 12.83,101.92C9.57,98.15 6.42,94.21 4.26,89.71C-0.43,79.92 -1.02,70.66 1.37,56.2C2.31,50.52 4.14,44.99 7.26,40.16C12.92,31.39 21.31,22.15 36.22,7.06C37.88,5.38 39.76,3.9 41.89,2.88C51.42,-1.67 57.69,-0.61 68.13,4.53L112.1,46.9C113.12,47.88 113.96,49.05 114.51,50.36C120.19,63.79 118.22,70.78 113.64,81.9C97.53,66.53 72.25,43.68 72.25,43.68C69.62,42.1 54.96,37.46 47.2,45.92C39.44,54.38 32.58,77.36 49.2,90.49C55.53,95.45 60.1,95.56 69.54,92.49Z",
    "M58.21,106.54C68.43,112.86 74.92,113.48 86.55,108.66L78.2,100.6C73.39,95.96 69.57,92.3 69.49,92.51C62.88,94.73 59.83,94.73 54.45,93.13C50.2,91.42 48.22,89.89 45.27,86.31L41.98,82.08L44.1,86.67C47.64,95.16 50.56,99.56 58.21,106.54Z",
    "M60.09,41.16C50.8,42.3 46.96,44.31 42.33,53.63C44.24,47.69 45.87,44.39 49.98,38.57C57.74,27.9 62.19,24.44 70.32,23.87C80.86,22.61 86.64,24.58 96.78,31.99L113.24,47.98C119.36,59.31 119.96,67.8 113.63,81.95L80.43,51.15C72.72,43.74 68.2,41.31 60.09,41.16Z",
    "M121.46,46.91L102.17,28.45L121.93,10.22C130.19,2.95 141.13,4.06 150.03,7.98C164.43,21.62 168.85,26.72 180.26,39.9L180.71,40.42C182.29,42.23 183.75,44.15 184.94,46.24C191.56,57.95 192.39,68.01 189.2,85.64C188.47,89.68 187.17,93.62 185.05,97.14C179.65,106.11 171.69,115.14 157.03,130.05C153.88,133.25 150.36,136.19 146.17,137.78C140.34,139.98 135.56,139.86 129.76,137.84C125.06,136.2 121.04,133.11 117.46,129.65L78.9,92.49C77.88,91.51 77.04,90.34 76.49,89.04C70.81,75.61 72.78,68.61 77.36,57.49C93.47,72.86 118.75,95.71 118.75,95.71C121.38,97.29 136.04,101.94 143.8,93.47C151.56,85.01 158.42,62.04 141.8,48.91C135.47,43.95 130.9,43.83 121.46,46.91Z",
    "M104.52,30.74L121.4,46.99C128.01,44.77 131.36,44.8 136.74,46.4C140.99,48.12 142.78,49.51 145.73,53.08L149.02,57.31L146.9,52.73C143.36,44.23 140.44,39.83 132.79,32.85C122.57,26.53 116.15,25.91 104.52,30.74Z",
    "M130.89,98.38C140.18,97.24 144.02,94.99 148.64,85.68C146.73,91.62 145.11,94.91 141,100.73C133.24,111.4 128.79,114.87 120.65,115.43C110.12,116.69 104.33,114.72 94.2,107.32L77.73,91.33C70.75,80.47 71.39,71.39 77.15,57.34L110.67,88.28C118.38,95.7 122.77,98.23 130.89,98.38Z",
)

/** Parsed once; [Path] is only ever read from here, so sharing across icons is safe. */
private val MARK_PATHS: List<Path> =
    MARK_PATH_DATA.map { PathParser().parsePathString(it).toPath() }

/*
 * Measured rather than taken from the declared 191x140 viewport. Two of the paths
 * run a fraction past it (to x=192.4, y=140.0), and Skia reports fast
 * control-point bounds which can be looser still. Measuring keeps the mark
 * centred and unclipped whatever the parser hands back.
 */
private val MARK_BOUNDS: List<Rect> = MARK_PATHS.map { it.getBounds() }
internal val MARK_LEFT: Float = MARK_BOUNDS.minOf { it.left }
internal val MARK_TOP: Float = MARK_BOUNDS.minOf { it.top }
internal val MARK_W: Float = MARK_BOUNDS.maxOf { it.right } - MARK_LEFT
internal val MARK_H: Float = MARK_BOUNDS.maxOf { it.bottom } - MARK_TOP

/**
 * Only consulted for the window icon -- Compose Desktop asks the tray for its own
 * size and rasterises to that, ignoring this. 64px downscales cleanly to the 32px
 * and 16px the Windows taskbar and Alt-Tab switcher use.
 */
private const val ICON_SIZE = 64f

/** Neutral grey for the not-latched tray icon. */
internal val MutedGrey = Color(0xFF9E9E9E)

/**
 * The Latch mark as an [ImageVector], built from the same path geometry as the
 * [Painter] above. Compose Desktop's Icon composable recolours it via tint, which
 * is what lets the rail header, credentials screen and top bar all show the mark
 * in the theme primary colour without duplicating any geometry.
 *
 * Each subpath is added individually for the same winding reason as the Painter:
 * merging them would turn the overlapping hook areas into transparent holes.
 */
internal val LatchMark: ImageVector by lazy {
    val builder = ImageVector.Builder(
        name = "LatchMark",
        defaultWidth = 191.dp,
        defaultHeight = 140.dp,
        viewportWidth = 191f,
        viewportHeight = 140f,
    )
    MARK_PATH_DATA.forEach { data ->
        builder.addPath(
            pathData = PathParser().parsePathString(data).toNodes(),
            fill = SolidColor(Color.Black),
        )
    }
    builder.build()
}

internal class LatchIcon(private val tint: Color) : Painter() {

    override val intrinsicSize: Size = Size(ICON_SIZE, ICON_SIZE)

    override fun DrawScope.onDraw() {
        // Fit the wide mark into a square canvas: scale to the tighter axis and
        // centre, so it letterboxes vertically instead of stretching.
        val factor = min(size.width / MARK_W, size.height / MARK_H)
        val dx = (size.width - MARK_W * factor) / 2f - MARK_LEFT * factor
        val dy = (size.height - MARK_H * factor) / 2f - MARK_TOP * factor

        translate(dx, dy) {
            scale(factor, factor, pivot = Offset.Zero) {
                // Filled subpath by subpath, as the source vector does. Merging
                // them into one Path would let non-zero winding turn the overlaps
                // between the two hooks into holes.
                for (path in MARK_PATHS) {
                    drawPath(path, color = tint)
                }
            }
        }
    }

    companion object {
        /**
         * Brand red, for the window and taskbar. A fresh instance per call: a
         * [Painter] mutates its own alpha/colorFilter during a draw, so the tray
         * and the window must not share one.
         */
        fun brand() = LatchIcon(AppTitleColor)

        /** Red while latched, grey while not. */
        fun forTray(latched: Boolean) =
            LatchIcon(if (latched) AppTitleColor else MutedGrey)
    }
}

