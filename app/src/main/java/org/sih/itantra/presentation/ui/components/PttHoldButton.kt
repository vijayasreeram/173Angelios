package org.sih.itantra.presentation.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.repository.CommunicationRepository.PttState
import org.sih.itantra.presentation.ui.theme.*

/**
 * Tactical Push-to-Talk (PTT) Button.
 * Provides intuitive press-and-hold gesture with spring animation, haptic feedback,
 * and state-reactive aerospace glow rings.
 */
@Composable
fun PttHoldButton(
    pttState: PttState,
    onPressStart: () -> Unit,
    onPressRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var isPressedDown by remember { mutableStateOf(false) }

    // 1. Mechanical Button Press Scale with Spring Overshoot on Release
    val buttonScale by animateFloatAsState(
        targetValue = if (isPressedDown) 0.96f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.42f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "pttButtonScale"
    )

    // 2. Tactical Mic Icon Scale (scales to 0.95 on press)
    val micScale by animateFloatAsState(
        targetValue = if (isPressedDown) 0.95f else 1.0f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pttMicScale"
    )

    val activeColor by animateColorAsState(
        targetValue = when (pttState) {
            PttState.IDLE -> CyberNeonRed
            PttState.LISTENING -> CyberLaserCyan
            PttState.PROCESSING -> CyberWarningAmber
            PttState.COMPRESSING -> CyberNeonRedBright
            PttState.TRANSMITTING -> CyberLaserCyan
            PttState.DELIVERED -> CyberMatrixGreen
            PttState.FAILED -> CyberWarningAmber
            PttState.EMERGENCY -> CyberNeonRedBright
        },
        animationSpec = tween(220),
        label = "pttColor"
    )

    // 3. Staggered Expanding Radar-Ping Ripple Loop
    val rippleTransition = rememberInfiniteTransition(label = "pttRipple")
    val ripple1 by rippleTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple1"
    )
    val ripple2 by rippleTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, delayMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple2"
    )

    val isTransmittingActive = isPressedDown ||
            pttState == PttState.LISTENING ||
            pttState == PttState.TRANSMITTING ||
            pttState == PttState.COMPRESSING

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(200.dp)
        ) {
            // Expanding Radar-Ping Ripples (Hardware-accelerated via graphicsLayer)
            if (isTransmittingActive) {
                listOf(ripple1, ripple2).forEach { progress ->
                    val rippleScale = 1.0f + (progress * 0.65f)
                    val rippleAlpha = ((1.0f - progress) * 0.7f).coerceIn(0f, 0.7f)
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .graphicsLayer {
                                scaleX = rippleScale
                                scaleY = rippleScale
                                alpha = rippleAlpha
                            }
                            .border(1.5.dp, activeColor, CircleShape)
                    )
                }
            }

            // Fixed Aerospace Border Ring
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .border(1.5.dp, activeColor.copy(alpha = 0.45f), CircleShape)
            )

            // Inner Interactive Tactile Button with Spring Overshoot
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(136.dp)
                    .graphicsLayer {
                        scaleX = buttonScale
                        scaleY = buttonScale
                    }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                activeColor.copy(alpha = 0.85f),
                                CyberCardDark
                            )
                        )
                    )
                    .border(2.dp, activeColor, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressedDown = true
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onPressStart()
                                tryAwaitRelease()
                                isPressedDown = false
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onPressRelease()
                            }
                        )
                    }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (pttState == PttState.EMERGENCY) Icons.Default.Warning else Icons.Default.Mic,
                        contentDescription = "Push To Talk",
                        tint = CyberTextWhite,
                        modifier = Modifier
                            .size(38.dp)
                            .graphicsLayer {
                                scaleX = micScale
                                scaleY = micScale
                            }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isPressedDown) "TRANSMITTING" else "HOLD PTT",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        color = CyberTextWhite
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Dynamic State Badge with Smooth Transition
        Text(
            text = when (pttState) {
                PttState.IDLE -> "READY (HOLD TO TALK)"
                PttState.LISTENING -> "● LISTENING..."
                PttState.PROCESSING -> "EXTRACTING MEANING..."
                PttState.COMPRESSING -> "ADAPTIVE COMPRESSION..."
                PttState.TRANSMITTING -> "TRANSMITTING PACKET..."
                PttState.DELIVERED -> "✓ PACKET DELIVERED (ACK)"
                PttState.FAILED -> "⚠ RETRYING TRANSMISSION..."
                PttState.EMERGENCY -> "⚡ CRITICAL SOS BROADCAST"
            },
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = activeColor
        )
    }
}
