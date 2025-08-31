package com.vinnovateit.latch.features.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.vinnovateit.latch.R
import com.vinnovateit.latch.features.auth.SecondPageActivity
import com.vinnovateit.latch.features.home.MainActivity
import com.vinnovateit.latch.ui.theme.LatchTheme
import com.vinnovateit.latch.ui.theme.ModernizFontFamily
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ContentAlpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview

class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LatchTheme {
                OnboardingScreen(
                    onComplete = {
                        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("hasSeenOnboarding", true).apply()

                        startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

data class SlideContent(
    val title: String,
    val description: AnnotatedString,
    val icon: @Composable () -> Unit,
    val icons: ImmutableList<Int> = persistentListOf()
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class,
    ExperimentalAnimationApi::class
)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var credentialsHandled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showCredentialsAlert by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(false) }

    val slides = remember {
        listOf(
            // Welcome slide
            SlideContent(
                title = "Welcome to Latch",
                description = buildAnnotatedString {
                    append("Let's get everything set up for you.")
                },
                icon = {},
                icons = persistentListOf()
            ),

            // No Sign-in Hassle
            SlideContent(
                title = "No Sign-in Hassle",
                description = buildAnnotatedString {
                    append("Latch logs you in automatically — no typing.")
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.WifiLock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }
            ),

            // How it Works
            SlideContent(
                title = "How it Works",
                description = buildAnnotatedString {
                    append("Enter your VIT credentials once.\n\n")
                    append("Latch auto-submits on hostel Wi-Fi.\n\n")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("Credentials stored securely.")
                    }
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }
            ),

            // Allow Permissions
            SlideContent(
                title = "Allow Permissions",
                description = buildAnnotatedString {
                    append("Latch needs notification access.")
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }
            ),

            // Set Up Account
            SlideContent(
                title = "Set Up Account",
                description = buildAnnotatedString {
                    append("Enter VIT ID & password to start auto-login.")
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }
            ),

            // Enter Credentials
            SlideContent(
                title = "Enter Your Credentials",
                description = buildAnnotatedString {
                    append("Provide your VIT login details for automatic connection.")
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Login,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }
            ),

            // Widgets & Tile
            SlideContent(
                title = "Widgets & Tile",
                description = buildAnnotatedString {
                    append("Add Latch widget or Quick Settings tile for one-tap access.")
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Widgets,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(80.dp)
                    )
                }
            ),

            // Final
            SlideContent(
                title = "You're Ready!",
                description = buildAnnotatedString {
                    append("Latch will auto-login whenever in range.")
                },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF1BA83C),
                        modifier = Modifier.size(80.dp)
                    )
                }
            )
        )
    }

    val pagerState = rememberPagerState(pageCount = { slides.size })

    val credentialsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            credentialsHandled = true
            scope.launch {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

    Scaffold(
        bottomBar = {
            LatchSetupBottomBar(
                pagerState = pagerState,
                animated = (pagerState.currentPage != 0),
                isFinishButtonEnabled = when (pagerState.currentPage) {
                    5 -> credentialsHandled
                    else -> true
                },
                onNextClicked = {
                    scope.launch {
                        if (pagerState.currentPage == 5 && !credentialsHandled) {
                            showCredentialsAlert = true
                        } else {
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
                }
            )
        }
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) { pageIndex ->
            val slide = slides[pageIndex]

            AnimatedContent(
                targetState = pageIndex,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() with
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() with
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                }
            ) { targetPage ->
                when (targetPage) {
                    0 -> WelcomeToLatchPage()
                    3 -> NotificationPermissionPage(slides[targetPage], onPermissionGranted = { permissionGranted = true })
                    5 -> CredentialsPage(slides[targetPage], onCredentialsClick = {
                        credentialsLauncher.launch(
                            Intent(context, SecondPageActivity::class.java).apply {
                                putExtra("fromOnboarding", true)
                            }
                        )
                    })
                    else -> StandardSlidePage(slides[targetPage])
                }
            }
        }
    }
    if (showCredentialsAlert) {
        AlertDialog(
            onDismissRequest = { showCredentialsAlert = false },
            title = { Text("Enter Credentials") },
            text = { Text("Please enter your credentials before proceeding.") },
            confirmButton = {
                TextButton(onClick = { showCredentialsAlert = false }) {
                    Text("OK")
                }
            }
        )
    }
}


@Composable
fun WelcomeToLatchPage() {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "Welcome to Latch",
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = 35.sp,
                lineHeight = 1.1.em,
                fontFamily = ModernizFontFamily,
                fontWeight = FontWeight.Bold
            ),
            textAlign = TextAlign.Start,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 25.dp, end = 25.dp)
        )

        Image(
            painter = painterResource(id = if (isSystemInDarkTheme()) R.drawable.ic_latch_dark else R.drawable.ic_latch_light),
            contentDescription = "Latch Logo",
            modifier = Modifier
                .align(Alignment.Center)
                .size(120.dp)
        )
        
        Text(
            text = "Let's get everything set up for you.",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 20.sp,
                fontFamily = SatoshiFontFamily,
                fontWeight = FontWeight.Bold
            ),
            textAlign = TextAlign.Start,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 30.dp)
        )
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NotificationPermissionPage(
    slide: SlideContent,
    onPermissionGranted: () -> Unit
) {
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else ""

    val notificationPermissionState = rememberPermissionState(
        permission = permission
    ) { granted -> if (granted) onPermissionGranted() }

    val isGranted = notificationPermissionState.status.isGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

    LaunchedEffect(isGranted) {
        if (isGranted) onPermissionGranted()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = slide.title,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 28.dp, top = 28.dp)
                .align(Alignment.TopStart)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(180.dp)
            ) { slide.icon() }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = slide.description.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                textAlign = TextAlign.Center,
                fontFamily = SatoshiFontFamily
            )

            Spacer(modifier = Modifier.height(44.dp))

            Button(
                onClick = {
                    if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionState.launchPermissionRequest()
                    }
                },
                enabled = !isGranted,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
            ) {
                if (isGranted) {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isGranted) "Permission Granted" else "Grant Notification Permission",
                    fontFamily = SatoshiFontFamily
                )
            }
        }
    }
}


@Composable
fun CredentialsPage(
    slide: SlideContent,
    onCredentialsClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = slide.title,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 28.dp, top = 28.dp)
                .align(Alignment.TopStart)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(180.dp)
            ) { slide.icon() }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = slide.description.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                textAlign = TextAlign.Center,
                fontFamily = SatoshiFontFamily
            )

            Spacer(modifier = Modifier.height(44.dp))

            Button(
                onClick = onCredentialsClick,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text("Set Up Credentials", fontFamily = SatoshiFontFamily, fontSize = 17.sp)
            }
        }
    }
}


@Composable
fun StandardSlidePage(slide: SlideContent) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = slide.title,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 28.dp, top = 30.dp)
                .align(Alignment.TopStart)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 40.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(180.dp)
            ) {
                slide.icon()
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = slide.description,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
                textAlign = TextAlign.Center,
                fontFamily = SatoshiFontFamily
            )
        }
    }
}


@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalAnimationApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun LatchSetupBottomBar(
    modifier: Modifier = Modifier,
    animated: Boolean = false,
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
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(24.dp), clip = true),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(
            topStart = 36.dp,
            topEnd = 36.dp,
            bottomStart = 36.dp,
            bottomEnd = 36.dp
        )
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
                if (pagerState.currentPage == 0) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.vinnovate),
                            contentDescription = "Vinnovateit Logo",
                            modifier = Modifier
                                .graphicsLayer {
                                    scaleX = 3.25f
                                    scaleY = 3.25f
                                }
                                .offset(x = 20.dp)
                        )
                    }
                } else {
                    AnimatedContent(
                        targetState = pagerState.currentPage,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInVertically { height -> height } + fadeIn()).togetherWith(
                                    slideOutVertically { height -> -height } + fadeOut()
                                )
                            } else {
                                (slideInVertically { height -> -height } + fadeIn()).togetherWith(
                                    slideOutVertically { height -> height } + fadeOut()
                                )
                            }.using(SizeTransform(clip = false))
                        },
                        label = "StepTextAnimation"
                    ) { targetPage ->
                        Text(
                            text = "Step ${targetPage} of ${pagerState.pageCount - 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = SatoshiFontFamily
                        )
                    }
                }

                // Next / Finish Button
                val isLastPage = pagerState.currentPage == pagerState.pageCount - 1
                val containerColor = if (isLastPage && !isFinishButtonEnabled) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }

                val contentColor = if (isLastPage && !isFinishButtonEnabled) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlpha.disabled)
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }

                MediumFloatingActionButton(
                    onClick = {
                        if (!isFinishButtonEnabled) return@MediumFloatingActionButton
                        if (isLastPage) {
                            onFinishClicked()
                        } else {
                            onNextClicked()
                        }
                    },
                    shape = RoundedCornerShape(
                        topStart = animatedTopStart.toInt().dp,
                        topEnd = animatedTopEnd.toInt().dp,
                        bottomStart = animatedBottomStart.toInt().dp,
                        bottomEnd = animatedBottomEnd.toInt().dp
                    ),
                    elevation = FloatingActionButtonDefaults.elevation(0.dp),
                    containerColor = containerColor,
                    contentColor = contentColor,
                    modifier = Modifier
                        .rotate(animatedRotation)
                        .padding(end = 0.dp)
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
                            Icon(Icons.Rounded.ArrowForward, contentDescription = "Next")
                        } else {
                            if (isFinishButtonEnabled) {
                                Icon(Icons.Rounded.Check, contentDescription = "Finish")
                            } else {
                                Icon(Icons.Rounded.Close, contentDescription = "Finish")
                            }
                        }
                    }
                }
            }
        }
    }
}



@Preview(showBackground = true)
@Composable
fun PreviewFirstPage() {
    WelcomeToLatchPage()
}
