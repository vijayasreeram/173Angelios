package org.sih.itantra

import org.junit.Assert.*
import org.junit.Test
import org.sih.itantra.domain.model.*
import org.sih.itantra.ml.semantic.SemanticDecoder
import org.sih.itantra.ml.semantic.SemanticEncoder
import org.sih.itantra.network.compression.AdaptiveCompressor
import org.sih.itantra.network.mesh.MeshRouter
import org.sih.itantra.network.packet.Crc16

class OverTheAirPipelineTest {

    private val encoder = SemanticEncoder()
    private val decoder = SemanticDecoder()
    private val compressor = AdaptiveCompressor()

    @Test
    fun testAllTenIndianLanguagesSupported() {
        val languages = Language.entries
        assertEquals("Should support 11 languages", 11, languages.size)
        
        for (lang in languages) {
            assertNotNull("Language code should not be null", lang.code)
            assertNotNull("Native name should not be null", lang.nativeName)
            assertEquals("Language lookup by ID must be reflexive", lang, Language.fromId(lang.id))
            assertEquals("Language lookup by Code must be reflexive", lang, Language.fromCode(lang.code))
        }
    }

    @Test
    fun testCrossLingualSemanticEncodingDecoding() {
        val englishInput = "Emergency: 1 person injured near north checkpoint"
        val semantic = encoder.encode(englishInput, Language.ENGLISH, Language.HINDI)

        assertEquals(SemanticIntent.EMERGENCY_MEDICAL, semantic.intent)
        assertEquals(Priority.EMERGENCY, semantic.priority)
        assertTrue("Entities should capture checkpoint", semantic.entities.containsKey("location"))

        // Decode into Hindi
        val hindiOutput = decoder.decode(semantic, Language.HINDI)
        assertTrue("Hindi output must contain emergency word", hindiOutput.contains("आपातकाल"))

        // Decode into Tamil
        val tamilOutput = decoder.decode(semantic, Language.TAMIL)
        assertTrue("Tamil output must contain emergency word", tamilOutput.contains("அவசரம்"))

        // Decode into Telugu
        val teluguOutput = decoder.decode(semantic, Language.TELUGU)
        assertTrue("Telugu output must contain emergency word", teluguOutput.contains("అత్యవసరం"))
    }

    @Test
    fun testAdaptiveCompressionRatios() {
        val text = "Critical situation at forward post bravo. Need medical evacuations."
        val semantic = encoder.encode(text, Language.ENGLISH, Language.HINDI)

        // Test Good Link (Full Text)
        val goodResult = compressor.compress(text, semantic, LinkQuality.GOOD, Priority.NORMAL)
        assertEquals(PacketType.NORMAL_TEXT, goodResult.packetType)

        // Test Poor Link (Compact Semantic)
        val poorResult = compressor.compress(text, semantic, LinkQuality.POOR, Priority.NORMAL)
        assertEquals(PacketType.SEMANTIC, poorResult.packetType)
        assertTrue("Semantic payload must be much smaller than text", poorResult.compressedBytes < text.length)

        // Test Emergency Link (Ultra-compact bitstream)
        val emergencyResult = compressor.compress(text, semantic, LinkQuality.EMERGENCY, Priority.EMERGENCY)
        assertEquals(PacketType.EMERGENCY, emergencyResult.packetType)
        assertEquals(12, emergencyResult.compressedBytes) // 12-byte ultra-dense wire payload
    }

    @Test
    fun testFullWireTransmissionRoundTrip() {
        val sourceText = "Sector 4 secured. Bravo unit standing by."
        val semantic = encoder.encode(sourceText, Language.ENGLISH, Language.HINDI)
        val compResult = compressor.compress(sourceText, semantic, LinkQuality.POOR, Priority.NORMAL)

        val packet = Packet(
            senderId = "NODE_001",
            receiverId = "BRDCAST_",
            languageId = Language.ENGLISH.id,
            priority = Priority.NORMAL,
            packetType = compResult.packetType,
            payload = compResult.payload
        )

        val wireBytes = packet.toByteArray()
        assertTrue("Packet must include CRC16", wireBytes.size >= Packet.HEADER_SIZE + Packet.CRC_SIZE)

        // Receive on other device
        val receivedPacket = Packet.fromByteArray(wireBytes)
        assertNotNull(receivedPacket)

        val decompressed = compressor.decompress(
            receivedPacket!!.payload,
            receivedPacket.packetType,
            receivedPacket.language
        )

        assertNotNull(decompressed.semantic)
        val finalSpeechText = decoder.decode(decompressed.semantic!!, Language.HINDI)
        assertNotNull(finalSpeechText)
        assertTrue("Final speech text must not be empty", finalSpeechText.isNotBlank())
    }

    @Test
    fun testMeshRouterStoreAndForward() {
        var forwardedPacket: Packet? = null
        val forwardRouter = MeshRouter(localNodeId = "NODE_LOCAL") { fwd ->
            forwardedPacket = fwd
            true
        }

        val broadcastPacket = Packet(
            messageId = 999111L,
            senderId = "NODE_REMOTE",
            receiverId = "BRDCAST_",
            ttl = 3,
            payload = "BROADCAST_PAYLOAD".toByteArray()
        )

        // First ingestion: should accept and forward (TTL 3 -> 2)
        kotlinx.coroutines.runBlocking {
            val action = forwardRouter.routeIncomingPacket(broadcastPacket)
            assertEquals(MeshRouter.RouteAction.ACCEPTED_AND_FORWARDED, action)
            assertNotNull(forwardedPacket)
            assertEquals(2.toByte(), forwardedPacket!!.ttl)

            // Second ingestion of same messageId: duplicate suppression
            val duplicateAction = forwardRouter.routeIncomingPacket(broadcastPacket)
            assertEquals(MeshRouter.RouteAction.DROPPED_DUPLICATE, duplicateAction)
        }
    }

    @Test
    fun testOfflineArbitraryEnglishToTamilTranslation() {
        val translator = org.sih.itantra.ml.translation.OfflineTranslatorEngine()

        // 1. Spoken conversational sentences
        val t1 = translator.translate("Can you hear me", Language.TAMIL)
        assertTrue("Must translate can you hear me to Tamil", t1.contains("கேட்கிறதா"))

        val t2 = translator.translate("We need food and water", Language.TAMIL)
        assertTrue("Must translate food and water to Tamil", t2.contains("குடிநீர்") || t2.contains("உணவு"))

        val t3 = translator.translate("The road is blocked do not come this way", Language.TAMIL)
        assertTrue("Must translate road blocked to Tamil", t3.contains("சாலை") || t3.contains("அடைக்கப்பட்டுள்ளது"))

        val t4 = translator.translate("All clear area is secure", Language.TAMIL)
        assertTrue("Must translate all clear to Tamil", t4.contains("பாதுகாப்") || t4.contains("சரி"))

        // 2. Hindi translation
        val h1 = translator.translate("We need food and water", Language.HINDI)
        assertTrue("Must translate food and water to Hindi", h1.contains("पानी") || h1.contains("भोजन"))
    }

    @Test
    fun testOfflineTamilToEnglishBidirectionalTranslation() {
        val translator = org.sih.itantra.ml.translation.OfflineTranslatorEngine()

        // 1. Common Tamil Greetings & Verification to English
        val e1 = translator.translate("வணக்கம்", Language.ENGLISH, Language.TAMIL)
        assertEquals("Hello", e1)

        val e2 = translator.translate("நான் பேசுவது கேட்கிறதா", Language.ENGLISH, Language.TAMIL)
        assertTrue("Must translate audio verification to English", e2.contains("hear", ignoreCase = true))

        // 2. Tactical Supplies (Tamil -> English)
        val e3 = translator.translate("எங்களுக்கு உணவும் தண்ணீரும் தேவை", Language.ENGLISH, Language.TAMIL)
        assertTrue("Must contain food and water", e3.contains("food", ignoreCase = true) && e3.contains("water", ignoreCase = true))

        // 3. Movement & Road Block (Tamil -> English)
        val e4 = translator.translate("சாலை அடைக்கப்பட்டுள்ளது இந்த வழியில் யாரும் வர வேண்டாம்", Language.ENGLISH, Language.TAMIL)
        assertTrue("Must contain road and blocked", e4.contains("road", ignoreCase = true) && e4.contains("blocked", ignoreCase = true))

        // 4. Status Check (Tamil -> English)
        val e5 = translator.translate("நாங்கள் பாதுகாப்பாக இருக்கிறோம்", Language.ENGLISH, Language.TAMIL)
        assertTrue("Must contain safe", e5.contains("safe", ignoreCase = true))

        // 5. Medical Emergency (Tamil -> English)
        val e6 = translator.translate("மருத்துவக் குழு மற்றும் மருத்துவர் தேவை", Language.ENGLISH, Language.TAMIL)
        assertTrue("Must contain doctor or medical", e6.contains("doctor", ignoreCase = true) || e6.contains("medical", ignoreCase = true))

        // 6. Automatic script detection without explicitly passing sourceLanguage (auto-infers Tamil)
        val e7 = translator.translate("வணக்கம்", Language.ENGLISH)
        assertEquals("Hello", e7)

        val e8 = translator.translate("தயவுசெய்து எங்களுக்கு உதவுங்கள்", Language.ENGLISH)
        assertTrue("Must contain help", e8.contains("help", ignoreCase = true))
    }

    @Test
    fun testOfflineHindiToEnglishTranslation() {
        val translator = org.sih.itantra.ml.translation.OfflineTranslatorEngine()

        val h1 = translator.translate("नमस्ते", Language.ENGLISH, Language.HINDI)
        assertEquals("Hello", h1)

        val h2 = translator.translate("हमें भोजन और पानी की आवश्यकता है", Language.ENGLISH, Language.HINDI)
        assertTrue("Must contain food and water", h2.contains("food", ignoreCase = true) && h2.contains("water", ignoreCase = true))

        val h3 = translator.translate("सड़क अवरुद्ध है", Language.ENGLISH, Language.HINDI)
        assertTrue("Must contain road and blocked", h3.contains("road", ignoreCase = true) && h3.contains("blocked", ignoreCase = true))
    }
}
