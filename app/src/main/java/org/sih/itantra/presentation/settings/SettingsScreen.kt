package org.sih.itantra.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.domain.model.TransportType
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.theme.*

@Composable
fun SettingsScreen(repository: CommunicationRepository) {
    val selectedLanguage by repository.selectedLanguage.collectAsState()
    val receiverLanguage by repository.receiverLanguage.collectAsState()
    val linkMetrics by repository.linkMetrics.collectAsState()
    val batteryState by repository.batteryManager.batteryState.collectAsState()

    var autoAdaptiveCompression by remember { mutableStateOf(true) }
    var hardwareStrobeSos by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceNavy)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SETTINGS & CONFIGURATION",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = TextWhite
                )
                Text(
                    text = "HARDWARE & SECURITY PROFILES",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = SignalCyanBright
                )
            }
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = SignalCyanBright, modifier = Modifier.size(24.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Transport Layer Selection
        SectionTitle("RADIO TRANSPORT SELECTION")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardNavyGlass)
                .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TransportType.entries.forEach { transport ->
                val isSelected = linkMetrics.activeTransport == transport
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) SignalCyanBright.copy(alpha = 0.15f) else CardNavy)
                        .border(1.dp, if (isSelected) SignalCyanBright else BorderGlass, RoundedCornerShape(6.dp))
                        .clickable { repository.networkMonitor.setTransport(transport) }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = transport.label, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextWhite)
                        Text(text = "MTU ${transport.maxMtu}B • ${if (transport.isWireless) "Wireless RF" else "Loopback Simulation"}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextDim)
                    }
                    if (isSelected) {
                        Text(text = "ACTIVE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NetworkGreen)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Security & Cryptography Box
        SectionTitle("SECURITY & ENCRYPTION")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardNavyGlass)
                .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = "Secure", tint = NetworkGreen, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("AES-256 GCM Authenticated", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                    Text("Local keys held in Android Keystore hardware module", fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = TextMuted)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Battery Adaptation
        SectionTitle("POWER & BATTERY ADAPTATION")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardNavyGlass)
                .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Column {
                Text(text = "Current State: ${batteryState.label}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NetworkGreen)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Automatically switches to pure semantic encoding and limits high-frequency scanning when battery drops below 20%.", fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = TextMuted)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // System Toggles
        SectionTitle("AUTONOMOUS ADAPTIVE SETTINGS")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(CardNavyGlass)
                .border(1.dp, BorderGlass, RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingToggle(title = "Auto Adaptive Compression", desc = "Shift between Full, Deflate, Semantic, and SOS by RF link score", checked = autoAdaptiveCompression, onCheckedChange = { autoAdaptiveCompression = it })
            HorizontalDivider(color = BorderGlass)
            SettingToggle(title = "Hardware Strobe on SOS", desc = "Fires high-frequency camera LED flash during active emergency", checked = hardwareStrobeSos, onCheckedChange = { hardwareStrobeSos = it })
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = TextDim,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun SettingToggle(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextWhite)
            Text(desc, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, color = TextMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextWhite,
                checkedTrackColor = IsroBlueBright,
                uncheckedThumbColor = TextDim,
                uncheckedTrackColor = SurfaceNavy
            )
        )
    }
}
