package com.vinnovateit.latch.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.vinnovateit.latch.ui.theme.LatchTheme
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

/** Corner radius of the window itself -- requires [Window]'s transparent flag. */
private val WindowCornerRadius = 14.dp

/** 18:9 aspect ratio window dimensions (360dp x 720dp) sitting on taskbar. */
private const val PREFERRED_W = 360f
private const val PREFERRED_H = 720f

/** Maximum fraction of screen usable height/width. */
private const val MAX_SCREEN_FRACTION = 0.95f

/** Minimum window dimensions matching 18:9 aspect ratio. */
private const val MIN_W = 360
private const val MIN_H = 720

/**
 * The window's size -- its only one, since the window is not resizable: the
 * preferred size, shrunk to fit when the display cannot give it that much room.
 *
 * Both axes shrink by the *same* factor, so a small screen gets a smaller
 * window rather than a differently-shaped one. Clamping each axis on its own
 * (which is what this used to do) stretched the window towards whatever shape
 * the screen was: with a preferred height taller than any laptop panel, the
 * height was always decided by the screen clamp and never by the preference, so
 * the window opened at ~90% of screen height on every machine and looked
 * enormous on small ones -- while the width, whose preference *did* fit, stayed
 * put and looked wide by comparison.
 *
 * The previous implementation pinned the window to a fixed 412x900 phone surface
 * and lowered [androidx.compose.ui.platform.LocalDensity] to make that fit on a
 * 1080p panel. That density override existed only to serve the fixed phone
 * layout; now that the UI is responsive it would just make everything small, so
 * it is gone and the OS scale factor is honoured as-is.
 */
private fun preferredWindowSize(): DpSize {
    return try {
        val gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val scale = gc.defaultTransform.scaleY.toFloat().coerceAtLeast(1f)

        // Screen bounds are physical pixels; the window size is in dp, so divide
        // the usable area back out by the OS scale before comparing. This is why
        // display *scaling*, not just resolution, decides how big this feels:
        // a 1080p panel at 150% has only 693dp of usable height to give.
        val usableW = (gc.bounds.width - insets.left - insets.right) / scale
        val usableH = (gc.bounds.height - insets.top - insets.bottom) / scale

        val fit = minOf(
            1f,
            usableW * MAX_SCREEN_FRACTION / PREFERRED_W,
            usableH * MAX_SCREEN_FRACTION / PREFERRED_H,
        )

        DpSize(
            // The minimum floors can exceed the screen on a genuinely tiny
            // display; coerceAtMost keeps the window on it regardless, since an
            // undecorated window hanging off the bottom cannot be dragged back.
            width = (PREFERRED_W * fit).coerceAtLeast(MIN_W.toFloat()).coerceAtMost(usableW).dp,
            height = (PREFERRED_H * fit).coerceAtLeast(MIN_H.toFloat()).coerceAtMost(usableH).dp,
        )
    } catch (e: Throwable) {
        // Headless or an exotic display setup.
        DpSize(PREFERRED_W.dp, PREFERRED_H.dp)
    }
}

/**
 * The main window.
 *
 * Note [visible] is passed as a *parameter* rather than wrapping this call in
 * `if (visible)`. Conditionally emitting a Window destroys and recreates it,
 * which wipes the whole composition -- nav destination, scroll positions,
 * expanded cards, half-typed text. For a tray app that is shown and hidden
 * dozens of times a day that is very visible. Compose skips rendering for an
 * invisible window, so keeping it alive is cheap, and live stats collection
 * keeps running while hidden, which is what we want.
 *
 * The OS chrome (title bar) is fully replaced. [onMinimize] and [onClose] are
 * threaded into [content] so the app's own top bar hosts the window controls,
 * making the bar immersive (no separate OS strip above the content).
 */
@Composable
internal fun LatchWindow(
    visible: Boolean,
    restoreTrigger: Int = 0,
    onCloseRequest: () -> Unit,
    content: @Composable (onMinimize: () -> Unit, onClose: () -> Unit) -> Unit,
) {
    val initialSize = remember { preferredWindowSize() }
    val state = rememberWindowState(
        position = WindowPosition(androidx.compose.ui.Alignment.BottomEnd),
        size = initialSize,
    )

    LaunchedEffect(visible, restoreTrigger) {
        if (visible) {
            state.isMinimized = false
        }
    }

    Window(
        visible = visible,
        onCloseRequest = onCloseRequest,
        state = state,
        resizable = false,
        undecorated = true,
        transparent = true,
        title = "Latch",
        icon = remember { LatchIcon.brand() },
    ) {
        LaunchedEffect(visible, restoreTrigger) {
            if (visible) {
                state.isMinimized = false
                (window as? java.awt.Frame)?.state = java.awt.Frame.NORMAL
                (window as? java.awt.Frame)?.extendedState = java.awt.Frame.NORMAL
                window.toFront()
                window.requestFocus()
            }
        }

        LatchTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(WindowCornerRadius)),
                shape = RoundedCornerShape(WindowCornerRadius),
                color = MaterialTheme.colorScheme.background,
            ) {
                WindowDraggableArea(modifier = Modifier.fillMaxSize()) {
                    content(
                        { state.isMinimized = true },
                        onCloseRequest,
                    )
                }
            }
        }
    }
}
