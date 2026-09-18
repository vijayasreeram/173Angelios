package org.sih.itantra.domain.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.sih.itantra.battery.BatteryAdaptiveManager
import org.sih.itantra.domain.model.ChatMessage
import org.sih.itantra.domain.model.DeliveryStatus
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.LinkMetrics
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.SemanticMessage
import org.sih.itantra.domain.model.TechnicalTelemetry
import org.sih.itantra.domain.model.TransportType
import org.sih.itantra.emergency.EmergencyManager
import org.sih.itantra.ml.audio.AudioCaptureManager
import org.sih.itantra.ml.semantic.SemanticDecoder
import org.sih.itantra.ml.semantic.SemanticEncoder
import org.sih.itantra.ml.stt.OfflineMultilingualSttEngine
import org.sih.itantra.ml.tts.OfflineTtsEngine
import org.sih.itantra.network.compression.AdaptiveCompressor
import org.sih.itantra.network.mesh.MeshRouter
import org.sih.itantra.network.monitor.NetworkQualityMonitor
import org.sih.itantra.network.transport.TransportManager
import org.sih.itantra.storage.LocalEncryptedDatabase

/**
 * Core Orchestrator for iTANTRA.
 * Connects the voice pipeline, ML engines, adaptive compression, CRC16 packet builder,
 * mesh router, multi-transport managers, and receiver reconstruction.
 */
class CommunicationRepository(
    private val context: Context,
    val audioCaptureManager: AudioCaptureManager = AudioCaptureManager(),
    val sttEngine: OfflineMultilingualSttEngine = OfflineMultilingualSttEngine(),
    val semanticEncoder: SemanticEncoder = SemanticEncoder(),
    val semanticDecoder: SemanticDecoder = SemanticDecoder(),
    val ttsEngine: OfflineTtsEngine = OfflineTtsEngine(context),
    val compressor: AdaptiveCompressor = AdaptiveCompressor(),
    val networkMonitor: NetworkQualityMonitor = NetworkQualityMonitor(),
    val batteryManager: BatteryAdaptiveManager = BatteryAdaptiveManager(context),
    val emergencyManager: EmergencyManager = EmergencyManager(context),
    val database: LocalEncryptedDatabase = LocalEncryptedDatabase(context),
    val transportManager: TransportManager = TransportManager(context),
    val speechManager: org.sih.itantra.ml.stt.AndroidSpeechManager = org.sih.itantra.ml.stt.AndroidSpeechManager(context),
    val translatorEngine: org.sih.itantra.ml.translation.OfflineTranslatorEngine = org.sih.itantra.ml.translation.OfflineTranslatorEngine()
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    val localNodeId: String = database.getOrCreateNodeId()

    val meshRouter = MeshRouter(localNodeId = localNodeId) { packetToForward ->
        transportManager.transmitPacket(packetToForward)
    }

    // Selected User Preferences
    private val _selectedLanguage = MutableStateFlow(database.getUserProfile().preferredLanguage)
    val selectedLanguage: StateFlow<Language> = _selectedLanguage.asStateFlow()

    private val _receiverLanguage = MutableStateFlow(Language.HINDI)
    val receiverLanguage: StateFlow<Language> = _receiverLanguage.asStateFlow()

    private val _activeTargetNode = MutableStateFlow<NetworkNode?>(null)
    val activeTargetNode: StateFlow<NetworkNode?> = _activeTargetNode.asStateFlow()

    // PTT UI Lifecycle State
    enum class PttState {
        IDLE,
        LISTENING,
        PROCESSING,
        COMPRESSING,
        TRANSMITTING,
        DELIVERED,
        FAILED,
        EMERGENCY
    }

    private val _pttState = MutableStateFlow(PttState.IDLE)
    val pttState: StateFlow<PttState> = _pttState.asStateFlow()

    val micAudioLevel: StateFlow<Float> = combine(
        audioCaptureManager.currentAmplitude,
        speechManager.audioRms
    ) { pcmLevel, speechRms ->
        maxOf(pcmLevel, speechRms)
    }.stateIn(scope, SharingStarted.Eagerly, 0f)

    val linkMetrics: StateFlow<LinkMetrics> = networkMonitor.metrics
    val messages: StateFlow<List<ChatMessage>> = database.messagesFlow
    val nodes: StateFlow<List<NetworkNode>> = database.nodesFlow
    val userProfile: StateFlow<org.sih.itantra.storage.UserProfile> = database.userProfileFlow
    val edgeServerStatus: StateFlow<org.sih.itantra.ml.translation.EdgeServerStatus> = translatorEngine.edgeServerStatus

    private val _screenRefreshRate = MutableStateFlow(120)
    val screenRefreshRate: StateFlow<Int> = _screenRefreshRate.asStateFlow()

    fun setScreenRefreshRate(hz: Int) {
        _screenRefreshRate.value = hz
    }

    fun updateUserProfile(
        username: String,
        gender: String = "Male",
        preferredLanguage: Language = _selectedLanguage.value,
        callsign: String = "AKHET-1",
        role: String = "Tactical Unit"
    ) {
        val updated = org.sih.itantra.storage.UserProfile(
            username = username.trim(),
            gender = gender.trim(),
            preferredLanguage = preferredLanguage,
            callsign = callsign.trim(),
            role = role.trim(),
            isInitialized = true
        )
        database.saveUserProfile(updated)
        setSelectedLanguage(preferredLanguage)
        scanForConnectedDevices()
    }

    fun selectPrivatePeer(node: NetworkNode?) {
        _activeTargetNode.value = node
    }

    fun clearPrivatePeer() {
        _activeTargetNode.value = null
    }

    fun sendPredefinedCommand(commandText: String) {
        stopPttCaptureAndSend(customTextOverride = commandText)
    }

    init {
        translatorEngine.initContext(context)
        (transportManager.getTransport(TransportType.WIFI_DIRECT) as? org.sih.itantra.network.transport.WifiDirectTransport)?.localNodeId = localNodeId
        networkMonitor.startMonitoring(scope)

        // 1. Synchronize real hardware battery level directly with network monitor metrics
        networkMonitor.updateBattery(batteryManager.batteryLevel.value)
        scope.launch {
            batteryManager.batteryLevel.collect { level ->
                networkMonitor.updateBattery(level)
            }
        }

        // 2. Periodic background probe to detect if Base Station GPU (app.py) is online
        scope.launch {
            while (isActive) {
                try {
                    translatorEngine.checkEdgeServerConnection()
                } catch (_: Exception) {}
                delay(4000L)
            }
        }

        scope.launch {
            transportManager.startAll()
        }

        // Listen for incoming delivered packets from mesh router
        scope.launch {
            meshRouter.deliveredPackets.collect { packet ->
                handleDeliveredPacket(packet)
            }
        }

        // Connect raw transport feed to mesh router
        scope.launch {
            transportManager.incomingPackets.collect { rawPacket ->
                meshRouter.routeIncomingPacket(rawPacket)
            }
        }

        // Auto-download on-device neural translation models in background
        if (_selectedLanguage.value != Language.ENGLISH) {
            translatorEngine.downloadModelIfNeeded(_selectedLanguage.value)
        }
        if (_receiverLanguage.value != Language.ENGLISH) {
            translatorEngine.downloadModelIfNeeded(_receiverLanguage.value)
        }
        // Pre-download Tamil and Hindi neural models for seamless bidirectional offline translation
        translatorEngine.downloadModelIfNeeded(Language.TAMIL)
        translatorEngine.downloadModelIfNeeded(Language.HINDI)

        // Ultra-fast 1.0s discovery heartbeat + real-time 3.0s disconnected device pruning
        scope.launch {
            delay(250L) // Initial quick broadcast on startup
            scanForConnectedDevices()
            while (isActive) {
                delay(1000L) // Real-time 1.0s pulse
                scanForConnectedDevices()
                database.pruneStaleNodes(maxAgeMs = 3000L) // Disconnected peers disappear within 3s
            }
        }
    }

    fun setSelectedLanguage(lang: Language) {
        _selectedLanguage.value = lang
        if (lang != Language.ENGLISH) {
            translatorEngine.downloadModelIfNeeded(lang)
        }
    }

    fun setReceiverLanguage(lang: Language) {
        _receiverLanguage.value = lang
        if (lang != Language.ENGLISH) {
            translatorEngine.downloadModelIfNeeded(lang)
        }
    }

    fun setActiveTargetNode(node: NetworkNode?) {
        _activeTargetNode.value = node
    }

    /**
     * Step 1: User presses PTT button
     */
    fun startPttCapture() {
        _pttState.value = PttState.LISTENING
        speechManager.clear()
        if (speechManager.isAvailable()) {
            try {
                speechManager.startListening(_selectedLanguage.value)
            } catch (e: Exception) {
                android.util.Log.w("CommunicationRepo", "SpeechManager startListening failed: ${e.message}")
            }
        }
    }

    /**
     * Step 2: User releases PTT button
     * Executes the complete Voice -> STT -> Semantic -> Compression -> Packet -> Transmit pipeline!
     */
    fun stopPttCaptureAndSend(customTextOverride: String? = null) {
        _pttState.value = PttState.PROCESSING

        scope.launch {
            val speechVoice = try { speechManager.stopListening().trim() } catch (e: Exception) { "" }
            val liveText = speechManager.liveTranscript.value.trim()
            val recognizedText = if (speechVoice.isNotBlank()) speechVoice else liveText

            // Determine the actual message text - NEVER use fake canned phrases!
            val textToUse = when {
                !customTextOverride.isNullOrBlank() -> customTextOverride.trim()
                recognizedText.isNotBlank() -> recognizedText
                else -> null
            }

            if (textToUse.isNullOrBlank()) {
                android.util.Log.d("CommunicationRepo", "No speech detected during PTT, resetting to IDLE without sending default message")
                _pttState.value = PttState.IDLE
                return@launch
            }

            // 1. Offline STT Transcription
            val sttStart = System.currentTimeMillis()
            val sttResult = sttEngine.transcribeText(textToUse, _selectedLanguage.value)
            val sttDuration = System.currentTimeMillis() - sttStart

            // 2. Semantic Extraction (General Comms is strictly non-emergency mode)
            val semStart = System.currentTimeMillis()
            val semantic = semanticEncoder.encode(
                sttResult.transcript,
                _selectedLanguage.value,
                _receiverLanguage.value
            )
            val semDuration = System.currentTimeMillis() - semStart

            // 3. Adaptive Compression (General Comms priority is always NORMAL)
            _pttState.value = PttState.COMPRESSING
            val compStart = System.currentTimeMillis()
            val currentLq = linkMetrics.value.linkQuality
            val compResult = compressor.compress(
                sttResult.transcript,
                semantic,
                currentLq,
                Priority.NORMAL
            )
            val compDuration = System.currentTimeMillis() - compStart

            // 4. Packetization with CRC16 Checksum
            _pttState.value = PttState.TRANSMITTING
            val targetNodeId = _activeTargetNode.value?.id ?: "BRDCAST_"
            val actualSourceLang = translatorEngine.detectScriptLanguage(textToUse) ?: _selectedLanguage.value
            val packet = Packet(
                senderId = meshRouter.localNodeId,
                receiverId = targetNodeId,
                languageId = actualSourceLang.id,
                priority = Priority.NORMAL,
                packetType = compResult.packetType,
                payload = compResult.payload
            )

            // 5. Mesh Queueing and Transmission
            val txStart = System.currentTimeMillis()
            meshRouter.enqueueOutgoing(packet)
            val txSuccess = transportManager.transmitPacket(packet, linkMetrics.value.activeTransport)
            val txDuration = System.currentTimeMillis() - txStart

            networkMonitor.recordPacketTransmission(txSuccess, txDuration)

            // 6. Log in Encrypted Local Database
            val telemetry = TechnicalTelemetry(
                sttDurationMs = sttDuration,
                semanticDurationMs = semDuration,
                compressionDurationMs = compDuration,
                networkTransitDurationMs = txDuration,
                reassemblyDurationMs = 5L,
                ttsDurationMs = 85L,
                payloadBytes = packet.payloadLength,
                rawEquivalentBytes = 64000
            )

            val chatMessage = ChatMessage(
                senderId = meshRouter.localNodeId,
                senderName = "${userProfile.value.username} (You)",
                receiverId = targetNodeId,
                text = sttResult.transcript,
                reconstructedSpeechText = semanticDecoder.decode(semantic, _selectedLanguage.value),
                priority = semantic.priority,
                packetType = compResult.packetType,
                transport = linkMetrics.value.activeTransport,
                language = _selectedLanguage.value,
                status = if (txSuccess) DeliveryStatus.DELIVERED_ACK else DeliveryStatus.FAILED,
                isIncoming = false,
                timestamp = System.currentTimeMillis(),
                telemetry = telemetry
            )

            database.saveMessage(chatMessage)

            _pttState.value = if (semantic.priority == Priority.EMERGENCY) {
                PttState.EMERGENCY
            } else if (txSuccess) {
                PttState.DELIVERED
            } else {
                PttState.FAILED
            }
        }
    }

    /**
     * Receiver Pipeline:
     * Receives packet -> verifies CRC16 -> decompresses -> reconstructs text in receiver language -> synthesizes TTS
     */
    private fun handleDeliveredPacket(packet: Packet) {
        // CRITICAL CHECK: Ignore packets sent by ourselves!
        // The sender speaks into their mic; only the opposite recipient should hear the TTS playback!
        // Also ensures local phone is never registered as a peer on its own radar.
        val cleanSender = packet.senderId.trim()
        val cleanLocal = localNodeId.trim()
        if (cleanSender.equals(cleanLocal, ignoreCase = true)) {
            android.util.Log.d("CommunicationRepository", "Suppressing self packet from $cleanSender - will not process or play TTS on sender phone")
            return
        }

        // Handle discovery beacon packets without producing chat/TTS
        if (packet.packetType == PacketType.HELLO || packet.packetType == PacketType.HEARTBEAT) {
            var peerBattery = batteryManager.batteryLevel.value
            val peerUsername = try {
                val payloadStr = String(packet.payload, Charsets.UTF_8)
                if (payloadStr.startsWith("iTANTRA_PEER|")) {
                    val parts = payloadStr.split("|")
                    if (parts.size >= 3) {
                        peerBattery = parts[2].trim().toIntOrNull() ?: peerBattery
                    }
                    if (parts.size >= 2 && parts[1].isNotBlank()) parts[1].trim() else "User ${packet.senderId}"
                } else {
                    "User ${packet.senderId}"
                }
            } catch (_: Exception) {
                "User ${packet.senderId}"
            }

            val remoteNode = org.sih.itantra.domain.model.NetworkNode(
                id = packet.senderId,
                name = peerUsername,
                rssi = -55,
                batteryPct = peerBattery,
                transportType = TransportType.WIFI_DIRECT,
                hopCount = 1,
                isDirectNeighbor = true
            )
            database.addOrUpdateNode(remoteNode)
            return
        }

        val decompressed = compressor.decompress(packet.payload, packet.packetType, packet.language)
        val isSos = packet.packetType == PacketType.SOS || packet.packetType == PacketType.EMERGENCY || packet.priority == Priority.EMERGENCY

        // Extract sender username from payload or known nodes
        val rawDistressUser = if (isSos && decompressed.semantic?.rawText?.isNotBlank() == true &&
            !decompressed.semantic.rawText.startsWith("SOS_BEACON") &&
            !decompressed.semantic.rawText.startsWith("CRITICAL SOS")) {
            decompressed.semantic.rawText
        } else null

        val senderDisplayName = rawDistressUser
            ?: database.nodesFlow.value.find { it.id == packet.senderId }?.name
            ?: "Operator ${packet.senderId.takeLast(4)}"

        // 1. Extract base text from incoming packet
        val baseText = if (isSos) {
            when (_selectedLanguage.value) {
                Language.TAMIL -> "அவசர எச்சரிக்கை! $senderDisplayName அவசர உதவி கோரியுள்ளார்! உடனடி உதவி தேவை!"
                Language.HINDI -> "आपातकालीन चेतावनी! $senderDisplayName ने आपातकालीन संकट चेतावनी भेजी है! तत्काल सहायता चाहिए!"
                Language.TELUGU -> "అత్యవసర హెచ్చరిక! $senderDisplayName అత్యవసర సహాయం కోరారు! తక్షణ సహాయం కావాలి!"
                Language.KANNADA -> "ತುರ್ತು ಎಚ್ಚರಿಕೆ! $senderDisplayName ತುರ್ತು ಸಹಾಯ ಕೋರಿದ್ದಾರೆ! ತಕ್ಷಣ ನೆರವು ಬೇಕು!"
                Language.MALAYALAM -> "അടിയന്തര മുന്നറിയിപ്പ്! $senderDisplayName അടിയന്തര സഹായം ആവശ്യപ്പെട്ടു! ഉടൻ സഹായം എത്തിക്കുക!"
                Language.BENGALI -> "জরুরী সতর্কতা! $senderDisplayName জরুরী সহায়তা চেয়েছেন!"
                Language.MARATHI -> "तातडीचा इशारा! $senderDisplayName यांनी आणीबाणी मदतीची मागणी केली आहे!"
                Language.GUJARATI -> "કટોકટી ચેતવણી! $senderDisplayName એ તાત્કાલિક સહાયની વિનંતી કરી છે!"
                Language.ODIA -> "ଜରୁରୀକାଳୀନ ଚେତାବନୀ! $senderDisplayName ଜରୁରୀ ପରିସ୍ଥିତିରେ ଅଛନ୍ତି! ତୁରନ୍ତ ସହାୟତା ଆବଶ୍ୟକ!"
                Language.PUNJABI -> "ਐਮਰਜੈਂਸੀ ਅਲਰਟ! $senderDisplayName ਨੇ ਤੁਰੰਤ ਮਦਦ ਦੀ ਬੇਨਤੀ ਕੀਤੀ ਹੈ!"
                else -> "Emergency SOS! User $senderDisplayName is in emergency! Need immediate assistance!"
            }
        } else if (decompressed.semantic != null && decompressed.semantic.rawText.isNotBlank()) {
            decompressed.semantic.rawText
        } else if (decompressed.reconstructedText.isNotBlank()) {
            decompressed.reconstructedText
        } else if (decompressed.semantic != null) {
            semanticDecoder.decode(decompressed.semantic, _selectedLanguage.value)
        } else {
            "Radio transmission received"
        }

        // 2. Offline Translate into this receiver's selected language (e.g. Tamil, Hindi, or English)
        val actualIncomingLanguage = translatorEngine.detectScriptLanguage(baseText) ?: packet.language
        val localTargetLanguage = _selectedLanguage.value

        val reconstructedText = if (isSos) {
            baseText
        } else if (actualIncomingLanguage == localTargetLanguage) {
            baseText
        } else {
            val translated = translatorEngine.translate(baseText, localTargetLanguage, actualIncomingLanguage)
            if (translated.isNotBlank()) {
                translated
            } else if (packet.priority == Priority.EMERGENCY && decompressed.semantic != null) {
                semanticDecoder.decode(decompressed.semantic, localTargetLanguage)
            } else {
                baseText
            }
        }

        val incomingMessage = ChatMessage(
            id = packet.messageId.toString(),
            senderId = packet.senderId,
            senderName = senderDisplayName,
            receiverId = packet.receiverId,
            text = reconstructedText,
            reconstructedSpeechText = reconstructedText,
            priority = packet.priority,
            packetType = packet.packetType,
            transport = linkMetrics.value.activeTransport,
            language = _selectedLanguage.value,
            status = DeliveryStatus.RECEIVED,
            isIncoming = true,
            timestamp = System.currentTimeMillis(),
            telemetry = TechnicalTelemetry(
                payloadBytes = packet.payloadLength,
                rawEquivalentBytes = 64000
            )
        )

        database.saveMessage(incomingMessage)

        // Automatically register incoming peer in discovered mesh nodes list using username and real battery
        val existingBattery = database.nodesFlow.value.find { it.id == packet.senderId }?.batteryPct ?: batteryManager.batteryLevel.value
        val remoteNode = org.sih.itantra.domain.model.NetworkNode(
            id = packet.senderId,
            name = senderDisplayName,
            rssi = -62,
            batteryPct = existingBattery,
            transportType = linkMetrics.value.activeTransport,
            hopCount = 1,
            isDirectNeighbor = true
        )
        database.addOrUpdateNode(remoteNode)

        // Synthesize speech locally if not muted
        ttsEngine.speak(reconstructedText, _selectedLanguage.value)
    }

    /**
     * Actively scans Wi-Fi network for connected peers running iTANTRA via UDP discovery beacon.
     */
    fun scanForConnectedDevices() {
        database.pruneStaleNodes(maxAgeMs = 4500L)
        scope.launch {
            val wifiTransport = transportManager.getTransport(TransportType.WIFI_DIRECT) as? org.sih.itantra.network.transport.WifiDirectTransport
            wifiTransport?.let { wifi ->
                val myUsername = userProfile.value.username
                wifi.broadcastDiscoveryBeacon(localNodeId, myUsername, batteryManager.batteryLevel.value)
            }
        }
    }

    fun triggerEmergencySos(customDistressMessage: String = "") {
        emergencyManager.triggerSos(source = "EMERGENCY_SCREEN", scope)
        val myUsername = userProfile.value.username.ifBlank { "Operator_${localNodeId.takeLast(4)}" }
        val msgToSend = if (customDistressMessage.isNotBlank()) customDistressMessage else myUsername

        // Immediately broadcast emergency packet with sender username embedded
        val emergencySemantic = SemanticMessage(
            intent = org.sih.itantra.domain.model.SemanticIntent.EMERGENCY_MEDICAL,
            priority = Priority.EMERGENCY,
            rawText = msgToSend,
            sourceLanguage = _selectedLanguage.value,
            targetLanguage = _receiverLanguage.value
        )
        val compResult = compressor.compress(
            msgToSend,
            emergencySemantic,
            org.sih.itantra.domain.model.LinkQuality.EMERGENCY,
            Priority.EMERGENCY
        )
        val basePayload = compResult.payload
        val usernameBytes = myUsername.toByteArray(Charsets.UTF_8)
        val sosPayload = ByteArray(basePayload.size + usernameBytes.size)
        System.arraycopy(basePayload, 0, sosPayload, 0, basePayload.size)
        System.arraycopy(usernameBytes, 0, sosPayload, basePayload.size, usernameBytes.size)

        val sosPacket = Packet(
            senderId = meshRouter.localNodeId,
            receiverId = "BRDCAST_",
            languageId = _selectedLanguage.value.id,
            priority = Priority.EMERGENCY,
            packetType = PacketType.SOS,
            payload = sosPayload
        )
        scope.launch {
            meshRouter.enqueueOutgoing(sosPacket)
            transportManager.transmitPacket(sosPacket)
        }
    }

    fun stopEmergencySos() {
        emergencyManager.stopSos()
    }
}
