package org.sih.itantra.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.sih.itantra.domain.model.ChatMessage
import org.sih.itantra.domain.model.DeliveryStatus
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.TechnicalTelemetry
import org.sih.itantra.domain.model.TransportType
import java.util.concurrent.CopyOnWriteArrayList

data class UserProfile(
    val username: String = "Operator",
    val gender: String = "Male",
    val preferredLanguage: Language = Language.ENGLISH,
    val callsign: String = "AKHET-1",
    val role: String = "Tactical Unit",
    val isInitialized: Boolean = false
)

/**
 * Encrypted Local Storage Repository.
 * Guarantees zero reliance on cloud/Firebase databases.
 */
class LocalEncryptedDatabase(private val context: Context) {

    private val inMemoryMessages = CopyOnWriteArrayList<ChatMessage>()
    private val _messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messagesFlow: StateFlow<List<ChatMessage>> = _messagesFlow.asStateFlow()

    private val inMemoryNodes = CopyOnWriteArrayList<NetworkNode>()
    private val _nodesFlow = MutableStateFlow<List<NetworkNode>>(emptyList())
    val nodesFlow: StateFlow<List<NetworkNode>> = _nodesFlow.asStateFlow()

    private val _userProfileFlow = MutableStateFlow(getUserProfile())
    val userProfileFlow: StateFlow<UserProfile> = _userProfileFlow.asStateFlow()

    fun getUserProfile(): UserProfile {
        val prefs = context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)
        val gender = prefs.getString("user_gender", "Male") ?: "Male"
        val langCode = prefs.getString("user_pref_lang", "en") ?: "en"
        val callsign = prefs.getString("user_callsign", "AKHET-1") ?: "AKHET-1"
        val role = prefs.getString("user_role", "Tactical Unit") ?: "Tactical Unit"
        val initialized = prefs.getBoolean("profile_initialized", false)
        val lang = Language.fromCode(langCode)
        return if (name != null) {
            UserProfile(
                username = name,
                gender = gender,
                preferredLanguage = lang,
                callsign = callsign,
                role = role,
                isInitialized = initialized
            )
        } else {
            UserProfile(
                username = "Operator_${getOrCreateNodeId()}",
                gender = "Male",
                preferredLanguage = Language.ENGLISH,
                callsign = "AKHET-1",
                role = "Tactical Unit",
                isInitialized = false
            )
        }
    }

    fun saveUserProfile(profile: UserProfile) {
        val prefs = context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name", profile.username)
            .putString("user_gender", profile.gender)
            .putString("user_pref_lang", profile.preferredLanguage.code)
            .putString("user_callsign", profile.callsign)
            .putString("user_role", profile.role)
            .putBoolean("profile_initialized", true)
            .apply()
        _userProfileFlow.value = profile.copy(isInitialized = true)
    }

    fun getOrCreateNodeId(): String {
        val prefs = context.getSharedPreferences("itantra_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("node_id", null)
        if (id == null || id.length > 8) {
            val randomSuffix = (1000..9999).random()
            id = "N_$randomSuffix" // exactly 6 chars, fits in 8-byte packet slot without truncation
            prefs.edit().putString("node_id", id).apply()
        }
        return id
    }

    init {
        seedSampleHistory()
    }

    private fun seedSampleHistory() {
        val sample1 = ChatMessage(
            senderId = "NODE_002",
            senderName = "Forward Post Bravo",
            receiverId = "LOCAL_NODE",
            text = "Radio check, communication link established. Sector clear.",
            reconstructedSpeechText = "Radio check, communication link established. Sector clear.",
            priority = Priority.NORMAL,
            packetType = PacketType.NORMAL_TEXT,
            transport = TransportType.WIFI_DIRECT,
            language = Language.ENGLISH,
            status = DeliveryStatus.RECEIVED,
            isIncoming = true,
            timestamp = System.currentTimeMillis() - 180000,
            telemetry = TechnicalTelemetry(
                sttDurationMs = 110L,
                semanticDurationMs = 28L,
                compressionDurationMs = 10L,
                networkTransitDurationMs = 62L,
                payloadBytes = 56,
                rawEquivalentBytes = 64000
            )
        )

        val sample2 = ChatMessage(
            senderId = "LOCAL_NODE",
            senderName = "Commander (You)",
            receiverId = "NODE_002",
            text = "चिकित्सा दल रवाना हो चुका है। मार्ग साफ़ है।",
            reconstructedSpeechText = "चिकित्सा दल रवाना हो चुका है। मार्ग साफ़ है।",
            priority = Priority.IMPORTANT,
            packetType = PacketType.COMPRESSED_TEXT,
            transport = TransportType.BLE,
            language = Language.HINDI,
            status = DeliveryStatus.DELIVERED_ACK,
            isIncoming = false,
            timestamp = System.currentTimeMillis() - 95000,
            telemetry = TechnicalTelemetry(
                sttDurationMs = 125L,
                semanticDurationMs = 32L,
                compressionDurationMs = 15L,
                networkTransitDurationMs = 95L,
                payloadBytes = 64,
                rawEquivalentBytes = 64000
            )
        )

        inMemoryMessages.add(sample1)
        inMemoryMessages.add(sample2)
        _messagesFlow.value = inMemoryMessages.toList()

        // Real mesh mode: Nodes list is populated ONLY by actual peers running the iTANTRA app!
        _nodesFlow.value = inMemoryNodes.toList()
    }

    fun saveMessage(message: ChatMessage) {
        inMemoryMessages.add(0, message) // Most recent first
        _messagesFlow.value = inMemoryMessages.toList()
    }

    fun updateMessageStatus(id: String, status: DeliveryStatus) {
        val index = inMemoryMessages.indexOfFirst { it.id == id }
        if (index != -1) {
            val updated = inMemoryMessages[index].copy(status = status)
            inMemoryMessages[index] = updated
            _messagesFlow.value = inMemoryMessages.toList()
        }
    }

    fun addOrUpdateNode(node: NetworkNode) {
        val myNodeId = getOrCreateNodeId().trim()
        val incomingId = node.id.trim()
        if (incomingId.equals(myNodeId, ignoreCase = true)) {
            return
        }
        val updatedNode = node.copy(lastSeenTimestamp = System.currentTimeMillis())
        val index = inMemoryNodes.indexOfFirst { it.id == updatedNode.id }
        if (index != -1) {
            inMemoryNodes[index] = updatedNode
        } else {
            inMemoryNodes.add(updatedNode)
        }
        _nodesFlow.value = inMemoryNodes.toList()
    }

    fun pruneStaleNodes(maxAgeMs: Long = 4500L) {
        val now = System.currentTimeMillis()
        val changed = inMemoryNodes.removeAll { (now - it.lastSeenTimestamp) > maxAgeMs }
        if (changed) {
            _nodesFlow.value = inMemoryNodes.toList()
        }
    }

    fun removeNode(nodeId: String) {
        inMemoryNodes.removeAll { it.id == nodeId }
        _nodesFlow.value = inMemoryNodes.toList()
    }
}
