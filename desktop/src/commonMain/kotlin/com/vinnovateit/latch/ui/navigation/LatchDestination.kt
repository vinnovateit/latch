package com.vinnovateit.latch.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.vinnovateit.latch.ui.components.LatchIcons

internal enum class LatchDestination(val label: String, val icon: ImageVector) {
    Home("Home", LatchIcons.HomeOutlined),
    Stats("Stats", LatchIcons.BarChart),
    Settings("Settings", LatchIcons.SettingsOutlined),
    About("About", LatchIcons.Info),
}
