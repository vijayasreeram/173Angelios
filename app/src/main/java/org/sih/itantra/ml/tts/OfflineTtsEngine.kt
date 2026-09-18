package org.sih.itantra.ml.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.sih.itantra.domain.model.Language
import java.util.Locale
import kotlin.math.sin

interface TextToSpeechEngine {
    fun speak(text: String, language: Language, onDone: () -> Unit = {})
    fun stop()
    fun shutdown()
}

/**
 * Tactical Offline TTS Engine.
 * Integrates Android TextToSpeech engine with fallback to tactical audio tone generator.
 */
class OfflineTtsEngine(private val context: Context) : TextToSpeechEngine, TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        try {
            tts = TextToSpeech(context, this)
        } catch (ignored: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
        }
    }

    override fun speak(text: String, language: Language, onDone: () -> Unit) {
        if (isInitialized && tts != null) {
            val locale = getLocaleForLanguage(language)
            val available = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                tts?.language = locale
            } else {
                tts?.language = Locale.ENGLISH
            }

            val utteranceId = "msg_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    onDone()
                }
                override fun onError(id: String?) {
                    playTacticalTone(440, 200)
                    onDone()
                }
            })

            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        } else {
            // Fallback audio feedback
            playTacticalTone(600, 250)
            onDone()
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /**
     * Synthesizes tactical audio beep using AudioTrack when voice engine is unavailable.
     */
    private fun playTacticalTone(freqHz: Int, durationMs: Int) {
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
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
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

    private fun getLocaleForLanguage(lang: Language): Locale = when (lang) {
        Language.HINDI -> Locale("hi", "IN")
        Language.TAMIL -> Locale("ta", "IN")
        Language.TELUGU -> Locale("te", "IN")
        Language.KANNADA -> Locale("kn", "IN")
        Language.MALAYALAM -> Locale("ml", "IN")
        Language.BENGALI -> Locale("bn", "IN")
        Language.MARATHI -> Locale("mr", "IN")
        Language.GUJARATI -> Locale("gu", "IN")
        Language.PUNJABI -> Locale("pa", "IN")
        Language.ODIA -> Locale("or", "IN")
        Language.ENGLISH -> Locale.ENGLISH
    }
}
