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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.compose.sdl.ComposeWindowScope
import com.vinnovateit.latch.ui.chrome.AppMenuActions
import com.vinnovateit.latch.ui.chrome.AppMenuHost
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.theme.LatchTheme
import com.vinnovateit.latch.ui.theme.satoshiFontFamily

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
    // Theme wraps the title bar too: the overflow DropdownMenu opens in a popup
    // that inherits CompositionLocals from here, not from LatchRoot below it.
    LatchTheme {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(TITLE_BAR_HEIGHT.dp).background(TITLE_BAR_COLOR),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Latch",
                    color = Color.White,
                    fontFamily = satoshiFontFamily(FontWeight.Medium),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
                // The rail covers navigation on wide windows; only the compact layout
                // needs the overflow menu. AppMenuActions is published by LatchRoot.
                appMenu.actions?.takeIf { it.showMenu }?.let { actions ->
                    NativeTitleBarMenuButton(actions)
                }
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
}

/**
 * Overflow menu for the compact layout, where the navigation rail is gone.
 * Reads the same [AppMenuActions] the JVM title bar uses, so navigation stays in
 * one place (LatchRoot). The hover guard prevents a press on the anchor while the
 * menu is open from dismissing and immediately reopening it.
 */
@Composable
private fun NativeTitleBarMenuButton(actions: AppMenuActions) {
    var expanded by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()

    Box {
        Box(
            modifier = Modifier
                .width(46.dp)
                .fillMaxHeight()
                .hoverable(source)
                .background(if (hovered || expanded) Color(0xFF2E2E2E) else Color.Transparent)
                .clickable(onClick = { expanded = !expanded }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LatchIcons.Menu,
                contentDescription = "Menu",
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { if (!hovered) expanded = false },
        ) {
            if (actions.showSettings) {
                DropdownMenuItem(
                    text = { Text("Settings", fontFamily = satoshiFontFamily(FontWeight.Medium)) },
                    leadingIcon = { Icon(LatchIcons.SettingsOutlined, contentDescription = null) },
                    onClick = {
                        expanded = false
                        actions.onOpenSettings()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("How it works", fontFamily = satoshiFontFamily(FontWeight.Medium)) },
                leadingIcon = { Icon(LatchIcons.Help, contentDescription = null) },
                onClick = {
                    expanded = false
                    actions.onHowItWorks()
                },
            )
            DropdownMenuItem(
                text = { Text("About", fontFamily = satoshiFontFamily(FontWeight.Medium)) },
                leadingIcon = { Icon(LatchIcons.Info, contentDescription = null) },
                onClick = {
                    expanded = false
                    actions.onOpenAbout()
                },
            )
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
