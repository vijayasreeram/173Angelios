package org.sih.itantra

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.network.mesh.MeshRouter

class MeshRoutingTest {

    @Test
    fun testDuplicateSuppression() = runBlocking {
        val forwardedList = mutableListOf<Packet>()
        val router = MeshRouter(localNodeId = "NODE_LOCAL") { packet ->
            forwardedList.add(packet)
            true
        }

        val packet = Packet(
            messageId = 99998888L,
            senderId = "NODE_A",
            receiverId = "NODE_LOCAL",
            ttl = 3
        )

        val firstAction = router.routeIncomingPacket(packet)
        assertEquals(MeshRouter.RouteAction.ACCEPTED_AND_FORWARDED, firstAction)

        // Same packet received again via alternate mesh relay
        val secondAction = router.routeIncomingPacket(packet)
        assertEquals("Duplicate packet must be suppressed", MeshRouter.RouteAction.DROPPED_DUPLICATE, secondAction)
    }

    @Test
    fun testTtlDecrementAndDropOnExpiry() = runBlocking {
        val forwardedList = mutableListOf<Packet>()
        val router = MeshRouter(localNodeId = "NODE_LOCAL") { packet ->
            forwardedList.add(packet)
            true
        }

        // Packet with TTL = 1 destined for another node
        val expiringPacket = Packet(
            messageId = 11112222L,
            senderId = "NODE_A",
            receiverId = "NODE_B", // Not for me
            ttl = 1
        )

        val action = router.routeIncomingPacket(expiringPacket)
        assertEquals("Packet with TTL=1 for another node must be dropped", MeshRouter.RouteAction.DROPPED_TTL_EXPIRED, action)
        assertTrue("No forwarding must occur for TTL=1", forwardedList.isEmpty())
    }

    @Test
    fun testPriorityOutboxOrdering() {
        val router = MeshRouter(localNodeId = "NODE_LOCAL")

        val normalPacket = Packet(messageId = 1L, priority = Priority.NORMAL)
        val importantPacket = Packet(messageId = 2L, priority = Priority.IMPORTANT)
        val emergencyPacket = Packet(messageId = 3L, priority = Priority.EMERGENCY)

        // Enqueue in reverse order
        router.enqueueOutgoing(normalPacket)
        router.enqueueOutgoing(importantPacket)
        router.enqueueOutgoing(emergencyPacket)

        // Polling order MUST be Emergency -> Important -> Normal
        val firstOut = router.pollNextOutgoing()
        assertNotNull(firstOut)
        assertEquals(Priority.EMERGENCY, firstOut!!.priority)

        val secondOut = router.pollNextOutgoing()
        assertNotNull(secondOut)
        assertEquals(Priority.IMPORTANT, secondOut!!.priority)

        val thirdOut = router.pollNextOutgoing()
        assertNotNull(thirdOut)
        assertEquals(Priority.NORMAL, thirdOut!!.priority)
    }

    @Test
    fun testSelfPacketSuppressedToPreventLocalEcho() = runBlocking {
        val forwardedList = mutableListOf<Packet>()
        val router = MeshRouter(localNodeId = "NODE_LOCAL") { packet ->
            forwardedList.add(packet)
            true
        }

        // Packet originated by NODE_LOCAL itself (e.g. broadcast or loopback)
        val selfPacket = Packet(
            messageId = 55554444L,
            senderId = "NODE_LOCAL",
            receiverId = "BRDCAST_",
            ttl = 3
        )

        val action = router.routeIncomingPacket(selfPacket)
        assertEquals("Self-packet must be suppressed as duplicate to prevent local echo", MeshRouter.RouteAction.DROPPED_DUPLICATE, action)
        assertTrue("Self-packet must never be forwarded or delivered locally", forwardedList.isEmpty())
    }
}
