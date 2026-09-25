package com.vinnovateit.latch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    val border: BorderStroke?,
    val iconScale: Float,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun powerButtonStyle(isConnected: Boolean): PowerButtonStyle {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer

    val targetContainer = when {
        isAmoled -> Color.Black
        isConnected -> primary
        else -> primaryContainer
    }
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "btnContainer",
    )

    val targetContent = when {
        isAmoled -> if (isConnected) primary else primary.copy(alpha = 0.4f)
        isConnected -> MaterialTheme.colorScheme.onPrimary
        else -> primary.copy(alpha = 0.5f)
    }
    val content by animateColorAsState(
        targetValue = targetContent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "btnContent",
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isConnected) 1.08f else 0.92f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "btnIconScale",
    )

    return PowerButtonStyle(
        container = container,
        content = content,
        border = if (isAmoled) {
            BorderStroke(2.dp, if (isConnected) primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        } else null,
        iconScale = iconScale,
    )
}

// ---------------------------------------------------------------------------
// Circular (compact) variant
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CircularPowerButton(
    isConnected: Boolean,
    onClick: () -> Unit,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val style = powerButtonStyle(isConnected)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "btnScale",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
            },
        shape = CircleShape,
        color = style.container,
        border = style.border,
        shadowElevation = if (isConnected && style.border == null) 6.dp else 0.dp,
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LatchIcons.PowerSettingsNew,
                contentDescription = if (isConnected) "Disconnect" else "Connect",
                tint = style.content,
                modifier = Modifier
                    .size(diameter * 0.45f)
                    .graphicsLayer {
                        scaleX = style.iconScale
                        scaleY = style.iconScale
                    },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Morphing (wide) variant — corner radius springs on press
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MorphingPowerButton(
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = powerButtonStyle(isConnected)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "btnScale",
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed) 24.dp else 50.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "btnCorner",
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
            },
        shape = RoundedCornerShape(cornerRadius),
        color = style.container,
        border = style.border,
        shadowElevation = if (isConnected && style.border == null) 4.dp else 0.dp,
        interactionSource = interactionSource,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LatchIcons.PowerSettingsNew,
                contentDescription = if (isConnected) "Disconnect" else "Connect",
                tint = style.content,
                modifier = Modifier
                    .fillMaxSize(0.45f)
                    .graphicsLayer {
                        scaleX = style.iconScale
                        scaleY = style.iconScale
                    },
            )
        }
    }
}
