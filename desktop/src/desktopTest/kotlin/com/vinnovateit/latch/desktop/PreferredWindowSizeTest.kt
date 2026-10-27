package com.vinnovateit.latch.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.Insets
import java.awt.Rectangle
import java.awt.geom.AffineTransform
import java.awt.image.ColorModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreferredWindowSizeTest {
    @Test
    fun `display scale does not shrink an already logical work area`() {
        for (scale in listOf(1.0, 1.25, 1.5, 2.0)) {
            val configuration = ScreenConfiguration(Rectangle(0, 0, 1280, 840), scale)

            assertEquals(
                DpSize(420.dp, 700.dp),
                preferredWindowSize(configuration, Insets(0, 0, 40, 0)),
                "display scale $scale",
            )
        }
    }

    @Test
    fun `taskbar insets and a short scaled display limit the initial size`() {
        val configuration = ScreenConfiguration(Rectangle(0, 0, 1093, 614), 1.25)
        val size = preferredWindowSize(configuration, Insets(0, 0, 38, 0))

        assertEquals(360.dp, size.width)
        assertEquals(576.dp, size.height)
    }

    @Test
    fun `a work area below the usual minimum still contains the initial size`() {
        val configuration = ScreenConfiguration(Rectangle(-300, 0, 300, 500), 2.0)
        val size = preferredWindowSize(configuration, Insets(10, 10, 30, 20))

        assertTrue(size.width <= 270.dp)
        assertTrue(size.height <= 460.dp)
    }

    private class ScreenConfiguration(
        private val screenBounds: Rectangle,
        private val scale: Double,
    ) : GraphicsConfiguration() {
        override fun getBounds(): Rectangle = Rectangle(screenBounds)
        override fun getDefaultTransform(): AffineTransform = AffineTransform.getScaleInstance(scale, scale)
        override fun getNormalizingTransform(): AffineTransform = AffineTransform()
        override fun getColorModel(): ColorModel = ColorModel.getRGBdefault()
        override fun getColorModel(transparency: Int): ColorModel = colorModel
        override fun getDevice(): GraphicsDevice =
            throw UnsupportedOperationException("Window sizing does not require a graphics device")
    }
}
