package org.sih.itantra.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.R
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.presentation.ui.theme.*
import org.sih.itantra.storage.UserProfile

/**
 * Cyberpunk Glassmorphic Red Tactical Command Top Bar.
 * Incorporates AKHET team crest, operator credentials,
 * private targeting vs broadcast mode, and 1-tap language switch.
 */
@Composable
fun TacticalTopBar(
    selectedLanguage: Language,
    isSosActive: Boolean,
    userProfile: UserProfile = UserProfile(),
    activeTargetNode: NetworkNode? = null,
    onOpenLanguage: () -> Unit,
    onTriggerSos: () -> Unit,
    onClearTarget: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sosTopPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberVoidBlack)
            .border(width = 0.5.dp, color = CyberBorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: AKHET Logo & Operator Call-Sign
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(CyberGlassSurface)
                        .border(1.5.dp, if (isSosActive) CyberNeonRed else CyberBorderRedBright, CircleShape)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_akhet_logo),
                        contentDescription = "AKHET Logo",
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = userProfile.username.uppercase(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberTextWhite,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isSosActive) CyberNeonRed else CyberMatrixGreen)
                        )
                    }

                    Text(
                        text = if (isSosActive) {
                            "CRITICAL SOS • TRANSMITTING"
                        } else if (activeTargetNode != null) {
                            "LINK: ${activeTargetNode.name}"
                        } else {
                            "MODE: BROADCAST ALL"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isSosActive) CyberNeonRedBright else if (activeTargetNode != null) CyberMatrixGreen else CyberTextDim
                    )
                }
            }

            // Right: Language Chip & Emergency Warning Trigger
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Language Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CyberGlassSurface)
                        .border(1.dp, CyberBorderRed, RoundedCornerShape(6.dp))
                        .clickable { onOpenLanguage() }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selectedLanguage.nativeName,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberNeonRedBright
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "(${selectedLanguage.code.uppercase()})",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.5.sp,
                            color = CyberTextDim
                        )
                    }
                }

                // SOS Trigger / Active Strobe
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (isSosActive) CyberNeonRed.copy(alpha = pulseAlpha) else CyberNeonRed.copy(alpha = 0.2f))
                        .border(1.dp, if (isSosActive) CyberNeonRedBright else CyberBorderRed, CircleShape)
                        .clickable { onTriggerSos() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "SOS",
                        tint = if (isSosActive) CyberTextWhite else CyberNeonRedBright,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Active Private Communication Banner
        if (activeTargetNode != null && !isSosActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberNeonRed.copy(alpha = 0.15f))
                    .padding(horizontal = 14.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PRIVATE LINK WITH [${activeTargetNode.name}] ACTIVE (Tap to revert to Broadcast)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    color = CyberNeonRedBright,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onClearTarget() }
                )
            }
        }
    }
}
