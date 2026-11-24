package com.vinnovateit.latch.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinnovateit.latch.core.platform.PlatformServices
import com.vinnovateit.latch.core.updater.UpdateState
import com.vinnovateit.latch.desktop.VinnovateItLogo
import com.vinnovateit.latch.desktop.LatchIcon
import com.vinnovateit.latch.ui.components.LatchDetailHeader
import com.vinnovateit.latch.ui.components.LatchIcons
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import com.vinnovateit.latch.ui.theme.modernizFontFamily

private val ContentMaxWidth = 720.dp

private const val LATCH_BLURB = "Latch is an auto-login utility built for the VIT hostel " +
    "Wi-Fi network. It detects the captive portal, signs you in " +
    "automatically with your saved credentials, and keeps your " +
    "session alive in the background — so you never have to open " +
    "a browser just to get online."

private const val VINNOVATEIT_BLURB = "VinnovateIT is a community of builders and innovators " +
    "exploring technology beyond classrooms, through hands-on learning and real problem " +
    "solving. We don't just learn tech, we build with it. Because real innovation begins " +
    "when ideas meet execution.\n\nSome of our major projects include Messit, BunkBuddies, " +
    "StudyHub, etc."

private const val GITHUB_REPO_URL = "https://github.com/vinnovateit/latch"
private const val INSTAGRAM_URL = "https://www.instagram.com/vinnovateit/"
private const val LINKEDIN_URL = "https://www.linkedin.com/company/v-innovate-it/"
private const val GITHUB_ORG_URL = "https://github.com/vinnovateit"

@Composable
fun AboutScreen(
    platform: PlatformServices,
    onBack: (() -> Unit)?,
    updateState: UpdateState = UpdateState.Idle,
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onInstallUpdate: (String) -> Unit = {},
    onDismissUpdate: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LatchDetailHeader(title = "About", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Spacer(Modifier.height(24.dp))

                // ── Latch logo + name + version + update check ──────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = LatchIcon.brand(),
                        contentDescription = "Latch",
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Latch",
                        fontFamily = modernizFontFamily(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Version + refresh icon inline
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        val versionText = buildString {
                            append("v")
                            append(platform.buildInfo.versionName)
                            if (platform.buildInfo.isDebug) append(" (debug)")
                            if (!platform.buildInfo.isInstalled) append(" — dev run")
                            if (updateState is UpdateState.UpToDate) append(" · latest")
                            else if (updateState is UpdateState.UpdateAvailable) append(" · ${updateState.version} available")
                            else if (updateState is UpdateState.Checking) append(" · checking…")
                        }
                        Text(
                            text = versionText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(2.dp))
                        val rotation by animateFloatAsState(
                            targetValue = if (updateState is UpdateState.Checking) 360f else 0f,
                            animationSpec = tween(600),
                            label = "refresh",
                        )
                        IconButton(
                            onClick = onCheckForUpdates,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = LatchIcons.Refresh,
                                contentDescription = "Check for updates",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp).rotate(rotation),
                            )
                        }
                    }

                    // Show download/install actions only when needed
                    when (updateState) {
                        is UpdateState.UpdateAvailable -> {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onDownloadUpdate) {
                                Text("Update available: v${updateState.version}. Update?")
                            }
                        }
                        is UpdateState.Downloaded -> {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { onInstallUpdate(updateState.filePath) }) {
                                Text("Install and restart")
                            }
                        }
                        else -> {}
                    }
                }

                Spacer(Modifier.height(20.dp))

                // ── Latch description ────────────────────────────────────────
                Text(
                    text = LATCH_BLURB,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(32.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(32.dp))

                // ── VinnovateIT logo + name + blurb ──────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = VinnovateItLogo,
                        contentDescription = "VinnovateIT",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .width(140.dp)
                            .height(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = VINNOVATEIT_BLURB,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ── Social handles ───────────────────────────────────────────
                SocialLinksRow(
                    onOpenInstagram = { platform.systemActions.openUrl(INSTAGRAM_URL) },
                    onOpenLinkedIn = { platform.systemActions.openUrl(LINKEDIN_URL) },
                    onOpenGitHub = { platform.systemActions.openUrl(GITHUB_ORG_URL) },
                )

                Spacer(Modifier.height(16.dp))

                // ── Contribute ───────────────────────────────────────────────
                ContributeSection(onContribute = { platform.systemActions.openUrl(GITHUB_REPO_URL) })

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SocialLinksRow(
    onOpenInstagram: () -> Unit,
    onOpenLinkedIn: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        SocialIconButton(icon = LatchIcons.Instagram, contentDescription = "Instagram", onClick = onOpenInstagram)
        Spacer(Modifier.width(24.dp))
        SocialIconButton(icon = LatchIcons.LinkedIn, contentDescription = "LinkedIn", onClick = onOpenLinkedIn)
        Spacer(Modifier.width(24.dp))
        SocialIconButton(icon = LatchIcons.GitHub, contentDescription = "GitHub", onClick = onOpenGitHub)
    }
}

@Composable
private fun SocialIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun ContributeSection(onContribute: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "We welcome contributions. Visit our GitHub repository to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onContribute) {
            Icon(
                imageVector = LatchIcons.GitHub,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Contribute on GitHub")
        }
    }
}
