package org.sih.itantra.domain.model

enum class PacketType(val code: Byte, val description: String) {
    NORMAL_TEXT(1, "Full UTF-8 Text"),
    COMPRESSED_TEXT(2, "Deflate Compressed Text"),
    SEMANTIC(3, "Compact Semantic Intent & Entities"),
    EMERGENCY(4, "High Priority Emergency Event"),
    SOS(5, "Ultra-Compact Broadcast SOS Beacon"),
    ACK(6, "Acknowledgement Receipt"),
    NACK(7, "Negative Acknowledgement (Retry)"),
    HELLO(8, "Mesh Neighbor Discovery"),
    HEARTBEAT(9, "Link Keepalive Telemetry");

    companion object {
        fun fromCode(code: Byte): PacketType = entries.firstOrNull { it.code == code } ?: NORMAL_TEXT
    }
}
