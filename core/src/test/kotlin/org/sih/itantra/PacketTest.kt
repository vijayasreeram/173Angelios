package org.sih.itantra

import org.junit.Assert.*
import org.junit.Test
import org.sih.itantra.domain.model.*
import org.sih.itantra.network.packet.Crc16

class PacketTest {

    @Test
    fun testCrc16ComputationAndVerification() {
        val testData = "ISRO_TACTICAL_DATA_PACKET".toByteArray(Charsets.UTF_8)
        val computedCrc = Crc16.compute(testData)
        assertTrue("CRC16 should be non-zero", computedCrc != 0)
        assertTrue("CRC16 verification must pass on untampered data", Crc16.verify(testData, computedCrc))

        // Corrupt a byte
        testData[2] = (testData[2].toInt() xor 0xFF).toByte()
        assertFalse("CRC16 verification must fail on corrupted data", Crc16.verify(testData, computedCrc))
    }

    @Test
    fun testPacketSerializationAndParsing() {
        val payload = "TEST_PAYLOAD_BYTES".toByteArray(Charsets.UTF_8)
        val originalPacket = Packet(
            version = 1,
            messageId = 123456789L,
            senderId = "ALPHA_01",
            receiverId = "BRAVO_02",
            languageId = Language.TAMIL.id,
            priority = Priority.EMERGENCY,
            ttl = 5,
            packetType = PacketType.SEMANTIC,
            payload = payload
        )

        val wireBytes = originalPacket.toByteArray()
        assertNotNull("Wire bytes must not be null", wireBytes)
        assertTrue("Wire size must match header + payload + crc", wireBytes.size == Packet.HEADER_SIZE + payload.size + Packet.CRC_SIZE)

        val parsedPacket = Packet.fromByteArray(wireBytes)
        assertNotNull("Packet parsing must succeed", parsedPacket)
        assertEquals(originalPacket.version, parsedPacket!!.version)
        assertEquals(originalPacket.messageId, parsedPacket.messageId)
        assertEquals(originalPacket.senderId, parsedPacket.senderId)
        assertEquals(originalPacket.receiverId, parsedPacket.receiverId)
        assertEquals(originalPacket.languageId, parsedPacket.languageId)
        assertEquals(originalPacket.priority, parsedPacket.priority)
        assertEquals(originalPacket.ttl, parsedPacket.ttl)
        assertEquals(originalPacket.packetType, parsedPacket.packetType)
        assertArrayEquals(originalPacket.payload, parsedPacket.payload)
    }

    @Test
    fun testCorruptedPacketRejection() {
        val payload = "CRITICAL_SOS_STREAM".toByteArray(Charsets.UTF_8)
        val packet = Packet(payload = payload)
        val wireBytes = packet.toByteArray()

        // Flip bit in payload
        wireBytes[Packet.HEADER_SIZE + 2] = (wireBytes[Packet.HEADER_SIZE + 2].toInt() xor 0x01).toByte()

        val parsed = Packet.fromByteArray(wireBytes)
        assertNull("Corrupted packet must be rejected (return null)", parsed)
    }
}
