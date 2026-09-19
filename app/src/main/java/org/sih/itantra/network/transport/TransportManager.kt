package org.sih.itantra.network.transport

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType

interface NetworkTransport {
    val transportType: TransportType
    val isAvailable: Boolean
    suspend fun start()
    suspend fun stop()
    suspend fun sendPacket(packet: Packet, destinationAddress: String? = null): Boolean
}

/**
 * TransportManager handles switching between BLE, Wi-Fi Direct, LoRa, and Loopback.
 * Dispatches incoming packets to the Mesh Router.
 */
class TransportManager(
    private val context: Context,
    private val bluetoothTransport: BluetoothTransport = BluetoothTransport(context),
    private val wifiDirectTransport: WifiDirectTransport = WifiDirectTransport(context),
    private val loraTransport: LoraTransport = LoraTransport(),
    private val loopbackTransport: LoopbackTransport = LoopbackTransport()
) {
    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val incomingPackets: SharedFlow<Packet> = _incomingPackets.asSharedFlow()

    private var activeTransportType: TransportType = TransportType.WIFI_DIRECT

    init {
        // Wire incoming packet feeds from all transports
        loopbackTransport.onPacketReceived = { packet ->
            _incomingPackets.tryEmit(packet)
        }
        wifiDirectTransport.onPacketReceived = { packet ->
            _incomingPackets.tryEmit(packet)
        }
        bluetoothTransport.onPacketReceived = { packet ->
            _incomingPackets.tryEmit(packet)
        }
        loraTransport.onPacketReceived = { packet ->
            _incomingPackets.tryEmit(packet)
        }
    }

    fun setActiveTransport(type: TransportType) {
        activeTransportType = type
    }

    fun getActiveTransport(): TransportType = activeTransportType

    suspend fun startAll() {
        // Wi-Fi first (it is the transport that carries mesh discovery), and each transport is
        // isolated so a failure in one can never prevent the others from starting.
        val starters: List<Pair<String, suspend () -> Unit>> = listOf(
            "wifi" to { wifiDirectTransport.start() },
            "bluetooth" to { bluetoothTransport.start() },
            "lora" to { loraTransport.start() },
            "loopback" to { loopbackTransport.start() }
        )
        for ((name, start) in starters) {
            try {
                start()
            } catch (t: Throwable) {
                android.util.Log.e("TransportManager", "Transport '$name' failed to start: ${t.message}", t)
            }
        }
    }

    suspend fun stopAll() {
        bluetoothTransport.stop()
        wifiDirectTransport.stop()
        loraTransport.stop()
        loopbackTransport.stop()
    }

    suspend fun transmitPacket(packet: Packet, preferredTransport: TransportType? = null): Boolean {
        val targetTransport = preferredTransport ?: activeTransportType
        return when (targetTransport) {
            TransportType.WIFI_DIRECT -> wifiDirectTransport.sendPacket(packet)
            TransportType.BLE -> bluetoothTransport.sendPacket(packet)
            TransportType.LORA -> loraTransport.sendPacket(packet)
            TransportType.LOOPBACK -> loopbackTransport.sendPacket(packet)
        }
    }

    fun getTransport(type: TransportType): NetworkTransport = when (type) {
        TransportType.WIFI_DIRECT -> wifiDirectTransport
        TransportType.BLE -> bluetoothTransport
        TransportType.LORA -> loraTransport
        TransportType.LOOPBACK -> loopbackTransport
    }
}
