package org.sih.itantra.domain.model

enum class SemanticIntent(val id: Byte, val canonicalLabel: String) {
    EMERGENCY_MEDICAL(1, "Medical Emergency"),
    RESCUE_REQUEST(2, "Immediate Rescue Needed"),
    RESOURCE_SHORTAGE(3, "Supply Shortage (Food/Water/Meds)"),
    STATUS_CHECK(4, "Status & Location Verification"),
    ROUTE_CLEAR(5, "Evacuation Route Safe"),
    CASUALTY_COUNT(6, "Casualty Report"),
    ALL_CLEAR(7, "Area Secure & Normal"),
    ACKNOWLEDGEMENT(8, "Command Acknowledged"),
    TEST_COMMUNICATION(9, "Radio Check / Testing"),
    GENERAL_REPORT(10, "Field Situation Report");

    companion object {
        fun fromId(id: Byte): SemanticIntent = entries.firstOrNull { it.id == id } ?: GENERAL_REPORT
    }
}

/**
 * High-efficiency semantic representation.
 * Instead of streaming raw PCM voice (e.g. 64,000 bytes for 2s of 16kHz audio),
 * this structure encapsulates the core meaning in fewer than 40 bytes!
 */
data class SemanticMessage(
    val intent: SemanticIntent,
    val entities: Map<String, String> = emptyMap(),
    val priority: Priority = Priority.NORMAL,
    val rawText: String,
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.ENGLISH,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Compact binary serialization for transmission over BLE/LoRa.
     * Format:
     * [1 byte Intent]
     * [1 byte Priority]
     * [1 byte SourceLang]
     * [1 byte TargetLang]
     * [1 byte EntityCount]
     * for each entity:
     *   [1 byte keyLen][key UTF8][1 byte valLen][val UTF8]
     */
    fun toBinaryPayload(): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        stream.write(intent.id.toInt())
        stream.write(priority.code.toInt())
        stream.write(sourceLanguage.id.toInt())
        stream.write(targetLanguage.id.toInt())
        stream.write(entities.size)
        for ((k, v) in entities) {
            val kBytes = k.toByteArray(Charsets.UTF_8)
            val vBytes = v.toByteArray(Charsets.UTF_8)
            stream.write(kBytes.size)
            stream.write(kBytes)
            stream.write(vBytes.size)
            stream.write(vBytes)
        }
        // fromBinaryPayload() reads a trailing [2-byte length][UTF-8 bytes] rawText block;
        // without writing it here the receiver always got rawText = "" and had to
        // reconstruct the message from intent+entities alone, garbling free-form sentences.
        val rawTextBytes = rawText.toByteArray(Charsets.UTF_8)
        val clampedLen = rawTextBytes.size.coerceAtMost(0xFFFF)
        stream.write((clampedLen shr 8) and 0xFF)
        stream.write(clampedLen and 0xFF)
        stream.write(rawTextBytes, 0, clampedLen)
        return stream.toByteArray()
    }

    companion object {
        fun fromBinaryPayload(data: ByteArray, fallbackRawText: String = ""): SemanticMessage? {
            if (data.size < 5) return null
            val buffer = java.nio.ByteBuffer.wrap(data)
            val intentId = buffer.get()
            val priorityCode = buffer.get()
            val sourceLangId = buffer.get()
            val targetLangId = buffer.get()
            val entityCount = buffer.get().toInt() and 0xFF

            val entities = mutableMapOf<String, String>()
            for (i in 0 until entityCount) {
                if (buffer.remaining() < 1) break
                val kLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < kLen) break
                val kBytes = ByteArray(kLen)
                buffer.get(kBytes)
                val key = String(kBytes, Charsets.UTF_8)

                if (buffer.remaining() < 1) break
                val vLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < vLen) break
                val vBytes = ByteArray(vLen)
                buffer.get(vBytes)
                val value = String(vBytes, Charsets.UTF_8)

                entities[key] = value
            }

            var text = fallbackRawText
            if (buffer.remaining() >= 2) {
                val high = buffer.get().toInt() and 0xFF
                val low = buffer.get().toInt() and 0xFF
                val rawLen = (high shl 8) or low
                if (rawLen > 0 && buffer.remaining() >= rawLen) {
                    val rawBytes = ByteArray(rawLen)
                    buffer.get(rawBytes)
                    text = String(rawBytes, Charsets.UTF_8)
                }
            }

            return SemanticMessage(
                intent = SemanticIntent.fromId(intentId),
                entities = entities,
                priority = Priority.fromCode(priorityCode),
                rawText = text,
                sourceLanguage = Language.fromId(sourceLangId),
                targetLanguage = Language.fromId(targetLangId)
            )
        }
    }
}
