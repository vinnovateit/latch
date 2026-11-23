package com.vinnovateit.latch.desktop.platform

/**
 * Windows SDL tray clicks are delivered through the tray menu ("Open Latch"),
 * so there is no D-Bus style activation bridge to watch here. The type stays
 * in the shared Main.kt wiring as a no-op.
 */
internal class TrayIconActivation(private val onActivate: () -> Unit) {
    fun start() {}
    fun stop() {}
}
