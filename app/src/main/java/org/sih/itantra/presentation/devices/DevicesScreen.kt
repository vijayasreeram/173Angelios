package org.sih.itantra.presentation.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.domain.model.TransportType
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.theme.*

/**
 * Device & Node Management Screen.
 * Allows viewing, pairing, prioritizing, and managing mesh communicator devices.
 */
@Composable
fun DevicesScreen(repository: CommunicationRepository) {
    val nodes by repository.nodes.collectAsState()
    val activeNode by repository.activeTargetNode.collectAsState()
    var selectedNodeForDialog by remember { mutableStateOf<NetworkNode?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceNavy)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DEVICE & NODE MANAGEMENT",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextWhite
                )
                Text(
                    text = "PAIR & PRIORITIZE MESH COMMUNICATORS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = SignalCyanBright
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(IsroBlueBright)
                    .clickable {
                        val newNode = NetworkNode(
                            id = "NODE_${System.currentTimeMillis() % 1000}",
                            name = "Search Unit Echo",
                            rssi = -68,
                            batteryPct = repository.batteryManager.batteryLevel.value,
                            transportType = TransportType.WIFI_DIRECT,
                            hopCount = 1
                        )
                        repository.database.addOrUpdateNode(newNode)
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = TextWhite, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("PAIR NODE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(nodes) { node ->
                val isPrioritized = activeNode?.id == node.id
                DeviceCard(
                    node = node,
                    isPrioritized = isPrioritized,
                    onInspect = { selectedNodeForDialog = node },
                    onTogglePriority = {
                        repository.setActiveTargetNode(if (isPrioritized) null else node)
                    }
                )
            }
        }
    }

    selectedNodeForDialog?.let { node ->
        AlertDialog(
            onDismissRequest = { selectedNodeForDialog = null },
            title = {
                Text(node.name, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, color = TextWhite)
            },
            text = {
                Column {
                    Text("Device ID: ${node.id}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = SignalCyanBright)
                    Text("Transport: ${node.transportType.label}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
                    Text("Signal Strength: ${node.rssi} dBm", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
                    Text("Battery Level: ${node.batteryPct}%", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
                    Text("Multi-Hop Distance: ${node.hopCount} Hops", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextMuted)
                    Text("Encryption: AES-256 GCM Authenticated", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = NetworkGreen)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    repository.database.removeNode(node.id)
                    selectedNodeForDialog = null
                }) {
                    Text("REMOVE NODE", color = EmergencyRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedNodeForDialog = null }) {
                    Text("CLOSE", color = SignalCyanBright)
                }
            },
            containerColor = CardNavy
        )
    }
}

@Composable
private fun DeviceCard(
    node: NetworkNode,
    isPrioritized: Boolean,
    onInspect: () -> Unit,
    onTogglePriority: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CardNavyGlass)
            .border(1.dp, if (isPrioritized) SignalCyanBright else BorderGlass, RoundedCornerShape(10.dp))
            .clickable { onInspect() }
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = node.name,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = TextWhite
                    )
                    if (isPrioritized) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Priority",
                            tint = WarningAmber,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${node.id} • ${node.transportType.label}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextDim
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${node.rssi} dBm",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (node.rssi > -75) NetworkGreen else WarningAmber
                    )
                    Text(
                        text = "BATT ${node.batteryPct}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = TextDim
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isPrioritized) SignalCyanBright else SurfaceNavy)
                        .clickable { onTogglePriority() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isPrioritized) "DEFAULT" else "SET TARGET",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPrioritized) DeepSpaceNavy else TextWhite
                    )
                }
            }
        }
    }
}
