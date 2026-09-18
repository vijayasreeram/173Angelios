package org.sih.itantra.ml.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Manages raw PCM audio capture at 16kHz, 16-bit Mono.
 * Computes live RMS amplitude and waveform history for the Compose UI.
 */
class AudioCaptureManager {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentAmplitude = MutableStateFlow(0f)
    val currentAmplitude: StateFlow<Float> = _currentAmplitude.asStateFlow()

    private val _waveformSamples = MutableStateFlow<List<Float>>(List(32) { 0.05f })
    val waveformSamples: StateFlow<List<Float>> = _waveformSamples.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val audioOutputStream = ByteArrayOutputStream()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = maxOf(
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
        sampleRate * 2 // ~1 second buffer
    )

    @SuppressLint("MissingPermission")
    fun startCapture(coroutineScope: CoroutineScope) {
        if (_isRecording.value) return

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback for emulator / non-mic environments
                startSyntheticCapture(coroutineScope)
                return
            }

            audioOutputStream.reset()
            audioRecord?.startRecording()
            _isRecording.value = true
            val startTime = System.currentTimeMillis()

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(1024)
                val byteBuffer = ByteArray(2048)

                while (isActive && _isRecording.value) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readCount > 0) {
                        // Calculate RMS Amplitude
                        var sum = 0.0
                        for (i in 0 until readCount) {
                            val sample = buffer[i]
                            sum += (sample * sample)
                            // Convert short to little-endian bytes
                            byteBuffer[i * 2] = (sample.toInt() and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                        }
                        audioOutputStream.write(byteBuffer, 0, readCount * 2)

                        val rms = sqrt(sum / readCount)
                        val normalizedAmp = (rms / 32768.0).toFloat().coerceIn(0.02f, 1.0f)
                        _currentAmplitude.value = normalizedAmp

                        // Shift waveform window
                        val currentList = _waveformSamples.value.toMutableList()
                        if (currentList.size >= 32) currentList.removeAt(0)
                        currentList.add(normalizedAmp)
                        _waveformSamples.value = currentList

                        _recordingDurationMs.value = System.currentTimeMillis() - startTime
                    }
                    delay(40) // ~25 FPS waveform update
                }
            }
        } catch (e: Exception) {
            startSyntheticCapture(coroutineScope)
        }
    }

    private fun startSyntheticCapture(coroutineScope: CoroutineScope) {
        _isRecording.value = true
        val startTime = System.currentTimeMillis()
        recordingJob = coroutineScope.launch(Dispatchers.Default) {
            val random = java.util.Random()
            while (isActive && _isRecording.value) {
                val amp = 0.2f + random.nextFloat() * 0.7f
                _currentAmplitude.value = amp

                val currentList = _waveformSamples.value.toMutableList()
                if (currentList.size >= 32) currentList.removeAt(0)
                currentList.add(amp)
                _waveformSamples.value = currentList

                _recordingDurationMs.value = System.currentTimeMillis() - startTime
                delay(40)
            }
        }
    }

    fun stopCapture(): ByteArray {
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null

        _currentAmplitude.value = 0f
        return audioOutputStream.toByteArray()
    }
}
