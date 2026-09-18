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
        // Emergency priority forces emergency or semantic packet regardless of link
        if (priority == Priority.EMERGENCY || linkQuality == LinkQuality.EMERGENCY) {
            val emergencyPayload = compressEmergency(semantic)
            return CompressionResult(
                packetType = PacketType.EMERGENCY,
                payload = emergencyPayload,
                uncompressedBytes = text.toByteArray(Charsets.UTF_8).size,
                compressedBytes = emergencyPayload.size,
                compressionRatioPct = calculateRatio(text.length * 2, emergencyPayload.size),
                strategy = "Ultra-Compact Emergency Bit-Stream"
            )
        }

        return when (linkQuality) {
            LinkQuality.GOOD -> {
                val utf8Bytes = text.toByteArray(Charsets.UTF_8)
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
                val utf8Bytes = text.toByteArray(Charsets.UTF_8)
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
                val binarySemantic = semantic.toBinaryPayload()
                val originalBytes = text.toByteArray(Charsets.UTF_8).size
                CompressionResult(
                    packetType = PacketType.SEMANTIC,
                    payload = binarySemantic,
                    uncompressedBytes = originalBytes,
                    compressedBytes = binarySemantic.size,
                    compressionRatioPct = calculateRatio(originalBytes, binarySemantic.size),
                    strategy = "Compact Semantic Tokens"
                )
            }

            LinkQuality.EMERGENCY -> {
                val emergencyPayload = compressEmergency(semantic)
                CompressionResult(
                    packetType = PacketType.SOS,
                    payload = emergencyPayload,
                    uncompressedBytes = text.toByteArray(Charsets.UTF_8).size,
                    compressedBytes = emergencyPayload.size,
                    compressionRatioPct = calculateRatio(text.length * 2, emergencyPayload.size),
                    strategy = "Emergency SOS Bitstream"
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
            PacketType.SEMANTIC, PacketType.EMERGENCY -> {
                val semantic = SemanticMessage.fromBinaryPayload(payload)
                DecompressedResult(semantic?.rawText ?: "", semantic)
            }
            PacketType.SOS -> {
                val semantic = decompressEmergency(payload, sourceLanguage)
                DecompressedResult("SOS BEACON BROADCAST", semantic)
            }
            else -> DecompressedResult(String(payload, Charsets.UTF_8), null)
        }
    }

    private fun compressEmergency(semantic: SemanticMessage): ByteArray {
        // Ultra-dense 12-byte payload:
        // [1B Intent][1B Priority][1B SourceLang][1B TargetLang][8B Timestamp]
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

        return SemanticMessage(
            intent = intent,
            priority = priority,
            rawText = "CRITICAL SOS BEACON BROADCAST",
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
