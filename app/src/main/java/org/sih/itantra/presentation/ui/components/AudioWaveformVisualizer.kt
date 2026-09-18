package org.sih.itantra.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.sih.itantra.presentation.ui.theme.BorderCyanHighlight
import org.sih.itantra.presentation.ui.theme.BorderGlass
import org.sih.itantra.presentation.ui.theme.IsroBlue
import org.sih.itantra.presentation.ui.theme.SignalCyanBright

/**
 * Animated audio waveform visualizer for real-time PCM microphone capture.
 * Renders pulsing bars with tactical gradient and corner accents.
 */
@Composable
fun AudioWaveformVisualizer(
    amplitudes: List<Float>,
    isRecording: Boolean,
    modifier: Modifier = Modifier,
    barColorStart: Color = SignalCyanBright,
    barColorEnd: Color = IsroBlue
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idlePulse"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(90.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f

            // 1. Tactical Frame & Grid line
            drawLine(
                color = BorderGlass,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1.dp.toPx()
            )

            // 2. Waveform Bars
            val barCount = amplitudes.size.coerceAtLeast(1)
            val barSpacing = 4.dp.toPx()
            val totalSpacing = barSpacing * (barCount - 1)
            val barWidth = ((width - totalSpacing) / barCount).coerceAtLeast(2f)

            val barBrush = Brush.verticalGradient(
                colors = listOf(barColorStart, barColorEnd),
                startY = 0f,
                endY = height
            )

            for (i in 0 until barCount) {
                val amp = if (isRecording) amplitudes[i] else idlePulse
                val barHeight = (amp * (height * 0.85f)).coerceIn(4f, height)

                val x = i * (barWidth + barSpacing)
                val topY = centerY - (barHeight / 2f)

                drawRoundRect(
                    brush = barBrush,
                    topLeft = Offset(x, topY),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }

            // 3. Tactical Corner Reticles
            val cornerLen = 12.dp.toPx()
            // Top Left
            drawLine(BorderCyanHighlight, Offset(0f, 0f), Offset(cornerLen, 0f), 2.dp.toPx())
            drawLine(BorderCyanHighlight, Offset(0f, 0f), Offset(0f, cornerLen), 2.dp.toPx())
            // Top Right
            drawLine(BorderCyanHighlight, Offset(width, 0f), Offset(width - cornerLen, 0f), 2.dp.toPx())
            drawLine(BorderCyanHighlight, Offset(width, 0f), Offset(width, cornerLen), 2.dp.toPx())
            // Bottom Left
            drawLine(BorderCyanHighlight, Offset(0f, height), Offset(cornerLen, height), 2.dp.toPx())
            drawLine(BorderCyanHighlight, Offset(0f, height), Offset(0f, height - cornerLen), 2.dp.toPx())
            // Bottom Right
            drawLine(BorderCyanHighlight, Offset(width, height), Offset(width - cornerLen, height), 2.dp.toPx())
            drawLine(BorderCyanHighlight, Offset(width, height), Offset(width, height - cornerLen), 2.dp.toPx())
        }
    }
}
