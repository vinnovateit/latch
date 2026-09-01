package com.vinnovateit.latch.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Scope provided to LatchBottomSheet content, enabling smooth animated dismissals. */
internal interface LatchBottomSheetScope {
    fun dismiss()
}

/**
 * Reusable Bottom Sheet overlay component for Desktop, providing a consistent
 * non-overshooting slide-up and slide-down bottom sheet UI matching the Android app design.
 */
@Composable
internal fun LatchBottomSheet(
    visible: Boolean = true,
    onDismissRequest: () -> Unit,
    content: @Composable LatchBottomSheetScope.() -> Unit,
) {
    val anim = remember { Animatable(1f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        anim.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    val dismissWithAnimation: () -> Unit = {
        coroutineScope.launch {
            anim.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
            onDismissRequest()
        }
    }

    val scope = remember(dismissWithAnimation) {
        object : LatchBottomSheetScope {
            override fun dismiss() = dismissWithAnimation()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Dark Scrim Overlay Backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = ((1f - anim.value.coerceIn(0f, 1f)) * 0.5f)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = dismissWithAnimation,
                ),
        )

        // Bottom Sheet Content Panel (Strictly clamped to prevent bottom edge lifting)
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = anim.value.coerceAtLeast(0f) * (size.height.takeIf { it > 0 } ?: 1000f)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Drag Handle Pill
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp),
                ) {}
                Spacer(Modifier.height(16.dp))

                scope.content()
            }
        }
    }
}
