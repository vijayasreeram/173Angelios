package org.sih.itantra.presentation.emergency

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.components.EmergencySlideButton
import org.sih.itantra.presentation.ui.components.StaggeredEntrance
import org.sih.itantra.presentation.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tactical SOS Command Screen.
 * Provides flashlight strobe, acoustic siren, vibration alerts, and multi-hop emergency broadcast bypass.
 */
@Composable
fun EmergencyScreen(repository: CommunicationRepository) {
    val isSosActive by repository.emergencyManager.isSosActive.collectAsState()
    val sosLogs by repository.emergencyManager.sosLogs.collectAsState()
    val nodes by repository.nodes.collectAsState()
    val nearbyCount = nodes.size
    val deliveredCount = if (isSosActive) nearbyCount else sosLogs.lastOrNull()?.deliveredCount ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberVoidBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 1. Tactical Emergency Protocol Header
        StaggeredEntrance(index = 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "EMERGENCY PROTOCOL",
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = CyberNeonRedBright
                    )
                    Text(
                        text = "HIGH-PRIORITY SOS • DISASTER & TRIAGE OVERRIDE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = CyberTextMuted
                    )
                }
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "SOS",
                    tint = CyberNeonRedBright,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Advisory Banner
        StaggeredEntrance(index = 1) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberNeonRed.copy(alpha = 0.15f))
                    .border(1.dp, CyberNeonRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = "[CRITICAL] LIFE-SAFETY BROADCAST PROTOCOL: Triggers emergency sirens and strobe on all devices connected to the Wi-Fi network. Announces: \"Emergency! ${repository.userProfile.value.username} is in emergency!\" to all nearby units regardless of private communication channel settings.",
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                    color = CyberTextWhite,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. SOS Active State Monitor Card
        StaggeredEntrance(index = 2) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSosActive) CyberNeonRed.copy(alpha = 0.2f) else CyberGlassSurface)
                    .border(2.dp, if (isSosActive) CyberNeonRedBright else CyberBorderRed, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isSosActive) "SOS ACTIVE (TRANSMITTING)" else "SOS STANDBY (READY)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSosActive) CyberNeonRedBright else CyberMatrixGreen
                        )
                        Text(
                            text = "PRIORITY: CRITICAL",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberNeonRedBright
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TRANSMISSION", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                            Text("MULTI-HOP", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberTextWhite)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("STATUS", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                            Text(if (isSosActive) "BROADCASTING" else "IDLE", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isSosActive) CyberNeonRedBright else CyberTextMuted)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("NEARBY DEVICES", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                            Text("$nearbyCount", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CyberTextWhite)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("DELIVERED", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = CyberTextDim)
                            Text("$deliveredCount", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (deliveredCount > 0) CyberMatrixGreen else CyberTextDim)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Hardware Controls Active Badges with Smooth Color Transitions
        StaggeredEntrance(index = 3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HardwarePill(Icons.Default.FlashlightOn, "STROBE", isSosActive)
                HardwarePill(Icons.Default.NotificationsActive, "SIREN", isSosActive)
                HardwarePill(Icons.Default.Vibration, "VIBRATE", isSosActive)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Primary Deliberate SOS Activation Control (continuous idle glow pulse & alert strobe)
        StaggeredEntrance(index = 4) {
            EmergencySlideButton(
                isActive = isSosActive,
                onTriggerSos = { repository.triggerEmergencySos() },
                onCancelSos = { repository.stopEmergencySos() }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 6. Quick Emergency Triage Action Buttons
        StaggeredEntrance(index = 5) {
            Column {
                Text(
                    text = "TACTICAL EMERGENCY TRIAGE PROTOCOL",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = CyberNeonRedBright,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Button 1: Medical Casualty Distress
                    Button(
                        onClick = {
                            repository.triggerEmergencySos("EMERGENCY: Medical assistance required immediately, casualties reported.")
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberNeonRed.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CyberNeonRed, RoundedCornerShape(8.dp))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = CyberNeonRedBright, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("🚨 MEDICAL CASUALTY SOS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyberTextWhite)
                                Text("Requests immediate doctor & medevac", fontFamily = FontFamily.SansSerif, fontSize = 10.sp, color = CyberTextMuted)
                            }
                        }
                    }

                    // Button 2: Hazard / Flood SOS
                    Button(
                        onClick = {
                            repository.triggerEmergencySos("EMERGENCY: Severe hazard and flood water rising, immediate rescue needed.")
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberNeonRed.copy(alpha = 0.2f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CyberNeonRed, RoundedCornerShape(8.dp))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = CyberNeonRedBright, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("🌊 HAZARD & FLOOD RESCUE SOS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CyberTextWhite)
                                Text("Requests immediate disaster rescue squad", fontFamily = FontFamily.SansSerif, fontSize = 10.sp, color = CyberTextMuted)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 7. Local SOS Audit Log
        StaggeredEntrance(index = 6) {
            Column {
                Text(
                    text = "LOCAL EMERGENCY EVENT LOGS",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = CyberTextDim
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (sosLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberGlassSurface)
                            .border(1.dp, CyberBorderRed, RoundedCornerShape(8.dp))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("NO RECENT EMERGENCY EVENTS", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CyberTextDim)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sosLogs.forEach { log ->
                            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyberGlassSurface)
                                    .border(1.dp, CyberBorderRed, RoundedCornerShape(8.dp))
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "SOS BEACON BROADCAST",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberNeonRedBright
                                        )
                                        Text(
                                            text = "Source: ${log.triggerSource} • Delivered to ${log.deliveredCount} nodes",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = CyberTextMuted
                                        )
                                    }
                                    Text(
                                        text = timeStr,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = CyberTextDim
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HardwarePill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isActive: Boolean) {
    val pillBg by animateColorAsState(
        targetValue = if (isActive) CyberNeonRed.copy(alpha = 0.28f) else CyberGlassSurface,
        animationSpec = tween(250),
        label = "pillBg"
    )
    val pillBorder by animateColorAsState(
        targetValue = if (isActive) CyberNeonRedBright else CyberBorderRed,
        animationSpec = tween(250),
        label = "pillBorder"
    )
    val pillTint by animateColorAsState(
        targetValue = if (isActive) CyberNeonRedBright else CyberTextMuted,
        animationSpec = tween(250),
        label = "pillTint"
    )

    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(pillBg)
            .border(1.dp, pillBorder, RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = pillTint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = pillTint
            )
        }
    }
}

