package org.sih.itantra

import org.junit.Assert.*
import org.junit.Test
import org.sih.itantra.domain.model.BatteryState
import org.sih.itantra.domain.model.LinkMetrics
import org.sih.itantra.domain.model.LinkQuality

class NetworkQualityTest {

    @Test
    fun testLinkQualityScoreComputation() {
        // Pristine RF link
        val goodMetrics = LinkMetrics(
            rssi = -50,
            packetLossPct = 0.0f,
            latencyMs = 30L
        )
        assertTrue("Good link score must exceed 0.75", goodMetrics.linkQualityScore >= 0.75f)
        assertEquals(LinkQuality.GOOD, goodMetrics.linkQuality)

        // Moderate RF link
        val mediumMetrics = LinkMetrics(
            rssi = -75,
            packetLossPct = 8.0f,
            latencyMs = 150L
        )
        assertEquals(LinkQuality.MEDIUM, mediumMetrics.linkQuality)

        // Severely degraded RF link
        val poorMetrics = LinkMetrics(
            rssi = -92,
            packetLossPct = 25.0f,
            latencyMs = 600L
        )
        assertEquals(LinkQuality.POOR, poorMetrics.linkQuality)
    }

    @Test
    fun testBatteryStateTransitions() {
        assertEquals(BatteryState.NORMAL, BatteryState.fromLevel(85))
        assertEquals(BatteryState.POWER_AWARE, BatteryState.fromLevel(45))
        assertEquals(BatteryState.LOW_POWER, BatteryState.fromLevel(18))
        assertEquals(BatteryState.CRITICAL, BatteryState.fromLevel(8))
    }
}
