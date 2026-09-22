package com.vinnovateit.latch.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.theme.modernizFontFamily
import com.vinnovateit.latch.ui.theme.satoshiFontFamily
import kotlinx.coroutines.launch

/**
 * Index of the "Your Account" slide, the only one that gates progress.
 *
 * The requirement used to be enforced on the final slide by disabling its
 * finish button. Nothing stopped a user walking straight past this slide, so
 * they landed on "You're Ready!" -- whose copy states setup is complete -- in
 * front of a greyed checkmark that silently swallowed clicks. Holding them here
 * instead puts the block where "Set Up Credentials" is in reach, and matches
 * what Android already does.
 */
private const val CREDENTIALS_PAGE = 3

/** Whether forward navigation off [page] is permitted. */
private fun canLeavePage(page: Int, hasCredentials: Boolean): Boolean =
    page != CREDENTIALS_PAGE || hasCredentials

private data class DesktopSlide(
    val title: String,
    val description: String,
    val icon: @Composable () -> Unit,
)

@Composable
fun DesktopOnboardingScreen(
    hasCredentials: Boolean,
    onComplete: () -> Unit,
    onNavigateToCredentials: () -> Unit,
    modifier: Modifier = Modifier,
    pagerState: PagerState = rememberPagerState(initialPage = 0, pageCount = { 6 }),
) {
    val scope = rememberCoroutineScope()

    val slides = remember {
        listOf(
            DesktopSlide(
                title = "Welcome to Latch",
                description = "Let's get everything set up for you.",
                icon = { HandsConnectAnimation() },
            ),
            DesktopSlide(
                title = "How it Works",
                description = "Latch will handle the \"Sign-in to Network\" captive portal for you every time you connect to the network with your credentials.",
                icon = {
                    Icon(
                        imageVector = LatchIcons.CaptivePortal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp),
                    )
                },
            ),
            DesktopSlide(
                title = "Background & Tray",
                description = "To monitor your Wi-Fi and automatically latch onto the network, Latch runs quietly in the background from your system tray.",
                icon = {
                    Icon(
                        imageVector = LatchIcons.DesktopWindows,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp),
                    )
                },
            ),
            DesktopSlide(
                title = "Your Account",
                description = "Please provide your campus credentials, and Latch will securely store them on this device to handle sign-in.",
                icon = {
                    Icon(
                        imageVector = LatchIcons.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp),
                    )
                },
            ),
            DesktopSlide(
                title = "Convenient Access",
                description = "Latch is accessible anytime from your system tray menu or keyboard shortcut. Click the tray icon to connect, disconnect, or view live stats.",
                icon = {
                    Icon(
                        imageVector = LatchIcons.Widgets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp),
                    )
                },
            ),
            DesktopSlide(
                title = "You're Ready!",
                description = "Your setup is complete. Latch will now automatically handle your Wi-Fi sign-in.",
                icon = {
                    Icon(
                        imageVector = LatchIcons.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF1BA83C),
                        modifier = Modifier.size(80.dp),
                    )
                },
            ),
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            DesktopOnboardingBottomBar(
                pagerState = pagerState,
                isForwardEnabled = canLeavePage(pagerState.currentPage, hasCredentials),
                onNextClicked = {
                    scope.launch {
                        if (!canLeavePage(pagerState.currentPage, hasCredentials)) return@launch
                        if (pagerState.currentPage < slides.size - 1) {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                onFinishClicked = onComplete,
            )
        },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = canLeavePage(pagerState.currentPage, hasCredentials),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) { page ->
            val slide = slides[page]
            if (page == 0) {
                // Slide 0: Welcome to Latch hero page matching Android 1:1
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                ) {
                    Text(
                        text = "Welcome to Latch",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontSize = 28.sp,
                            lineHeight = 1.4.em,
                            fontFamily = modernizFontFamily(),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.align(Alignment.TopStart),
                    )

                    HandsConnectAnimation(modifier = Modifier.align(Alignment.Center))

                    Text(
                        text = "Let's get everything set up for you.",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            fontFamily = satoshiFontFamily(),
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        textAlign = TextAlign.Start,
                        modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 16.dp),
                    )
                }
            } else if (page == 3) {
                // Slide 3: Account Credentials Page
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = slide.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 26.sp,
                            fontFamily = satoshiFontFamily(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )

                    Spacer(modifier = Modifier.weight(0.8f))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(120.dp),
                    ) {
                        slide.icon()
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = slide.description,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontFamily = satoshiFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = onNavigateToCredentials,
                        colors = if (hasCredentials) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                    ) {
                        if (hasCredentials) {
                            Icon(
                                imageVector = LatchIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Credentials Configured", fontFamily = satoshiFontFamily(), fontWeight = FontWeight.Bold)
                        } else {
                            Text("Set Up Credentials", fontFamily = satoshiFontFamily(), fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1.2f))
                }
            } else {
                // Standard Onboarding Slide matching Android 1:1
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = slide.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 26.sp,
                            fontFamily = satoshiFontFamily(),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )

                    Spacer(modifier = Modifier.weight(0.8f))

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(120.dp),
                    ) {
                        slide.icon()
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = slide.description,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            fontFamily = satoshiFontFamily(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )

                    Spacer(modifier = Modifier.weight(1.2f))
                }
            }
        }
    }
}
