package com.vinnovateit.latch.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.compose.sdl.ComposeWindowScope
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import com.vinnovateit.latch.ui.components.LatchIcons

private const val TITLE_BAR_HEIGHT = 40
private val TITLE_BAR_COLOR = Color(0xFF1E1E1E)
private val CLOSE_HOVER_RED = Color(0xFFC42B1C)

/**
 * Native counterpart of the JVM LatchWindow: same custom title bar and layout.
 * Window dragging and resizing are the compositor's job on this port; the SDL
 * window itself is resizable, and the title-bar buttons drive minimize/close.
 */
@Composable
internal fun ComposeWindowScope.LatchWindow(
    onCloseRequest: () -> Unit,
    appMenu: AppMenuHost,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT.dp).background(TITLE_BAR_COLOR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Latch",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            TitleButton("Minimize", LatchIcons.Minimize, Color.Gray) { window.minimize() }
            TitleButton("Close", LatchIcons.Close, CLOSE_HOVER_RED) {
                onCloseRequest()
                window.close()
            }
        }
        Box(modifier = Modifier.fillMaxSize().padding(top = TITLE_BAR_HEIGHT.dp)) {
            content()
        }
    }
}

@Composable
private fun TitleButton(label: String, icon: ImageVector, hover: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .hoverable(source)
            .background(if (hovered) hover else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}
