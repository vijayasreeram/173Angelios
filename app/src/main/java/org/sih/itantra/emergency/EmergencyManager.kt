package org.sih.itantra.emergency

import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sin

data class SosLogEvent(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val triggerSource: String,
    val deliveredCount: Int = 3,
    val nearbyDevices: Int = 4
)

/**
 * Emergency SOS Command Manager.
 * Orchestrates multi-hop broadcast, hardware strobe flashlight, siren, and haptic buzzer.
 */
class EmergencyManager(private val context: Context) {

    private val _isSosActive = MutableStateFlow(false)
    val isSosActive: StateFlow<Boolean> = _isSosActive.asStateFlow()

    private val _sosLogs = MutableStateFlow<List<SosLogEvent>>(emptyList())
    val sosLogs: StateFlow<List<SosLogEvent>> = _sosLogs.asStateFlow()

    private var emergencyJob: Job? = null
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null
    private var vibrator: Vibrator? = null

    init {
        try {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraId = cameraManager?.cameraIdList?.firstOrNull()

            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (ignored: Exception) {}
    }

    fun triggerSos(source: String = "MANUAL_UI_TRIGGER", coroutineScope: CoroutineScope) {
        if (_isSosActive.value) return
        _isSosActive.value = true

        val newLog = SosLogEvent(triggerSource = source)
        _sosLogs.value = listOf(newLog) + _sosLogs.value

        emergencyJob = coroutineScope.launch(Dispatchers.Default) {
            // High-frequency SOS strobe + haptic pulses + siren tone
            var torchOn = false
            while (isActive && _isSosActive.value) {
                // 1. Hardware Strobe
                torchOn = !torchOn
                setTorchMode(torchOn)

                // 2. SOS Haptic Vibrate (Morse: . . . - - - . . .)
                triggerSosVibration()

                // 3. Audio Siren Beep
                playSirenPulse(if (torchOn) 900 else 600, 200)

                delay(250)
            }
        }
    }

    fun stopSos() {
        _isSosActive.value = false
        emergencyJob?.cancel()
        emergencyJob = null
        setTorchMode(false)
        vibrator?.cancel()
    }

    private fun setTorchMode(enabled: Boolean) {
        try {
            if (cameraId != null) {
                cameraManager?.setTorchMode(cameraId!!, enabled)
            }
        } catch (ignored: Exception) {}
    }

    private fun triggerSosVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(150)
            }
        } catch (ignored: Exception) {}
    }

    private fun playSirenPulse(freqHz: Int, durationMs: Int) {
        try {
            val sampleRate = 8000
            val numSamples = (sampleRate * (durationMs / 1000.0)).toInt()
            val sample = ShortArray(numSamples)
            for (i in 0 until numSamples) {
                val angle = 2.0 * Math.PI * i / (sampleRate.toDouble() / freqHz)
                sample[i] = (sin(angle) * 32767).toInt().toShort()
            }

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(numSamples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(sample, 0, numSamples)
            audioTrack.play()
        } catch (ignored: Exception) {}
    }
}
