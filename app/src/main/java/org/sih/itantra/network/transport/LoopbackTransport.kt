package org.sih.itantra.network.transport

import kotlinx.coroutines.delay
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType

/**
 * Loopback & Simulated Transport.
 * Enables zero-hardware peer-to-peer demonstration and local validation of the full pipeline.
 */
class LoopbackTransport : NetworkTransport {
    override val transportType: TransportType = TransportType.LOOPBACK
    override val isAvailable: Boolean = true

    var onPacketReceived: ((Packet) -> Unit)? = null
    var simulatedLatencyMs: Long = 45L
    var simulatedPacketDropRate: Float = 0.0f

    override suspend fun start() {}
    override suspend fun stop() {}

    override suspend fun sendPacket(packet: Packet, destinationAddress: String?): Boolean {
        val random = java.util.Random().nextFloat()
        if (random < simulatedPacketDropRate) {
            return false // Simulated drop
        }

        delay(simulatedLatencyMs)

        // Serialize to wire bytes and parse back to thoroughly verify serialization integrity!
        val wireBytes = packet.toByteArray()
        val receivedPacket = Packet.fromByteArray(wireBytes)
        if (receivedPacket != null) {
            onPacketReceived?.invoke(receivedPacket)
            return true
        }
        return false
    }
}
