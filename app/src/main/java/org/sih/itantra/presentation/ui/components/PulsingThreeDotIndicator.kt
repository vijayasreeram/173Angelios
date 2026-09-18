package org.sih.itantra.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.sih.itantra.presentation.ui.theme.CyberNeonRedBright

/**
 * Tactical inline pulsing three-dot indicator.
 * Provides smooth 120fps staggered pulsing without layout shifts.
 */
@Composable
fun PulsingThreeDotIndicator(
    modifier: Modifier = Modifier,
    color: Color = CyberNeonRedBright,
    dotSize: Dp = 6.dp,
    spacing: Dp = 4.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "threeDotPulse")

    val dot0Scale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot0"
    )

    val dot1Scale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )

    val dot2Scale by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DotItem(size = dotSize, color = color, scale = dot0Scale)
        DotItem(size = dotSize, color = color, scale = dot1Scale)
        DotItem(size = dotSize, color = color, scale = dot2Scale)
    }
}

@Composable
private fun DotItem(
    size: Dp,
    color: Color,
    scale: Float
) {
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = (0.35f + (scale * 0.65f)).coerceIn(0f, 1f)
            }
            .clip(CircleShape)
            .background(color)
    )
}
