package com.vinnovateit.latch.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.ui.components.LatchIcons

private val ContentMaxWidth = 560.dp

/**
 * Full-window takeover shown the moment an update is found, rather than
 * leaving it buried in Settings where nobody would see it until they went
 * looking. Mirrors [AboutScreen] and [CredentialsScreen] in taking over the
 * whole window instead of living inside the nav rail -- this is not a
 * destination someone navigates to, it interrupts whatever they were doing.
 *
 * Only rendered by [com.vinnovateit.latch.ui.LatchRoot] for the states that
 * warrant an interruption ([UpdateState.UpdateAvailable], [UpdateState.Downloading],
 * [UpdateState.Downloaded]); [UpdateState.Error] during a download is shown here
 * too so the in-progress takeover doesn't just vanish, but a plain check failure
 * stays silent and demoted to Settings -- see LatchRoot's gating.
 *
 * Installing itself is not a button here -- LatchRoot fires it automatically
 * the instant the state becomes [UpdateState.Downloaded], since there is
 * nothing left for the user to decide once the file is on disk. This screen
 * just reflects that it's happening; [onRetry] only matters if it fails.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateScreen(
    state: UpdateState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = LatchIcons.SystemUpdateAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                val version = state.versionOrNull()
                Text(
                    text = if (version != null) "Update available: v$version" else "Downloading update…",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))

                when (state) {
                    is UpdateState.UpdateAvailable -> {
                        Text(
                            text = if (state.manualInstall) {
                                "Download it from the release page and install it over this copy of Latch."
                            } else {
                                "A new version of Latch is ready to download."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 20.dp),
                        )
                        if (state.releaseNotes.isNotBlank()) {
                            ReleaseNotesCard(state.releaseNotes)
                            Spacer(Modifier.height(24.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = onSkip) { Text("Not now") }
                            Button(onClick = onDownload) {
                                Text(if (state.manualInstall) "Open download page" else "Download update")
                            }
                        }
                    }

                    is UpdateState.Downloading -> {
                        Text(
                            text = "Please keep the app open while the update downloads.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 24.dp),
                        )
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = onCancelDownload) { Text("Cancel") }
                    }

                    is UpdateState.Downloaded -> {
                        Text(
                            text = "Update downloaded. Installing now -- Latch will close briefly and reopen.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 20.dp),
                        )
                        LoadingIndicator(modifier = Modifier.size(32.dp))
                    }

                    is UpdateState.Error -> {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 24.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = onSkip) { Text("Dismiss") }
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(),
                            ) { Text("Retry") }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}

private fun UpdateState.versionOrNull(): String? = when (this) {
    is UpdateState.UpdateAvailable -> version
    is UpdateState.Downloaded -> version
    else -> null
}

@Composable
private fun ReleaseNotesCard(notes: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "What's new",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = notes.trim(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
