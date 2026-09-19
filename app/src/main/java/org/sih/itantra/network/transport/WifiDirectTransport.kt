package org.sih.itantra.network.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.*
import org.sih.itantra.domain.model.Packet
import org.sih.itantra.domain.model.TransportType
import java.io.ByteArrayOutputStream
import java.net.*

/**
 * Wi-Fi Direct & Ad-hoc Local Mesh Transport.
 * Offers real peer-to-peer over-the-air communication using UDP broadcast and Wi-Fi Direct sockets.
 * Operates autonomously without cellular infrastructure or internet connectivity.
 */
class WifiDirectTransport(
    private val context: Context,
    var localNodeId: String = "NODE_UNKNOWN"
) : NetworkTransport {
    override val transportType: TransportType = TransportType.WIFI_DIRECT

    companion object {
        const val MESH_PORT = 8988
        private const val TAG = "WifiDirectTransport"
    }

    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = p2pManager?.initialize(context, context.mainLooper, null)

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private var serverSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var listeningJob: Job? = null
    private var tcpListeningJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val isAvailable: Boolean
        get() = true

    var onPacketReceived: ((Packet) -> Unit)? = null

    val knownPeerIps = java.util.concurrent.CopyOnWriteArraySet<String>()

    @SuppressLint("MissingPermission")
    override suspend fun start() {
        try {
            // 1. Acquire Multicast Lock to allow receipt of broadcast/multicast UDP packets on Android
            try {
                if (multicastLock?.isHeld != true) {
                    multicastLock = wifiManager?.createMulticastLock("itantra_p2p_multicast")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not acquire multicast lock: ${t.message}")
            }

            // 2 & 3. Open UDP + TCP sockets independently: a failure of one must never leave the
            // device unable to receive on the other (previously any bind error aborted start()).
            ensureUdpSocket()
            ensureTcpSocket()

            // 4. Launch background UDP receiver coroutine
            listeningJob?.cancel()
            listeningJob = scope.launch {
                val buffer = ByteArray(4096)
                while (isActive) {
                    try {
                        val sock = serverSocket
                        if (sock == null || sock.isClosed) {
                            // Socket lost (e.g. Wi-Fi reconnect): retry instead of busy-spinning
                            delay(1000L)
                            ensureUdpSocket()
                            continue
                        }
                        val datagram = DatagramPacket(buffer, buffer.size)
                        sock.receive(datagram)

                        val peerIp = datagram.address?.hostAddress
                        if (!peerIp.isNullOrBlank() && peerIp != "127.0.0.1") {
                            knownPeerIps.add(peerIp)
                        }

                        val payloadBytes = datagram.data.copyOfRange(datagram.offset, datagram.offset + datagram.length)
                        val incomingPacket = Packet.fromByteArray(payloadBytes)

                        if (incomingPacket != null) {
                            val cleanSender = incomingPacket.senderId.trim()
                            val cleanLocal = localNodeId.trim()
                            val isSelf = cleanSender.equals(cleanLocal, ignoreCase = true)

                            if (!isSelf) {
                                Log.d(TAG, "Received UDP packet from $cleanSender ($peerIp): ${incomingPacket.messageId}")
                                withContext(Dispatchers.Default) {
                                    onPacketReceived?.invoke(incomingPacket)
                                }
                            } else {
                                Log.d(TAG, "Ignored loopback of self-transmitted packet ${incomingPacket.messageId}")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (isActive) {
                            Log.w(TAG, "Error in UDP receive loop: ${e.message}")
                            delay(200L)
                        }
                    }
                }
            }

            // 5. Launch background TCP receiver coroutine
            tcpListeningJob?.cancel()
            tcpListeningJob = scope.launch {
                while (isActive) {
                    try {
                        val server = tcpServerSocket
                        if (server == null || server.isClosed) {
                            delay(1000L)
                            ensureTcpSocket()
                            continue
                        }
                        val client = server.accept()
                        scope.launch(Dispatchers.IO) {
                            try {
                                val peerIp = client.inetAddress?.hostAddress
                                if (!peerIp.isNullOrBlank() && peerIp != "127.0.0.1") {
                                    knownPeerIps.add(peerIp)
                                }

                                val stream = client.getInputStream()
                                val buffer = ByteArray(4096)
                                val baos = ByteArrayOutputStream()
                                client.soTimeout = 2000

                                val readCount = stream.read(buffer)
                                if (readCount > 0) {
                                    baos.write(buffer, 0, readCount)
                                    val incomingPacket = Packet.fromByteArray(baos.toByteArray())
                                    if (incomingPacket != null) {
                                        val cleanSender = incomingPacket.senderId.trim()
                                        val cleanLocal = localNodeId.trim()
                                        val isSelf = cleanSender.equals(cleanLocal, ignoreCase = true)

                                        if (!isSelf) {
                                            Log.d(TAG, "Received TCP packet from $cleanSender ($peerIp): ${incomingPacket.messageId}")
                                            withContext(Dispatchers.Default) {
                                                onPacketReceived?.invoke(incomingPacket)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.v(TAG, "TCP connection handled: ${e.message}")
                            } finally {
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        if (isActive) {
                            Log.w(TAG, "Error in TCP accept loop: ${e.message}")
                            delay(200L)
                        }
                    }
                }
            }

            Log.i(TAG, "Wi-Fi Direct / Local Mesh Transport started on port $MESH_PORT (UDP & TCP)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Wi-Fi Direct sockets", e)
        }
    }

    private fun ensureUdpSocket() {
        try {
            if (serverSocket == null || serverSocket?.isClosed == true) {
                serverSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MESH_PORT))
                }
                Log.i(TAG, "UDP socket bound on port $MESH_PORT")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bind UDP socket on $MESH_PORT: ${t.message}")
        }
    }

    private fun ensureTcpSocket() {
        try {
            if (tcpServerSocket == null || tcpServerSocket?.isClosed == true) {
                tcpServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MESH_PORT))
                }
                Log.i(TAG, "TCP socket bound on port $MESH_PORT")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not bind TCP ServerSocket on $MESH_PORT: ${t.message}")
        }
    }

    /**
     * The Wi-Fi network the phone is joined to. When that Wi-Fi has no internet (typical for a
     * hotspot / field router) Android routes an unbound socket over mobile data instead, so LAN
     * broadcasts and peer connections never reach the other phone. Binding to the Wi-Fi network fixes that.
     */
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): android.net.Network? = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.allNetworks?.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Throwable) {
        null
    }

    private fun bindToWifi(socket: DatagramSocket) {
        try { wifiNetwork()?.bindSocket(socket) } catch (t: Throwable) {
            Log.v(TAG, "UDP bindSocket to Wi-Fi skipped: ${t.message}")
        }
    }

    private fun bindToWifi(socket: Socket) {
        try { wifiNetwork()?.bindSocket(socket) } catch (t: Throwable) {
            Log.v(TAG, "TCP bindSocket to Wi-Fi skipped: ${t.message}")
        }
    }

    override suspend fun stop() {
        listeningJob?.cancel()
        listeningJob = null
        tcpListeningJob?.cancel()
        tcpListeningJob = null
        try {
            serverSocket?.close()
            serverSocket = null
            tcpServerSocket?.close()
            tcpServerSocket = null
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping transport", e)
        }
    }

    /**
     * Resolves the default gateway IPv4 address (e.g. Hotspot host or Wi-Fi AP).
     */
    fun getGatewayIp(): String? {
        try {
            val dhcp = wifiManager?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val gw = dhcp.gateway
                return "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
            }
        } catch (_: Exception) {}

        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val linkProps = cm?.getLinkProperties(cm.activeNetwork)
            linkProps?.routes?.forEach { route ->
                route.gateway?.let { gw ->
                    if (gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress) {
                        return gw.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Reads /proc/net/arp to detect all hardware-connected Wi-Fi peers
     * (Hotspot clients, Wi-Fi Direct peers, LAN devices).
     */
    fun getDiscoveredArpIps(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val arpDoc = java.io.File("/proc/net/arp")
            if (arpDoc.exists() && arpDoc.canRead()) {
                arpDoc.bufferedReader().useLines { lines ->
                    for (line in lines.drop(1)) {
                        val tokens = line.split(Regex("\\s+"))
                        if (tokens.size >= 4) {
                            val ip = tokens[0]
                            val mac = tokens[3]
                            if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) && mac != "00:00:00:00:00:00") {
                                ips.add(ip)
                                knownPeerIps.add(ip)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "Could not read /proc/net/arp: ${e.message}")
        }
        return ips
    }

    /**
     * Dynamically gathers all candidate IP addresses to reach all peers on the network.
     */
    fun getDynamicTargetAddresses(destinationAddress: String? = null): Set<InetAddress> {
        val targetAddresses = mutableSetOf<InetAddress>()

        // 1. Explicit destination if supplied
        if (destinationAddress != null) {
            try { targetAddresses.add(InetAddress.getByName(destinationAddress)) } catch (_: Exception) {}
        }

        // 2. Global subnet broadcast
        try { targetAddresses.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}

        // 3. Dynamic Gateway (Hotspot Host)
        getGatewayIp()?.let { gw ->
            try {
                targetAddresses.add(InetAddress.getByName(gw))
                knownPeerIps.add(gw)
            } catch (_: Exception) {}
        }

        // 4. Standard gateways across all Android Hotspot & Wi-Fi Direct implementations
        val standardGateways = listOf(
            "192.168.49.1", "192.168.49.255",
            "192.168.43.1", "192.168.43.255",
            "192.168.1.1", "192.168.1.255",
            "192.168.0.1", "192.168.0.255",
            "192.168.225.1", "192.168.225.255",
            "192.168.137.1", "192.168.137.255",
            "172.20.10.1", "172.20.10.255"
        )
        for (gw in standardGateways) {
            try { targetAddresses.add(InetAddress.getByName(gw)) } catch (_: Exception) {}
        }

        // 5. Interface broadcast addresses & active subnet client pools
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    addr.broadcast?.let { targetAddresses.add(it) }

                    // Compute /24 subnet prefix and target standard Android DHCP client IPs
                    val ip = addr.address
                    if (ip is Inet4Address && !ip.isLoopbackAddress) {
                        val host = ip.hostAddress ?: continue
                        val parts = host.split(".")
                        if (parts.size == 4) {
                            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."
                            // Target gateway .1
                            try { targetAddresses.add(InetAddress.getByName("${prefix}1")) } catch (_: Exception) {}
                            // Target standard Android DHCP client range (.2 to .20 and .100 to .115)
                            for (lastOctet in 2..20) {
                                try { targetAddresses.add(InetAddress.getByName("$prefix$lastOctet")) } catch (_: Exception) {}
                            }
                            for (lastOctet in 100..115) {
                                try { targetAddresses.add(InetAddress.getByName("$prefix$lastOctet")) } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 6. All ARP peers and known received peer IPs
        for (ip in getDiscoveredArpIps()) {
            try { targetAddresses.add(InetAddress.getByName(ip)) } catch (_: Exception) {}
        }
        for (ip in knownPeerIps) {
            try { targetAddresses.add(InetAddress.getByName(ip)) } catch (_: Exception) {}
        }

        return targetAddresses
    }

    /**
     * Broadcasts a discovery beacon over all available Wi-Fi interfaces and peer IPs.
     */
    suspend fun broadcastDiscoveryBeacon(
        nodeId: String,
        deviceName: String,
        batteryPct: Int
    ): Boolean {
        val payload = "iTANTRA_PEER|$deviceName|$batteryPct".toByteArray(Charsets.UTF_8)
        val beacon = Packet(
            senderId = nodeId,
            receiverId = "BRDCAST_",
            packetType = org.sih.itantra.domain.model.PacketType.HELLO,
            priority = org.sih.itantra.domain.model.Priority.NORMAL,
            payload = payload
        )
        return sendPacket(beacon)
    }

    override suspend fun sendPacket(packet: Packet, destinationAddress: String?): Boolean = withContext(Dispatchers.IO) {
        val wireBytes = packet.toByteArray()

        try {
            val socket = DatagramSocket().apply {
                broadcast = true
            }
            bindToWifi(socket)

            val targetAddresses = getDynamicTargetAddresses(destinationAddress)

            // 1. Broadcast over-the-air UDP across all target endpoints
            for (addr in targetAddresses) {
                try {
                    val datagram = DatagramPacket(wireBytes, wireBytes.size, addr, MESH_PORT)
                    socket.send(datagram)
                } catch (e: Exception) {
                    Log.v(TAG, "Failed to UDP send to $addr", e)
                }
            }
            try { socket.close() } catch (_: Exception) {}

            // 2. Parallel TCP Direct Delivery to all known peer IPs and gateway for 100% reliability
            val tcpTargets = mutableSetOf<String>()
            if (destinationAddress != null) tcpTargets.add(destinationAddress)
            tcpTargets.addAll(knownPeerIps)
            getGatewayIp()?.let { tcpTargets.add(it) }

            for (targetIp in tcpTargets) {
                if (targetIp == "127.0.0.1") continue
                scope.launch(Dispatchers.IO) {
                    try {
                        Socket().use { tcp ->
                            bindToWifi(tcp)
                            tcp.connect(InetSocketAddress(targetIp, MESH_PORT), 400)
                            val out = tcp.getOutputStream()
                            out.write(wireBytes)
                            out.flush()
                        }
                    } catch (_: Exception) {}
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast packet", e)
            false
        }
    }
}
