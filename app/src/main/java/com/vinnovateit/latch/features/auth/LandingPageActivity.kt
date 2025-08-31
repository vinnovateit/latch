package com.vinnovateit.latch.features.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.vinnovateit.latch.R
import com.vinnovateit.latch.features.home.MainActivity
import com.vinnovateit.latch.data.StoredCredentials
import com.vinnovateit.latch.features.onboarding.OnboardingActivity
import com.vinnovateit.latch.features.settings.manager.SettingsManager
import com.vinnovateit.latch.ui.theme.LatchTheme
import com.vinnovateit.latch.ui.theme.ModernizFontFamily
import com.vinnovateit.latch.ui.theme.SatoshiFontFamily

class LandingPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        SettingsManager.initialize(this)

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val hasSeenOnboarding = prefs.getBoolean("hasSeenOnboarding", false)

        lifecycleScope.launch {
            if (!hasSeenOnboarding) {
                setContent {
                    LatchTheme {
                        LandingPageScreen(onGetStarted = {
                            startActivity(Intent(this@LandingPageActivity, OnboardingActivity::class.java))
                            finish()
                        })
                    }
                }
            } else {
                if (StoredCredentials.credentialsExist(this@LandingPageActivity)) {
                    startActivity(Intent(this@LandingPageActivity, MainActivity::class.java))
                    finish()
                } else {
                    startActivity(Intent(this@LandingPageActivity, SecondPageActivity::class.java))
                    finish()
                }
            }
        }
    }
}

@Composable
fun LandingPageScreen(onGetStarted: () -> Unit) {
    val logoRes = if (isSystemInDarkTheme()) R.drawable.ic_latch_dark else R.drawable.ic_latch_light

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = logoRes),
                contentDescription = stringResource(R.string.landing_latch_logo_content_description),
                modifier = Modifier.size(120.dp)
            )
            Text(
                text = stringResource(R.string.app_name_uppercase),
                fontSize = 30.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = ModernizFontFamily
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onGetStarted,
                shape = RoundedCornerShape(24),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    text = stringResource(R.string.get_started),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = SatoshiFontFamily
                )
            }
        }

        Image(
            painter = painterResource(id = R.drawable.vinnovate),
            contentDescription = stringResource(R.string.landing_vinnovateit_logo_content_description),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .size(150.dp)
        )
    }
}