package com.vinnovateit.latch.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.R
import com.vinnovateit.latch.ui.theme.ModernizFontFamily

/** Below this width the top-bar menu stays the only navigation affordance. */
internal val RailVisibleWidth = 600.dp
/** Between [RailVisibleWidth] and this width the rail is icon-only. */
internal val RailExpandedWidth = 840.dp

/** Destinations offered by the rail; other routes keep their existing UI. */
internal val RailTopLevelRoutes = setOf(LatchRoutes.HOME, LatchRoutes.STATS, LatchRoutes.SETTINGS)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LatchNavigationRail(
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    expanded: Boolean,
) {
    val railState = rememberWideNavigationRailState(
        initialValue = if (expanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
    )
    LaunchedEffect(expanded) {
        if (expanded) railState.expand() else railState.collapse()
    }

    WideNavigationRail(
        state = railState,
        header = {
            // FAB-like header: Latch mark + wordmark in the display font,
            // mirroring the desktop wide rail.
            Row(
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingActionButton(
                    onClick = { onSelect(LatchRoutes.HOME) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_latch),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                        if (railState.currentValue == WideNavigationRailValue.Expanded) {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "LATCH",
                                fontFamily = ModernizFontFamily,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        },
    ) {
        RailDestinations.forEach { destination ->
            WideNavigationRailItem(
                selected = destination.route == selectedRoute,
                onClick = { onSelect(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
                railExpanded = railState.currentValue == WideNavigationRailValue.Expanded,
                iconPosition = NavigationItemIconPosition.Start,
            )
        }
    }
}

private data class RailDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val RailDestinations = listOf(
    RailDestination(LatchRoutes.HOME, "Home", Icons.Rounded.Home),
    RailDestination(LatchRoutes.STATS, "Stats", Icons.Rounded.BarChart),
    RailDestination(LatchRoutes.SETTINGS, "Settings", Icons.Rounded.Settings),
)
