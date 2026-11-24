package com.vinnovateit.latch.desktop

import java.awt.Container
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/** Refresh the Swing content of transparent Windows windows without changing their bounds. */
internal class WindowContentLayoutListener : ComponentAdapter() {
    override fun componentResized(event: ComponentEvent) {
        (event.component as? Container)?.let(::refreshContent)
    }

    override fun componentShown(event: ComponentEvent) {
        (event.component as? Container)?.let(::refreshContent)
    }

    fun refreshContent(container: Container) {
        // Validate the child hierarchy before repainting at the window's assigned size.
        container.invalidate()
        container.validate()
        container.repaint()
    }
}
