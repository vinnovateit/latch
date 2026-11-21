package com.vinnovateit.latch.desktop

/**
 * Linux fix: Compose Desktop rasterises the tray Painter via TYPE_INT_RGB (no alpha),
 * making transparent pixels white. After Compose installs the TrayIcon we replace its
 * image with one rendered into TYPE_INT_ARGB using AWT's Path2D, which understands the
 * same SVG path data we already have in [MARK_PATH_DATA].
 */
internal fun patchLinuxTrayIconAlpha(latched: Boolean, onAction: () -> Unit = {}) {
    val systemTray = java.awt.SystemTray.getSystemTray()
    val sz = systemTray.trayIconSize
    val w = sz.width.coerceAtLeast(22)
    val h = sz.height.coerceAtLeast(22)

    // Fit the wide mark (191×140 viewport) into the square tray slot.
    val scaleX = w.toFloat() / MARK_W
    val scaleY = h.toFloat() / MARK_H
    val factor = minOf(scaleX, scaleY) * 0.85f          // 85% so it doesn't fill edge-to-edge
    val dx = (w - MARK_W * factor) / 2f - MARK_LEFT * factor
    val dy = (h - MARK_H * factor) / 2f - MARK_TOP * factor

    val awtColor = if (latched) {
        java.awt.Color(AppTitleColor.red, AppTitleColor.green, AppTitleColor.blue)
    } else {
        java.awt.Color(MutedGrey.red, MutedGrey.green, MutedGrey.blue)
    }

    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g2 = img.createGraphics()
    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g2.color = awtColor

    val at = java.awt.geom.AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble())
    at.scale(factor.toDouble(), factor.toDouble())

    MARK_PATH_DATA.forEach { svgData ->
        val awtPath = svgToAwtPath(svgData)
        g2.fill(at.createTransformedShape(awtPath))
    }
    g2.dispose()

    systemTray.trayIcons.forEach { icon ->
        icon.image = img
        icon.isImageAutoSize = true
        if (icon.actionListeners.isEmpty()) {
            icon.addActionListener { onAction() }
        }
        if (icon.mouseListeners.none { it is LinuxTrayClickListener }) {
            icon.addMouseListener(LinuxTrayClickListener(onAction))
        }
    }
}

private class LinuxTrayClickListener(private val onLeftClick: () -> Unit) : java.awt.event.MouseAdapter() {
    override fun mousePressed(e: java.awt.event.MouseEvent) {
        handle(e)
    }
    override fun mouseReleased(e: java.awt.event.MouseEvent) {
        handle(e)
    }
    override fun mouseClicked(e: java.awt.event.MouseEvent) {
        if (e.button == java.awt.event.MouseEvent.BUTTON1) {
            onLeftClick()
        }
    }

    private fun handle(e: java.awt.event.MouseEvent) {
        if (e.button == java.awt.event.MouseEvent.BUTTON1 && e.id == java.awt.event.MouseEvent.MOUSE_RELEASED) {
            onLeftClick()
        } else if (e.isPopupTrigger || e.button == java.awt.event.MouseEvent.BUTTON3) {
            val icon = e.source as? java.awt.TrayIcon ?: return
            val popup = icon.popupMenu ?: return
            runCatching { popup.show(e.component ?: java.awt.Canvas(), e.x, e.y) }
        }
    }
}

/**
 * Minimal SVG absolute-command parser covering only the commands used in [MARK_PATH_DATA]:
 * M (moveto), L (lineto), C (cubic bezier), Z (closepath).
 * Compose's [PathParser] produces the same geometry; this AWT version lets us draw
 * it with Graphics2D without pulling in the Compose runtime off the main thread.
 */
private fun svgToAwtPath(svgData: String): java.awt.geom.Path2D.Float {
    val path = java.awt.geom.Path2D.Float()
    val nums = mutableListOf<Float>()
    var cmd = ' '

    fun nums(n: Int): FloatArray {
        val result = FloatArray(n) { nums.removeFirst() }
        return result
    }

    val tokenRegex = Regex("""[MmLlCcZz]|[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?""")
    tokenRegex.findAll(svgData).forEach { match ->
        val t = match.value
        if (t.length == 1 && t[0].isLetter()) {
            // Flush pending numbers with previous command first
            cmd = t[0]
        } else {
            nums.add(t.toFloat())
            // Dispatch when we have enough numbers for the command
            when (cmd.uppercaseChar()) {
                'M' -> if (nums.size >= 2) {
                    val (x, y) = nums(2)
                    path.moveTo(x, y)
                    cmd = 'L' // In SVG, coordinate pairs following M act as implicit L (lineTo)
                }
                'L' -> if (nums.size >= 2) { val (x, y) = nums(2); path.lineTo(x, y) }
                'C' -> if (nums.size >= 6) {
                    val (x1, y1, x2, y2, x, y) = nums(6)
                    path.curveTo(x1, y1, x2, y2, x, y)
                }
                'Z' -> path.closePath()
                else -> {}
            }
        }
    }
    if (cmd.uppercaseChar() == 'Z') path.closePath()
    return path
}

private operator fun FloatArray.component6() = this[5]
