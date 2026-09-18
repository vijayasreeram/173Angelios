package org.sih.itantra.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.components.*
import org.sih.itantra.presentation.ui.theme.*

@Composable
fun HomeScreen(
    repository: CommunicationRepository,
    onNavigateToTalk: () -> Unit,
    onNavigateToEmergency: () -> Unit
) {
    val linkMetrics by repository.linkMetrics.collectAsState()
    val pttState by repository.pttState.collectAsState()
    val selectedLanguage by repository.selectedLanguage.collectAsState()
    val waveformSamples by repository.audioCaptureManager.waveformSamples.collectAsState()
    val isRecording by repository.audioCaptureManager.isRecording.collectAsState()
    val recentMessages by repository.messages.collectAsState()
    val isSosActive by repository.emergencyManager.isSosActive.collectAsState()

    var showLanguageDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceNavy)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 1. Tactical Command Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ANGELIOS",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    letterSpacing = 1.sp,
                    color = TextWhite
                )
                Text(
                    text = "ISRO-GRADE TACTICAL COMMS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = SignalCyanBright,
                    letterSpacing = 0.5.sp
                )
            }

            // Language Selector Chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardNavyGlass)
                    .border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
                    .clickable { showLanguageDialog = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "Language",
                        tint = SignalCyanBright,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = selectedLanguage.nativeName,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextWhite
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Telemetry Card: "Can I communicate right now?"
        TelemetryCard(metrics = linkMetrics)

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Audio Waveform Monitor
        Text(
            text = "SPEECH ENCODING WAVEFORM",
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = TextDim,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        AudioWaveformVisualizer(
            amplitudes = waveformSamples,
            isRecording = isRecording
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Central PTT Interaction
        PttHoldButton(
            pttState = pttState,
            onPressStart = { repository.startPttCapture() },
            onPressRelease = { repository.stopPttCaptureAndSend() },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Emergency SOS Quick Banner
        EmergencySlideButton(
            isActive = isSosActive,
            onTriggerSos = { repository.triggerEmergencySos() },
            onCancelSos = { repository.stopEmergencySos() }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 6. Recent Transmission Preview
        if (recentMessages.isNotEmpty()) {
            val lastMsg = recentMessages.first()
            Text(
                text = "LAST RECEIVED TRANSMISSION",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextDim
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(CardNavyGlass)
                    .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = lastMsg.senderName.uppercase(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SignalCyanBright
                        )
                        Text(
                            text = lastMsg.status.name,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = NetworkGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = lastMsg.text,
                        fontSize = 13.sp,
                        color = TextWhite,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    // Language Selection Modal
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = {
                Text(
                    text = "SELECT LANGUAGE (10 INDIAN LANGUAGES)",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = SignalCyanBright
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Language.entries.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    repository.setSelectedLanguage(lang)
                                    showLanguageDialog = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = lang.displayName, color = TextWhite, fontSize = 14.sp)
                            Text(text = lang.nativeName, color = SignalCyanBright, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = BorderGlass)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text("CLOSE", color = SignalCyanBright)
                }
            },
            containerColor = CardNavy
        )
    }
}
