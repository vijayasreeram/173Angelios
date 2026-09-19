package org.sih.itantra.presentation.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.components.AudioWaveformVisualizer
import org.sih.itantra.presentation.ui.components.PttHoldButton
import org.sih.itantra.presentation.ui.components.StaggeredEntrance
import org.sih.itantra.presentation.ui.theme.*

/**
 * Dedicated General Communication Screen.
 * Non-Emergency Tactical Voice & Text Pipeline with Real-Time
 * Arbitrary English-to-Indian Language Translation (Tamil, Hindi, etc.).
 */
@Composable
fun TalkScreen(repository: CommunicationRepository) {
    val pttState by repository.pttState.collectAsState()
    val selectedLanguage by repository.selectedLanguage.collectAsState()
    val targetNode by repository.activeTargetNode.collectAsState()
    val linkMetrics by repository.linkMetrics.collectAsState()
    val waveformSamples by repository.speechManager.waveformSamples.collectAsState()
    val isRecording = pttState == CommunicationRepository.PttState.LISTENING
    val amplitude by repository.speechManager.audioRms.collectAsState()
    val durationMs by repository.speechManager.recordingDurationMs.collectAsState()
    val liveTranscript by repository.speechManager.liveTranscript.collectAsState()
    val messages by repository.messages.collectAsState()
    val userProfile by repository.userProfile.collectAsState()
    val serverStatus by repository.edgeServerStatus.collectAsState()

    var customMessageText by remember { mutableStateOf("") }

    // Dynamic Language-Adaptive Tactical Presets
    // Dynamic Language-Adaptive Tactical Presets (User Requested: Where are you, We are safe, We are good to go)
    val quickPresets = remember(selectedLanguage) {
        when (selectedLanguage) {
            Language.TAMIL -> listOf(
                "நீங்கள் எங்கே இருக்கிறீர்கள்?" to "[இடம்] எங்கே உள்ளீர்கள்?",
                "நாங்கள் பாதுகாப்பாக இருக்கிறோம்" to "[நலம்] நாங்கள் பாதுகாப்பாக உள்ளோம்",
                "நாங்கள் செல்ல தயாராக உள்ளோம்" to "[தயார்] நாங்கள் செல்லலாம்",
                "எங்களுக்கு குடிநீர் மற்றும் உணவு தேவைப்படுகிறது" to "[உணவு] குடிநீர் & உணவு தேவை",
                "மருத்துவக் குழு மற்றும் மருத்துவர் தேவை" to "[மருத்துவம்] மருத்துவர் தேவை",
                "சாலை அடைக்கப்பட்டுள்ளது இந்த வழியில் யாரும் வர வேண்டாம்" to "[எச்சரிக்கை] பாதை அடைப்பு",
                "நாங்கள் உங்களுக்கு உதவ வருகிறோம்" to "[உதவி] உதவ வருகிறோம்",
                "புரிந்தது செய்தி உறுதி செய்யப்பட்டது" to "[உறுதி] செய்தி புரிந்தது"
            )
            Language.HINDI -> listOf(
                "आप कहाँ हैं?" to "[स्थान] आप कहाँ हैं?",
                "हम सुरक्षित हैं" to "[सुरक्षा] हम सुरक्षित हैं",
                "हम आगे बढ़ने के लिए तैयार हैं" to "[तैयार] हम तैयार हैं",
                "हमें भोजन और पानी की आवश्यकता है" to "[राशन] भोजन व पानी चाहिए",
                "चिकित्सा दल की तत्काल आवश्यकता है" to "[चिकित्सा] चिकित्सा दल चाहिए",
                "सड़क अवरुद्ध है, इस तरफ न आएं" to "[चेतावनी] सड़क अवरुद्ध",
                "हम मदद के लिए आ रहे हैं" to "[सहायता] मदद को आ रहे हैं",
                "समझ गया, संदेश प्राप्त हुआ" to "[पुष्टि] संदेश प्राप्त हुआ"
            )
            Language.TELUGU -> listOf(
                "మీరు ఎక్కడ ఉన్నారు?" to "[స్థానం] ఎక్కడ ఉన్నారు?",
                "మేము సురక్షితంగా ఉన్నాము" to "[రక్షణ] మేము సురక్షితం",
                "మేము వెళ్ళడానికి సిద్ధంగా ఉన్నాము" to "[సిద్ధం] వెళ్ళడానికి సిద్ధం",
                "మాకు ఆహారం మరియు నీరు అవసరం" to "[ఆహారం] ఆహారం & నీరు",
                "వైద్య బృందం అవసరం" to "[వైద్యం] వైద్య బృందం",
                "రహదారి మూసివేయబడింది, ఈ మార్గంలో రావద్దు" to "[హెచ్చరిక] రహదారి మూసివేయబడింది",
                "మేము సహాయం చేయడానికి వస్తున్నాము" to "[సహాయం] వస్తున్నాము",
                "అర్థమైంది" to "[ధృవీకరణ] అర్థమైంది"
            )
            Language.KANNADA -> listOf(
                "ನೀವು ಎಲ್ಲಿದ್ದೀರಿ?" to "[ಸ್ಥಳ] ಎಲ್ಲಿದ್ದೀರಿ?",
                "ನಾವು ಸುರಕ್ಷಿತವಾಗಿದ್ದೇವೆ" to "[ಕ್ಷೇಮ] ಸುರಕ್ಷಿತವಾಗಿದ್ದೇವೆ",
                "ನಾವು ಹೊರಡಲು ಸಿದ್ಧರಾಗಿದ್ದೇವೆ" to "[ಸಿದ್ಧ] ಹೊರಡಲು ಸಿದ್ಧ",
                "ನಮಗೆ ಆಹಾರ ಮತ್ತು ನೀರು ಬೇಕು" to "[ಆಹಾರ] ಆಹಾರ & ನೀರು",
                "ವೈದ್ಯಕೀಯ ನೆರವು ಬೇಕು" to "[ವೈದ್ಯಕೀಯ] ನೆರವು ಬೇಕು",
                "ರಸ್ತೆ ಬಂದ್ ಆಗಿದೆ, ಈ ಕಡೆ ಬರಬೇಡಿ" to "[ಎಚ್ಚರಿಕೆ] ರಸ್ತೆ ಬಂದ್",
                "ನಾವು ಸಹಾಯಕ್ಕೆ ಬರುತ್ತಿದ್ದೇವೆ" to "[ಸಹಾಯ] ಬರುತ್ತಿದ್ದೇವೆ",
                "ತಿಳಿಯಿತು" to "[ಖಚಿತ] ತಿಳಿಯಿತು"
            )
            Language.MALAYALAM -> listOf(
                "നിങ്ങൾ എവിടെയാണ്?" to "[സ്ഥലം] എവിടെയാണ്?",
                "ഞങ്ങൾ സുരക്ഷിതരാണ്" to "[സുരക്ഷ] സുരക്ഷിതരാണ്",
                "ഞങ്ങൾ പുറപ്പെടാൻ തയ്യാറാണ്" to "[തയ്യാറാണ്] പോകാൻ തയ്യാറാണ്",
                "ഞങ്ങൾക്ക് ഭക്ഷണവും വെള്ളവും വേണം" to "[ഭക്ഷണം] ഭക്ഷണവും വെള്ളവും",
                "മെഡിക്കൽ ടീം ആവശ്യമാണ്" to "[ചികിത്സ] മെഡിക്കൽ ടീം",
                "റോഡ് തടസ്സപ്പെട്ടിരിക്കുന്നു, ഈ വഴി വരരുത്" to "[മുന്നറിയിപ്പ്] വഴി തടസ്സപ്പെട്ടു",
                "ഞങ്ങൾ സഹായിക്കാൻ വരുന്നു" to "[സഹായം] സഹായിക്കാൻ വരുന്നു",
                "മനസ്സിലായി" to "[സ്ഥിരീകരണം] മനസ്സിലായി"
            )
            Language.ODIA -> listOf(
                "ଆପଣ କେଉଁଠାରେ ଅଛନ୍ତି?" to "[ସ୍ଥାନ] ଆପଣ କେଉଁଠି?",
                "ଆମେ ସୁରକ୍ଷିତ ଅଛୁ" to "[ସୁରକ୍ଷା] ଆମେ ସୁରକ୍ଷିତ",
                "ଆମେ ଯିବା ପାଇଁ ପ୍ରସ୍ତୁତ" to "[ପ୍ରସ୍ତୁତ] ଆମେ ପ୍ରସ୍ତୁତ",
                "ଆମକୁ ଖାଦ୍ୟ ଏବଂ ପାଣି ଦରକାର" to "[ଖାଦ୍ୟ] ଖାଦ୍ୟ ଓ ପାଣି",
                "ଡାକ୍ତରୀ ଦଳ ଆବଶ୍ୟକ" to "[ଚିକିତ୍ସା] ଡାକ୍ତରୀ ଦଳ",
                "ରାସ୍ତା ବନ୍ଦ ଅଛି, ଏହି ବାଟରେ ଆସନ୍ତୁ ନାହିଁ" to "[ଚେତାବନୀ] ରାସ୍ତା ବନ୍ଦ",
                "ଆମେ ସାହାଯ୍ୟ ପାଇଁ ଆସୁଛୁ" to "[ସହାୟତା] ସାହାଯ୍ୟ ପାଇଁ ଆସୁଛୁ",
                "ବୁଝିପାରିଲି" to "[ସ୍ପଷ୍ଟ] ବୁଝିପାରିଲି"
            )
            else -> listOf(
                "Where are you" to "[LOC] Where Are You",
                "We are safe" to "[SAFE] We Are Safe",
                "We are good to go" to "[READY] We Are Good To Go",
                "We need food and water" to "[SUPPLY] Food & Water",
                "Medical team needed" to "[MEDIC] Doctor Needed",
                "The road is blocked do not come this way" to "[ALERT] Road Blocked",
                "We are moving to extraction point" to "[MOVE] Extraction Point",
                "Order understood, standing by" to "[ACK] Roger That"
            )
        }
    }

    val activeSentence = when {
        customMessageText.isNotBlank() -> customMessageText.trim()
        liveTranscript.isNotBlank() -> liveTranscript.trim()
        else -> ""
    }

    val targetLangForPreview = if (selectedLanguage != Language.ENGLISH) Language.ENGLISH else Language.TAMIL
    var liveTamilTranslation by remember { mutableStateOf("") }
    LaunchedEffect(activeSentence, targetLangForPreview, selectedLanguage) {
        if (activeSentence.isNotBlank() && activeSentence.length >= 2) {
            delay(300L) // Debounce so user can type/speak smoothly with zero UI lag
            val trans = withContext(Dispatchers.IO) {
                try {
                    repository.translatorEngine.translate(activeSentence, targetLangForPreview, selectedLanguage)
                } catch (_: Throwable) { "" }
            }
            liveTamilTranslation = trans
        } else {
            liveTamilTranslation = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberVoidBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 220.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. General Communication Header Banner & Base Station Indicator
            StaggeredEntrance(index = 0) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "GENERAL COMMUNICATION",
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = CyberTextWhite
                            )
                            Text(
                                text = "NON-EMERGENCY • VOICE & TEXT TRANSLATE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = CyberMatrixGreen
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(CyberMatrixGreen.copy(alpha = 0.2f))
                                .border(1.dp, CyberMatrixGreen.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "NORMAL",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberMatrixGreen
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Real-Time Base Station GPU Server Indicator Pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (serverStatus.isOnline) CyberMatrixGreen.copy(alpha = 0.15f) else CyberCardDark)
                            .border(
                                1.dp,
                                if (serverStatus.isOnline) CyberMatrixGreen.copy(alpha = 0.6f) else CyberBorderSubtle,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(if (serverStatus.isOnline) CyberMatrixGreen else CyberTextDim)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (serverStatus.isOnline) {
                                    if (serverStatus.isCuda) "BASE STATION GPU: ONLINE (CUDA)" else "BASE STATION: ONLINE"
                                } else {
                                    "BASE STATION: OFFLINE (ON-DEVICE AI)"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (serverStatus.isOnline) CyberMatrixGreen else CyberTextDim
                            )
                        }

                        Text(
                            text = if (serverStatus.isOnline) {
                                serverStatus.endpoint ?: "192.168.137.1:5000"
                            } else {
                                "100% On-Device Neural"
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = if (serverStatus.isOnline) CyberTextWhite else CyberTextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Tactical Channel Bar
            StaggeredEntrance(index = 1) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberGlassSurface)
                        .border(1.dp, CyberBorderRed, RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "TARGET RECEIVER",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = CyberTextDim
                            )
                            Text(
                                text = targetNode?.name ?: "ALL MESH NODES (BROADCAST)",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextWhite
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "YOUR LANGUAGE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = CyberTextDim
                            )
                            Text(
                                text = "${selectedLanguage.displayName} (${selectedLanguage.nativeName})",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberNeonRedBright
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Audio Waveform & Live Duration
            StaggeredEntrance(index = 2) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val seconds = durationMs / 1000
                        val millis = (durationMs % 1000) / 100
                        Text(
                            text = "DURATION: %02d:%02d.%d".format(seconds / 60, seconds % 60, millis),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecording) CyberNeonRedBright else CyberTextMuted
                        )
                        Text(
                            text = "MIC RMS: ${(amplitude * 100).toInt()}%",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = CyberNeonRedBright
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    AudioWaveformVisualizer(
                        amplitudes = waveformSamples,
                        isRecording = isRecording,
                        modifier = Modifier.height(80.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Live Voice Transcript Box & Translation Preview
            if (pttState == CommunicationRepository.PttState.LISTENING || liveTranscript.isNotBlank()) {
                StaggeredEntrance(index = 3) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberGlassSurface)
                            .border(1.dp, CyberNeonRedBright, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = "Voice",
                                    tint = CyberNeonRedBright,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (pttState == CommunicationRepository.PttState.LISTENING)
                                        "LISTENING TO VOICE (${selectedLanguage.displayName.uppercase()})..."
                                    else
                                        "TRANSCRIBED VOICE TEXT",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = CyberNeonRedBright,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (liveTranscript.isNotBlank()) "\"$liveTranscript\"" else "Speak clearly into microphone...",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 13.sp,
                                color = CyberTextWhite,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Translation Preview Banner
            if (liveTamilTranslation.isNotBlank()) {
                StaggeredEntrance(index = 3) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberGlassSurface)
                            .border(1.dp, CyberMatrixGreen, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = "Translate",
                                    tint = CyberMatrixGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "RECEIVER AUTO-TRANSLATION PREVIEW (${targetLangForPreview.displayName.uppercase()} • ${targetLangForPreview.nativeName})",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = CyberMatrixGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "\"$liveTamilTranslation\"",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 13.sp,
                                color = CyberTextWhite,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 5. Quick Tactical Presets & Text Input
            StaggeredEntrance(index = 4) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "TACTICAL TALK PRESETS (NON-EMERGENCY)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = CyberNeonRedBright,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Preset Chips Grid
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (chunk in quickPresets.chunked(2)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for ((presetText, label) in chunk) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(CyberGlassSurface)
                                            .border(1.dp, CyberBorderRed, RoundedCornerShape(6.dp))
                                            .clickable {
                                                repository.stopPttCaptureAndSend(presetText)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = CyberTextWhite
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Custom Tactical Text Input with Direct Transmit Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customMessageText,
                            onValueChange = { customMessageText = it },
                            placeholder = {
                                Text(
                                    "Type any tactical message to transmit...",
                                    fontFamily = FontFamily.SansSerif,
                                    fontSize = 12.sp,
                                    color = CyberTextDim
                                )
                            },
                            trailingIcon = {
                                if (customMessageText.isNotBlank()) {
                                    IconButton(onClick = { customMessageText = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = CyberTextDim)
                                    }
                                }
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Send
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSend = {
                                    if (customMessageText.isNotBlank()) {
                                        repository.stopPttCaptureAndSend(customMessageText)
                                        customMessageText = ""
                                    }
                                }
                            ),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberNeonRedBright,
                                unfocusedBorderColor = CyberBorderRed,
                                focusedTextColor = CyberTextWhite,
                                unfocusedTextColor = CyberTextWhite
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (customMessageText.isNotBlank()) {
                                    repository.stopPttCaptureAndSend(customMessageText)
                                    customMessageText = ""
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberNeonRed),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = CyberTextWhite)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 6. Recent Channel Messages & Telemetry
            StaggeredEntrance(index = 5) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (messages.isNotEmpty()) {
                        Text(
                            text = "RECENT CHANNEL MESSAGES (AUDIO & TEXT)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyberTextDim,
                            modifier = Modifier.align(Alignment.Start)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            messages.take(3).forEach { msg ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(CyberGlassSurface)
                                        .border(1.dp, CyberBorderRed, RoundedCornerShape(8.dp))
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = if (msg.isIncoming) msg.senderName else "${userProfile.username} (You)",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (msg.isIncoming) CyberNeonRedBright else CyberTextWhite
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = msg.text,
                                                fontFamily = FontFamily.SansSerif,
                                                fontSize = 12.sp,
                                                color = CyberTextWhite
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                repository.ttsEngine.speak(msg.text, selectedLanguage)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "Speak",
                                                tint = CyberNeonRedBright,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Tactical Telemetry Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyberGlassSurface)
                            .border(1.dp, CyberBorderRed, RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("LATENCY", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                                Text("${linkMetrics.latencyMs} ms", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberTextWhite)
                            }
                            Column {
                                Text("PAYLOAD", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                                Text("38 BYTES", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberTextWhite)
                            }
                            Column {
                                Text("SAVINGS", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                                Text("99.4%", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberMatrixGreen)
                            }
                            Column {
                                Text("PRIORITY", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                                Text("NORMAL", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberMatrixGreen)
                            }
                        }
                    }
                }
            }
        }

        // Stationary, Fixed PTT Hold Button (Anchored at BottomCenter, never moves)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            CyberVoidBlack.copy(alpha = 0.88f),
                            CyberVoidBlack,
                            CyberVoidBlack
                        )
                    )
                )
                .padding(bottom = 12.dp, top = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            PttHoldButton(
                pttState = pttState,
                onPressStart = {
                    repository.startPttCapture()
                },
                onPressRelease = {
                    repository.stopPttCaptureAndSend()
                }
            )
        }
    }
}
