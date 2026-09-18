package org.sih.itantra.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.presentation.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tactical Radar Sweep Visualizer.
 * Constant-velocity 120fps sweep with trailing phosphor bloom, range rings,
 * and interactive nearby mesh nodes.
 */
@Composable
fun RadarMeshSweep(
    nodes: List<NetworkNode>,
    selectedNodeId: String?,
    onNodeSelected: (NetworkNode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Constant-velocity 120fps continuous sweep
    val infiniteTransition = rememberInfiniteTransition(label = "radarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweepAngle"
    )

    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )

    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#00FF9D")
            isFakeBoldText = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    val whiteTextPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isFakeBoldText = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    val centerPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FF2E5E")
            isFakeBoldText = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
    }
    val dashedEffect = remember {
        PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(nodes) {
                    detectTapGestures { tapOffset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val maxRadius = minOf(size.width, size.height) * 0.45f
                        nodes.forEachIndexed { index, node ->
                            val angleDeg = (index * (360f / maxOf(nodes.size, 1)) + 45f)
                            val rad = Math.toRadians(angleDeg.toDouble())
                            val distanceRatio = (0.35f + (index * 0.18f)).coerceIn(0.2f, 0.85f)
                            val nodePos = Offset(
                                x = (center.x + (maxRadius * distanceRatio) * cos(rad)).toFloat(),
                                y = (center.y + (maxRadius * distanceRatio) * sin(rad)).toFloat()
                            )
                            val distance = (tapOffset - nodePos).getDistance()
                            if (distance < 30.dp.toPx()) {
                                onNodeSelected(node)
                            }
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = minOf(size.width, size.height) * 0.45f

            // 1. Concentric Range Rings (25%, 50%, 75%, 100%)
            for (step in 1..4) {
                val r = (maxRadius / 4f) * step
                drawCircle(
                    color = CyberBorderRed.copy(alpha = 0.4f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = dashedEffect)
                )
            }

            // Crosshairs
            drawLine(
                color = CyberBorderRed.copy(alpha = 0.35f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = CyberBorderRed.copy(alpha = 0.35f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            // 2. Trailing Phosphor Radar Sector Bloom (Constant Velocity)
            rotate(degrees = sweepAngle, pivot = center) {
                drawArc(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, CyberNeonRed.copy(alpha = 0.22f)),
                        startX = center.x,
                        endX = center.x + maxRadius
                    ),
                    startAngle = -42f,
                    sweepAngle = 42f,
                    useCenter = true,
                    topLeft = Offset(center.x - maxRadius, center.y - maxRadius),
                    size = Size(maxRadius * 2, maxRadius * 2)
                )
            }

            // Leading Radar Sweep Beam
            val sweepRad = Math.toRadians(sweepAngle.toDouble())
            val beamEnd = Offset(
                x = (center.x + maxRadius * cos(sweepRad)).toFloat(),
                y = (center.y + maxRadius * sin(sweepRad)).toFloat()
            )
            drawLine(
                brush = Brush.radialGradient(
                    colors = listOf(CyberNeonRedBright, CyberNeonRed.copy(alpha = 0.2f)),
                    center = center,
                    radius = maxRadius
                ),
                start = center,
                end = beamEnd,
                strokeWidth = 2.dp.toPx()
            )

            // 3. Render Mesh Nodes at Polar Offsets
            nodes.forEachIndexed { index, node ->
                val angleDeg = (index * (360f / maxOf(nodes.size, 1)) + 45f)
                val rad = Math.toRadians(angleDeg.toDouble())
                val distanceRatio = (0.35f + (index * 0.18f)).coerceIn(0.2f, 0.85f)
                val nodePos = Offset(
                    x = (center.x + (maxRadius * distanceRatio) * cos(rad)).toFloat(),
                    y = (center.y + (maxRadius * distanceRatio) * sin(rad)).toFloat()
                )

                val isSelected = node.id == selectedNodeId
                val nodeColor = when {
                    node.rssi > -70 -> CyberMatrixGreen
                    node.rssi > -85 -> CyberLaserCyan
                    else -> CyberNeonRedBright
                }

                // Pulsing ring around node
                drawCircle(
                    color = nodeColor.copy(alpha = (1f - pulseRadius) * 0.6f),
                    radius = 8.dp.toPx() + (pulseRadius * 12.dp.toPx()),
                    center = nodePos,
                    style = Stroke(1.5.dp.toPx())
                )

                // Node Center Dot
                drawCircle(
                    color = if (isSelected) Color.White else nodeColor,
                    radius = if (isSelected) 6.dp.toPx() else 4.5.dp.toPx(),
                    center = nodePos
                )

                // Draw Username Label right beside the radar dot (zero allocations per frame)
                drawContext.canvas.nativeCanvas.apply {
                    val paintToUse = if (isSelected) whiteTextPaint else textPaint
                    paintToUse.textSize = 9.5.dp.toPx()
                    drawText(node.name, nodePos.x + 8.dp.toPx(), nodePos.y + 3.dp.toPx(), paintToUse)
                }
            }

            // 4. Central Node (Local Phone)
            drawCircle(
                color = CyberNeonRedBright,
                radius = 5.dp.toPx(),
                center = center
            )
            drawCircle(
                color = CyberNeonRedGlow,
                radius = 11.dp.toPx(),
                center = center,
                style = Stroke(1.5.dp.toPx())
            )
            drawContext.canvas.nativeCanvas.apply {
                centerPaint.textSize = 8.5.dp.toPx()
                drawText("YOU", center.x + 8.dp.toPx(), center.y + 3.dp.toPx(), centerPaint)
            }
        }
    }
}

