package org.sih.itantra.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.presentation.ui.theme.*

/**
 * Tactical Emergency SOS Button with continuous slow pulse glow at idle,
 * high-alert strobe pulse when triggered, and hardware-accelerated transforms.
 */
@Composable
fun EmergencySlideButton(
    isActive: Boolean,
    onTriggerSos: () -> Unit,
    onCancelSos: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 1. Idle Continuous Glow Pulse (alpha 0.4 <-> 0.8, 2s, LinearEasing)
    val idleTransition = rememberInfiniteTransition(label = "idleSosPulse")
    val idleGlowAlpha by idleTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleGlowAlpha"
    )

    // 2. Active High-Alert Strobe Pulse
    val activeTransition = rememberInfiniteTransition(label = "activeSosStrobe")
    val pulseScale by activeTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sosPulseScale"
    )

    val currentScale = if (isActive) pulseScale else 1.0f
    val borderColor = if (isActive) CyberNeonRedBright else CyberNeonRed.copy(alpha = idleGlowAlpha)
    val backgroundBrush = if (isActive) {
        Brush.horizontalGradient(
            colors = listOf(CyberNeonRed, CyberNeonBloodOrange, CyberNeonRed)
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                CyberNeonRed.copy(alpha = idleGlowAlpha * 0.24f),
                CyberGlassSurface.copy(alpha = 0.95f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = currentScale
                scaleY = currentScale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundBrush)
            .border(2.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable {
                if (isActive) onCancelSos() else onTriggerSos()
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "SOS",
                tint = if (isActive) CyberTextWhite else CyberNeonRedBright,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isActive) "SOS ACTIVE - TAP TO CANCEL" else "TAP FOR EMERGENCY SOS BROADCAST",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = CyberTextWhite,
                letterSpacing = 0.5.sp
            )
        }
    }
}

