package com.vinnovateit.latch.ui

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Hover tracking built on the port's [hoverable] modifier; the native Compose
 * build does not expose the desktop-only `onPointerEvent` API.
 */
@Composable
fun Modifier.onHoverCompat(onEnter: () -> Unit, onExit: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    androidx.compose.runtime.LaunchedEffect(hovered) {
        if (hovered) onEnter() else onExit()
    }
    return hoverable(source)
}
