package org.sih.itantra

import org.junit.Assert.*
import org.junit.Test
import org.sih.itantra.domain.model.*
import org.sih.itantra.ml.semantic.SemanticDecoder
import org.sih.itantra.ml.semantic.SemanticEncoder
import org.sih.itantra.network.compression.AdaptiveCompressor

class SemanticCompressionTest {

    private val encoder = SemanticEncoder()
    private val decoder = SemanticDecoder()
    private val compressor = AdaptiveCompressor()

    @Test
    fun testTamilToHindiSemanticExtractionAndReconstruction() {
        val tamilInput = "அவசரம், வடக்கு சோதனைச் சாவடியில் ஒருவர் காயமடைந்துள்ளார்."
        val semantic = encoder.encode(tamilInput, Language.TAMIL, Language.HINDI)

        assertEquals("Intent must be EMERGENCY_MEDICAL", SemanticIntent.EMERGENCY_MEDICAL, semantic.intent)
        assertEquals("Priority must be EMERGENCY", Priority.EMERGENCY, semantic.priority)
        assertEquals("Location entity must match north_checkpoint", "north_checkpoint", semantic.entities["location"])
        assertEquals("Count entity must be 1", "1", semantic.entities["count"])

        // Test binary bit-packing
        val binary = semantic.toBinaryPayload()
        assertTrue("Semantic binary payload must be ultra-compact (< 80 bytes)", binary.size < 80)

        val unpacked = SemanticMessage.fromBinaryPayload(binary)
        assertNotNull("Unpacking binary semantic payload must succeed", unpacked)
        assertEquals(semantic.intent, unpacked!!.intent)
        assertEquals(semantic.entities["location"], unpacked.entities["location"])

        // Reconstruct in Hindi
        val reconstructedHindi = decoder.decode(unpacked, Language.HINDI)
        assertTrue("Reconstructed Hindi text must mention checkpost", reconstructedHindi.contains("चेकपॉइंट"))
        assertTrue("Reconstructed Hindi text must mention injured", reconstructedHindi.contains("घायल"))
    }

    @Test
    fun testEnglishEmergencyMedicalExtraction() {
        val englishInput = "Emergency, there is a person injured near north checkpoint."
        val semantic = encoder.encode(englishInput, Language.ENGLISH, Language.ENGLISH)

        assertEquals(SemanticIntent.EMERGENCY_MEDICAL, semantic.intent)
        assertEquals(Priority.EMERGENCY, semantic.priority)
        assertEquals("north_checkpoint", semantic.entities["location"])

        val reconstructedEnglish = decoder.decode(semantic, Language.ENGLISH)
        assertTrue("Reconstructed English must mention emergency", reconstructedEnglish.contains("Emergency"))
        assertTrue("Reconstructed English must mention north checkpoint", reconstructedEnglish.contains("North Checkpoint"))
    }

    @Test
    fun testAdaptiveCompressionStrategyAndPayloadReduction() {
        val input = "Emergency, there is a person injured near north checkpoint."
        val semantic = encoder.encode(input, Language.ENGLISH, Language.ENGLISH)

        // 1. Poor Link Quality -> Semantic compression
        val compResult = compressor.compress(input, semantic, LinkQuality.POOR, Priority.NORMAL)
        assertEquals(PacketType.SEMANTIC, compResult.packetType)
        assertTrue("Semantic payload should be < 80 bytes", compResult.compressedBytes < 80)

        // Calculate bandwidth savings compared to 2s 16kHz raw PCM (64,000 bytes)
        val rawAudioBytes = 64000
        val savingsPct = ((rawAudioBytes - compResult.compressedBytes).toFloat() / rawAudioBytes.toFloat()) * 100f
        assertTrue("Bandwidth reduction compared to raw audio must exceed 99%", savingsPct > 99.0f)
    }
}
