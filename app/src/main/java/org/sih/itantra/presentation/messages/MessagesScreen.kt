package org.sih.itantra.presentation.messages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.model.ChatMessage
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Communication History Screen.
 * Provides clean tactical messaging bubbles with expandable SIH Judge / Developer Technical Details.
 */
@Composable
fun MessagesScreen(repository: CommunicationRepository) {
    val messages by repository.messages.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceNavy)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "COMMUNICATION HISTORY",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextWhite
                )
                Text(
                    text = "AES-256 ENCRYPTED LOCAL LOGS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = SignalCyanBright
                )
            }

            Text(
                text = "${messages.size} PACKETS",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextDim
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("NO TRANSMISSIONS LOGGED", fontFamily = FontFamily.Monospace, color = TextDim, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg ->
                    MessageCard(
                        message = msg,
                        onPlayTts = { repository.ttsEngine.speak(msg.reconstructedSpeechText, msg.language) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    message: ChatMessage,
    onPlayTts: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val timeStr = remember(message.timestamp) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestamp))
    }

    val priorityColor = when (message.priority) {
        Priority.EMERGENCY -> EmergencyRed
        Priority.IMPORTANT -> WarningAmber
        Priority.NORMAL -> SignalCyan
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardNavyGlass)
            .border(1.dp, if (message.priority == Priority.EMERGENCY) EmergencyRed else BorderGlass, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Column {
            // Header Row: Sender + Time + Priority Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.senderName,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = if (message.isIncoming) SignalCyanBright else TextWhite
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• $timeStr",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextDim
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(priorityColor.copy(alpha = 0.15f))
                        .border(1.dp, priorityColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = message.priority.label,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = priorityColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Message Content
            Text(
                text = message.text,
                fontSize = 14.sp,
                color = TextWhite,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Sub-row: Audio synthesis trigger + Technical Details Accordion Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onPlayTts() }
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Speak",
                        tint = SignalCyanBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "PLAY OFFLINE TTS",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = SignalCyanBright
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Text(
                        text = if (isExpanded) "HIDE TELEMETRY" else "TECHNICAL DETAILS",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextDim
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle Details",
                        tint = TextDim,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Expandable Technical Telemetry Drawer (For Judges / Developers)
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceNavy)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "JUDGE / DEVELOPER TELEMETRY BREAKDOWN",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SignalCyanBright
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    TelemetryRow("STT Inference Time:", "${message.telemetry.sttDurationMs} ms")
                    TelemetryRow("Semantic NLP Parse:", "${message.telemetry.semanticDurationMs} ms")
                    TelemetryRow("Adaptive Compression:", "${message.telemetry.compressionDurationMs} ms")
                    TelemetryRow("Mesh Radio Transit:", "${message.telemetry.networkTransitDurationMs} ms")
                    TelemetryRow("Receiver Reassembly:", "${message.telemetry.reassemblyDurationMs} ms")
                    TelemetryRow("Offline TTS Synthesis:", "${message.telemetry.ttsDurationMs} ms")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = BorderGlass)
                    TelemetryRow("Total Pipeline Latency:", "${message.telemetry.totalLatencyMs} ms", isHighlight = true)
                    TelemetryRow("Packet Payload Size:", "${message.telemetry.payloadBytes} Bytes")
                    TelemetryRow("Raw Audio Equivalent:", "${message.telemetry.rawEquivalentBytes} Bytes")
                    TelemetryRow("Bandwidth Reduction:", "${String.format("%.2f", message.telemetry.compressionRatioPct)}%", isHighlight = true)
                    TelemetryRow("Active Transport:", message.transport.label)
                }
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String, isHighlight: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim)
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = if (isHighlight) FontWeight.Bold else FontWeight.Normal,
            color = if (isHighlight) NetworkGreen else TextWhite
        )
    }
}
