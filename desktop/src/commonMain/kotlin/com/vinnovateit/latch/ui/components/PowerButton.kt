package com.vinnovateit.latch.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
    val border: Color?,
    val iconScale: Float,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun powerButtonStyle(isConnected: Boolean): PowerButtonStyle {
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val isAmoled = usePureBlack && LocalIsDarkTheme.current
    val colorScheme = MaterialTheme.colorScheme

    val container by animateColorAsState(
        targetValue = if (isAmoled) Color.Black else if (isConnected) colorScheme.primary else colorScheme.primaryContainer,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "btnContainer",
    )
    val content by animateColorAsState(
        targetValue = if (isAmoled) {
            if (isConnected) colorScheme.primary else colorScheme.onSurface
        } else {
            if (isConnected) colorScheme.onPrimary else colorScheme.onPrimaryContainer
        },
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
        border = if (isAmoled) (if (isConnected) colorScheme.primary else colorScheme.outline) else null,
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
        border = style.border?.let { BorderStroke(2.dp, it) },
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

/**
 * Corner radii as a percentage of the button's own side, not absolute dp.
 *
 * 50% is a circle, so the button rests as one and matches [CircularPowerButton]
 * in the compact layout. Pressing squares it off.
 *
 * Android expresses the same morph as 50.dp -> 24.dp, which only reads as a
 * circle because its button lands near 100dp; the percentages below are those
 * numbers at that size. Desktop caps the button at 340dp, where a fixed 50.dp
 * radius is a rounded square instead, so the ratio has to be the thing that is
 * fixed rather than the radius.
 */
private const val RESTING_CORNER_PERCENT = 50
private const val PRESSED_CORNER_PERCENT = 24

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

    val cornerPercent by animateIntAsState(
        targetValue = if (isPressed) PRESSED_CORNER_PERCENT else RESTING_CORNER_PERCENT,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "btnCorner",
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "btnScale",
    )

    // A percentage radius only draws a circle on a square, and the slot this sits
    // in is not square in a short window, so take the smaller side and centre in
    // it rather than stretching into a pill.
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)

        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(side)
                .graphicsLayer {
                    scaleX = buttonScale
                    scaleY = buttonScale
                },
            shape = RoundedCornerShape(percent = cornerPercent),
            color = style.container,
            border = style.border?.let { BorderStroke(2.dp, it) },
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
}
