package org.sih.itantra.network.mesh

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.TransportType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * Tactical Mesh Router.
 * Implements store-and-forward, multi-hop forwarding, duplicate suppression,
 * TTL decrement, and strict emergency-first priority queuing.
 */
class MeshRouter(
    val localNodeId: String = "NODE_ALPHA_01",
    private val forwardPacketHandler: (suspend (Packet) -> Boolean)? = null
) {
    // Sliding window of processed message IDs to prevent loops/duplicate broadcasts
    private val seenMessageCache = ConcurrentHashMap<Long, Long>()

    // Priority Queue for Outbound Transmissions
    private val priorityOutbox = PriorityBlockingQueue<PrioritizedPacket>(32) { a, b ->
        // Emergency > Important > Normal
        val pDiff = b.packet.priority.code.compareTo(a.packet.priority.code)
        if (pDiff != 0) pDiff else a.enqueuedTime.compareTo(b.enqueuedTime)
    }

    // Active Neighbors and Routing Table
    private val _neighbors = ConcurrentHashMap<String, NetworkNode>()
    val neighbors: Map<String, NetworkNode> get() = _neighbors

    // Verified packets destined for this local node
    private val _deliveredPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val deliveredPackets: SharedFlow<Packet> = _deliveredPackets.asSharedFlow()

    data class PrioritizedPacket(
        val packet: Packet,
        val enqueuedTime: Long = System.currentTimeMillis()
    )

    init {
        // Seed default local topology for tactical mesh operation
        registerNeighbor(NetworkNode("NODE_BRAVO_02", "Relay Bravo", -64, 92, TransportType.WIFI_DIRECT, 1))
        registerNeighbor(NetworkNode("NODE_CHARLIE_03", "Base Camp Alpha", -78, 74, TransportType.BLE, 2))
        registerNeighbor(NetworkNode("NODE_DELTA_04", "Field Squad 4", -89, 58, TransportType.LORA, 3))
    }

    fun registerNeighbor(node: NetworkNode) {
        _neighbors[node.id] = node
    }

    /**
     * Enqueues an outgoing packet with priority handling.
     * Emergency packets immediately jump to the head of the queue.
     */
    fun enqueueOutgoing(packet: Packet): PrioritizedPacket {
        seenMessageCache[packet.messageId] = System.currentTimeMillis()
        val item = PrioritizedPacket(packet)
        priorityOutbox.offer(item)
        return item
    }

    fun pollNextOutgoing(): Packet? {
        return priorityOutbox.poll()?.packet
    }

    /**
     * Ingestion point for all incoming wire packets from any transport.
     * Performs duplicate check, TTL check, and routes to local node or forwards to next hop.
     */
    suspend fun routeIncomingPacket(packet: Packet): RouteAction {
        // 0. Drop any packets originated by this local node (prevents local voice playback echo)
        val cleanSender = packet.senderId.trim()
        val cleanLocal = localNodeId.trim()
        if (cleanSender.equals(cleanLocal, ignoreCase = true)) {
            return RouteAction.DROPPED_DUPLICATE
        }

        // 1. Duplicate Suppression
        if (seenMessageCache.containsKey(packet.messageId)) {
            return RouteAction.DROPPED_DUPLICATE
        }
        seenMessageCache[packet.messageId] = System.currentTimeMillis()
        pruneSeenCache()

        // 2. Check if packet is destined for local node or broadcast
        val isForMe = packet.receiverId.trim() == localNodeId ||
                packet.receiverId.trim() == "BRDCAST_" ||
                packet.receiverId.trim() == "BROADCAST"

        if (isForMe) {
            _deliveredPackets.tryEmit(packet)
        }

        // Discovery beacons are link-local: re-broadcasting them only floods the channel with duplicates.
        if (packet.packetType == org.sih.itantra.domain.model.PacketType.HELLO ||
            packet.packetType == org.sih.itantra.domain.model.PacketType.HEARTBEAT) {
            return if (isForMe) RouteAction.ACCEPTED_LOCAL else RouteAction.DROPPED_TTL_EXPIRED
        }

        // 3. Multi-Hop Forwarding if broadcast or for another node and TTL > 1
        if (packet.ttl > 1) {
            val forwardedPacket = packet.copy(ttl = (packet.ttl - 1).toByte())
            forwardPacketHandler?.invoke(forwardedPacket)
            return if (isForMe) RouteAction.ACCEPTED_AND_FORWARDED else RouteAction.FORWARDED
        }

        return if (isForMe) RouteAction.ACCEPTED_LOCAL else RouteAction.DROPPED_TTL_EXPIRED
    }

    private fun pruneSeenCache() {
        if (seenMessageCache.size > 2048) {
            val now = System.currentTimeMillis()
            val iterator = seenMessageCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 300_000L) { // 5 minutes TTL
                    iterator.remove()
                }
            }
        }
    }

    enum class RouteAction {
        ACCEPTED_LOCAL,
        ACCEPTED_AND_FORWARDED,
        FORWARDED,
        DROPPED_DUPLICATE,
        DROPPED_TTL_EXPIRED
    }
}
