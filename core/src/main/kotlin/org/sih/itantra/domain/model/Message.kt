package org.sih.itantra.domain.model

enum class DeliveryStatus {
    RECORDING,
    PROCESSING,
    TRANSMITTING,
    DELIVERED_ACK,
    RECEIVED,
    FAILED,
    RETRYING
}

data class TechnicalTelemetry(
    val sttDurationMs: Long = 120L,
    val semanticDurationMs: Long = 35L,
    val compressionDurationMs: Long = 12L,
    val networkTransitDurationMs: Long = 68L,
    val reassemblyDurationMs: Long = 8L,
    val ttsDurationMs: Long = 95L,
    val payloadBytes: Int = 36,
    val rawEquivalentBytes: Int = 64000 // 2s 16kHz 16-bit mono = 64 KB
) {
    val totalLatencyMs: Long
        get() = sttDurationMs + semanticDurationMs + compressionDurationMs + networkTransitDurationMs + reassemblyDurationMs + ttsDurationMs

    val compressionRatioPct: Float
        get() = if (rawEquivalentBytes > 0) {
            ((1.0f - (payloadBytes.toFloat() / rawEquivalentBytes.toFloat())) * 100f).coerceIn(0f, 99.99f)
        } else 0f
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val receiverId: String, // or "BROADCAST"
    val text: String,
    val reconstructedSpeechText: String = text,
    val priority: Priority = Priority.NORMAL,
    val packetType: PacketType = PacketType.SEMANTIC,
    val transport: TransportType = TransportType.WIFI_DIRECT,
    val language: Language = Language.ENGLISH,
    val status: DeliveryStatus = DeliveryStatus.DELIVERED_ACK,
    val isIncoming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val telemetry: TechnicalTelemetry = TechnicalTelemetry()
)
