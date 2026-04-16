package com.vinnovateit.latch.features.about

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vinnovateit.latch.R
import com.vinnovateit.latch.common.util.TooltipHint

data class TeamMember(
    val name: String,
    val role: String,
    val imageRes: Int,
    val githubUrl: String,
    val linkedinUrl: String
)

val teamMembers = listOf(
    TeamMember("SOUMOJIT", "Project Manager", R.drawable.syro, "https://github.com/soumojit2004", "https://linkedin.com/in/soumojit-ganguly"),
    TeamMember("AYUSH KUMAR", "Tech Head", R.drawable.ayush1, "https://github.com/AyushK0808", "https://linkedin.com/in/ayush-kumar-061a58251"),
    TeamMember("AYUSH KUMAR", "Projects Head", R.drawable.ayush2, "https://github.com/thecoder-001", "https://linkedin.com/in/ayush-kumar-cs"),
    TeamMember("MIHIR JOSHI", "Creative Head", R.drawable.mihir, "https://github.com/J-Mihir", "https://linkedin.com/in/mihir-shekhar-joshi"),
    TeamMember("LAKSHYA", "Developer", R.drawable.lakshya, "https://github.com/2005lakshya", "https://linkedin.com/in/lakshya-gupta2005"),
    TeamMember("SARTHAK", "Developer", R.drawable.sarthak, "https://github.com/SarthakMiglani", "https://www.linkedin.com/in/sarthak--miglani"),
    TeamMember("TANMOY SAHA", "Developer", R.drawable.tanmoy, "https://github.com/TSaha4", "https://linkedin.com/in/tanmoy-saha-4b0ab228a"),
    TeamMember("LAVAN", "Developer", R.drawable.lavan, "https://github.com/lavan8t", "https://linkedin.com/in/lavan8t"),
    TeamMember("VIVEK VATTEM", "Designer", R.drawable.vivek, "https://github.com/vivekvattem", "https://www.linkedin.com/in/vivek-vattem-3102662a8"),
    TeamMember("ARYAMAN", "Designer", R.drawable.aryaman, "https://github.com/aryamanbhatia1", "https://linkedin.com/in/aryaman-bhatia-97b99b256"),
    TeamMember("ARCHIT NIGAM", "Designer", R.drawable.archit, "https://github.com/architnigam", "https://linkedin.com/in/archit-nigam-a18895314"),
    TeamMember("KRISH MEHTA", "Project Manager", R.drawable.krish, "https://github.com/krxsh007", "http://www.linkedin.com/in/krishmmehta-0t")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetTheTeamPage(onBackClick: () -> Unit) {
    Scaffold { innerPadding ->
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current

        Box(modifier = Modifier.fillMaxSize()) {
            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    // Apply content padding to push the start of the list below the TopBar area
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Spacer to push the vinnovate logo down, leaving room for the absolutely positioned back button
                Spacer(modifier = Modifier.height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp))

                // Centered Vinnovate Logo with cropped height
                Box(
                    modifier = Modifier
                        .height(70.dp) // your cropped height
                        .width(200.dp), // keep width
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_vinnovateit),
                        contentDescription = "Vinnovate Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                val intent = Intent(Intent.ACTION_VIEW, "https://vinnovateit.com".toUri())
                                context.startActivity(intent)
                            },
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                }

                // Social Media Logo
                Row(
                    horizontalArrangement = Arrangement.spacedBy(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 30.dp, bottom = 30.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.linkedin),
                        contentDescription = "LinkedIn",
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                val intent = Intent(Intent.ACTION_VIEW, "https://www.linkedin.com/company/v-innovate-it/".toUri())
                                context.startActivity(intent)
                            },
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                    Image(
                        painter = painterResource(id = R.drawable.github),
                        contentDescription = "GitHub",
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                val intent = Intent(Intent.ACTION_VIEW, "https://github.com/vinnovateit".toUri())
                                context.startActivity(intent)
                            },
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                    Image(
                        painter = painterResource(id = R.drawable.instagram),
                        contentDescription = "Instagram",
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                val intent = Intent(Intent.ACTION_VIEW, "https://www.instagram.com/vinnovateit/".toUri())
                                context.startActivity(intent)
                            },
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                }

                // Heading
                Text(
                    text = "Meet The Team",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily(Font(R.font.outfit_variable))
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )

                Spacer(modifier = Modifier.height(30.dp))

                // Team Member Cards
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

                Column(
                    modifier = Modifier.padding(horizontal = if (isLandscape) 32.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isLandscape) 10.dp else 20.dp)
                ) {
                    teamMembers.chunked(if (isLandscape) 3 else 2).forEach { rowMembers ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(if (isLandscape) 15.dp else 25.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowMembers.forEach { member ->
                                TeamMemberCard(
                                    teamMember = member,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowMembers.size < (if (isLandscape) 3 else 2)) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                ContributingSection()
                Spacer(modifier = Modifier.height(100.dp))
            }

            // Protective Status Bar Gradient Scrim
            // Fades from solid surface color at the top to transparent at the bottom
            val surfaceColor = MaterialTheme.colorScheme.surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                surfaceColor,
                                surfaceColor.copy(alpha = 0.8f),
                                Color.Transparent
                            )
                        )
                    )
            )

            TooltipHint(tooltipText = "Back") {
                FilledIconButton(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 12.dp, top = 4.dp)
                        .size(40.dp)
                        .clip(CircleShape),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onBackClick()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun TeamMemberCard(
    teamMember: TeamMember,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Card(
        modifier = modifier.graphicsLayer(
            shadowElevation = 20f,
            clip = true
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = teamMember.imageRes),
                contentDescription = "Profile picture of ${teamMember.name}",
                modifier = Modifier
                    .size(160.dp)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    ),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = teamMember.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily(Font(R.font.moderniz)),
                    fontSize = 10.sp
                ),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = teamMember.role,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily(Font(R.font.satoshi_regular))
                ),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val intent = Intent(Intent.ACTION_VIEW, teamMember.githubUrl.toUri())
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.github),
                        contentDescription = "GitHub of ${teamMember.name}",
                        modifier = Modifier.size(25.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            val intent = Intent(Intent.ACTION_VIEW, teamMember.linkedinUrl.toUri())
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.linkedin),
                        contentDescription = "LinkedIn of ${teamMember.name}",
                        modifier = Modifier.size(25.dp),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
}

@Composable
fun ContributingSection() {
    val context = LocalContext.current
    Spacer(modifier = Modifier.height(30.dp))

    // Heading
    Text(
        text = "Contribute",
        style = MaterialTheme.typography.headlineMedium.copy(
            fontFamily = FontFamily(Font(R.font.outfit_variable))
        ),
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Box(
        modifier = Modifier
            .width(150.dp)
            .height(3.dp)
            .background(MaterialTheme.colorScheme.primary)
    )

    Spacer(modifier = Modifier.height(20.dp))

    // Simple description
    Text(
        text = "We welcome contributions. Visit our GitHub repository to get started.",
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily(Font(R.font.satoshi_regular))
        ),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
    )

    // GitHub button
    OutlinedButton(
        onClick = {
            val intent = Intent(
                Intent.ACTION_VIEW,
                "https://github.com/vinnovateit/auto-net-connector".toUri()
            )
            context.startActivity(intent)
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            painter = painterResource(id = R.drawable.github),
            contentDescription = "GitHub",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Contribute on GitHub")
    }
}
