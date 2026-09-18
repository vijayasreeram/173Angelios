package org.sih.itantra.presentation.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.theme.*

/**
 * Screen 2: Onboarding & Mesh Architecture Screen.
 * Demonstrates the 4 core pillars of the SIH26173 solution:
 * 1. Zero Internet Dependency (Decentralized P2P)
 * 2. Semantic Communication (64KB -> 38B)
 * 3. Multi-hop Mesh Routing (Store-and-forward)
 * 4. Life-Critical Emergency SOS (Priority 0)
 */
@Composable
fun OnboardingScreen(
    repository: CommunicationRepository,
    onCompleteOnboarding: () -> Unit
) {
    var currentPage by remember { mutableIntStateOf(0) }

    val pages = listOf(
        OnboardingData(
            icon = Icons.Default.SignalCellularOff,
            tagline = "ZERO INTERNET DEPENDENCY",
            title = "Communicate without the internet.",
            description = "Disasters dismantle cellular towers. Angelios forms an autonomous, decentralised peer-to-peer tactical mesh instantly using BLE, Wi-Fi Direct, and LoRa."
        ),
        OnboardingData(
            icon = Icons.Default.Psychology,
            tagline = "SEMANTIC COMMUNICATION",
            title = "Your voice becomes meaning.",
            description = "Instead of transmitting bandwidth-heavy raw audio, edge NLP extracts structured intents and entities—shrinking 64,000-byte voice recordings to a 38-byte packet."
        ),
        OnboardingData(
            icon = Icons.Default.Hub,
            tagline = "ADAPTIVE MESH ROUTING",
            title = "Meaning travels through the strongest path.",
            description = "Multi-hop routing relays packets across intermediate survivor and responder phones with duplicate suppression and store-and-forward reliability."
        ),
        OnboardingData(
            icon = Icons.Default.Warning,
            tagline = "LIFE-CRITICAL PROTOCOL",
            title = "Emergency communication survives.",
            description = "One-touch SOS beacon overrides all queues, triggers hardware flash strobes, acoustic alarms, and broadcasts across all nearby nodes."
        )
    )

    val infiniteTransition = rememberInfiniteTransition(label = "beaconPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceNavy)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Aerospace Tag
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "iTANTRA // ONBOARDING // STEP ${currentPage + 1} OF 4",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = SignalCyanBright
            )

            TextButton(onClick = onCompleteOnboarding) {
                Text(
                    text = "SKIP",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDim
                )
            }
        }

        // Center Content with Tactical Graphic
        val page = pages[currentPage]
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            // Tactical Connected Globe / Beacon Canvas
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(CardNavyGlass)
                    .border(1.5.dp, BorderCyanHighlight, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(IsroBlueBright.copy(alpha = 0.35f * pulse), Color.Transparent),
                            center = center,
                            radius = size.width / 2
                        ),
                        radius = size.width / 2,
                        center = center
                    )
                }

                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = SignalCyanBright,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = page.tagline,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = WarningAmber,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = page.title,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = TextWhite,
                textAlign = TextAlign.Center,
                lineHeight = 30.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = page.description,
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                color = TextMuted,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }

        // Bottom Controls: Page Dots + Next Button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..3) {
                    Box(
                        modifier = Modifier
                            .size(if (i == currentPage) 20.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(if (i == currentPage) SignalCyanBright else BorderGlass)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (currentPage < 3) {
                        currentPage++
                    } else {
                        onCompleteOnboarding()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IsroBlueBright),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (currentPage < 3) "CONTINUE" else "SELECT LANGUAGE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.sp,
                    color = TextWhite
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = TextWhite,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

private data class OnboardingData(
    val icon: ImageVector,
    val tagline: String,
    val title: String,
    val description: String
)
