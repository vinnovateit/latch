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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.settings.SettingsManager
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.desktop.resources.Res
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_cancel
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_confirm
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_message
import com.vinnovateit.latch.desktop.resources.stats_reset_dialog_title
import com.vinnovateit.latch.desktop.resources.update_credentials
import com.vinnovateit.latch.ui.components.AccentColorPicker
import com.vinnovateit.latch.ui.components.AccentSwatch
import com.vinnovateit.latch.ui.components.LatchDetailHeader
import com.vinnovateit.latch.ui.components.LatchIcons
import com.vinnovateit.latch.ui.components.SelectionOption
import com.vinnovateit.latch.ui.components.SettingsActionDialog
import com.vinnovateit.latch.ui.components.SettingsItem
import com.vinnovateit.latch.ui.components.SettingsRowGap
import com.vinnovateit.latch.ui.components.SettingsSection
import com.vinnovateit.latch.ui.components.SettingsSelectionDialog
import com.vinnovateit.latch.ui.theme.AccentSeeds
import com.vinnovateit.latch.ui.theme.LocalIsDarkTheme
import org.jetbrains.compose.resources.stringResource

/** Settings content never stretches wider than this, however wide the window is. */
private val ContentMaxWidth = 720.dp

/**
 * Settings, mirroring the Android app's Account / Appearance / Data Management
 * sections, plus two the desktop build needs: System (start at login) and
 * Application Updates.
 *
 * Android's ModalBottomSheet pickers become AlertDialogs here -- a drag-up sheet
 * is a touch gesture, and the nesting (pure-black inside Theme, monochrome inside
 * Accent) is preserved so the mental model carries over.
 */
@Composable
fun SettingsScreen(
    platform: PlatformServices,
    updateState: UpdateState,
    onBack: (() -> Unit)?,
    onNavigateToCredentials: () -> Unit,
    onClearStats: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallUpdate: (String) -> Unit,
    onDismissUpdate: () -> Unit,
) {
    val autoLogin by SettingsManager.autoLogin.collectAsStateWithLifecycle()
    val theme by SettingsManager.theme.collectAsStateWithLifecycle()
    val accentColor by SettingsManager.accentColor.collectAsStateWithLifecycle()
    val useMonochrome by SettingsManager.useMonochrome.collectAsStateWithLifecycle()
    val usePureBlack by SettingsManager.usePureBlack.collectAsStateWithLifecycle()
    val speedUnits by SettingsManager.speedUnits.collectAsStateWithLifecycle()
    val isDark = LocalIsDarkTheme.current

    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var showUnitsDialog by remember { mutableStateOf(false) }
    var showClearStatsDialog by remember { mutableStateOf(false) }

    var autostartEnabled by remember {
        mutableStateOf(platform.systemActions.isAutostartEnabled())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LatchDetailHeader(
                title = "Settings",
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    // -------------------------------------------------------------
                    // Account
                    // -------------------------------------------------------------
                    SettingsSection(title = "Account") {
                        SettingsItem(
                            title = "Auto-login",
                            subtitle = "Login automatically when a VIT WIFI is available nearby",
                            leadingIcon = LatchIcons.Login,
                            trailingContent = {
                                Switch(
                                    checked = autoLogin,
                                    onCheckedChange = { SettingsManager.setAutoLogin(it) },
                                )
                            },
                        )
                        SettingsRowGap()
                        SettingsItem(
                            title = stringResource(Res.string.update_credentials),
                            subtitle = "Change the registration number or password Latch uses",
                            leadingIcon = LatchIcons.Autorenew,
                            onClick = onNavigateToCredentials,
                        )
                    }

                    // -------------------------------------------------------------
                    // Appearance
                    // -------------------------------------------------------------
                    SettingsSection(title = "Appearance") {
                        SettingsItem(
                            title = "Theme",
                            subtitle = theme,
                            leadingIcon = when (theme) {
                                "Light" -> LatchIcons.LightMode
                                "Dark" -> LatchIcons.DarkMode
                                else -> LatchIcons.DesktopWindows
                            },
                            onClick = { showThemeDialog = true },
                        )
                        SettingsRowGap()
                        SettingsItem(
                            title = "Accent colour",
                            subtitle = when {
                                useMonochrome -> "Monochrome"
                                AccentSeeds.parseHexOrNull(accentColor) != null -> "Custom ($accentColor)"
                                else -> accentColor
                            },
                            leadingIcon = LatchIcons.InvertColors,
                            onClick = { showAccentDialog = true },
                            trailingContent = {
                                AccentSwatch(
                                    accentColor = AccentSeeds.forName(accentColor),
                                    useMonochrome = useMonochrome,
                                )
                            },
                        )
                    }

                    // -------------------------------------------------------------
                    // Data Management
                    // -------------------------------------------------------------
                    SettingsSection(title = "Data Management") {
                        SettingsItem(
                            title = "Speed units",
                            subtitle = speedUnits,
                            leadingIcon = LatchIcons.BarChart,
                            onClick = { showUnitsDialog = true },
                        )
                        SettingsRowGap()
                        SettingsItem(
                            title = "Clear session history",
                            subtitle = "Delete all recorded sessions and usage totals",
                            leadingIcon = LatchIcons.Restore,
                            onClick = { showClearStatsDialog = true },
                        )
                    }

                    // -------------------------------------------------------------
                    // System (only where autostart is actually supported)
                    // -------------------------------------------------------------
                    if (platform.capabilities.supportsAutostart) {
                        SettingsSection(title = "System") {
                            SettingsItem(
                                title = "Run at startup",
                                subtitle = "Launch Latch automatically when you sign in",
                                leadingIcon = LatchIcons.DesktopWindows,
                                trailingContent = {
                                    Switch(
                                        checked = autostartEnabled,
                                        onCheckedChange = { enabled ->
                                            platform.systemActions.setAutostart(enabled)
                                            // Read back rather than trusting the write:
                                            // the registry key can fail silently.
                                            autostartEnabled =
                                                platform.systemActions.isAutostartEnabled()
                                        },
                                    )
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        // -----------------------------------------------------------------------
        // Dialogs
        // -----------------------------------------------------------------------

        if (showThemeDialog) {
            SettingsSelectionDialog(
                title = "Theme",
                options = listOf(
                    SelectionOption("System Default", LatchIcons.DesktopWindows),
                    SelectionOption("Light", LatchIcons.LightMode),
                    SelectionOption("Dark", LatchIcons.DarkMode),
                ),
                selected = theme,
                onSelect = { SettingsManager.setTheme(it) },
                onDismiss = { showThemeDialog = false },
                bottomContent = {
                    // Pure black only means anything in a dark scheme, so it is
                    // disabled (and visibly dimmed) whenever the app is light.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Pure black",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isDark) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            )
                            Text(
                                text = "AMOLED-friendly true black background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (isDark) 1f else 0.38f,
                                ),
                            )
                        }
                        Switch(
                            checked = usePureBlack,
                            enabled = isDark,
                            onCheckedChange = { SettingsManager.setUsePureBlack(it) },
                        )
                    }
                },
            )
        }

        if (showAccentDialog) {
            SettingsSelectionDialog(
                title = "Accent colour",
                description = "Pick the seed colour the whole scheme is generated from.",
                options = emptyList(),
                selected = accentColor,
                onSelect = { SettingsManager.setAccentColor(it) },
                onDismiss = { showAccentDialog = false },
                bottomContent = {
                    AccentColorPicker(
                        selectedColorName = accentColor,
                        useMonochrome = useMonochrome,
                        onColorSelected = { SettingsManager.setAccentColor(it) },
                        onMonochromeToggle = { SettingsManager.setUseMonochrome(it) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                },
            )
        }

        if (showUnitsDialog) {
            SettingsSelectionDialog(
                title = "Speed units",
                description = "How live and recorded transfer rates are displayed.",
                options = listOf(
                    SelectionOption("bps", displayLabel = "Bits per second (bps)"),
                    SelectionOption("Bps", displayLabel = "Bytes per second (Bps)"),
                ),
                selected = speedUnits,
                onSelect = { SettingsManager.setSpeedUnits(it) },
                onDismiss = { showUnitsDialog = false },
            )
        }

        if (showClearStatsDialog) {
            SettingsActionDialog(
                title = stringResource(Res.string.stats_reset_dialog_title),
                description = stringResource(Res.string.stats_reset_dialog_message),
                confirmText = stringResource(Res.string.stats_reset_dialog_confirm),
                cancelText = stringResource(Res.string.stats_reset_dialog_cancel),
                onConfirm = {
                    onClearStats()
                    showClearStatsDialog = false
                },
                onDismiss = { showClearStatsDialog = false },
            )
        }
    }
}

/**
 * The update row and its detail area.
 *
 * The status line and the check button are always present so the section never
 * looks inert; the area beneath it expands only when there is something to act on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun UpdatePanel(
    state: UpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallUpdate: (String) -> Unit,
    onDismissUpdate: () -> Unit,
) {
    val statusText = when (state) {
        is UpdateState.Idle -> "Not checked yet"
        is UpdateState.Checking -> "Checking for updates…"
        is UpdateState.UpToDate -> "You are up to date"
        is UpdateState.UpdateAvailable -> "Version ${state.version} is available"
        is UpdateState.Downloading -> "Downloading… ${(state.progress * 100).toInt()}%"
        is UpdateState.Downloaded -> "Version ${state.version} is ready to install"
        is UpdateState.Dismissed -> "Version ${state.version} available — postponed"
        is UpdateState.Error -> "Update check failed"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsItem(
            title = "Software updates",
            subtitle = statusText,
            leadingIcon = LatchIcons.SystemUpdateAlt,
            trailingContent = {
                if (state is UpdateState.Checking) {
                    LoadingIndicator(modifier = Modifier.size(24.dp))
                } else if (state !is UpdateState.Downloading) {
                    OutlinedButton(onClick = onCheckForUpdates) {
                        Text(if (state is UpdateState.Error) "Retry" else "Check")
                    }
                }
            },
        )

        when (state) {
            is UpdateState.UpdateAvailable -> {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (state.releaseNotes.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = state.releaseNotes.trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownloadUpdate) { Text("Download") }
                        TextButton(onClick = onDismissUpdate) { Text("Not now") }
                    }
                }
            }

            is UpdateState.Downloading -> {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    // The check button is hidden while downloading, so without
                    // this a stalled 50 MB transfer would have no way out.
                    TextButton(onClick = onCancelDownload) { Text("Cancel") }
                }
            }

            is UpdateState.Downloaded -> {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { onInstallUpdate(state.filePath) }) {
                        Text("Install and restart")
                    }
                    TextButton(onClick = onDismissUpdate) { Text("Later") }
                }
            }

            is UpdateState.Error -> {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismissUpdate) { Text("Dismiss") }
                }
            }

            else -> Unit
        }
    }
}
