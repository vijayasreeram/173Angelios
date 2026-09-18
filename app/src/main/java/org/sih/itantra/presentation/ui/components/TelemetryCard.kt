package org.sih.itantra.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.model.LinkMetrics
import org.sih.itantra.domain.model.LinkQuality
import org.sih.itantra.presentation.ui.theme.*

/**
 * Aerospace Telemetry Card.
 * Presents real-time RF radio metrics and adaptive communication strategy.
 */
@Composable
fun TelemetryCard(
    metrics: LinkMetrics,
    modifier: Modifier = Modifier
) {
    val qualityColor = when (metrics.linkQuality) {
        LinkQuality.GOOD -> NetworkGreen
        LinkQuality.MEDIUM -> WarningAmber
        LinkQuality.POOR -> SignalCyan
        LinkQuality.EMERGENCY -> EmergencyRed
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardNavyGlass)
            .border(1.dp, BorderGlass, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Column {
            // Header Row: Transport + Quality Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(qualityColor, RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = metrics.activeTransport.label.uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(qualityColor.copy(alpha = 0.15f))
                        .border(1.dp, qualityColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${metrics.linkQuality.label} LINK (${(metrics.linkQualityScore * 100).toInt()}%)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = qualityColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4 Grid Columns of Telemetry Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricItem("RSSI", "${metrics.rssi} dBm", qualityColor)
                MetricItem("LATENCY", "${metrics.latencyMs} ms", TextWhite)
                MetricItem("LOSS", "${String.format("%.1f", metrics.packetLossPct)}%", if (metrics.packetLossPct > 5) WarningAmber else TextWhite)
                MetricItem("BATTERY", "${metrics.batteryPct}%", if (metrics.batteryPct < 20) EmergencyRed else NetworkGreen)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Strategy Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceNavy)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ADAPTIVE STRATEGY:",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextMuted
                )
                Text(
                    text = metrics.linkQuality.targetStrategy,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = SignalCyanBright
                )
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, valueColor: Color) {
    Column {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = TextDim
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}
