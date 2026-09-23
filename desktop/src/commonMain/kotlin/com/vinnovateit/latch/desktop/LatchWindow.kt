package com.vinnovateit.latch.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.shape.RoundedCornerShape as MenuCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.vinnovateit.latch.desktop.LatchMark
import com.vinnovateit.latch.ui.chrome.AppMenuActions
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.theme.LatchTheme
import com.vinnovateit.latch.ui.theme.satoshiFontFamily
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

/** Windows/GNOME close-button hover colour -- kept consistent for native feel. */
private val CloseHoverRed = Color(0xFFE81123)

/** Height of the custom title bar replacing the OS chrome. */
private val TitleBarHeight = 36.dp

/** Corner radius of the window frame. */
private val WindowCornerRadius = 12.dp

/** Default window dimensions (420dp x 700dp) for modern desktop layout. */
private const val PREFERRED_W = 420f
private const val PREFERRED_H = 700f

/** Maximum fraction of screen usable height/width. */
private const val MAX_SCREEN_FRACTION = 0.92f

/** Minimum window dimensions. See [WindowResizePolicy] for how they are applied. */
private const val MIN_W = WindowResizePolicy.MIN_W
private const val MIN_H = WindowResizePolicy.MIN_H

private fun preferredWindowSize(): DpSize {
    return try {
        val gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        val scale = gc.defaultTransform.scaleY.toFloat().coerceAtLeast(1f)

        val usableW = (gc.bounds.width - insets.left - insets.right) / scale
        val usableH = (gc.bounds.height - insets.top - insets.bottom) / scale

        val fit = minOf(
            1f,
            usableW * MAX_SCREEN_FRACTION / PREFERRED_W,
            usableH * MAX_SCREEN_FRACTION / PREFERRED_H,
        )

        val h = (PREFERRED_H * fit).coerceAtLeast(MIN_H.toFloat()).coerceAtMost(usableH)
        val w = (PREFERRED_W * fit).coerceAtLeast(MIN_W.toFloat()).coerceAtMost(usableW)

        DpSize(w.dp, h.dp)
    } catch (e: Throwable) {
        DpSize(PREFERRED_W.dp, PREFERRED_H.dp)
    }
}

/**
 * The main desktop window.
 *
 * Uses an integrated custom title bar with moving/dragging abilities strictly
 * restricted to the title bar only, and does not allow full screen or maximize.
 *
 * ## Who owns the window size
 *
 * The window manager does. Latch asks for a size when the window is created
 * ([preferredWindowSize]) and advertises a minimum it can lay out at
 * (`window.minimumSize`, which reaches the WM as a normal size hint). Everything
 * after that -- tiling, maximising, a workspace or monitor change -- is the WM's
 * decision, and Latch renders at whatever size it is handed.
 *
 * Latch sets its own size in exactly one situation: the user drags one of
 * [WindowResizeHandles], which exist because an undecorated window has no OS
 * resize border. Those go through [WindowResizePolicy].
 *
 * This used to be different, and the difference was a bug. A `componentResized`
 * listener enforced a maximum 4:3 aspect ratio plus the minimums by calling
 * `setSize` in response to *every* resize, including the ones the compositor had
 * just assigned. Under a tiling window manager that is a loop: Hyprland assigns
 * the tile, Latch answers with a narrower size, Hyprland reassigns the tile, and
 * the two sides trade sizes indefinitely -- measured at roughly 20 resize events
 * per second and 16% CPU, with the window visibly flickering between the two
 * geometries and Compose laying out at the narrower one, leaving the rest of the
 * window transparent.
 *
 * The 4:3 cap also contradicted the app it was capping: the desktop UI has had a
 * 900dp wide layout with a navigation rail since `4c35ac0`, five weeks before the
 * cap arrived in `c8ddbc7`, and `width <= height * 4/3` puts that layout out of
 * reach for any window shorter than 675px.
 */
@Composable
internal fun LatchWindow(
    visible: Boolean,
    restoreTrigger: Int = 0,
    onCloseRequest: () -> Unit,
    appMenu: AppMenuHost,
    content: @Composable () -> Unit,
) {
    val initialSize = remember { preferredWindowSize() }
    val state = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = initialSize,
    )

    LaunchedEffect(visible, restoreTrigger) {
        if (visible) {
            state.isMinimized = false
        }
    }

    // Strict invariant: Never allow full screen or maximize
    LaunchedEffect(state.placement) {
        if (state.placement != WindowPlacement.Floating) {
            state.placement = WindowPlacement.Floating
        }
    }

    Window(
        visible = visible,
        onCloseRequest = onCloseRequest,
        state = state,
        resizable = true,
        undecorated = true,
        transparent = true,
        title = "Latch",
        icon = remember { LatchIcon.brand() },
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(MIN_W, MIN_H)

            // Intercept OS maximize shortcuts (e.g. Super+Up, F11) and restore to normal.
            // This is a discrete state, not a geometry negotiation: it fires once per
            // maximise and settles. Tiling is not reported through it -- under Hyprland
            // a tiled Latch stays at extendedState 0 -- so it does not fight a tiled
            // window.
            window.addWindowStateListener { e ->
                if ((e.newState and Frame.MAXIMIZED_BOTH) != 0) {
                    (window as? Frame)?.extendedState = Frame.NORMAL
                }
            }

            // Deliberately no componentResized listener. Answering a resize with a
            // resize is what fought the compositor; see the note on this function.
        }

        LaunchedEffect(visible, restoreTrigger) {
            if (visible) {
                state.isMinimized = false
                (window as? Frame)?.state = Frame.NORMAL
                (window as? Frame)?.extendedState = Frame.NORMAL
                window.toFront()
                window.requestFocus()
            }
        }

        LatchTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(WindowCornerRadius),
                    ),
                shape = RoundedCornerShape(WindowCornerRadius),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Title bar: THE ONLY DRAGGABLE AREA IN THE ENTIRE WINDOW
                        WindowDraggableArea(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(TitleBarHeight),
                        ) {
                            LatchTitleBar(
                                onMinimize = { state.isMinimized = true },
                                onClose = onCloseRequest,
                                menuActions = appMenu.actions,
                            )
                        }

                        // App content area: completely non-draggable.
                        // Mouse clicks, text selections, and scrolling work normally without dragging.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            content()
                        }
                    }

                    // The window is undecorated, so these stand in for the OS resize
                    // border. They fire only from a pointer drag, and they are the only
                    // place Latch sets its own size.
                    val currentBounds = {
                        WindowBounds(window.x, window.y, window.width, window.height)
                    }
                    val apply = { b: WindowBounds ->
                        window.setBounds(b.x, b.y, b.width, b.height)
                    }
                    WindowResizeHandles(
                        onResizeRight = { dx ->
                            apply(WindowResizePolicy.resizeRight(currentBounds(), dx))
                        },
                        onResizeBottom = { dy ->
                            apply(WindowResizePolicy.resizeBottom(currentBounds(), dy))
                        },
                        onResizeBottomRight = { dx, dy ->
                            apply(WindowResizePolicy.resizeBottomRight(currentBounds(), dx, dy))
                        },
                        onResizeLeft = { dx ->
                            apply(WindowResizePolicy.resizeLeft(currentBounds(), dx))
                        },
                        onResizeBottomLeft = { dx, dy ->
                            apply(WindowResizePolicy.resizeBottomLeft(currentBounds(), dx, dy))
                        },
                    )
                }
            }
        }
    }
}

/**
 * Custom native-styled title bar:
 * - Brand icon and title on the left (draggable via WindowDraggableArea)
 * - Application menu, then Minimize and Close on the right with native hover states
 * - Maximize button is omitted intentionally because fullscreen/maximize is not allowed.
 *
 * The buttons are interactive children of the draggable area, exactly as Minimize
 * and Close already were, so the title bar stays the only draggable region.
 */
@Composable
private fun LatchTitleBar(
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    menuActions: AppMenuActions?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LatchMark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp).size(16.dp),
        )
        Text(
            text = "Latch",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        if (menuActions != null) {
            TitleBarMenuButton(menuActions)
        }
        TitleBarButton(
            icon = LatchIcons.Minimize,
            contentDescription = "Minimize",
            onClick = onMinimize,
            hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TitleBarButton(
            icon = LatchIcons.Close,
            contentDescription = "Close",
            onClick = onClose,
            hoverColor = CloseHoverRed,
            iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
            hoverIconTint = Color.White,
        )
    }
}

/**
 * Hamburger sharing the control row's geometry and hover treatment, so it reads as
 * part of the chrome rather than a stray app control.
 *
 * The dropdown is a Popup, so it is not clipped by the 36dp title bar.
 *
 * Toggling it is not as simple as flipping [expanded] on click. The popup is
 * non-focusable by design (see the note on its properties below) and is dispatched
 * above the title bar, so pressing the hamburger while the menu is open produces:
 *
 * ```text
 * onDismissRequest -> anchor press -> anchor release -> onClick
 * ```
 *
 * The dismissal lands first and closes the menu, then the click reopens it in the
 * same gesture. That is the "closes and immediately reopens" bug, and `!expanded`
 * alone does not fix it because the state is already false by the time the click
 * runs. The anchor can only be hovered when the press was aimed at it, so that one
 * dismissal is left to the click path and every other dismissal still closes.
 */
@Composable
internal fun TitleBarMenuButton(actions: AppMenuActions) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(
                if (hovered || expanded) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { expanded = !expanded },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LatchIcons.Menu,
            contentDescription = "Menu",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (!hovered) expanded = false },
            shape = MenuCornerShape(12.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.width(200.dp),
            // Load-bearing on Linux: see 0f5a7ff, "fix Linux home top bar menu
            // freeze on first setup after login" (#87). A focusable popup here
            // deadlocks the first post-login menu open. Moved verbatim with the
            // menu; do not drop it without evidence on Linux.
            properties = PopupProperties(focusable = false),
        ) {
            if (actions.showSettings) {
                DropdownMenuItem(
                    text = { Text("Settings", fontSize = 15.sp, fontFamily = satoshiFontFamily()) },
                    leadingIcon = { Icon(LatchIcons.SettingsOutlined, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onOpenSettings()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("How it works", fontSize = 15.sp, fontFamily = satoshiFontFamily()) },
                leadingIcon = { Icon(LatchIcons.Help, contentDescription = null) },
                onClick = {
                    expanded = false
                    actions.onHowItWorks()
                },
            )
            DropdownMenuItem(
                text = { Text("About", fontSize = 15.sp, fontFamily = satoshiFontFamily()) },
                leadingIcon = { Icon(LatchIcons.Info, contentDescription = null) },
                onClick = {
                    expanded = false
                    actions.onOpenAbout()
                },
            )
        }
    }
}

@Composable
private fun TitleBarButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    hoverColor: Color,
    iconTint: Color,
    hoverIconTint: Color = iconTint,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(if (hovered) hoverColor else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (hovered) hoverIconTint else iconTint,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun BoxScope.WindowResizeHandles(
    onResizeRight: (Int) -> Unit,
    onResizeBottom: (Int) -> Unit,
    onResizeBottomRight: (Int, Int) -> Unit,
    onResizeLeft: (Int) -> Unit,
    onResizeBottomLeft: (Int, Int) -> Unit,
) {
    val handleThickness = 6.dp

    // Right border handle
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(handleThickness)
            .align(Alignment.CenterEnd)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResizeRight(dragAmount.x.toInt())
                }
            },
    )

    // Bottom border handle
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(handleThickness)
            .align(Alignment.BottomCenter)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResizeBottom(dragAmount.y.toInt())
                }
            },
    )

    // Bottom-right corner handle
    Box(
        modifier = Modifier
            .size(14.dp)
            .align(Alignment.BottomEnd)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResizeBottomRight(dragAmount.x.toInt(), dragAmount.y.toInt())
                }
            },
    )

    // Left border handle
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(handleThickness)
            .align(Alignment.CenterStart)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResizeLeft(dragAmount.x.toInt())
                }
            },
    )

    // Bottom-left corner handle
    Box(
        modifier = Modifier
            .size(14.dp)
            .align(Alignment.BottomStart)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResizeBottomLeft(dragAmount.x.toInt(), dragAmount.y.toInt())
                }
            },
    )
}

