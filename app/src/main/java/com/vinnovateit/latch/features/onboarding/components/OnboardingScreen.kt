package com.vinnovateit.latch.features.onboarding.components

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.vinnovateit.latch.R
import com.vinnovateit.latch.core.platform.android.StoredCredentials
import com.vinnovateit.latch.features.onboarding.pages.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onNavigateToCredentials: () -> Unit
) {
    val context = LocalContext.current
    var credentialsHandled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var permissionGranted by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val offsetX = remember { Animatable(0f) }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        if (StoredCredentials.credentialsExist(context)) {
            credentialsHandled = true
        }
    }

    val slides = remember {
        listOf(
            SlideContent(
                "Welcome to Latch",
                buildAnnotatedString { append("Let's get everything setup for you.") },
                {},
                persistentListOf()
            ),
            SlideContent(
                "How it Works",
                buildAnnotatedString { append("Latch will handle the \"Sign-in to Network\" page for you every time you connect to the network with your credentials.") },
                {
                    Icon(
                        painter = painterResource(id = R.drawable.captive_portal_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }),
            SlideContent(
                "Enable Notifications",
                buildAnnotatedString { append("To monitor your connection and provide status updates, Latch runs a service that requires a persistent notification. You can minimize or hide it from your phone's settings at any time.") },
                {
                    Icon(
                        imageVector = Icons.Rounded.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }),
            SlideContent(
                "Your Account",
                buildAnnotatedString { append("Please provide your credentials. And we will handle the magic for you.") },
                {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }),
            SlideContent(
                "Convenient Access",
                buildAnnotatedString { append("For quick access, add the Latch widget to your home screen or the tile to your Quick Settings panel.") },
                {
                    Icon(
                        imageVector = Icons.Rounded.Widgets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }),
            SlideContent(
                "You're Ready!",
                buildAnnotatedString { append("Your setup is complete. Latch will now handle your Wi-Fi sign-in.") },
                {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF1BA83C),
                        modifier = Modifier.size(80.dp)
                    )
                })
        )
    }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { slides.size })

    LaunchedEffect(pagerState.isScrollInProgress, credentialsHandled, permissionGranted) {
        if (pagerState.isScrollInProgress) {
            if (pagerState.currentPage == 2 && pagerState.targetPage > 2 && !permissionGranted) { scope.launch { pagerState.scrollToPage(2) } }
            if (pagerState.currentPage == 3 && pagerState.targetPage > 3 && !credentialsHandled) { scope.launch { pagerState.scrollToPage(3) } }
        }
    }

    if (isLandscape) {
        // --- NEW LANDSCAPE-ONLY UI ---
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val onNextClicked: () -> Unit = {
                scope.launch {
                    if ((pagerState.currentPage == 2 && !permissionGranted) || (pagerState.currentPage == 3 && !credentialsHandled)) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        return@launch
                    }
                    if (pagerState.currentPage < slides.size - 1) {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            }
            val onFinishClicked: () -> Unit = {
                if (pagerState.currentPage == slides.size - 1) {
                    onComplete()
                }
            }

            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = when (pagerState.currentPage) {
                        2 -> permissionGranted
                        3 -> credentialsHandled
                        else -> true
                    },
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                        when (pageIndex) {
                            0 -> WelcomeToLatchPageLandscape()
                            2 -> NotificationPermissionPageLandscape(slides[pageIndex], onPermissionGranted = { permissionGranted = true })
                            3 -> SetUpAccountPageLandscape(slides[pageIndex], onCredentialsClick = {
                                onNavigateToCredentials()
                            })
                            else -> StandardSlidePageLandscape(slides[pageIndex])
                        }
                }

                LandscapeFloatingNavControls(
                    pagerState = pagerState,
                    isFinishButtonEnabled = when (pagerState.currentPage) {
                        2 -> permissionGranted
                        3 -> credentialsHandled
                        else -> true
                    },
                    onNextClicked = onNextClicked,
                    onFinishClicked = onFinishClicked,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    } else {
        // --- ORIGINAL UNCHANGED PORTRAIT UI ---
        Scaffold(
            bottomBar = {
                LatchSetupBottomBar(
                    pagerState = pagerState,
                    isFinishButtonEnabled = when (pagerState.currentPage) {
                        2 -> permissionGranted
                        3 -> credentialsHandled
                        else -> true
                    },
                    onNextClicked = {
                        scope.launch {
                            if ((pagerState.currentPage == 2 && !permissionGranted) || (pagerState.currentPage == 3 && !credentialsHandled)) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                offsetX.animateTo(20f, tween(50))
                                offsetX.animateTo(-20f, tween(50))
                                offsetX.animateTo(10f, tween(50))
                                offsetX.animateTo(-10f, tween(50))
                                offsetX.animateTo(0f, tween(50))
                                return@launch
                            }

                            if (pagerState.currentPage < slides.size - 1) {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    onFinishClicked = {
                        when (pagerState.currentPage) {
                            slides.size - 1 -> onComplete()
                            else -> {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .navigationBarsPadding()
                )
            }
        ) { paddingValues ->
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = when (pagerState.currentPage) {
                    2 -> permissionGranted
                    3 -> credentialsHandled
                    else -> true
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) { pageIndex ->
                    when (pageIndex) {
                        0 -> WelcomeToLatchPage()
                        2 -> NotificationPermissionPage(slides[pageIndex], onPermissionGranted = { permissionGranted = true })
                        3 -> SetUpAccountPage(slides[pageIndex], onCredentialsClick = {
                            onNavigateToCredentials()
                        })
                        else -> StandardSlidePage(slides[pageIndex])
                    }
            }
        }
    }
}