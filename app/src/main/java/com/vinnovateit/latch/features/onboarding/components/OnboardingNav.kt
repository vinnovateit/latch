package com.vinnovateit.latch.features.onboarding.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.common.ui.VinnovateITLogo
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun LatchSetupBottomBar(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    onNextClicked: () -> Unit,
    onFinishClicked: () -> Unit,
    isFinishButtonEnabled: Boolean
) {
    val morphAnimationSpec = tween<Float>(durationMillis = 600, easing = FastOutSlowInEasing)
    val rotationAnimationSpec = tween<Float>(durationMillis = 900, easing = FastOutSlowInEasing)

    val targetShapeValues = when (pagerState.currentPage % 3) {
        0 -> listOf(50f, 50f, 50f, 50f) // Circle
        1 -> listOf(26f, 26f, 26f, 26f) // Rounded square
        else -> listOf(18f, 50f, 18f, 50f) // Leaf shape
    }

    val animatedTopStart by animateFloatAsState(targetShapeValues[0], morphAnimationSpec, label = "TopStart")
    val animatedTopEnd by animateFloatAsState(targetShapeValues[1], morphAnimationSpec, label = "TopEnd")
    val animatedBottomStart by animateFloatAsState(targetShapeValues[2], morphAnimationSpec, label = "BottomStart")
    val animatedBottomEnd by animateFloatAsState(targetShapeValues[3], morphAnimationSpec, label = "BottomEnd")

    val animatedRotation by animateFloatAsState(
        targetValue = pagerState.currentPage * 360f,
        animationSpec = rotationAnimationSpec,
        label = "Rotation"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    AnimatedContent(
                        targetState = pagerState.currentPage,
                        transitionSpec = {
                            if (initialState == 0 || targetState == 0) {
                                (fadeIn(animationSpec = tween(220, delayMillis = 90)) togetherWith
                                        fadeOut(animationSpec = tween(90)))
                                    .using(SizeTransform(clip = false))
                            } else {
                                (slideInVertically { height -> height } + fadeIn())
                                    .togetherWith(slideOutVertically { height -> -height } + fadeOut())
                                    .using(SizeTransform(clip = false))
                            }
                        },
                        label = "LogoVsStepText"
                    ) { currentPage ->
                        if (currentPage == 0) {
                            VinnovateITLogo(modifier = Modifier.padding(start = 10.dp))
                        } else {
                            Text(
                                text = "Step $currentPage of ${pagerState.pageCount - 1}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = SatoshiFontFamily,
                                modifier = Modifier.padding(start = 16.dp)
                            )
                        }
                    }
                }

                val isLastPage = pagerState.currentPage == pagerState.pageCount - 1
                val containerColor = if (!isFinishButtonEnabled) { MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f) } else { MaterialTheme.colorScheme.primaryContainer }
                val contentColor = if (!isFinishButtonEnabled) { MaterialTheme.colorScheme.onSurface.copy() } else { MaterialTheme.colorScheme.onPrimaryContainer }

                MediumFloatingActionButton(
                    onClick = {
                        if (!isFinishButtonEnabled) return@MediumFloatingActionButton
                        if (isLastPage) { onFinishClicked() } else { onNextClicked() }
                    },
                    shape = RoundedCornerShape(
                        topStart = animatedTopStart.toInt().dp,
                        topEnd = animatedTopEnd.toInt().dp,
                        bottomStart = animatedBottomStart.toInt().dp,
                        bottomEnd = animatedBottomEnd.toInt().dp
                    ),
                    containerColor = containerColor,
                    contentColor = contentColor,
                    modifier = Modifier
                        .rotate(animatedRotation)
                ) {
                    AnimatedContent(
                        modifier = Modifier.rotate(-animatedRotation),
                        targetState = pagerState.currentPage < pagerState.pageCount - 1,
                        transitionSpec = {
                            ContentTransform(
                                targetContentEnter = fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                                        scaleIn(initialScale = 0.9f, animationSpec = tween(220, delayMillis = 90)),
                                initialContentExit = fadeOut(animationSpec = tween(90)) +
                                        scaleOut(targetScale = 0.9f, animationSpec = tween(90))
                            ).using(SizeTransform(clip = false))
                        },
                        label = "AnimatedFabIcon"
                    ) { isNextPage ->
                        if (isNextPage) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, contentDescription = "Next")
                        } else {
                            if (isFinishButtonEnabled) { Icon(Icons.Rounded.Check, contentDescription = "Finish") } else { Icon(Icons.Rounded.Close, contentDescription = "Finish") }
                        }
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LandscapeFloatingNavControls(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    onNextClicked: () -> Unit,
    onFinishClicked: () -> Unit,
    isFinishButtonEnabled: Boolean
) {
    val morphAnimationSpec = tween<Float>(durationMillis = 600, easing = FastOutSlowInEasing)
    val rotationAnimationSpec = tween<Float>(durationMillis = 900, easing = FastOutSlowInEasing)

    val targetShapeValues = when (pagerState.currentPage % 3) {
        0 -> listOf(50f, 50f, 50f, 50f) // Circle
        1 -> listOf(26f, 26f, 26f, 26f) // Rounded square
        else -> listOf(18f, 50f, 18f, 50f) // Leaf shape
    }

    val animatedTopStart by animateFloatAsState(targetShapeValues[0], morphAnimationSpec, label = "TopStart")
    val animatedTopEnd by animateFloatAsState(targetShapeValues[1], morphAnimationSpec, label = "TopEnd")
    val animatedBottomStart by animateFloatAsState(targetShapeValues[2], morphAnimationSpec, label = "BottomStart")
    val animatedBottomEnd by animateFloatAsState(targetShapeValues[3], morphAnimationSpec, label = "BottomEnd")

    val animatedRotation by animateFloatAsState(
        targetValue = pagerState.currentPage * 360f,
        animationSpec = rotationAnimationSpec,
        label = "Rotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart
        ) {
            AnimatedContent(
                targetState = pagerState.currentPage,
                transitionSpec = {
                    if (initialState == 0 || targetState == 0) {
                        (fadeIn(animationSpec = tween(220, delayMillis = 90)) togetherWith
                                fadeOut(animationSpec = tween(90)))
                            .using(SizeTransform(clip = false))
                    } else {
                        (slideInVertically { height -> height } + fadeIn())
                            .togetherWith(slideOutVertically { height -> -height } + fadeOut())
                            .using(SizeTransform(clip = false))
                    }
                },
                label = "LogoVsStepText"
            ) { currentPage ->
                if (currentPage == 0) {
                    VinnovateITLogo(modifier = Modifier.padding(start = 10.dp))
                } else {
                    Text(
                        text = "Step $currentPage of ${pagerState.pageCount - 1}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = SatoshiFontFamily
                    )
                }
            }
        }

        val isLastPage = pagerState.currentPage == pagerState.pageCount - 1
        val containerColor = if (!isFinishButtonEnabled) { MaterialTheme.colorScheme.onSurface } else { MaterialTheme.colorScheme.primaryContainer }
        val contentColor = if (!isFinishButtonEnabled) { MaterialTheme.colorScheme.onSurface.copy() } else { MaterialTheme.colorScheme.onPrimaryContainer }

        // --- Button updated to MediumFloatingActionButton with animation modifiers ---
        MediumFloatingActionButton(
            onClick = {
                if (!isFinishButtonEnabled) return@MediumFloatingActionButton
                if (isLastPage) { onFinishClicked() } else { onNextClicked() }
            },
            shape = RoundedCornerShape(
                topStart = animatedTopStart.toInt().dp,
                topEnd = animatedTopEnd.toInt().dp,
                bottomStart = animatedBottomStart.toInt().dp,
                bottomEnd = animatedBottomEnd.toInt().dp
            ),
            containerColor = containerColor,
            contentColor = contentColor,
            modifier = Modifier
                .rotate(animatedRotation)
        ) {
            AnimatedContent(
                modifier = Modifier.rotate(-animatedRotation), // Counter-rotates the icon
                targetState = pagerState.currentPage < pagerState.pageCount - 1,
                transitionSpec = {
                    ContentTransform(
                        targetContentEnter = fadeIn(animationSpec = tween(220, delayMillis = 90)) +
                                scaleIn(initialScale = 0.9f, animationSpec = tween(220, delayMillis = 90)),
                        initialContentExit = fadeOut(animationSpec = tween(90)) +
                                scaleOut(targetScale = 0.9f, animationSpec = tween(90))
                    ).using(SizeTransform(clip = false))
                },
                label = "AnimatedFabIcon"
            ) { isNextPage ->
                if (isNextPage) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, contentDescription = "Next")
                } else {
                    if (isFinishButtonEnabled) { Icon(Icons.Rounded.Check, contentDescription = "Finish") } else { Icon(Icons.Rounded.Close, contentDescription = "Finish") }
                }
            }
        }
    }
}