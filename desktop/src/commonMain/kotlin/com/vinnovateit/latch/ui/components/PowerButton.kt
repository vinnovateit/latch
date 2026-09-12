package com.vinnovateit.latch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme

// ---------------------------------------------------------------------------
// Style helper
// ---------------------------------------------------------------------------

private data class PowerButtonStyle(
    val container: Color,
    val content: Color,
    val border: Color?,
)

@Composable
private fun powerButtonStyle(isConnected: Boolean): PowerButtonStyle {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme

    val container by animateColorAsState(
        targetValue = if (isAmoled) Color.Black else if (isConnected) colorScheme.primary else colorScheme.primaryContainer,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "btnContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isAmoled) {
            if (isConnected) colorScheme.primary else colorScheme.onSurface
        } else {
            if (isConnected) colorScheme.onPrimary else colorScheme.onPrimaryContainer
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "btnContent",
    )

    return PowerButtonStyle(
        container = container,
        content = content,
        border = if (isAmoled) (if (isConnected) colorScheme.primary else colorScheme.outline) else null,
    )
}

// ---------------------------------------------------------------------------
// Circular (compact) variant
// ---------------------------------------------------------------------------

@Composable
internal fun CircularPowerButton(
    isConnected: Boolean,
    onClick: () -> Unit,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val style = powerButtonStyle(isConnected)
    val interactionSource = remember { MutableInteractionSource() }

    Surface(
        onClick = onClick,
        modifier = modifier.size(diameter),
        shape = CircleShape,
        color = style.container,
        border = style.border?.let { BorderStroke(2.dp, it) },
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LatchIcons.PowerSettingsNew,
                contentDescription = if (isConnected) "Disconnect" else "Connect",
                tint = style.content,
                modifier = Modifier.size(diameter * 0.45f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Morphing (wide) variant — corner radius springs on press
// ---------------------------------------------------------------------------

@Composable
internal fun MorphingPowerButton(
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = powerButtonStyle(isConnected)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 24.dp else 50.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "btnCorner",
    )

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(cornerRadius),
        color = style.container,
        border = style.border?.let { BorderStroke(2.dp, it) },
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LatchIcons.PowerSettingsNew,
                contentDescription = if (isConnected) "Disconnect" else "Connect",
                tint = style.content,
                modifier = Modifier.fillMaxSize(0.45f),
            )
        }
    }
}
