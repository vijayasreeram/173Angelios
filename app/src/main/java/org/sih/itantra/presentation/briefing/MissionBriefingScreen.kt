package org.sih.itantra.presentation.briefing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.presentation.ui.theme.*

/**
 * Screen 12: Mission Briefing & Tactical Poster Screen.
 * Presents the overall architecture, ISRO problem statement fulfillment,
 * and core tactical capabilities.
 */
@Composable
fun MissionBriefingScreen(
    onReturnToConsole: () -> Unit,
    onNavigateToDiagnostics: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceNavy)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Mission Badge
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(CardNavyGlass)
                .border(1.dp, BorderCyanHighlight, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(SignalCyanBright)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ISRO // SMART INDIA HACKATHON 2024",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SignalCyanBright,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Title & Mission Statement
        Text(
            text = "ANGELIOS",
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 28.sp,
            letterSpacing = 2.sp,
            color = TextWhite
        )

        Text(
            text = "People. Technology. A Safer Tomorrow.",
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = SignalCyanBright
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Autonomous peer-to-peer multilingual voice communication network for post-disaster tactical rescue operations without internet, cellular, or electrical infrastructure.",
            fontFamily = FontFamily.SansSerif,
            fontSize = 12.sp,
            color = TextMuted,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 6 Core Architectural Capabilities Grid
        Text(
            text = "CORE TACTICAL CAPABILITIES",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TextDim,
            letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.height(10.dp))

        val capabilities = listOf(
            CapabilityItem(
                icon = Icons.Default.Translate,
                title = "Multilingual Edge NLP",
                desc = "10 Indian languages with local on-device STT, semantic parser, and multi-voice offline TTS engine.",
                tag = "10 LANGUAGES"
            ),
            CapabilityItem(
                icon = Icons.Default.Hub,
                title = "Zero-Internet Mesh",
                desc = "Autonomous decentralized routing over BLE 5.0, Wi-Fi Direct, and LoRa with store-and-forward.",
                tag = "MULTI-HOP"
            ),
            CapabilityItem(
                icon = Icons.Default.Compress,
                title = "Semantic Compression",
                desc = "Reduces 64,000-byte raw voice audio recordings down to an ultra-compact 38-byte semantic packet.",
                tag = "99.4% SAVED"
            ),
            CapabilityItem(
                icon = Icons.Default.Warning,
                title = "Military SOS Protocol",
                desc = "Priority-zero emergency beacon overrides all traffic, triggering hardware flash strobes and sirens.",
                tag = "LIFE-CRITICAL"
            ),
            CapabilityItem(
                icon = Icons.Default.Security,
                title = "Hardware Keystore AES-256",
                desc = "End-to-end authenticated Galois/Counter Mode encryption safeguarding all tactical voice data.",
                tag = "FIPS READY"
            ),
            CapabilityItem(
                icon = Icons.Default.Sensors,
                title = "Autonomous Adaptation",
                desc = "Real-time link-quality score shifts between raw audio, deflate, semantic intents, and emergency modes.",
                tag = "BATTERY AWARE"
            )
        )

        capabilities.forEach { item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardNavyGlass)
                    .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(IsroBlue.copy(alpha = 0.25f))
                            .border(1.dp, SignalCyanBright.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = SignalCyanBright,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.title,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = TextWhite
                            )
                            Text(
                                text = item.tag,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = SignalCyanBright
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = item.desc,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 11.sp,
                            color = TextMuted,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Action Buttons
        Button(
            onClick = onReturnToConsole,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IsroBlueBright),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "RETURN TO TACTICAL CONSOLE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = TextWhite
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onNavigateToDiagnostics,
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalCyanBright),
            border = androidx.compose.foundation.BorderStroke(1.dp, SignalCyanBright.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "VIEW AUDIT & PERFORMANCE DIAGNOSTICS",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = SignalCyanBright
            )
        }
    }
}

private data class CapabilityItem(
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val tag: String
)
