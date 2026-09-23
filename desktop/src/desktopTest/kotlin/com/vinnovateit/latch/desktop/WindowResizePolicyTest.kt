package com.vinnovateit.latch.desktop

import com.vinnovateit.latch.desktop.WindowResizePolicy.MIN_H
import com.vinnovateit.latch.desktop.WindowResizePolicy.MIN_W
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WindowResizePolicy] is the whole of Latch's sizing authority, so these pin both
 * halves of it: the minimums it does enforce on the user's own drags, and the
 * aspect ratio it deliberately does not enforce on anything.
 */
class WindowResizePolicyTest {

    private val bounds = WindowBounds(x = 100, y = 50, width = 420, height = 700)

    @Test
    fun `dragging the right edge out grows the window and leaves the origin alone`() {
        val result = WindowResizePolicy.resizeRight(bounds, dx = 120)

        assertEquals(WindowBounds(100, 50, 540, 700), result)
    }

    @Test
    fun `dragging the right edge in stops at the minimum width`() {
        val result = WindowResizePolicy.resizeRight(bounds, dx = -1000)

        assertEquals(MIN_W, result.width)
        assertEquals(100, result.x, "the left edge is the anchor and must not move")
    }

    @Test
    fun `dragging the bottom edge up stops at the minimum height`() {
        val result = WindowResizePolicy.resizeBottom(bounds, dy = -1000)

        assertEquals(MIN_H, result.height)
        assertEquals(50, result.y, "the top edge is the anchor and must not move")
    }

    @Test
    fun `dragging the left edge keeps the right edge pinned`() {
        val right = bounds.x + bounds.width

        val result = WindowResizePolicy.resizeLeft(bounds, dx = -150)

        assertEquals(570, result.width, "moving the left edge 150 left widens by 150")
        assertEquals(right, result.x + result.width, "the right edge is the anchor")
    }

    @Test
    fun `the right edge stays pinned even when the left drag is clamped`() {
        val right = bounds.x + bounds.width

        // Far past MIN_W, so the requested width is unreachable and gets clamped.
        val result = WindowResizePolicy.resizeLeft(bounds, dx = 1000)

        assertEquals(MIN_W, result.width)
        assertEquals(
            right,
            result.x + result.width,
            "clamping the width must move x by the amount actually applied, not the amount asked for",
        )
    }

    @Test
    fun `the bottom-left corner pins the top-right corner`() {
        val right = bounds.x + bounds.width

        val result = WindowResizePolicy.resizeBottomLeft(bounds, dx = -80, dy = 60)

        assertEquals(right, result.x + result.width, "the right edge is anchored")
        assertEquals(50, result.y, "the top edge is anchored")
        assertEquals(500, result.width)
        assertEquals(760, result.height)
    }

    @Test
    fun `the bottom-right corner pins the top-left corner and clamps both axes`() {
        val result = WindowResizePolicy.resizeBottomRight(bounds, dx = -1000, dy = -1000)

        assertEquals(WindowBounds(100, 50, MIN_W, MIN_H), result)
    }

    /**
     * The regression this whole change exists for. A 4:3 cap on a 700px-tall window
     * would stop it at 933px wide; the desktop UI's own wide layout starts at 900dp
     * and the user is entitled to drag well past it.
     */
    @Test
    fun `the user can drag far wider than four-thirds of the height`() {
        val result = WindowResizePolicy.resizeRight(bounds, dx = 1400)

        assertEquals(1820, result.width)
        assertTrue(
            result.width > result.height * 4 / 3,
            "width ${result.width} must not be capped at ${result.height * 4 / 3}",
        )
    }

    @Test
    fun `a short window may still be wide`() {
        val short = WindowBounds(x = 0, y = 0, width = 900, height = MIN_H)

        val result = WindowResizePolicy.resizeRight(short, dx = 200)

        assertEquals(1100, result.width)
        assertEquals(MIN_H, result.height, "widening must not drag the height along with it")
    }
}
