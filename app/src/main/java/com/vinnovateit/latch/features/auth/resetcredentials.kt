//package com.vinnovateit.latch.features.auth
//
//import android.content.Intent
//import android.os.Bundle
//import android.widget.Toast
//import androidx.activity.ComponentActivity
//import androidx.activity.compose.setContent
//import androidx.compose.foundation.background
//import androidx.compose.foundation.layout.*
//import androidx.compose.foundation.text.KeyboardOptions
//import androidx.compose.material3.*
//import androidx.compose.runtime.*
//import androidx.compose.ui.Alignment
//import androidx.compose.ui.Modifier
//import androidx.compose.ui.focus.onFocusChanged
//import androidx.compose.ui.graphics.Color
//import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.text.TextStyle
//import androidx.compose.ui.text.font.FontWeight
//import androidx.compose.ui.text.font.FontFamily
//import androidx.compose.ui.text.font.Font
//import androidx.compose.ui.text.input.ImeAction
//import androidx.compose.ui.text.input.KeyboardType
//import androidx.compose.ui.text.input.PasswordVisualTransformation
//import androidx.compose.foundation.text.selection.TextSelectionColors
//import androidx.compose.ui.unit.dp
//import androidx.compose.ui.unit.sp
//import com.vinnovateit.latch.data.CredentialDatabase
//import com.vinnovateit.latch.data.CredentialEntity
//import kotlinx.coroutines.launch
//import androidx.compose.foundation.Image
//import androidx.compose.foundation.clickable
//import androidx.compose.foundation.interaction.MutableInteractionSource
//import androidx.compose.ui.draw.paint
//import androidx.compose.ui.layout.ContentScale
//import androidx.compose.ui.res.painterResource
//import androidx.compose.ui.unit.sp
//import com.vinnovateit.latch.ui.theme.LatchTheme

//class SecondPageActivity : ComponentActivity() {
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        val editMode = intent.getBooleanExtra("editMode", false)
//        setContent {
//            LatchTheme {
//                CredentialsScreen(
//                    editMode = editMode,
//                    onCredentialsSaved = {
//                        startActivity(Intent(this, MainActivity::class.java))
//                        finish()
//                    }
//                )
//            }
//        }
//    }
//}
//
//@Composable
//fun CredentialsScreen(editMode: Boolean, onCredentialsSaved: () -> Unit) {
//    val context = LocalContext.current
//    val scope = rememberCoroutineScope()
//    var regNo by remember { mutableStateOf("") }
//    var password by remember { mutableStateOf("") }
//    var message by remember { mutableStateOf("") }
//    var loaded by remember { mutableStateOf(false) }
//    var regNoFocused by remember { mutableStateOf(false) }
//    var passwordFocused by remember { mutableStateOf(false) }
//    var passwordVisible by remember { mutableStateOf(false) }
//
//    // Load from DB
//    LaunchedEffect(Unit) {
//        val db = CredentialDatabase.getInstance(context)
//        val existing = db.credentialDao().getCredential()
//        if (existing != null && !editMode) {
//            onCredentialsSaved()
//        } else if (existing != null && !loaded) {
//            regNo = existing.registrationNumber
//            password = existing.password
//            loaded = true
//        }
//    }
//
//    Box(
//        modifier = Modifier
//            .fillMaxSize()
//            .background(MaterialTheme.colorScheme.background)
//            .paint(
//                painter = painterResource(id = R.drawable.backgroundline),
//                contentScale = ContentScale.FillBounds
//            )
//            .padding(24.dp)
//    ) {
//        Column(
//            modifier = Modifier
//                .align(Alignment.Center)
//                .offset(y = (-40).dp),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            Text(
//                text = "Let's Get Started",
//                fontSize = 35.sp,
//                fontWeight = FontWeight.Bold,
//                fontFamily = OutfitFontFamily,
//                color = MaterialTheme.colorScheme.onBackground
//            )
//
//            Spacer(modifier = Modifier.height(16.dp))
//
//            Text(
//                text = "Enter credentials",
//                fontSize = 20.sp,
//                fontFamily = OutfitFontFamily,
//                color = MaterialTheme.colorScheme.onBackground,
//                fontWeight = FontWeight.Medium
//            )
//
//            Spacer(modifier = Modifier.height(20.dp))
//
//            TextField(
//                value = regNo,
//                onValueChange = { regNo = it },
//                label = if (regNo.isEmpty() && !regNoFocused) { { Text("Registration Number", color = Color(0xFFC01221)) } } else null,
//                singleLine = true,
//                trailingIcon = {
//                    Image(
//                        painter = painterResource(id = R.drawable.ic_username),
//                        contentDescription = "Username Icon",
//                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFC01221))
//                    )
//                },
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .onFocusChanged { focusState ->
//                        regNoFocused = focusState.isFocused
//                    },
//                textStyle = TextStyle(
//                    color = Color(0xFFC01221),
//                    fontSize = 18.sp,
//                    fontFamily = SatoshiRegularFontFamily
//                ),
//                keyboardOptions = KeyboardOptions(
//                    keyboardType = KeyboardType.Text,
//                    imeAction = androidx.compose.ui.text.input.ImeAction.Next
//                ),
//                colors = TextFieldDefaults.colors(
//                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
//                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
//                    focusedContainerColor = MaterialTheme.colorScheme.background,
//                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
//                    cursorColor = MaterialTheme.colorScheme.onBackground,
//                    focusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
//                    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
//                    focusedIndicatorColor = MaterialTheme.colorScheme.onBackground,
//                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
//                ),
//            )
//
//            Spacer(modifier = Modifier.height(16.dp))
//
//            TextField(
//                value = password,
//                onValueChange = { password = it },
//                label = if (password.isEmpty() && !passwordFocused) { { Text("Password", color = Color(0xFFC01221)) } } else null,
//                singleLine = true,
//                visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
//                trailingIcon = {
//                    Image(
//                        painter = painterResource(id = R.drawable.ic_password),
//                        contentDescription = "Password Icon",
//                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFFC01221)),
//                        modifier = Modifier.clickable(
//                            indication = null,
//                            interactionSource = remember { MutableInteractionSource() }
//                        ) { passwordVisible = !passwordVisible }
//                    )
//                },
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .onFocusChanged { focusState ->
//                        passwordFocused = focusState.isFocused
//                    },
//                textStyle = TextStyle(
//                    color = Color(0xFFC01221),
//                    fontSize = 18.sp,
//                    fontFamily = SatoshiRegularFontFamily
//                ),
//                keyboardOptions = KeyboardOptions(
//                    keyboardType = KeyboardType.Password,
//                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
//                ),
//                colors = TextFieldDefaults.colors(
//                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
//                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
//                    focusedContainerColor = MaterialTheme.colorScheme.background,
//                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
//                    cursorColor = MaterialTheme.colorScheme.onBackground,
//                    focusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
//                    unfocusedLabelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
//                    focusedIndicatorColor = MaterialTheme.colorScheme.onBackground,
//                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
//                ),
//            )
//
//            Spacer(modifier = Modifier.height(24.dp))
//
//            Button(
//                onClick = {
//                    if (regNo.isNotBlank() && password.isNotBlank()) {
//                        scope.launch {
//                            val db = CredentialDatabase.getInstance(context)
//                            db.credentialDao().insertCredential(
//                                CredentialEntity(id = "singleton", registrationNumber = regNo, password = password)
//                            )
//
//                            Toast.makeText(context, "Credentials saved!", Toast.LENGTH_SHORT).show()
//                            onCredentialsSaved()
//                        }
//                    } else {
//                        message = "Please enter User ID and Password"
//                    }
//                },
//                modifier = Modifier
//                    .fillMaxWidth() // Set to max width
//                    .padding(horizontal = 40.dp),
//                shape = androidx.compose.foundation.shape.RoundedCornerShape(7.dp),
//                colors = ButtonDefaults.buttonColors(
//                    containerColor = MaterialTheme.colorScheme.primary,
//                    contentColor = MaterialTheme.colorScheme.onPrimary
//                )
//            ) {
//                Text(
//                    text = if (editMode) "Update Credentials" else "Save Credentials",
//                    fontSize = 18.sp,
//                    fontWeight = FontWeight.SemiBold,
//                    fontFamily = OutfitFontFamily,
//                )
//            }
//
//            if (message.isNotEmpty()) {
//                Spacer(modifier = Modifier.height(16.dp))
//                Text(text = message, color = MaterialTheme.colorScheme.error)
//            }
//        }
//    }
//}
