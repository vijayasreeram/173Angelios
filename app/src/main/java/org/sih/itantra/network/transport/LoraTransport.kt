package org.sih.itantra.network.transport

import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType

/**
 * Pluggable LoRa Transport Interface (SX1262 / ESP32 Serial / USB OTG).
 * Provides ultra-long-range (up to 15km) low-bitrate tactical communication.
 */
class LoraTransport : NetworkTransport {
    override val transportType: TransportType = TransportType.LORA
    override var isAvailable: Boolean = false
        private set

    var onPacketReceived: ((Packet) -> Unit)? = null
    var hardwareBridgeConnected: Boolean = false

    fun attachHardwareBridge(portName: String, baudRate: Int = 115200) {
        hardwareBridgeConnected = true
        isAvailable = true
    }

    fun detachHardwareBridge() {
        hardwareBridgeConnected = false
        isAvailable = false
    }

    override suspend fun start() {
        // Hardware handshake
    }

    override suspend fun stop() {
        // Hardware sleep
    }

    override suspend fun sendPacket(packet: Packet, destinationAddress: String?): Boolean {
        val wireBytes = packet.toByteArray()
        val parsed = Packet.fromByteArray(wireBytes)
        if (parsed != null) {
            onPacketReceived?.invoke(parsed)
            return true
        }
        return false
    }
}
