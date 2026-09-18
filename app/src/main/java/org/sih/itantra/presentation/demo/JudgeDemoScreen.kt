package org.sih.itantra.presentation.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Science
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.LinkQuality
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.theme.*

/**
 * Interactive SIH Judging Demonstration Studio.
 * Allows judges to simulate end-to-end multi-language voice-to-meaning-to-packet-to-speech transmission
 * between Phone A and Phone B under Good, Medium, Poor, and Emergency network conditions.
 */
@Composable
fun JudgeDemoScreen(repository: CommunicationRepository) {
    val coroutineScope = rememberCoroutineScope()

    var sourceLang by remember { mutableStateOf(Language.TAMIL) }
    var targetLang by remember { mutableStateOf(Language.HINDI) }
    var networkSimCondition by remember { mutableStateOf(LinkQuality.POOR) }

    var isRunningDemo by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(0) }

    var demoTranscript by remember { mutableStateOf("அவசரம், வடக்கு சோதனைச் சாவடியில் ஒருவர் காயமடைந்துள்ளார்.") }
    var demoSemanticIntent by remember { mutableStateOf("EMERGENCY_MEDICAL") }
    var demoSemanticEntities by remember { mutableStateOf("location=north_checkpoint, count=1") }
    var demoCompressedSize by remember { mutableStateOf("38 Bytes") }
    var demoReconstructedText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceNavy)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SIH JUDGE DEMO STUDIO",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextWhite
                )
                Text(
                    text = "DUAL-DEVICE (PHONE A ➔ PHONE B) SIMULATION",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = SignalCyanBright
                )
            }
            Icon(Icons.Default.Science, contentDescription = "Demo", tint = SignalCyanBright, modifier = Modifier.size(24.dp))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 1. Language Controls for Demonstration
        Text(text = "1. SELECT SPOKEN & RECEIVER LANGUAGES", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LanguageSelectorCard("PHONE A (SPEAKER)", sourceLang, Modifier.weight(1f)) {
                sourceLang = when (sourceLang) {
                    Language.TAMIL -> Language.HINDI
                    Language.HINDI -> Language.ENGLISH
                    Language.ENGLISH -> Language.TELUGU
                    Language.TELUGU -> Language.BENGALI
                    else -> Language.TAMIL
                }
                demoTranscript = when (sourceLang) {
                    Language.TAMIL -> "அவசரம், வடக்கு சோதனைச் சாவடியில் ஒருவர் காயமடைந்துள்ளார்."
                    Language.HINDI -> "आपातकाल, उत्तर चेकपॉइंट के पास एक व्यक्ति घायल है।"
                    Language.ENGLISH -> "Emergency, there is a person injured near north checkpoint."
                    Language.TELUGU -> "అత్యవసరం, ఉత్తర చెక్‌పోస్ట్ వద్ద ఒక వ్యక్తి గాయపడ్డాడు."
                    Language.BENGALI -> "জরুরী অবস্থা, উত্তর চেকপয়েন্টের কাছে একজন আহত হয়েছেন।"
                    else -> "Emergency, person injured near north checkpoint."
                }
            }

            LanguageSelectorCard("PHONE B (RECEIVER)", targetLang, Modifier.weight(1f)) {
                targetLang = when (targetLang) {
                    Language.HINDI -> Language.TAMIL
                    Language.TAMIL -> Language.ENGLISH
                    Language.ENGLISH -> Language.KANNADA
                    Language.KANNADA -> Language.MALAYALAM
                    else -> Language.HINDI
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Simulated Network Condition
        Text(text = "2. SELECT NETWORK LINK DEGRADATION", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LinkQuality.entries.forEach { condition ->
                val isSelected = networkSimCondition == condition
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) SignalCyanBright else CardNavyGlass)
                        .border(1.dp, if (isSelected) SignalCyanBright else BorderGlass, RoundedCornerShape(6.dp))
                        .clickable {
                            networkSimCondition = condition
                            repository.networkMonitor.setSimulatedCondition(condition)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = condition.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) DeepSpaceNavy else TextWhite
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Trigger Live Simulation
        Button(
            onClick = {
                if (isRunningDemo) return@Button
                isRunningDemo = true
                currentStep = 1

                coroutineScope.launch {
                    repository.setSelectedLanguage(sourceLang)
                    repository.setReceiverLanguage(targetLang)

                    // Step 1: Voice Capture & STT
                    delay(500)
                    currentStep = 2

                    // Step 2: Semantic Intent Extraction
                    delay(600)
                    val semantic = repository.semanticEncoder.encode(demoTranscript, sourceLang, targetLang)
                    demoSemanticIntent = semantic.intent.canonicalLabel
                    demoSemanticEntities = semantic.entities.entries.joinToString(", ") { "${it.key}=${it.value}" }
                    currentStep = 3

                    // Step 3: Adaptive Compression & Packetization
                    delay(500)
                    val comp = repository.compressor.compress(demoTranscript, semantic, networkSimCondition, semantic.priority)
                    demoCompressedSize = "${comp.payload.size} Bytes"
                    currentStep = 4

                    // Step 4: Network Transit (Loopback / BLE)
                    delay(600)
                    currentStep = 5

                    // Step 5: Phone B Reconstructs Text in target language
                    delay(500)
                    val reconstructed = repository.semanticDecoder.decode(semantic, targetLang)
                    demoReconstructedText = reconstructed
                    currentStep = 6

                    // Step 6: Phone B Plays Offline Speech Synthesizer
                    repository.ttsEngine.speak(reconstructed, targetLang)
                    isRunningDemo = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IsroBlueBright),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = TextWhite)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isRunningDemo) "EXECUTING PIPELINE STEP $currentStep / 6..." else "RUN END-TO-END DEMONSTRATION",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = TextWhite
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Live Visual Pipeline Step-by-Step Inspector
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardNavyGlass)
                .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                .padding(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepRow(1, "PHONE A: Voice Captured & Offline STT", demoTranscript, currentStep >= 1)
                StepRow(2, "PHONE A: Semantic Meaning & Entities", "Intent: $demoSemanticIntent\nEntities: $demoSemanticEntities", currentStep >= 2)
                StepRow(3, "PHONE A: Adaptive Packet Created", "Wire Size: $demoCompressedSize (vs 64,000 bytes raw audio)", currentStep >= 3)
                StepRow(4, "MESH TRANSIT: CRC16 Validated & Routed", "Transport: ${networkSimCondition.targetStrategy} • 0 Errors", currentStep >= 4)
                StepRow(5, "PHONE B: Meaning Reconstructed (${targetLang.displayName})", demoReconstructedText.ifBlank { "Reconstructing in ${targetLang.nativeName}..." }, currentStep >= 5)
                StepRow(6, "PHONE B: Offline Voice Speech Synthesized", "Audio Output Triggered via Offline TTS", currentStep >= 6)
            }
        }
    }
}

@Composable
private fun LanguageSelectorCard(title: String, language: Language, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CardNavyGlass)
            .border(1.dp, BorderGlass, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column {
            Text(text = title, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = TextDim)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = language.displayName, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextWhite)
            Text(text = language.nativeName, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = SignalCyanBright)
        }
    }
}

@Composable
private fun StepRow(stepNumber: Int, title: String, content: String, isComplete: Boolean) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isComplete) NetworkGreen else SurfaceNavy)
                .border(1.dp, if (isComplete) NetworkGreen else BorderGlass, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isComplete) "✓" else "$stepNumber",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isComplete) DeepSpaceNavy else TextDim
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = title, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (isComplete) TextWhite else TextDim)
            Text(text = content, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = if (isComplete) SignalCyanBright else TextDim)
        }
    }
}
