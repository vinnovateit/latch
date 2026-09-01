package com.vinnovateit.latch.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.desktop.LatchMark
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.credentials_error_message
import com.vinnovateit.latch.desktop.resources.credentials_subtitle
import com.vinnovateit.latch.desktop.resources.credentials_title
import com.vinnovateit.latch.desktop.resources.password
import com.vinnovateit.latch.desktop.resources.registration_number
import com.vinnovateit.latch.desktop.resources.save_credentials
import com.vinnovateit.latch.ui.components.LatchDetailHeader
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.components.LeafOverlay
import com.vinnovateit.latch.ui.theme.modernizFontFamily
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val REG_NO_REGEX = "^[0-9]{2}[A-Z]{3}[0-9]{4}$".toRegex()

/**
 * Credential entry, dressed like the Android onboarding account page: leaf
 * background, the Latch mark, and the encryption notice that tells people where
 * their password is actually going.
 *
 * Saved through the platform CredentialStore, which on Windows is DPAPI -- the
 * blob decrypts only for the logged-in Windows account, which is what the notice
 * at the bottom is claiming, so it stays accurate.
 *
 * [onCancel] is null during first-run setup, where there is nothing to go back
 * to; it is supplied when the screen is reached from Settings.
 */
@Composable
fun CredentialsScreen(
    initialRegNo: String = "",
    initialPassword: String = "",
    onSave: (String, String) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var regNo by remember(initialRegNo) { mutableStateOf(initialRegNo.uppercase().trim()) }
    var pass by remember(initialPassword) { mutableStateOf(initialPassword) }
    var passwordVisible by remember { mutableStateOf(false) }
    val regNoFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val shakeOffset = remember { Animatable(0f) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val errorMessage = stringResource(Res.string.credentials_error_message)

    LaunchedEffect(Unit) {
        regNoFocusRequester.requestFocus()
    }

    fun triggerError(msg: String) {
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

    val submit = {
        val trimmedRegNo = regNo.trim().uppercase()
        when {
            trimmedRegNo.isBlank() || pass.isBlank() -> {
                triggerError(errorMessage)
            }
            !REG_NO_REGEX.matches(trimmedRegNo) -> {
                triggerError("Invalid Registration Number")
            }
            else -> {
                onSave(trimmedRegNo, pass)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LeafOverlay(
            modifier = Modifier.fillMaxSize(),
            contentDescription = null,
            alignment = Alignment.TopCenter,
            contentScale = ContentScale.Crop,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            LatchDetailHeader(
                title = "",
                onBack = onCancel,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = LatchMark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    text = stringResource(Res.string.credentials_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = modernizFontFamily(),
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.credentials_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(32.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(x = shakeOffset.value.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    OutlinedTextField(
                        value = regNo,
                        onValueChange = {
                            regNo = it.uppercase().filter { char -> char.isLetterOrDigit() }.take(9)
                        },
                        label = { Text(stringResource(Res.string.registration_number)) },
                        leadingIcon = { Icon(LatchIcons.Person, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(regNoFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && event.type == KeyEventType.KeyDown) {
                                    passwordFocusRequester.requestFocus()
                                    true
                                } else {
                                    false
                                }
                            },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { passwordFocusRequester.requestFocus() }
                        ),
                    )
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(stringResource(Res.string.password)) },
                        leadingIcon = { Icon(LatchIcons.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) LatchIcons.Visibility else LatchIcons.VisibilityOff,
                                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocusRequester)
                            .onPreviewKeyEvent { event ->
                                if ((event.key == Key.Enter || event.key == Key.NumPadEnter) && event.type == KeyEventType.KeyDown) {
                                    submit()
                                    true
                                } else {
                                    false
                                }
                            },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { submit() }
                        ),
                    )
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = submit,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.save_credentials),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (onCancel != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }

                Spacer(Modifier.height(32.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LatchIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = buildAnnotatedString {
                                append("Latch does not collect your data. Your credentials are ")
                                append("encrypted and ")
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                    append("stored securely on this device.")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
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
