package org.sih.itantra.domain.model

enum class TransportType(val label: String, val maxMtu: Int, val isWireless: Boolean) {
    WIFI_DIRECT("Wi-Fi Direct", 1400, true),
    BLE("Bluetooth LE 5.0", 244, true),
    LORA("LoRa (SX1262)", 250, true),
    LOOPBACK("Local Mesh Loopback", 1024, false);
}

enum class LinkQuality(val label: String, val minScore: Float, val targetStrategy: String) {
    GOOD("EXCELLENT", 0.75f, "Full UTF-8 Text"),
    MEDIUM("MODERATE", 0.50f, "Deflate Compressed Text"),
    POOR("DEGRADED", 0.25f, "Semantic Token Bit-Stream"),
    EMERGENCY("CRITICAL", 0.00f, "Ultra-Compact SOS Beacon");
}

enum class BatteryState(val label: String, val thresholdPct: Int) {
    NORMAL("Normal Operation (>50%)", 50),
    POWER_AWARE("Power Aware (20-50%)", 20),
    LOW_POWER("Low-Power Mode (10-20%)", 10),
    CRITICAL("Critical Power (<10%)", 0);

    companion object {
        fun fromLevel(pct: Int): BatteryState = when {
            pct > 50 -> NORMAL
            pct > 20 -> POWER_AWARE
            pct > 10 -> LOW_POWER
            else -> CRITICAL
        }
    }
}

data class LinkMetrics(
    val rssi: Int = -65, // dBm
    val packetLossPct: Float = 0.0f,
    val latencyMs: Long = 45L,
    val jitterMs: Long = 8L,
    val batteryPct: Int = 85,
    val activeTransport: TransportType = TransportType.WIFI_DIRECT
) {
    /**
     * Compute composite Link Quality Score (LQS) in [0.0, 1.0]
     */
    val linkQualityScore: Float
        get() {
            val rssiNorm = ((rssi + 100).coerceIn(0, 70) / 70f)
            val lossFactor = (1.0f - (packetLossPct / 100f)).coerceIn(0f, 1f)
            val latencyNorm = (1.0f - (latencyMs.coerceIn(20L, 1000L) - 20) / 980f)
            return ((rssiNorm * 0.4f) + (lossFactor * 0.4f) + (latencyNorm * 0.2f)).coerceIn(0f, 1f)
        }

    val linkQuality: LinkQuality
        get() = when {
            linkQualityScore >= LinkQuality.GOOD.minScore -> LinkQuality.GOOD
            linkQualityScore >= LinkQuality.MEDIUM.minScore -> LinkQuality.MEDIUM
            linkQualityScore >= LinkQuality.POOR.minScore -> LinkQuality.POOR
            else -> LinkQuality.EMERGENCY
        }
}

data class NetworkNode(
    val id: String,
    val name: String,
    val rssi: Int,
    val batteryPct: Int,
    val transportType: TransportType,
    val hopCount: Int = 1,
    val isDirectNeighbor: Boolean = true,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

data class MeshRoute(
    val destinationId: String,
    val nextHopId: String,
    val hopCount: Int,
    val metricScore: Float,
    val updatedTimestamp: Long = System.currentTimeMillis()
)
