package org.sih.itantra.ml.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.sih.itantra.domain.model.Language

/**
 * Native Android On-Device Speech Recognizer.
 * Connects directly to Android's built-in offline speech recognition engine.
 */
class AndroidSpeechManager(private val context: Context) {

    private val TAG = "AndroidSpeechManager"
    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _audioRms = MutableStateFlow(0f)
    val audioRms: StateFlow<Float> = _audioRms.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _waveformSamples = MutableStateFlow<List<Float>>(List(32) { 0.05f })
    val waveformSamples: StateFlow<List<Float>> = _waveformSamples.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private var sessionStartTime = 0L

    var lastRecognizedText: String = ""
        private set

    private var resultDeferred: CompletableDeferred<String>? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(language: Language) {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    Log.w(TAG, "SpeechRecognizer is not available on this device")
                    return@post
                }

                if (recognizer == null) {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }

                val bcp47 = when (language) {
                    Language.HINDI -> "hi-IN"
                    Language.TAMIL -> "ta-IN"
                    Language.TELUGU -> "te-IN"
                    Language.KANNADA -> "kn-IN"
                    Language.MALAYALAM -> "ml-IN"
                    Language.BENGALI -> "bn-IN"
                    Language.MARATHI -> "mr-IN"
                    Language.GUJARATI -> "gu-IN"
                    Language.PUNJABI -> "pa-IN"
                    Language.ODIA -> "or-IN"
                    Language.ENGLISH -> "en-IN"
                }

                _liveTranscript.value = ""
                lastRecognizedText = ""
                sessionStartTime = System.currentTimeMillis()
                _recordingDurationMs.value = 0L
                val deferred = CompletableDeferred<String>()
                resultDeferred = deferred

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, bcp47)
                    if (bcp47 != "en-IN") {
                        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN", "en-US"))
                    }
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(TAG, "SpeechRecognizer onReadyForSpeech")
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer onBeginningOfSpeech")
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.04f, 1f)
                        _audioRms.value = normalized
                        val current = _waveformSamples.value.toMutableList()
                        if (current.size >= 32) current.removeAt(0)
                        current.add(normalized)
                        _waveformSamples.value = current
                        if (sessionStartTime > 0L) {
                            _recordingDurationMs.value = System.currentTimeMillis() - sessionStartTime
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        Log.d(TAG, "SpeechRecognizer onEndOfSpeech")
                        _isListening.value = false
                        _audioRms.value = 0f
                    }

                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer onError: $error")
                        _isListening.value = false
                        _audioRms.value = 0f
                        val fallback = if (lastRecognizedText.isNotBlank()) lastRecognizedText else _liveTranscript.value
                        deferred.complete(fallback)
                        if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            try { recognizer?.destroy() } catch (_: Exception) {}
                            recognizer = null
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val recognized = matches?.firstOrNull { it.isNotBlank() }?.trim() ?: ""
                        Log.i(TAG, "SpeechRecognizer onResults: '$recognized'")
                        if (recognized.isNotBlank()) {
                            lastRecognizedText = recognized
                            _liveTranscript.value = recognized
                        }
                        _isListening.value = false
                        deferred.complete(recognized)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull { it.isNotBlank() }?.trim() ?: ""
                        if (partial.isNotBlank()) {
                            Log.d(TAG, "SpeechRecognizer onPartialResults: '$partial'")
                            lastRecognizedText = partial
                            _liveTranscript.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                recognizer?.startListening(intent)
                Log.d(TAG, "Started speech recognition in $bcp47")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition", e)
                resultDeferred?.complete("")
            }
        }
    }

    suspend fun stopListening(): String {
        mainHandler.post {
            try {
                recognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping recognizer: ${e.message}")
            }
        }

        val result = try {
            withTimeoutOrNull(1500L) {
                resultDeferred?.await()
            }
        } catch (_: Exception) {
            null
        }

        val finalResult = when {
            !result.isNullOrBlank() -> result.trim()
            lastRecognizedText.isNotBlank() -> lastRecognizedText.trim()
            _liveTranscript.value.isNotBlank() -> _liveTranscript.value.trim()
            else -> ""
        }
        Log.i(TAG, "stopListening finalized: '$finalResult'")
        return finalResult
    }

    fun setManualText(text: String) {
        lastRecognizedText = text
        _liveTranscript.value = text
    }

    fun clear() {
        lastRecognizedText = ""
        _liveTranscript.value = ""
        resultDeferred = null
    }
}
