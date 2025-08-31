package com.vinnovateit.latch.features.about

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.vinnovateit.latch.ui.theme.LatchTheme // Ensure the correct import for LatchTheme

class MeetTheTeamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LatchTheme {
                MeetTheTeamPage(onBackClick = { finish() })
            }
        }
    }
}
