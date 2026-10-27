package com.vinnovateit.latch.desktop

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowContentLayoutListenerTest {
    @Test
    fun `resize lays out the content at the assigned bounds without resizing the window`() {
        EventQueue.invokeAndWait {
            val root = JPanel(BorderLayout())
            val content = JPanel()
            root.add(content, BorderLayout.CENTER)
            root.addNotify()
            try {
                root.setBounds(100, 50, 420, 700)
                root.validate()
                assertEquals(Dimension(420, 700), content.size)

                val listener = WindowContentLayoutListener()
                root.addComponentListener(listener)
                for (bounds in listOf(Rectangle(100, 50, 360, 600), Rectangle(20, 30, 1200, 700))) {
                    root.bounds = bounds
                    root.dispatchEvent(ComponentEvent(root, ComponentEvent.COMPONENT_RESIZED))

                    assertEquals(bounds.size, content.size)
                    assertEquals(bounds, root.bounds)
                    assertTrue(root.isValid)
                }
                root.removeComponentListener(listener)
            } finally {
                root.removeNotify()
            }
        }
    }

    @Test
    fun `show refreshes a stale content size on startup or restore`() {
        EventQueue.invokeAndWait {
            val root = JPanel(BorderLayout())
            val content = JPanel()
            root.add(content, BorderLayout.CENTER)
            root.addNotify()
            try {
                root.setSize(420, 700)
                root.validate()
                root.setSize(360, 600)
                assertEquals(Dimension(420, 700), content.size)

                val listener = WindowContentLayoutListener()
                root.addComponentListener(listener)
                root.dispatchEvent(ComponentEvent(root, ComponentEvent.COMPONENT_SHOWN))

                assertEquals(Dimension(360, 600), content.size)
                assertEquals(Rectangle(0, 0, 360, 600), root.bounds)
                assertTrue(root.isValid)
                root.removeComponentListener(listener)
            } finally {
                root.removeNotify()
            }
        }
    }
}
