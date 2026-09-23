package com.vinnovateit.latch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.platform.Platform
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.ui.onboarding.FakeCredentialStore
import com.vinnovateit.latch.ui.onboarding.FakePlatformServices
import com.vinnovateit.latch.ui.onboarding.InMemoryKeyValueStore
import com.vinnovateit.latch.ui.theme.LatchTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The wide layout's power button has to read as the same control as the compact
 * layout's, which is a circle.
 *
 * It regressed because the morph was written as an absolute corner radius
 * (50.dp), a number that only draws a circle on a button about 100dp across. The
 * desktop button is capped at 340dp, so it rendered as a rounded square. These
 * assertions are made against actual pixels because the defect is purely visual
 * and the shape argument alone would not have shown it.
 */
class PowerButtonShapeTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val store = InMemoryKeyValueStore()
        Platform.install(FakePlatformServices(FakeCredentialStore(), store))
        SettingsManager.initialize(store)
    }

    private val side = 200

    /** Renders the button on a known background so "filled" is unambiguous. */
    private fun renderWideButton() {
        compose.setContent {
            LatchTheme {
                Box(Modifier.size(side.dp).background(Backdrop)) {
                    MorphingPowerButton(isConnected = false, onClick = {})
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * Sanity check on the render, not the regression itself: a rounded square
     * with a 50.dp radius also leaves the extreme corner bare, so this alone
     * does not separate the two. `the button reaches the edge on the axes but
     * not on the diagonal` is the one that does.
     */
    @Test
    fun `the button is painted with bare corners and filled edges`() {
        renderWideButton()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val r = pixels.width / 2
        // Inset so antialiasing at the exact boundary cannot decide the test.
        val inset = (pixels.width * 0.04f).toInt()

        // Dead corner of the bounding box: bare for any rounded shape.
        assertEquals(
            Backdrop,
            pixels[inset, inset],
            "the top-left corner must be outside the button",
        )

        // Middle of each edge is on the circle, so just inside must be painted.
        assertTrue(pixels[r, inset].alpha > 0f && pixels[r, inset] != Backdrop, "top edge midpoint is inside the circle")
        assertTrue(pixels[inset, r] != Backdrop, "left edge midpoint is inside the circle")
        assertTrue(pixels[pixels.width - 1 - inset, r] != Backdrop, "right edge midpoint is inside the circle")
    }

    /**
     * A circle is symmetric about both axes, a rounded square with the wrong
     * radius still is, so this pins the thing that actually separates them: how
     * far the paint reaches along the diagonal versus along the axis.
     */
    @Test
    fun `the button reaches the edge on the axes but not on the diagonal`() {
        renderWideButton()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val r = pixels.width / 2

        fun filledAlong(dx: Int, dy: Int): Int {
            var steps = 0
            while (steps < r) {
                val x = r + dx * steps
                val y = r + dy * steps
                if (x !in 0 until pixels.width || y !in 0 until pixels.height) break
                if (pixels[x, y] == Backdrop) break
                steps++
            }
            return steps
        }

        val alongAxis = filledAlong(1, 0)
        val alongDiagonal = filledAlong(1, 1)

        // On a circle the diagonal run stops at r/sqrt(2), about 71% of the axis
        // run. On a square it would reach the full corner and exceed the axis run.
        assertTrue(
            alongDiagonal < alongAxis,
            "diagonal reach $alongDiagonal must be shorter than axis reach $alongAxis for a circle",
        )
        val ratio = alongDiagonal.toFloat() / alongAxis
        assertTrue(
            ratio in 0.62f..0.80f,
            "diagonal/axis ratio was $ratio, expected about 0.71 for a circle",
        )
    }

    /**
     * A percentage corner radius draws a pill, not a circle, on a non-square
     * box, and the wide layout's slot is not square in a short window. The
     * button pins its own aspect ratio to stop that.
     */
    @Test
    fun `the button stays circular in a slot that is not square`() {
        compose.setContent {
            LatchTheme {
                Box(Modifier.size(width = (side * 2).dp, height = side.dp).background(Backdrop)) {
                    MorphingPowerButton(isConnected = false, onClick = {})
                }
            }
        }
        compose.waitForIdle()
        val pixels = compose.onRoot().captureToImage().toPixelMap()

        var minX = pixels.width
        var maxX = -1
        var minY = pixels.height
        var maxY = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] != Backdrop) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        val paintedWidth = maxX - minX + 1
        val paintedHeight = maxY - minY + 1

        assertTrue(
            kotlin.math.abs(paintedWidth - paintedHeight) <= 2,
            "painted area was ${paintedWidth}x$paintedHeight, expected square so the shape is a circle not a pill",
        )
    }

    private companion object {
        val Backdrop = Color(0xFF00FF00)
    }
}
