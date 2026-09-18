package org.sih.itantra.ml.stt

import org.sih.itantra.domain.model.Language

data class SttResult(
    val transcript: String,
    val confidence: Float,
    val detectedLanguage: Language,
    val durationMs: Long
)

interface SpeechRecognizer {
    suspend fun transcribe(pcmBytes: ByteArray, language: Language): SttResult
}
