package com.vinnovateit.latch.features.onboarding

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import com.vinnovateit.latch.R
import com.vinnovateit.latch.common.ui.LeafOverlay
import com.vinnovateit.latch.data.StoredCredentials
import com.vinnovateit.latch.features.home.MainActivity
import com.vinnovateit.latch.ui.theme.LatchTheme
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily



@Composable
fun CredentialsScreen(editMode: Boolean, onCredentialsSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var regNo by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var regNoError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(editMode) {
        if (editMode) {
            regNo = StoredCredentials.getUserId(context) ?: ""
            password = StoredCredentials.getPassword(context) ?: ""
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
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CredentialFormInputs(
                        regNo = regNo,
                        onRegNoChange = { regNo = it.uppercase(); regNoError = null },
                        password = password,
                        onPasswordChange = { password = it; passwordError = null },
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                        regNoError = regNoError,
                        passwordError = passwordError
                    )

                    Button(
                        onClick = {
                            if (regNo.isNotBlank() && password.isNotBlank()) {
                                val regNoRegex = Regex("^[0-9]{2}[A-Za-z]{3}[0-9]{4}$")
                                if (!regNoRegex.matches(regNo)) {
                                    regNoError = "Invalid format (e.g. 23BCE1234)"
                                } else {
                                    scope.launch {
                                        if (StoredCredentials.saveCredentials(context, regNo, password)) {
                                            onCredentialsSaved()
                                        } else {
                                            passwordError = "Couldn't save credentials securely. Please try again."
                                        }
                                    }
                                }
                            } else {
                                if (regNo.isBlank()) regNoError = context.getString(R.string.credentials_error_message)
                                if (password.isBlank()) passwordError = context.getString(R.string.credentials_error_message)
                            }
                        },
                        modifier = Modifier
                            .height(50.dp),
                        shape = RoundedCornerShape(24.dp),
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
            }
        } else {
            // --- Portrait Layout ---
            // CRITICAL FIX: Changed from .align(Alignment.Center) to .fillMaxSize() + Arrangement.Center
            // to stop infinite height constraint crashes while allowing vertical scrolling.
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

                CredentialFormInputs(
                    regNo = regNo,
                    onRegNoChange = { regNo = it.uppercase(); regNoError = null },
                    password = password,
                    onPasswordChange = { password = it; passwordError = null },
                    passwordVisible = passwordVisible,
                    onPasswordVisibilityChange = { passwordVisible = !passwordVisible },
                    regNoError = regNoError,
                    passwordError = passwordError
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        if (regNo.isNotBlank() && password.isNotBlank()) {
                            val regNoRegex = Regex("^[0-9]{2}[A-Za-z]{3}[0-9]{4}$")
                            if (!regNoRegex.matches(regNo)) {
                                regNoError = "Invalid format (e.g. 23BCE1234)"
                            } else {
                                scope.launch {
                                    if (StoredCredentials.saveCredentials(context, regNo, password)) {
                                        onCredentialsSaved()
                                    } else {
                                        passwordError = "Couldn't save credentials securely. Please try again."
                                    }
                                }
                            }
                        } else {
                            if (regNo.isBlank()) regNoError = context.getString(R.string.credentials_error_message)
                            if (password.isBlank()) passwordError = context.getString(R.string.credentials_error_message)
                        }
                    },
                    modifier = Modifier
                        .height(50.dp),
                    shape = RoundedCornerShape(24.dp),
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
    regNoError: String?,
    passwordError: String?
) {
    OutlinedTextField(
        value = regNo,
        onValueChange = onRegNoChange,
        label = { Text(stringResource(id = R.string.registration_number)) },
        singleLine = true,
        isError = regNoError != null,
        supportingText = { if (regNoError != null) Text(regNoError) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        textStyle = TextStyle(
            fontSize = 16.sp,
            fontFamily = SatoshiFontFamily
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Next
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
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = passwordError != null,
            supportingText = { if (passwordError != null) Text(passwordError) },
            modifier = Modifier.weight(1f),
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontFamily = SatoshiFontFamily
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
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