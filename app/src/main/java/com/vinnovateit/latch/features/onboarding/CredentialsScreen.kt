package com.vinnovateit.latch.features.onboarding

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.R
import com.vinnovateit.latch.common.ui.LeafOverlay
import com.vinnovateit.latch.common.util.TooltipHint
import com.vinnovateit.latch.core.credentials.RegistrationNumber
import com.vinnovateit.latch.core.platform.android.StoredCredentials
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily
import kotlinx.coroutines.launch

/**
 * [onBackClick] shows a back button in edit mode, reached from Settings like the
 * other pushed screens. First-run setup has none: onboarding leads here.
 */
@Composable
fun CredentialsScreen(
    editMode: Boolean,
    onCredentialsSaved: () -> Unit,
    onBackClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regNo by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val regNoFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val shakeOffset = remember { Animatable(0f) }
    val snackbarHostState = remember { SnackbarHostState() }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(Unit) {
        regNoFocusRequester.requestFocus()
    }

    LaunchedEffect(editMode) {
        if (editMode) {
            regNo = StoredCredentials.getUserId(context)?.uppercase() ?: ""
            password = StoredCredentials.getPassword(context) ?: ""
        }
    }

    val triggerError: (String) -> Unit = { msg ->
        scope.launch {
            shakeOffset.snapTo(0f)
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    0f at 0
                    (-12f) at 50
                    12f at 100
                    (-8f) at 150
                    8f at 200
                    (-4f) at 250
                    4f at 300
                    0f at 400
                },
            )
        }
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    val handleSubmit: () -> Unit = {
        val trimmedRegNo = RegistrationNumber.normalize(regNo)
        when {
            trimmedRegNo.isBlank() || password.isBlank() -> {
                triggerError(context.getString(R.string.credentials_error_message))
            }
            !RegistrationNumber.isValid(trimmedRegNo) -> {
                triggerError("Invalid Registration Number")
            }
            else -> {
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        StoredCredentials.saveCredentials(context, trimmedRegNo, password)
                    }
                    if (saved) {
                        onCredentialsSaved()
                    } else {
                        triggerError("Couldn't save credentials securely. Please try again.")
                    }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LeafOverlay(
            contentDescription = stringResource(R.string.home_background_pattern_content_description),
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center
        )

        if (onBackClick != null) {
            TooltipHint(tooltipText = "Back") {
                FilledIconButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 16.dp, top = 16.dp)
                        .size(40.dp)
                        .clip(CircleShape),
                    onClick = onBackClick,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (isLandscape) {
            // --- Landscape Layout ---
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Pane: Titles
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(id = R.string.credentials_title),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = SatoshiFontFamily,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (editMode) "Update your Wi-Fi details to stay connected" else stringResource(id = R.string.credentials_subtitle),
                        fontSize = if (editMode) 14.sp else 20.sp,
                        fontFamily = SatoshiFontFamily,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.width(32.dp))

                // Right Pane: Form Fields (Scrollable)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .offset(x = shakeOffset.value.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CredentialFormInputs(
                        regNo = regNo,
                        onRegNoChange = { regNo = it.uppercase().filter { char -> char.isLetterOrDigit() }.take(9) },
                        password = password,
                        onPasswordChange = { password = it },
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                        regNoFocusRequester = regNoFocusRequester,
                        passwordFocusRequester = passwordFocusRequester,
                        onSubmit = handleSubmit,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    SaveButton(
                        editMode = editMode,
                        onSubmit = handleSubmit
                    )
                }
            }
        } else {
            // --- Portrait Layout ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = stringResource(id = R.string.credentials_title),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SatoshiFontFamily,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (editMode) "Update your Wi-Fi details to stay connected" else stringResource(id = R.string.credentials_subtitle),
                    fontSize = if (editMode) 14.sp else 20.sp,
                    fontFamily = SatoshiFontFamily,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(48.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = shakeOffset.value.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CredentialFormInputs(
                        regNo = regNo,
                        onRegNoChange = { regNo = it.uppercase().filter { char -> char.isLetterOrDigit() }.take(9) },
                        password = password,
                        onPasswordChange = { password = it },
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                        regNoFocusRequester = regNoFocusRequester,
                        passwordFocusRequester = passwordFocusRequester,
                        onSubmit = handleSubmit,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                SaveButton(
                    editMode = editMode,
                    onSubmit = handleSubmit
                )
            }
        }

        if (editMode && onBackClick != null) {
            val haptic = LocalHapticFeedback.current
            // Same control and placement as the Settings and Stats back buttons.
            // Drawn after the form so it stays tappable over scrolled content.
            FilledIconButton(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                    .padding(start = 12.dp, top = 4.dp)
                    .size(40.dp)
                    .clip(CircleShape),
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBackClick()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(16.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun CredentialFormInputs(
    regNo: String,
    onRegNoChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: () -> Unit,
    regNoFocusRequester: FocusRequester,
    passwordFocusRequester: FocusRequester,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = regNo,
        onValueChange = onRegNoChange,
        label = { Text(stringResource(id = R.string.registration_number)) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .focusRequester(regNoFocusRequester),
        textStyle = TextStyle(
            fontSize = 16.sp,
            fontFamily = SatoshiFontFamily
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onNext = { passwordFocusRequester.requestFocus() }
        ),
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(id = R.string.password)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier
                .weight(1f)
                .focusRequester(passwordFocusRequester),
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontFamily = SatoshiFontFamily
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { onSubmit() }
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(onClick = onPasswordVisibilityChange) {
            Icon(
                imageVector = if (passwordVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                contentDescription = if (passwordVisible) "Hide Password" else "Show Password",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SaveButton(
    editMode: Boolean,
    onSubmit: () -> Unit,
) {
    // Animatable corner radius: starts fully round (27dp = height/2), springs to squircle on tap.
    val cornerRadius = remember { Animatable(27f) }
    val scope = rememberCoroutineScope()

    Button(
        onClick = {
            scope.launch {
                // Press: spring corners inward to squircle shape.
                cornerRadius.animateTo(
                    targetValue = 12f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    )
                )
                // Trigger submit while corners are squircle.
                onSubmit()
                // Spring back to fully round.
                cornerRadius.animateTo(
                    targetValue = 27f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(54.dp),
        shape = RoundedCornerShape(cornerRadius.value.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text(
            text = if (editMode) stringResource(id = R.string.update_credentials) else stringResource(id = R.string.save_credentials),
            fontSize = 18.sp,
            fontFamily = SatoshiFontFamily,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}