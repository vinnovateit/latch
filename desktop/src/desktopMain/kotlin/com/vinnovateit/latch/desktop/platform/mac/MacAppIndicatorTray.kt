package com.vinnovateit.latch.desktop.platform.mac

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.vinnovateit.latch.desktop.LatchMark
import com.vinnovateit.latch.desktop.MARK_PATH_DATA
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min

private interface GtkLib : Library {
    fun gtk_init_check(argc: Pointer?, argv: Pointer?): Boolean
    fun gtk_main_iteration_do(blocking: Boolean): Boolean
    fun gtk_menu_new(): Pointer
    fun gtk_menu_item_new_with_label(label: String): Pointer
    fun gtk_menu_item_set_label(menuItem: Pointer, label: String)
    fun gtk_separator_menu_item_new(): Pointer
    fun gtk_menu_shell_append(menuShell: Pointer, child: Pointer)
    fun gtk_widget_show_all(widget: Pointer)
    fun g_signal_connect_data(
        instance: Pointer,
        detailed_signal: String,
        c_handler: Callback,
        data: Pointer?,
        destroy_data: Pointer?,
        connect_flags: Int,
    ): Long

    companion object {
        val INSTANCE: GtkLib? by lazy {
            runCatching { Native.load("gtk-3", GtkLib::class.java) }.getOrNull()
        }
    }
}

private interface AppIndicatorLib : Library {
    fun app_indicator_new(id: String, icon_name: String, category: Int): Pointer
    fun app_indicator_set_status(self: Pointer, status: Int)
    fun app_indicator_set_menu(self: Pointer, menu: Pointer)
    fun app_indicator_set_icon_full(self: Pointer, icon_name: String, icon_desc: String)

    companion object {
        val INSTANCE: AppIndicatorLib? by lazy {
            runCatching { Native.load("appindicator3", AppIndicatorLib::class.java) }
                .getOrElse { runCatching { Native.load("ayatana-appindicator3", AppIndicatorLib::class.java) }.getOrNull() }
        }
    }
}

private fun interface GCallback : Callback {
    fun callback(widget: Pointer, data: Pointer?)
}

/**
 * Native Linux AppIndicator / StatusNotifierItem system tray integration.
 * Renders native GTK menu with "Open Latch", "Connect"/"Disconnect", and "Exit Latch".
 * Works seamlessly on GNOME, KDE Plasma, XFCE, Ubuntu, Fedora, Arch, and Wayland.
 */
object LinuxAppIndicatorTray {

    private var indicator: Pointer? = null
    private var isInitialized = false
    private var itemConnectPtr: Pointer? = null
    private var openLatchCallback: GCallback? = null
    private var toggleConnectCallback: GCallback? = null
    private var exitLatchCallback: GCallback? = null

    private var currentLatched = false
    private var iconFileConnected: File? = null
    private var iconFileDisconnected: File? = null

    @Volatile private var isRunning = true

    fun stop() {
        isRunning = false
    }

    fun isSupported(): Boolean {
        return GtkLib.INSTANCE != null && AppIndicatorLib.INSTANCE != null
    }

    fun init(
        isLatched: Boolean,
        onOpenLatch: () -> Unit,
        onToggleConnect: () -> Unit,
        onExitLatch: () -> Unit,
    ) {
        val gtk = GtkLib.INSTANCE ?: return
        val appInd = AppIndicatorLib.INSTANCE ?: return
        if (isInitialized) return

        runCatching {
            gtk.gtk_init_check(null, null)

            iconFileConnected = renderIconToTempFile(latched = true)
            iconFileDisconnected = renderIconToTempFile(latched = false)

            currentLatched = isLatched
            val initialIconPath = if (isLatched) iconFileConnected!!.absolutePath else iconFileDisconnected!!.absolutePath

            // Category 0 = APP_INDICATOR_CATEGORY_APPLICATION_STATUS
            val ind = appInd.app_indicator_new("latch-desktop", initialIconPath, 0)
            appInd.app_indicator_set_status(ind, 1) // 1 = APP_INDICATOR_STATUS_ACTIVE
            indicator = ind

            val menu = gtk.gtk_menu_new()

            // 1. Open Latch
            val itemOpen = gtk.gtk_menu_item_new_with_label("Open Latch")
            openLatchCallback = GCallback { _, _ ->
                javax.swing.SwingUtilities.invokeLater { onOpenLatch() }
            }
            gtk.g_signal_connect_data(itemOpen, "activate", openLatchCallback!!, null, null, 0)
            gtk.gtk_menu_shell_append(menu, itemOpen)

            // Separator
            gtk.gtk_menu_shell_append(menu, gtk.gtk_separator_menu_item_new())

            // 2. Connect / Disconnect
            val connectLabel = if (isLatched) "Disconnect" else "Connect"
            val itemConnect = gtk.gtk_menu_item_new_with_label(connectLabel)
            itemConnectPtr = itemConnect
            toggleConnectCallback = GCallback { _, _ ->
                javax.swing.SwingUtilities.invokeLater { onToggleConnect() }
            }
            gtk.g_signal_connect_data(itemConnect, "activate", toggleConnectCallback!!, null, null, 0)
            gtk.gtk_menu_shell_append(menu, itemConnect)

            // Separator
            gtk.gtk_menu_shell_append(menu, gtk.gtk_separator_menu_item_new())

            // 3. Exit Latch
            val itemExit = gtk.gtk_menu_item_new_with_label("Exit Latch")
            exitLatchCallback = GCallback { _, _ ->
                javax.swing.SwingUtilities.invokeLater { onExitLatch() }
            }
            gtk.g_signal_connect_data(itemExit, "activate", exitLatchCallback!!, null, null, 0)
            gtk.gtk_menu_shell_append(menu, itemExit)

            gtk.gtk_widget_show_all(menu)
            appInd.app_indicator_set_menu(ind, menu)

            isInitialized = true

            // Run GTK event pump thread
            val thread = Thread({
                while (isRunning) {
                    try {
                        var processedCount = 0
                        while (processedCount < 10 && gtk.gtk_main_iteration_do(false)) {
                            processedCount++
                        }
                        Thread.sleep(if (processedCount > 0) 50 else 200)
                    } catch (_: Throwable) {
                        break
                    }
                }
            }, "Latch-Linux-Tray-Pump")
            thread.isDaemon = true
            thread.start()
        }
    }

    fun updateStatus(isLatched: Boolean) {
        val gtk = GtkLib.INSTANCE ?: return
        val appInd = AppIndicatorLib.INSTANCE ?: return
        val ind = indicator ?: return
        if (!isInitialized) return

        currentLatched = isLatched
        val iconFile = if (isLatched) iconFileConnected else iconFileDisconnected
        if (iconFile != null && iconFile.exists()) {
            appInd.app_indicator_set_icon_full(ind, iconFile.absolutePath, "Latch")
        }

        itemConnectPtr?.let { item ->
            gtk.gtk_menu_item_set_label(item, if (isLatched) "Disconnect" else "Connect")
        }
    }

    private fun renderIconToTempFile(latched: Boolean): File {
        val w = 48
        val h = 48
        val markW = 191f
        val markH = 140f
        val markLeft = 0f
        val markTop = 0f

        val factor = min(w.toFloat() / markW, h.toFloat() / markH) * 0.85f
        val dx = (w - markW * factor) / 2f
        val dy = (h - markH * factor) / 2f

        val color = if (latched) Color(255, 255, 255, 255) else Color(255, 255, 255, 140)

        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color

        val at = AffineTransform.getTranslateInstance(dx.toDouble(), dy.toDouble())
        at.scale(factor.toDouble(), factor.toDouble())

        MARK_PATH_DATA.forEach { svgData ->
            val awtPath = svgToAwtPath(svgData)
            g2.fill(at.createTransformedShape(awtPath))
        }
        g2.dispose()

        val tempFile = File.createTempFile("latch_tray_${if (latched) "on" else "off"}_", ".png")
        tempFile.deleteOnExit()
        ImageIO.write(img, "PNG", tempFile)
        return tempFile
    }

    private fun svgToAwtPath(svgData: String): java.awt.geom.Path2D.Float {
        val path = java.awt.geom.Path2D.Float()
        val nums = mutableListOf<Float>()
        var cmd = ' '

        fun nums(n: Int): FloatArray {
            return FloatArray(n) { nums.removeFirst() }
        }

        val tokenRegex = Regex("""[MmLlCcZz]|[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?""")
        tokenRegex.findAll(svgData).forEach { match ->
            val t = match.value
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
            } else {
                nums.add(t.toFloat())
                when (cmd.uppercaseChar()) {
                    'M' -> if (nums.size >= 2) {
                        val (x, y) = nums(2)
                        path.moveTo(x, y)
                        cmd = 'L'
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
}
