package org.sih.itantra.presentation.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sih.itantra.domain.model.NetworkNode
import org.sih.itantra.domain.model.TransportType
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.components.PulsingThreeDotIndicator
import org.sih.itantra.presentation.ui.components.RadarMeshSweep
import org.sih.itantra.presentation.ui.components.StaggeredEntrance
import org.sih.itantra.presentation.ui.theme.*

/**
 * 5. Tactical Mesh & Wi-Fi Devices Screen.
 * Renders Wi-Fi Direct network parameters, live connected Wi-Fi devices,
 * peer discovery scanner, and tactical multi-hop radar.
 */
@Composable
fun NetworkScreen(repository: CommunicationRepository) {
    val nodes by repository.nodes.collectAsState()
    val activeNode by repository.activeTargetNode.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var isScanning by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        repository.scanForConnectedDevices()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberVoidBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Header
        item {
            StaggeredEntrance(index = 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TACTICAL MESH & WI-FI",
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = CyberTextWhite
                        )
                        Text(
                            text = "CONNECTED WI-FI DEVICES & AD-HOC PEERS",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyberNeonRedBright,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi",
                        tint = CyberNeonRedBright,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // 2. Wi-Fi Network & Interface Status Card
        item {
            StaggeredEntrance(index = 1) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyberGlassSurface)
                        .border(1.dp, CyberBorderRed, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(CyberMatrixGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "WI-FI DIRECT P2P NETWORK",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTextWhite
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CyberMatrixGreen.copy(alpha = 0.2f))
                                    .border(1.dp, CyberMatrixGreen, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberMatrixGreen
                                )
                            }
                        }

                        HorizontalDivider(color = CyberBorderRed.copy(alpha = 0.3f), thickness = 0.5.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(text = "SUBNET BROADCAST", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                                Text(text = "255.255.255.255", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CyberTextWhite)
                            }
                            Column {
                                Text(text = "MESH UDP PORT", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                                Text(text = "8988", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CyberLaserCyan)
                            }
                            Column {
                                Text(text = "ENCRYPTION", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                                Text(text = "AES-256", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CyberMatrixGreen)
                            }
                        }
                    }
                }
            }
        }

        // 3. Scan for Wi-Fi Devices Button with Inline Pulsing Three-Dot Indicator
        item {
            StaggeredEntrance(index = 2) {
                val scanBtnBg by animateColorAsState(
                    targetValue = if (isScanning) CyberCardDark else CyberNeonRed,
                    animationSpec = tween(250),
                    label = "scanBtnBg"
                )
                Button(
                    onClick = {
                        if (!isScanning) {
                            isScanning = true
                            scanMessage = "Broadcasting discovery beacon & scanning ARP subnet..."
                            coroutineScope.launch {
                                repository.scanForConnectedDevices()
                                delay(1000)
                                isScanning = false
                                scanMessage = "Scan complete: ${nodes.size} devices reachable over Wi-Fi"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scanBtnBg
                    ),
                    shape = RoundedCornerShape(8.dp),
                    border = if (isScanning) BorderStroke(1.dp, CyberLaserCyan) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    if (isScanning) {
                        PulsingThreeDotIndicator(
                            color = CyberLaserCyan,
                            dotSize = 6.dp,
                            spacing = 5.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "SCANNING FOR WI-FI PEERS...",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberLaserCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Scan",
                            tint = CyberTextWhite,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SCAN FOR CONNECTED WI-FI DEVICES",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberTextWhite
                        )
                    }
                }

                AnimatedVisibility(visible = scanMessage != null) {
                    scanMessage?.let {
                        Text(
                            text = it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyberLaserCyan,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                        )
                    }
                }
            }
        }

        // 4. Interactive Tactical Radar
        item {
            StaggeredEntrance(index = 3) {
                Column {
                    Text(
                        text = "LIVE MESH RADAR SWEEP",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberNeonRedBright
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RadarMeshSweep(
                        nodes = nodes,
                        selectedNodeId = activeNode?.id,
                        onNodeSelected = { repository.setActiveTargetNode(it) }
                    )
                }
            }
        }

        // 5. Connected Devices Count & Private Channel Policy Banner
        item {
            StaggeredEntrance(index = 4) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyberGlassSurface)
                        .border(1.dp, CyberBorderRed, RoundedCornerShape(10.dp))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "CONNECTED DEVICES",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberTextWhite
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CyberNeonRed.copy(alpha = 0.25f))
                                        .border(1.dp, CyberNeonRedBright, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${nodes.size} ONLINE",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyberNeonRedBright
                                    )
                                }
                            }

                            Text(
                                text = if (activeNode != null) "PRIVATE: ${activeNode?.name}" else "MODE: BROADCAST ALL",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeNode != null) CyberNeonRedBright else CyberMatrixGreen
                            )
                        }

                        HorizontalDivider(color = CyberBorderRed.copy(alpha = 0.3f), thickness = 0.5.dp)

                        Text(
                            text = "[PRIVATE 1-TO-1 COMMS] Tap any operator card below to initiate a private peer session for normal voice & pre-commands.\n[SOS RULE] Emergency SOS alerts ALWAYS bypass private mode and instantly broadcast to ALL devices on the Wi-Fi network.",
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 10.5.sp,
                            color = CyberTextMuted,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }

        // 6. Device List Items or Empty Radar State
        if (nodes.isEmpty()) {
            item {
                StaggeredEntrance(index = 5) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(CyberGlassSurface)
                            .border(1.dp, CyberBorderRed, RoundedCornerShape(10.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Radar,
                                contentDescription = "Radar",
                                tint = CyberNeonRedBright.copy(alpha = 0.6f),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "NO OTHER MESH OPERATORS DETECTED YET",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberTextWhite
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Open Angelios on Phone B (on the same Wi-Fi or Mobile Hotspot) to see them appear live on your radar with their username.",
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 11.sp,
                                color = CyberTextDim,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        } else {
            itemsIndexed(nodes) { index, node ->
                val isSelected = node.id == activeNode?.id
                StaggeredEntrance(index = 5 + index) {
                    WifiConnectedDeviceCard(
                        node = node,
                        isSelected = isSelected,
                        onSelect = { repository.setActiveTargetNode(if (isSelected) null else node) }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun WifiConnectedDeviceCard(
    node: NetworkNode,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val rssiColor = when {
        node.rssi > -70 -> CyberMatrixGreen
        node.rssi > -85 -> CyberLaserCyan
        else -> CyberNeonRedBright
    }

    val cardBg by animateColorAsState(
        targetValue = if (isSelected) CyberGlassCard.copy(alpha = 0.95f) else CyberGlassSurface,
        animationSpec = tween(220),
        label = "cardBg"
    )

    val cardBorderColor by animateColorAsState(
        targetValue = if (isSelected) CyberNeonRedBright else CyberBorderRed,
        animationSpec = tween(220),
        label = "cardBorderColor"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cardBg)
            .border(1.dp, cardBorderColor, RoundedCornerShape(10.dp))
            .clickable { onSelect() }
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) CyberNeonRedBright.copy(alpha = 0.2f) else CyberNeonRed.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (node.transportType == TransportType.WIFI_DIRECT) Icons.Default.Wifi else Icons.Default.CellTower,
                            contentDescription = "Device",
                            tint = if (isSelected) CyberNeonRedBright else CyberTextWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = node.name,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = CyberTextWhite
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(CyberMatrixGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "CONNECTED",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberMatrixGreen
                                )
                            }
                        }
                        Text(
                            text = "NODE: ${node.id} • ${node.transportType.label} • ${node.hopCount} HOP",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = CyberTextMuted
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${node.rssi} dBm",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = rssiColor
                    )
                    Text(
                        text = "BATT: ${node.batteryPct}%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (node.batteryPct < 20) CyberNeonRedBright else CyberTextDim
                    )
                }
            }

            // Quick Target Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isSelected) "● Private link active with this node" else "○ Tap to start private 1-to-1 comms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    color = if (isSelected) CyberNeonRedBright else CyberTextDim
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSelected) CyberNeonRed else CyberCardDark)
                        .border(1.dp, if (isSelected) CyberNeonRedBright else CyberBorderRed, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (isSelected) "UNLINK" else "CONNECT PRIVATE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) CyberTextWhite else CyberNeonRedBright
                    )
                }
            }
        }
    }
}
