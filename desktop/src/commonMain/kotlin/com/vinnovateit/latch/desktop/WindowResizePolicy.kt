package com.vinnovateit.latch.desktop

/**
 * Window geometry, in the same units AWT reports: device pixels, origin at the
 * top-left of the screen.
 */
internal data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * The sizing rules Latch applies to *its own* resize gestures.
 *
 * Latch has a custom title bar, so the window is undecorated and the OS draws no
 * resize border. [WindowResizeHandles] supplies that border, and this is the
 * policy behind it.
 *
 * The scope is deliberately narrow. These functions describe what should happen
 * when the user drags one of Latch's own handles, and nothing else. Geometry that
 * arrives from outside -- a tiling window manager, a maximise, a workspace change,
 * a monitor hotplug -- is the compositor's to decide, and Latch applies it as
 * given. An earlier version enforced these bounds from `componentResized` as well,
 * which meant every externally assigned size was answered with a `setSize` the
 * compositor then overrode, and the two sides oscillated for as long as the window
 * stayed tiled. See the note on [LatchWindow].
 *
 * The minimums survive as a *hint* (`window.minimumSize`, which reaches the WM
 * through the normal size hints) and as the clamp below. A window manager is free
 * to ignore the hint, and if it does, Latch renders at whatever size it was given.
 */
internal object WindowResizePolicy {

    /**
     * Smallest size Latch lays out correctly at. Advertised to the window manager
     * and enforced on Latch's own handles; never forced back onto the compositor.
     */
    const val MIN_W = 360
    const val MIN_H = 600

    /** Drag on the right edge. The left edge stays where it is. */
    fun resizeRight(bounds: WindowBounds, dx: Int): WindowBounds =
        bounds.copy(width = (bounds.width + dx).coerceAtLeast(MIN_W))

    /** Drag on the bottom edge. The top edge stays where it is. */
    fun resizeBottom(bounds: WindowBounds, dy: Int): WindowBounds =
        bounds.copy(height = (bounds.height + dy).coerceAtLeast(MIN_H))

    /** Drag on the bottom-right corner. The top-left corner stays where it is. */
    fun resizeBottomRight(bounds: WindowBounds, dx: Int, dy: Int): WindowBounds =
        bounds.copy(
            width = (bounds.width + dx).coerceAtLeast(MIN_W),
            height = (bounds.height + dy).coerceAtLeast(MIN_H),
        )

    /**
     * Drag on the left edge. The window origin moves, so the *right* edge has to
     * be pinned explicitly: the new x is chosen so that `x + width` is unchanged,
     * including when the drag is clamped at [MIN_W].
     */
    fun resizeLeft(bounds: WindowBounds, dx: Int): WindowBounds {
        val width = (bounds.width - dx).coerceAtLeast(MIN_W)
        return bounds.copy(x = bounds.x + (bounds.width - width), width = width)
    }

    /** Drag on the bottom-left corner: [resizeLeft] plus the bottom edge. */
    fun resizeBottomLeft(bounds: WindowBounds, dx: Int, dy: Int): WindowBounds =
        resizeLeft(bounds, dx).copy(height = (bounds.height + dy).coerceAtLeast(MIN_H))
}
