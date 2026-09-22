package com.vinnovateit.latch.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.home_status_connected
import com.vinnovateit.latch.desktop.resources.home_status_disconnected
import com.vinnovateit.latch.ui.theme.modernizFontFamily
import com.vinnovateit.latch.ui.theme.satoshiFontFamily
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource


/**
 * Home status area: a transient LATCHED / DISCONNECTED pill that shows on a
 * connection change and dismisses itself after 3.5s.
 *
 * Carries no brand mark. Application identity is already stated by the window
 * title bar, and again by the navigation rail on wide layouts, so a third mark
 * beside the pill was only repetition.
 *
 * Carries no actions either: the application menu lives in the window title bar
 * alongside Minimize and Close, and reserves no space here because those controls
 * sit in a physically separate row above the content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LatchHomeTopBar(
    isLatched: Boolean,
    modifier: Modifier = Modifier,
) {
    var showPill by remember(isLatched) { mutableStateOf(true) }

    LaunchedEffect(isLatched) {
        showPill = true
        delay(3500)
        showPill = false
    }

    TopAppBar(
        modifier = modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                AnimatedVisibility(
                    visible = showPill,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(200)),
                    label = "TopBarTitlePill",
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isLatched) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = stringResource(
                                if (isLatched) Res.string.home_status_connected
                                else Res.string.home_status_disconnected,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isLatched) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontFamily = satoshiFontFamily(),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Secondary-screen header: optional compact back button + title.
 */
@Composable
internal fun LatchDetailHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = LatchIcons.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = modernizFontFamily(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (actions != null) {
            actions()
        }

        Spacer(Modifier.width(12.dp))
    }
}

/**
 * The LATCHED / DISCONNECTED pill (kept for standalone uses).
 */
@Composable
internal fun StatusPill(
    visible: Boolean = true,
    isConnected: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut(tween(300)) + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                AnimatedContent(
                    targetState = isConnected,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label = "PillText",
                ) { connected ->
                    Text(
                        text = if (connected) {
                            stringResource(Res.string.home_status_connected)
                        } else {
                            stringResource(Res.string.home_status_disconnected)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        fontFamily = satoshiFontFamily(),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
