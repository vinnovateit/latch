package com.vinnovateit.latch.ui.screens

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
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
import org.jetbrains.compose.resources.stringResource

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
    var regNo by remember(initialRegNo) { mutableStateOf(initialRegNo) }
    var pass by remember(initialPassword) { mutableStateOf(initialPassword) }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorMessage = stringResource(Res.string.credentials_error_message)

    val submit = {
        if (regNo.isBlank() || pass.isBlank()) {
            error = errorMessage
        } else {
            onSave(regNo.trim(), pass)
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

                OutlinedTextField(
                    value = regNo,
                    onValueChange = { regNo = it; error = null },
                    label = { Text(stringResource(Res.string.registration_number)) },
                    leadingIcon = { Icon(LatchIcons.Person, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    isError = error != null && regNo.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it; error = null },
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null && pass.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = submit,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.save_credentials),
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
    }
}
