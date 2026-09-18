package org.sih.itantra.ml.vad

import kotlin.math.abs

/**
 * Energy-based Voice Activity Detector (VAD) with zero-crossing rate estimator.
 * Distinguishes speech frames from ambient silence and background noise.
 */
class VoiceActivityDetector(
    private val energyThreshold: Double = 350.0,
    private val zcrThreshold: Double = 0.35
) {
    data class VadResult(
        val isSpeech: Boolean,
        val energy: Double,
        val zeroCrossingRate: Double
    )

    fun processFrame(pcm16Bytes: ByteArray): VadResult {
        if (pcm16Bytes.size < 2) return VadResult(false, 0.0, 0.0)

        val sampleCount = pcm16Bytes.size / 2
        var sumEnergy = 0.0
        var zeroCrossings = 0
        var prevSign = 0

        for (i in 0 until sampleCount) {
            val sample = (pcm16Bytes[i * 2].toInt() and 0xFF) or
                    (pcm16Bytes[i * 2 + 1].toInt() shl 8)

            sumEnergy += abs(sample.toDouble())

            val currentSign = if (sample >= 0) 1 else -1
            if (i > 0 && currentSign != prevSign) {
                zeroCrossings++
            }
            prevSign = currentSign
        }

        val avgEnergy = sumEnergy / sampleCount
        val zcr = zeroCrossings.toDouble() / sampleCount

        val isSpeech = avgEnergy > energyThreshold && zcr < zcrThreshold
        return VadResult(isSpeech, avgEnergy, zcr)
    }
}
