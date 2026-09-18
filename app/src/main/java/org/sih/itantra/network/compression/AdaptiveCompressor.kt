package org.sih.itantra.network.compression

import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.LinkQuality
import org.sih.itantra.domain.model.PacketType
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.SemanticIntent
import org.sih.itantra.domain.model.SemanticMessage
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

data class CompressionResult(
    val packetType: PacketType,
    val payload: ByteArray,
    val uncompressedBytes: Int,
    val compressedBytes: Int,
    val compressionRatioPct: Float,
    val strategy: String
)

/**
 * Adaptive Communication Compression Engine.
 * Dynamically shifts strategies based on Link Quality Score (LQS).
 */
class AdaptiveCompressor {

    fun compress(
        text: String,
        semantic: SemanticMessage,
        linkQuality: LinkQuality,
        priority: Priority
    ): CompressionResult {
        // Ultra-dense Emergency Beacon is strictly reserved for EMERGENCY priority or emergency link
        if (priority == Priority.EMERGENCY || linkQuality == LinkQuality.EMERGENCY) {
            val emergencyPayload = compressEmergency(semantic)
            return CompressionResult(
                packetType = PacketType.EMERGENCY,
                payload = emergencyPayload,
                uncompressedBytes = text.toByteArray(Charsets.UTF_8).size,
                compressedBytes = emergencyPayload.size,
                compressionRatioPct = calculateRatio(text.length * 2, emergencyPayload.size),
                strategy = "Ultra-Compact Emergency Beacon (12B)"
            )
        }

        val utf8Bytes = text.toByteArray(Charsets.UTF_8)
        return when (linkQuality) {
            LinkQuality.GOOD -> {
                CompressionResult(
                    packetType = PacketType.NORMAL_TEXT,
                    payload = utf8Bytes,
                    uncompressedBytes = utf8Bytes.size,
                    compressedBytes = utf8Bytes.size,
                    compressionRatioPct = 0f,
                    strategy = "Full Fidelity UTF-8"
                )
            }
            LinkQuality.MEDIUM -> {
                val deflated = deflate(utf8Bytes)
                CompressionResult(
                    packetType = PacketType.COMPRESSED_TEXT,
                    payload = deflated,
                    uncompressedBytes = utf8Bytes.size,
                    compressedBytes = deflated.size,
                    compressionRatioPct = calculateRatio(utf8Bytes.size, deflated.size),
                    strategy = "Deflate Compressed Text"
                )
            }
            LinkQuality.POOR -> {
                val semanticPayload = semantic.toBinaryPayload()
                CompressionResult(
                    packetType = PacketType.SEMANTIC,
                    payload = semanticPayload,
                    uncompressedBytes = utf8Bytes.size,
                    compressedBytes = semanticPayload.size,
                    compressionRatioPct = calculateRatio(utf8Bytes.size, semanticPayload.size),
                    strategy = "Compact Semantic Bit-Stream (<80B)"
                )
            }
            LinkQuality.EMERGENCY -> {
                val emergencyPayload = compressEmergency(semantic)
                CompressionResult(
                    packetType = PacketType.EMERGENCY,
                    payload = emergencyPayload,
                    uncompressedBytes = utf8Bytes.size,
                    compressedBytes = emergencyPayload.size,
                    compressionRatioPct = calculateRatio(text.length * 2, emergencyPayload.size),
                    strategy = "Ultra-Compact Emergency Beacon (12B)"
                )
            }
        }
    }

    fun decompress(
        payload: ByteArray,
        packetType: PacketType,
        sourceLanguage: Language
    ): DecompressedResult {
        return when (packetType) {
            PacketType.NORMAL_TEXT -> {
                val text = String(payload, Charsets.UTF_8)
                DecompressedResult(text, null)
            }
            PacketType.COMPRESSED_TEXT -> {
                val inflated = inflate(payload)
                val text = String(inflated, Charsets.UTF_8)
                DecompressedResult(text, null)
            }
            PacketType.SEMANTIC -> {
                val semantic = SemanticMessage.fromBinaryPayload(payload)
                DecompressedResult(semantic?.rawText ?: "", semantic)
            }
            PacketType.EMERGENCY, PacketType.SOS -> {
                val semantic = decompressEmergency(payload, sourceLanguage)
                val displayText = if (semantic.rawText.isNotBlank() && semantic.rawText != "CRITICAL SOS BEACON BROADCAST") {
                    semantic.rawText
                } else {
                    "EMERGENCY DISTRESS BEACON"
                }
                DecompressedResult(displayText, semantic)
            }
            else -> DecompressedResult(String(payload, Charsets.UTF_8), null)
        }
    }

    private fun compressEmergency(semantic: SemanticMessage): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(12)
        buffer.put(semantic.intent.id)
        buffer.put(semantic.priority.code)
        buffer.put(semantic.sourceLanguage.id)
        buffer.put(semantic.targetLanguage.id)
        buffer.putLong(semantic.timestamp)
        return buffer.array()
    }

    private fun decompressEmergency(data: ByteArray, fallbackLang: Language): SemanticMessage {
        if (data.size < 12) {
            return SemanticMessage(
                intent = SemanticIntent.EMERGENCY_MEDICAL,
                priority = Priority.EMERGENCY,
                rawText = "SOS BROADCAST",
                sourceLanguage = fallbackLang,
                targetLanguage = fallbackLang
            )
        }
        val buffer = java.nio.ByteBuffer.wrap(data)
        val intent = SemanticIntent.fromId(buffer.get())
        val priority = Priority.fromCode(buffer.get())
        val srcLang = Language.fromId(buffer.get())
        val tgtLang = Language.fromId(buffer.get())
        val timestamp = buffer.getLong()

        val extraText = if (data.size > 12) {
            String(data, 12, data.size - 12, Charsets.UTF_8).trim()
        } else {
            ""
        }
        val rawMsg = if (extraText.isNotBlank()) extraText else "CRITICAL SOS BEACON BROADCAST"

        return SemanticMessage(
            intent = intent,
            priority = priority,
            rawText = rawMsg,
            sourceLanguage = srcLang,
            targetLanguage = tgtLang,
            timestamp = timestamp
        )
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(input)
        deflater.finish()
        val stream = ByteArrayOutputStream(input.size)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            stream.write(buffer, 0, count)
        }
        deflater.end()
        return stream.toByteArray()
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(input)
        val stream = ByteArrayOutputStream(input.size * 2)
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            stream.write(buffer, 0, count)
        }
        inflater.end()
        return stream.toByteArray()
    }

    private fun calculateRatio(original: Int, compressed: Int): Float {
        if (original <= 0) return 0f
        val saved = original - compressed
        return ((saved.toFloat() / original.toFloat()) * 100f).coerceIn(0f, 99.99f)
    }

    data class DecompressedResult(
        val reconstructedText: String,
        val semantic: SemanticMessage?
    )
}
