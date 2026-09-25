package com.vinnovateit.latch.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.desktop.VinnovateItLogo
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.theme.satoshiFontFamily
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HandLeftVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "HandLeft",
        defaultWidth = 50.dp,
        defaultHeight = 36.dp,
        viewportWidth = 50f,
        viewportHeight = 36f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M17.96,23.886L22.941,28.654L17.839,33.361C16.024,34.959 13.707,34.99 11.64,34.339C10.925,34.113 10.3,33.674 9.758,33.155C6.525,30.057 5.381,28.706 2.368,25.228C2.154,24.981 1.956,24.719 1.788,24.439C-0.224,21.083 -0.416,18.358 0.605,13.134C0.711,12.591 0.889,12.061 1.156,11.575C2.619,8.908 4.817,6.413 9.354,1.823C9.783,1.39 10.269,1.007 10.819,0.744C13.281,-0.431 14.9,-0.158 17.596,1.169L28.95,12.113C29.214,12.366 29.43,12.668 29.573,13.005C31.041,16.474 30.533,18.28 29.349,21.152C25.189,17.183 18.659,11.282 18.659,11.282C17.979,10.873 14.194,9.674 12.19,11.859C10.186,14.045 8.413,19.978 12.706,23.369C14.34,24.65 15.522,24.68 17.96,23.886Z").toNodes(),
            fill = SolidColor(Color(0xFFC01221)),
        )
        addPath(
            pathData = PathParser().parsePathString("M15.033,27.516C17.671,29.149 19.348,29.308 22.352,28.062L20.196,25.982C18.953,24.784 17.966,23.839 17.945,23.891C16.24,24.465 15.451,24.466 14.061,24.053C12.964,23.61 12.454,23.215 11.692,22.292L10.842,21.199L11.389,22.383C12.302,24.577 13.058,25.713 15.033,27.516Z").toNodes(),
            fill = SolidColor(Color(0xFF670002)),
        )
        addPath(
            pathData = PathParser().parsePathString("M15.519,10.63C13.119,10.925 12.128,11.445 10.934,13.85C11.427,12.316 11.847,11.464 12.908,9.962C14.912,7.205 16.061,6.311 18.162,6.166C20.883,5.84 22.377,6.348 24.995,8.261L29.247,12.391C30.828,15.317 30.982,17.509 29.346,21.164L20.774,13.211C18.782,11.296 17.615,10.669 15.519,10.63Z").toNodes(),
            fill = SolidColor(Color(0xFF670002)),
        )
    }.build()
}

private val HandRightVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "HandRight",
        defaultWidth = 50.dp,
        defaultHeight = 36.dp,
        viewportWidth = 50f,
        viewportHeight = 36f,
    ).apply {
        addPath(
            pathData = PathParser().parsePathString("M31.368,12.114L26.387,7.346L31.489,2.639C33.623,0.761 36.448,1.048 38.748,2.062C42.466,5.583 43.608,6.901 46.555,10.304L46.671,10.438C47.077,10.907 47.457,11.402 47.762,11.942C49.473,14.966 49.686,17.564 48.864,22.117C48.676,23.161 48.338,24.178 47.792,25.087C46.397,27.405 44.341,29.736 40.555,33.588C39.743,34.414 38.833,35.172 37.75,35.582C36.243,36.152 35.01,36.121 33.514,35.599C32.299,35.175 31.261,34.377 30.335,33.485L20.378,23.888C20.114,23.633 19.898,23.332 19.755,22.995C18.288,19.526 18.795,17.72 19.979,14.848C24.139,18.817 30.669,24.718 30.669,24.718C31.349,25.128 35.134,26.326 37.138,24.141C39.143,21.955 40.915,16.022 36.622,12.631C34.988,11.35 33.806,11.32 31.368,12.114Z").toNodes(),
            fill = SolidColor(Color(0xFFC01221)),
        )
        addPath(
            pathData = PathParser().parsePathString("M26.994,7.938L31.355,12.136C33.06,11.563 33.925,11.571 35.315,11.984C36.413,12.428 36.874,12.786 37.636,13.708L38.486,14.802L37.94,13.617C37.026,11.424 36.27,10.287 34.295,8.485C31.657,6.852 29.998,6.692 26.994,7.938Z").toNodes(),
            fill = SolidColor(Color(0xFF670002)),
        )
        addPath(
            pathData = PathParser().parsePathString("M33.803,25.408C36.203,25.112 37.195,24.532 38.389,22.127C37.895,23.66 37.475,24.512 36.415,26.014C34.41,28.771 33.261,29.665 31.161,29.811C28.439,30.137 26.945,29.628 24.327,27.715L20.075,23.585C18.273,20.781 18.436,18.436 19.924,14.808L28.582,22.8C30.573,24.715 31.707,25.369 33.803,25.408Z").toNodes(),
            fill = SolidColor(Color(0xFF670002)),
        )
    }.build()
}

@Composable
fun HandsConnectAnimation(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 140.dp,
    durationMs: Int = 600,
) {
    val leftOffsetX = remember { Animatable(-180f) }
    val leftOffsetY = remember { Animatable(-180f) }
    val rightOffsetX = remember { Animatable(180f) }
    val rightOffsetY = remember { Animatable(180f) }

    LaunchedEffect(Unit) {
        val spec = tween<Float>(durationMillis = durationMs, easing = FastOutSlowInEasing)
        launch { leftOffsetX.animateTo(0f, spec) }
        launch { leftOffsetY.animateTo(0f, spec) }
        launch { rightOffsetX.animateTo(0f, spec) }
        launch { rightOffsetY.animateTo(0f, spec) }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = HandRightVector,
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .offset {
                    IntOffset(
                        rightOffsetX.value.roundToInt(),
                        rightOffsetY.value.roundToInt(),
                    )
                },
            contentScale = ContentScale.Fit,
        )

        Image(
            imageVector = HandLeftVector,
            contentDescription = null,
            modifier = Modifier
                .size(sizeDp)
                .offset {
                    IntOffset(
                        leftOffsetX.value.roundToInt(),
                        leftOffsetY.value.roundToInt(),
                    )
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 1:1 match of Android LatchSetupBottomBar: morphing shape FAB, rotation animation,
 * VinnovateIT branding logo, and page indicator dots.
 */
@Composable
fun DesktopOnboardingBottomBar(
    pagerState: PagerState,
    isFinishButtonEnabled: Boolean,
    onNextClicked: () -> Unit,
    onFinishClicked: () -> Unit,
    onBackClicked: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val morphAnimationSpec = tween<Float>(durationMillis = 600, easing = FastOutSlowInEasing)
    val rotationAnimationSpec = tween<Float>(durationMillis = 900, easing = FastOutSlowInEasing)

    val targetShapeValues = when (pagerState.currentPage % 3) {
        0 -> listOf(28f, 28f, 28f, 28f) // Circle
        1 -> listOf(14f, 14f, 14f, 14f) // Rounded square
        else -> listOf(8f, 28f, 8f, 28f) // Leaf shape
    }

    val animatedTopStart by animateFloatAsState(targetShapeValues[0], morphAnimationSpec, label = "TopStart")
    val animatedTopEnd by animateFloatAsState(targetShapeValues[1], morphAnimationSpec, label = "TopEnd")
    val animatedBottomStart by animateFloatAsState(targetShapeValues[2], morphAnimationSpec, label = "BottomStart")
    val animatedBottomEnd by animateFloatAsState(targetShapeValues[3], morphAnimationSpec, label = "BottomEnd")

    val animatedRotation by animateFloatAsState(
        targetValue = pagerState.currentPage * 360f,
        animationSpec = rotationAnimationSpec,
        label = "Rotation",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (pagerState.currentPage == 0) {
                        Icon(
                            imageVector = VinnovateItLogo,
                            contentDescription = "VinnovateIT",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(width = 110.dp, height = 36.dp),
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onBackClicked != null) {
                                IconButton(
                                    onClick = onBackClicked,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = LatchIcons.ArrowBack,
                                        contentDescription = "Back",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = "Step ${pagerState.currentPage} of ${pagerState.pageCount - 1}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = satoshiFontFamily(),
                            )
                        }
                    }
                }

                val isLastPage = pagerState.currentPage == pagerState.pageCount - 1
                val isAccountPage = pagerState.currentPage == 3
                val isEnabled = if (isLastPage) {
                    isFinishButtonEnabled
                } else if (isAccountPage) {
                    isFinishButtonEnabled
                } else {
                    true
                }

                val fabShape = RoundedCornerShape(
                    topStart = animatedTopStart.dp,
                    topEnd = animatedTopEnd.dp,
                    bottomEnd = animatedBottomEnd.dp,
                    bottomStart = animatedBottomStart.dp,
                )

                FloatingActionButton(
                    onClick = {
                        if (isLastPage) {
                            if (isEnabled) onFinishClicked()
                        } else {
                            onNextClicked()
                        }
                    },
                    shape = fabShape,
                    containerColor = if (!isEnabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    } else if (isLastPage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (!isEnabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else if (isLastPage) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = if (isEnabled) 2.dp else 0.dp),
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.rotate(animatedRotation),
                    ) {
                        AnimatedContent(
                            targetState = isLastPage,
                            transitionSpec = {
                                (slideInVertically { it } + fadeIn()).togetherWith(
                                    slideOutVertically { -it } + fadeOut(),
                                )
                            },
                            label = "FabIcon",
                        ) { last ->
                            if (last) {
                                Icon(
                                    imageVector = LatchIcons.Check,
                                    contentDescription = "Finish",
                                    modifier = Modifier.size(24.dp),
                                )
                            } else {
                                Icon(
                                    imageVector = LatchIcons.ArrowForwardIos,
                                    contentDescription = "Next",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Page Indicator Dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pagerState.pageCount) { page ->
                    val isCurrent = page == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isCurrent) 10.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
    }
}
