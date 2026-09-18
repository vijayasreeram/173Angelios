package org.sih.itantra.domain.model

import org.sih.itantra.network.packet.Crc16
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tactical Packet Structure for iTANTRA mesh and point-to-point transports.
 *
 * Wire format:
 * [0..1]   MAGIC HEADER (0x53, 0x49 -> "SI")
 * [2]      VERSION (1 byte)
 * [3..10]  MESSAGE ID (8 bytes, Long)
 * [11..18] SENDER ID (8 bytes UTF-8 ASCII)
 * [19..26] RECEIVER ID (8 bytes UTF-8 ASCII or "BRDCAST_")
 * [27]     LANGUAGE ID (1 byte)
 * [28]     PRIORITY (1 byte)
 * [29]     TTL (1 byte)
 * [30]     PACKET TYPE (1 byte)
 * [31..32] PAYLOAD LENGTH (2 bytes unsigned short)
 * [33..N]  PAYLOAD (N bytes)
 * [N+1..N+2] CRC16 (2 bytes unsigned short)
 */
data class Packet(
    val version: Byte = 1,
    val messageId: Long = System.currentTimeMillis() xor (java.util.Random().nextLong() and 0x7FFFFFFF),
    val senderId: String = "NODE_001",
    val receiverId: String = "BRDCAST_",
    val languageId: Byte = Language.ENGLISH.id,
    val priority: Priority = Priority.NORMAL,
    val ttl: Byte = 5,
    val packetType: PacketType = PacketType.SEMANTIC,
    val payload: ByteArray = ByteArray(0),
    val crc16: Int = 0
) {
    val payloadLength: Int get() = payload.size

    val language: Language get() = Language.fromId(languageId)

    /**
     * Serializes this packet to binary wire format including computing CRC16 over header + payload.
     */
    fun toByteArray(): ByteArray {
        val totalSize = HEADER_SIZE + payload.size + CRC_SIZE
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)
        buffer.put(version)
        buffer.putLong(messageId)

        // Fixed 8-byte sender ID
        val sBytes = senderId.padEnd(8, ' ').take(8).toByteArray(Charsets.US_ASCII)
        buffer.put(sBytes)

        // Fixed 8-byte receiver ID
        val rBytes = receiverId.padEnd(8, ' ').take(8).toByteArray(Charsets.US_ASCII)
        buffer.put(rBytes)

        buffer.put(languageId)
        buffer.put(priority.code)
        buffer.put(ttl)
        buffer.put(packetType.code)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)

        // Calculate CRC16 on the bytes written so far
        val contentBytes = ByteArray(HEADER_SIZE + payload.size)
        System.arraycopy(buffer.array(), 0, contentBytes, 0, contentBytes.size)
        val computedCrc = Crc16.compute(contentBytes)

        buffer.putShort(computedCrc.toShort())

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Packet
        if (messageId != other.messageId) return false
        if (senderId != other.senderId) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val MAGIC_0: Byte = 0x53 // 'S'
        const val MAGIC_1: Byte = 0x49 // 'I'
        const val HEADER_SIZE = 33
        const val CRC_SIZE = 2

        /**
         * Parses and strictly validates a packet from raw byte array.
         * Returns null if magic bytes, length, or CRC16 checksum fail.
         */
        fun fromByteArray(data: ByteArray): Packet? {
            if (data.size < HEADER_SIZE + CRC_SIZE) return null

            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

            val m0 = buffer.get()
            val m1 = buffer.get()
            if (m0 != MAGIC_0 || m1 != MAGIC_1) return null

            val version = buffer.get()
            val messageId = buffer.getLong()

            val sBytes = ByteArray(8)
            buffer.get(sBytes)
            val senderId = String(sBytes, Charsets.US_ASCII).trim()

            val rBytes = ByteArray(8)
            buffer.get(rBytes)
            val receiverId = String(rBytes, Charsets.US_ASCII).trim()

            val languageId = buffer.get()
            val priorityCode = buffer.get()
            val ttl = buffer.get()
            val typeCode = buffer.get()
            val payloadLen = buffer.short.toInt() and 0xFFFF

            if (buffer.remaining() < payloadLen + CRC_SIZE) return null

            val payload = ByteArray(payloadLen)
            buffer.get(payload)

            val receivedCrc = buffer.short.toInt() and 0xFFFF

            // Validate CRC
            val contentBytes = ByteArray(HEADER_SIZE + payloadLen)
            System.arraycopy(data, 0, contentBytes, 0, contentBytes.size)
            if (!Crc16.verify(contentBytes, receivedCrc)) {
                return null // Corrupted packet rejected
            }

            return Packet(
                version = version,
                messageId = messageId,
                senderId = senderId,
                receiverId = receiverId,
                languageId = languageId,
                priority = Priority.fromCode(priorityCode),
                ttl = ttl,
                packetType = PacketType.fromCode(typeCode),
                payload = payload,
                crc16 = receivedCrc
            )
        }
    }
}
