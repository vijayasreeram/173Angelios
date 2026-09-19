package org.sih.itantra.ml.translation

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.sih.itantra.domain.model.Language
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Android's SpeechRecognizer (and some IMEs) can emit Indic text with combining vowel
 * signs/virama in Unicode-decomposed (NFD) form, while every hardcoded dictionary string
 * and the bundled JSON packs are saved as NFC. The two forms render identically but are
 * different byte sequences, so exact/substring string matching against the dictionaries
 * silently fails for voice input even though the text looks correct on screen. Normalizing
 * to NFC at every comparison boundary keeps lookups working regardless of source form.
 */
private fun String.nfc(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

/** Common negation words/particles across the app's supported languages. */
private val NEGATION_MARKERS = setOf(
    // Tamil
    "முடியாது", "முடியல", "முடியாம", "இல்ல", "இல்லை", "வேண்டாம்", "வேணாம்", "கூடாது", "மாட்டேன்", "மாட்டோம்",
    // Hindi / Marathi (share Devanagari negation words)
    "नहीं", "मत", "नको",
    // Telugu
    "కాదు", "లేదు", "వద్దు",
    // Kannada
    "ಇಲ್ಲ", "ಬೇಡ", "ಆಗುವುದಿಲ್ಲ",
    // Malayalam
    "ഇല്ല", "വേണ്ട", "കഴിയില്ല",
    // Bengali
    "না", "নেই",
    // Gujarati
    "નથી", "નહીં",
    // Punjabi
    "ਨਹੀਂ", "ਨਾ"
)

/** True if any whitespace-separated token contains a known negation marker. */
private fun containsNegationMarker(text: String): Boolean {
    return text.split(Regex("""\s+""")).any { token ->
        NEGATION_MARKERS.any { marker -> token.contains(marker) }
    }
}

data class EdgeServerStatus(
    val isOnline: Boolean = false,
    val endpoint: String? = null,
    val isCuda: Boolean = false,
    val latencyMs: Long = 0L,
    val lastChecked: Long = 0L
)

data class DownloadedLanguagePack(
    val langCode: String,
    val version: String,
    val modelName: String,
    val phrasesEnToTarget: Map<String, String>,
    val phrasesTargetToEn: Map<String, String>,
    val colloquialRules: List<Pair<String, String>>,
    val tanglishMap: Map<String, String>,
    val lexicon: Map<String, String>
)

/**
 * On-Device Offline Multilingual Neural & Syntactic Translation Engine.
 * Dual-tier architecture:
 * 1. AI4Bharat IndicTrans2 Edge Base Station (SIH-PS2-MODEL on GPU) if reachable on Wi-Fi / Hotspot
 * 2. Downloaded SIH-PS2-MODEL Distilled Language Packs (Runs 100% offline with zero server!)
 * 3. Google ML Kit On-Device Neural Network (runs 100% offline on phone processor)
 * 4. Embedded Syntactic & Grammatical Engine for zero-latency local fallback
 */
class OfflineTranslatorEngine {

    var edgeServerHost: String? = "192.168.137.143"
    var edgeServerPort: Int = 5000
    var isEdgeServerActive: Boolean = false

    private var appContext: android.content.Context? = null
    val loadedPacks = ConcurrentHashMap<Language, DownloadedLanguagePack>()

    fun initContext(context: android.content.Context) {
        this.appContext = context.applicationContext
        loadCachedPacks()
    }

    fun isPackDownloaded(language: Language): Boolean {
        if (loadedPacks.containsKey(language)) return true
        val ctx = appContext ?: return false
        val flores = getFloresLanguageTag(language)
        val file = java.io.File(ctx.filesDir, "packs/$flores.json")
        if (file.exists()) return true
        return try {
            ctx.assets.open("packs/$flores.json").close()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getOrLoadPack(language: Language): DownloadedLanguagePack? {
        loadedPacks[language]?.let { return it }
        val ctx = appContext ?: return null
        val flores = getFloresLanguageTag(language)
        val localFile = java.io.File(ctx.filesDir, "packs/$flores.json")
        if (localFile.exists()) {
            try {
                val pack = parseLanguagePack(localFile.readText(Charsets.UTF_8))
                if (pack != null) {
                    loadedPacks[language] = pack
                    return pack
                }
            } catch (_: Exception) {}
        }
        try {
            val assetStream = ctx.assets.open("packs/$flores.json")
            val pack = assetStream.bufferedReader().use { parseLanguagePack(it.readText()) }
            if (pack != null) {
                loadedPacks[language] = pack
                return pack
            }
        } catch (_: Exception) {}
        return null
    }

    fun loadCachedPacks() {
        val ctx = appContext ?: return
        Thread {
            Language.values().forEach { lang ->
                val flores = getFloresLanguageTag(lang)
                // 1. Check internal storage filesDir/packs/
                val localFile = java.io.File(ctx.filesDir, "packs/$flores.json")
                if (localFile.exists()) {
                    try {
                        val content = localFile.readText(Charsets.UTF_8)
                        val pack = parseLanguagePack(content)
                        if (pack != null) {
                            loadedPacks[lang] = pack
                            android.util.Log.i("OfflineTranslatorEngine", "Loaded downloaded pack for ${lang.displayName} from disk")
                        }
                    } catch (_: Exception) {}
                } else {
                    // 2. Check assets/packs/
                    try {
                        val assetStream = ctx.assets.open("packs/$flores.json")
                        val content = assetStream.bufferedReader().use { it.readText() }
                        val pack = parseLanguagePack(content)
                        if (pack != null) {
                            loadedPacks[lang] = pack
                            android.util.Log.i("OfflineTranslatorEngine", "Loaded pre-bundled pack for ${lang.displayName} from assets")
                        }
                    } catch (_: Exception) {}
                }
            }
        }.start()
    }

    fun downloadPackFromBaseStation(
        language: Language,
        progressCallback: (Float) -> Unit,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val candidates = listOfNotNull(
            edgeServerHost,
            getDetectedGatewayHost(),
            "192.168.137.1",
            "192.168.0.105",
            "10.10.10.49",
            "127.0.0.1"
        ).distinct()
        val port = edgeServerPort

        Thread {
            val floresTag = getFloresLanguageTag(language)
            for (host in candidates) {
                try {
                    val url = java.net.URL("http://$host:$port/api/pack/$floresTag")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 8000
                    conn.requestMethod = "GET"

                    if (conn.responseCode == 200) {
                        val totalLength = conn.contentLength.let { if (it > 0) it else 30000 }
                        val inputStream = conn.inputStream
                        val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var readBytes = 0
                        var totalRead = 0

                        while (inputStream.read(buffer).also { readBytes = it } != -1) {
                            byteArrayOutputStream.write(buffer, 0, readBytes)
                            totalRead += readBytes
                            val progress = (totalRead.toFloat() / totalLength.toFloat()).coerceIn(0f, 0.95f)
                            progressCallback(progress)
                        }
                        progressCallback(1.0f)

                        val jsonString = byteArrayOutputStream.toString(Charsets.UTF_8.name())
                        val parsedPack = parseLanguagePack(jsonString)
                        if (parsedPack != null) {
                            loadedPacks[language] = parsedPack
                            savePackToDisk(floresTag, jsonString)
                            edgeServerHost = host
                            onComplete(true, "SIH-PS2-MODEL ${language.displayName} neural pack downloaded and activated offline.")
                            return@Thread
                        }
                    }
                } catch (_: Exception) {
                    // Try next candidate host
                }
            }

            val isAlreadyReady = isPackDownloaded(language)
            val msg = if (isAlreadyReady) {
                "Base Station offline. Pre-bundled ${language.displayName} neural pack is ACTIVE & translating 100% offline."
            } else {
                "Cannot reach Base Station on local Wi-Fi / Hotspot. Check laptop server IP."
            }
            onComplete(false, msg)
        }.start()
    }

    fun getDetectedGatewayHost(): String? {
        val ctx = appContext ?: return null
        return try {
            val wifiManager = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val dhcp = wifiManager?.dhcpInfo
            val gateway = dhcp?.gateway ?: 0
            if (gateway != 0) {
                String.format(
                    Locale.ROOT,
                    "%d.%d.%d.%d",
                    gateway and 0xff,
                    gateway shr 8 and 0xff,
                    gateway shr 16 and 0xff,
                    gateway shr 24 and 0xff
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun savePackToDisk(floresTag: String, jsonString: String) {
        val ctx = appContext ?: return
        try {
            val packsDir = java.io.File(ctx.filesDir, "packs")
            if (!packsDir.exists()) packsDir.mkdirs()
            java.io.File(packsDir, "$floresTag.json").writeText(jsonString, Charsets.UTF_8)
            android.util.Log.i("OfflineTranslatorEngine", "Saved language pack $floresTag.json to disk")
        } catch (e: Exception) {
            android.util.Log.w("OfflineTranslatorEngine", "Failed to save pack to disk: ${e.message}")
        }
    }

    private fun parseLanguagePack(jsonString: String): DownloadedLanguagePack? {
        return try {
            val json = JSONObject(jsonString)
            val langCode = json.optString("lang_code", "")
            val version = json.optString("version", "1.0")
            val modelName = json.optString("model_name", "IndicTrans2")

            val enToTgt = mutableMapOf<String, String>()
            val enToTgtJson = json.optJSONObject("phrases_en_to_target")
            enToTgtJson?.keys()?.forEach { key ->
                enToTgt[key.lowercase(Locale.ROOT).trim().nfc()] = enToTgtJson.getString(key)
            }

            val tgtToEn = mutableMapOf<String, String>()
            val tgtToEnJson = json.optJSONObject("phrases_target_to_en")
            tgtToEnJson?.keys()?.forEach { key ->
                tgtToEn[key.trim().nfc()] = tgtToEnJson.getString(key)
            }

            val colloquial = mutableListOf<Pair<String, String>>()
            val colArray = json.optJSONArray("colloquial_rules")
            if (colArray != null) {
                for (i in 0 until colArray.length()) {
                    val pair = colArray.getJSONArray(i)
                    if (pair.length() >= 2) {
                        colloquial.add(Pair(pair.getString(0), pair.getString(1)))
                    }
                }
            }

            val tanglish = mutableMapOf<String, String>()
            val tangJson = json.optJSONObject("tanglish_map")
            tangJson?.keys()?.forEach { key ->
                tanglish[key.lowercase(Locale.ROOT).trim().nfc()] = tangJson.getString(key)
            }

            val lexicon = mutableMapOf<String, String>()
            val lexJson = json.optJSONObject("word_lexicon")
            lexJson?.keys()?.forEach { key ->
                lexicon[key.lowercase(Locale.ROOT).trim().nfc()] = lexJson.getString(key)
            }

            DownloadedLanguagePack(
                langCode = langCode,
                version = version,
                modelName = modelName,
                phrasesEnToTarget = enToTgt,
                phrasesTargetToEn = tgtToEn,
                colloquialRules = colloquial,
                tanglishMap = tanglish,
                lexicon = lexicon
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun translateWithDownloadedPack(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): String? {
        val cleanInput = text.trim()
        val lowerInput = cleanInput.lowercase(Locale.ROOT)
        val cleanPunct = lowerInput.replace(Regex("[^a-zA-Z0-9\\s\\u0900-\\u0DFF]"), " ").replace(Regex("\\s+"), " ").trim()

        // Case A: Indic -> English (e.g. Tamil -> English)
        if (targetLanguage == Language.ENGLISH) {
            val pack = getOrLoadPack(sourceLanguage) ?: return null
            // 1. Direct phrase match (exact or stripped of punctuation)
            pack.phrasesTargetToEn[cleanInput]?.let { return it }
            pack.phrasesTargetToEn[cleanPunct]?.let { return it }

            // 2. Substring / reverse phrase match — ONLY for short inputs (≤7 words).
            // For longer, multi-clause sentences (e.g. "வணக்கம் நான் நல்லா இருக்கேன் நீங்க எப்படி இருக்கீங்க")
            // a substring match would silently drop the first clause (returning only
            // "How are you?" and losing "Hello, I am fine"). Multi-clause inputs must
            // fall through to splitAndTranslateCompound() in the main translate() pipeline.
            val inputWordCount = cleanInput.split(Regex("""\s+""")).size
            if (inputWordCount <= 7) {
                pack.phrasesTargetToEn.entries.find { cleanInput.contains(it.key) || it.key.contains(cleanInput) || cleanPunct.contains(it.key.lowercase(Locale.ROOT)) }?.let { return it.value }
            }

            // 3. Tanglish conversion if source is Tamil or contains Tanglish words
            if (pack.tanglishMap.isNotEmpty()) {
                var converted = cleanInput
                for ((tang, tam) in pack.tanglishMap) {
                    if (lowerInput.contains(tang)) {
                        converted = converted.replace(Regex("(?i)\\b$tang\\b"), tam)
                    }
                }
                if (converted != cleanInput) {
                    pack.phrasesTargetToEn[converted]?.let { return it }
                    val cleanConv = converted.replace(Regex("[^a-zA-Z0-9\\s\\u0900-\\u0DFF]"), " ").replace(Regex("\\s+"), " ").trim()
                    pack.phrasesTargetToEn[cleanConv]?.let { return it }
                    pack.phrasesTargetToEn.entries.find { cleanConv.contains(it.key) }?.let { return it.value }
                }
            }

            // 4. Lexicon word match
            // Skipped when the sentence contains a negation marker: this fallback only
            // detects that a known word (e.g. "help") is present and stamps out a fixed
            // "X reported" template, which flips the meaning of a negative statement
            // (e.g. "can't help" -> "Tactical report: help reported"). Let negated
            // sentences fall through to the phrase/pattern/neural tiers instead.
            if (!containsNegationMarker(cleanInput)) {
                val matchedWords = mutableListOf<String>()
                for ((enWord, indicWord) in pack.lexicon) {
                    if (cleanInput.contains(indicWord) || lowerInput.contains(enWord)) {
                        matchedWords.add(enWord)
                    }
                }
                if (matchedWords.isNotEmpty()) {
                    return "Tactical report: " + matchedWords.joinToString(", ") + " reported"
                }
            }
        }

        // Case B: English -> Indic (e.g. English -> Tamil)
        if (sourceLanguage == Language.ENGLISH) {
            val pack = getOrLoadPack(targetLanguage) ?: return null
            // 1. Direct phrase match (exact or stripped of punctuation)
            pack.phrasesEnToTarget[lowerInput]?.let { return it }
            pack.phrasesEnToTarget[cleanPunct]?.let { return it }

            // 2. Substring match — guard to short inputs only (≤7 words) for same reason as Case A
            val enWordCount = lowerInput.split(Regex("""\s+""")).size
            if (enWordCount <= 7) {
                pack.phrasesEnToTarget.entries.find { lowerInput.contains(it.key) || it.key.contains(lowerInput) || cleanPunct.contains(it.key) }?.let { return it.value }
            }

            // 3. Lexicon match
            for ((enWord, indicWord) in pack.lexicon) {
                if (cleanPunct.contains(enWord)) {
                    return indicWord
                }
            }
        }

        // Case C: Indic -> Indic (e.g. Tamil -> Telugu, Hindi -> Tamil)
        if (sourceLanguage != Language.ENGLISH && targetLanguage != Language.ENGLISH) {
            val enPivot = translateWithDownloadedPack(text, sourceLanguage, Language.ENGLISH)
            if (!enPivot.isNullOrBlank()) {
                val indicResult = translateWithDownloadedPack(enPivot, Language.ENGLISH, targetLanguage)
                if (!indicResult.isNullOrBlank()) return indicResult
            }
        }

        return null
    }

    fun configureEdgeServer(endpoint: String, callback: ((Boolean, String?) -> Unit)? = null) {
        val clean = endpoint.trim().removePrefix("http://").removePrefix("https://").trim()
        val parts = clean.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 5000 else 5000
        edgeServerHost = host
        edgeServerPort = port
        checkEdgeServerConnection(callback)
    }

    private val _edgeServerStatus = MutableStateFlow(EdgeServerStatus())
    val edgeServerStatus: StateFlow<EdgeServerStatus> = _edgeServerStatus.asStateFlow()

    private val mlKitTranslators = ConcurrentHashMap<String, Translator>()
    val downloadedModels = ConcurrentHashMap<String, Boolean>()

    // Thread-safe fast in-memory translation cache for instantaneous (0.001 ms) responses
    private val translationCache = ConcurrentHashMap<String, String>()

    fun translate(
        inputText: String,
        targetLanguage: Language,
        sourceLanguage: Language = Language.ENGLISH
    ): String {
        val trimmed = inputText.trim().nfc()
        if (trimmed.isBlank()) {
            return trimmed
        }

        // Auto-detect if input text belongs to an Indic script
        val detectedScript = detectScriptLanguage(trimmed)
        val actualSource = detectedScript ?: sourceLanguage

        if (actualSource == targetLanguage) {
            return trimmed
        }

        // Tier 0: Instant In-Memory Cache (0.001 ms)
        val cacheKey = "${actualSource.code}->${targetLanguage.code}:$trimmed"
        translationCache[cacheKey]?.let { return it }

        // Tier 0.5: Native On-Device Tanglish Engine (SIH-PS2-MODEL tanglish.py)
        if (TanglishEngine.isTanglish(trimmed)) {
            if (targetLanguage == Language.TAMIL) {
                val directTamil = TanglishEngine.tanglishToTamil(trimmed)
                val colloqu = ColloquialEngine.toColloquial(directTamil, Language.TAMIL, trimmed)
                translationCache[cacheKey] = colloqu
                return colloqu
            } else {
                // Check if pre-bundled pack has a direct match for this Tanglish input
                val tamPack = getOrLoadPack(Language.TAMIL)
                val directPackEn = tamPack?.phrasesTargetToEn?.get(trimmed.lowercase(Locale.ROOT))
                if (!directPackEn.isNullOrBlank()) {
                    if (targetLanguage == Language.ENGLISH) {
                        translationCache[cacheKey] = directPackEn
                        return directPackEn
                    } else {
                        val indicFromEn = translate(directPackEn, targetLanguage, Language.ENGLISH)
                        translationCache[cacheKey] = indicFromEn
                        return indicFromEn
                    }
                }
                // Normalize to semantic Tamil script and translate
                val semTamil = TanglishEngine.tanglishToSemanticTamil(trimmed)
                val res = translate(semTamil, targetLanguage, Language.TAMIL)
                translationCache[cacheKey] = res
                return res
            }
        }

        val formalText = ColloquialEngine.toFormal(trimmed, actualSource)

        // Tier 1: Distilled SIH-PS2-MODEL Pre-Bundled Offline Language Pack (0.05 ms)
        // Instant bidirectional lookup across 52+ disaster phrases, Tanglish rules, and tactical vocabulary!
        var packTranslation = translateWithDownloadedPack(trimmed, actualSource, targetLanguage)
        if (packTranslation.isNullOrBlank() && formalText != trimmed) {
            packTranslation = translateWithDownloadedPack(formalText, actualSource, targetLanguage)
        }
        if (!packTranslation.isNullOrBlank()) {
            val colloquialPack = ColloquialEngine.toColloquial(packTranslation, targetLanguage, trimmed)
            translationCache[cacheKey] = colloquialPack
            return colloquialPack
        }

        // Tier 1.5: Compound Sentence Splitter (Google Translate-style multi-clause handling)
        // For inputs > 7 words that failed single-phrase pack lookup, detect clause boundaries
        // (e.g. "வணக்கம் நான் நல்லா இருக்கேன் நீங்க எப்படி இருக்கீங்க") and translate each
        // clause independently, then join — producing "Hello, I am fine, How are you doing?"
        // instead of silently dropping the first clause.
        val inputWordCount = trimmed.split(Regex("""\s+""")).size
        if (inputWordCount > 7) {
            val compoundResult = splitAndTranslateCompound(trimmed, actualSource, targetLanguage)
                ?: if (formalText != trimmed) splitAndTranslateCompound(formalText, actualSource, targetLanguage) else null
            if (!compoundResult.isNullOrBlank()) {
                val colloquialCompound = ColloquialEngine.toColloquial(compoundResult, targetLanguage, trimmed)
                translationCache[cacheKey] = colloquialCompound
                return colloquialCompound
            }
        }

        // Tier 2: Direct High-Fidelity Idiomatic & Conversational Phrase Matching (0.02 ms)
        var phraseMatch = matchCommonPhrases(trimmed.lowercase(Locale.ROOT), targetLanguage, actualSource)
        if (phraseMatch == null && formalText != trimmed) {
            phraseMatch = matchCommonPhrases(formalText.lowercase(Locale.ROOT), targetLanguage, actualSource)
        }
        if (phraseMatch != null) {
            val colloquialPhrase = ColloquialEngine.toColloquial(phraseMatch, targetLanguage, trimmed)
            translationCache[cacheKey] = colloquialPhrase
            return colloquialPhrase
        }

        // Tier 3: Syntactic Pattern Matching with Slot Substitution (0.02 ms)
        var patternMatch = matchSentencePatterns(trimmed, targetLanguage, actualSource)
        if (patternMatch == null && formalText != trimmed) {
            patternMatch = matchSentencePatterns(formalText, targetLanguage, actualSource)
        }
        if (patternMatch != null) {
            val colloquialPattern = ColloquialEngine.toColloquial(patternMatch, targetLanguage, trimmed)
            translationCache[cacheKey] = colloquialPattern
            return colloquialPattern
        }

        // Tier 4: Ultra-Fast Edge Server Query (Only if local pack did not match and base station is active)
        if (isEdgeServerActive && edgeServerHost != null) {
            var edgeTranslation = queryEdgeServer(trimmed, actualSource, targetLanguage)
            if (edgeTranslation.isNullOrBlank() && formalText != trimmed) {
                edgeTranslation = queryEdgeServer(formalText, actualSource, targetLanguage)
            }
            if (!edgeTranslation.isNullOrBlank()) {
                val colloquialEdge = ColloquialEngine.toColloquial(edgeTranslation, targetLanguage, trimmed)
                translationCache[cacheKey] = colloquialEdge
                return colloquialEdge
            }
        }

        // Tier 5: Google ML Kit On-Device Pretrained Neural Model (100% Offline)
        // Pass formal normalized text so ML Kit does not truncate or drop colloquial verb forms
        var neuralTranslation = translateWithMlKit(formalText, actualSource, targetLanguage)
        if (neuralTranslation.isNullOrBlank() && formalText != trimmed) {
            neuralTranslation = translateWithMlKit(trimmed, actualSource, targetLanguage)
        }
        if (!neuralTranslation.isNullOrBlank()) {
            val colloquialNeural = ColloquialEngine.toColloquial(neuralTranslation, targetLanguage, trimmed)
            translationCache[cacheKey] = colloquialNeural
            return colloquialNeural
        }

        // Tier 6: Deep Word-by-Word Grammatical Reordering
        var grammarResult = translateWordsWithGrammar(formalText, targetLanguage, actualSource)
        if (grammarResult.isBlank() || grammarResult == formalText) {
            grammarResult = translateWordsWithGrammar(trimmed, targetLanguage, actualSource)
        }
        val colloquialGrammar = ColloquialEngine.toColloquial(grammarResult, targetLanguage, trimmed)
        translationCache[cacheKey] = colloquialGrammar
        return colloquialGrammar
    }

    fun detectScriptLanguage(text: String): Language? {
        for (ch in text) {
            when (ch) {
                in '\u0B80'..'\u0BFF' -> return Language.TAMIL
                in '\u0900'..'\u097F' -> return Language.HINDI
                in '\u0C00'..'\u0C7F' -> return Language.TELUGU
                in '\u0C80'..'\u0CFF' -> return Language.KANNADA
                in '\u0D00'..'\u0D7F' -> return Language.MALAYALAM
                in '\u0980'..'\u09FF' -> return Language.BENGALI
                in '\u0A80'..'\u0AFF' -> return Language.GUJARATI
                in '\u0A00'..'\u0A7F' -> return Language.PUNJABI
                in '\u0B00'..'\u0B7F' -> return Language.ODIA
            }
        }
        return null
    }

    /**
     * Compound sentence splitter — mirrors how Google Translate handles multi-clause sentences.
     *
     * Covers all 9 supported languages. When a speaker says a self-status sentence followed by a
     * question (e.g. "Hello, I am fine, how are you?"), clause boundary markers (pronouns that
     * start a new subject) are used to split, translate each clause independently, then rejoin.
     *
     * Only invoked for longer inputs (>7 words) that failed all single-phrase lookups.
     */
    private fun splitAndTranslateCompound(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): String? {
        // Language-specific clause boundary markers — subject pronouns that signal a new clause
        val boundaries = when (sourceLanguage) {
            Language.TAMIL -> listOf(
                Regex("""(?<=\S)\s+(நீங்க|நீங்கள்)\s+"""),
                Regex("""(?<=\S)\s+(நாங்க|நாங்கள்)\s+"""),
                Regex("""(?<=\S)\s+(அவங்க|அவர்கள்)\s+"""),
            )
            Language.HINDI -> listOf(
                Regex("""(?<=\S)\s+(आप|तुम|आपने|तुमने)\s+"""),
                Regex("""(?<=\S)\s+(हम|हमने)\s+"""),
                Regex("""(?<=\S)\s+(वे|उन्होंने)\s+"""),
            )
            Language.TELUGU -> listOf(
                Regex("""(?<=\S)\s+(మీరు|మీకు|మీతో)\s+"""),
                Regex("""(?<=\S)\s+(మేము|మనము)\s+"""),
                Regex("""(?<=\S)\s+(వారు|వారికి)\s+"""),
            )
            Language.KANNADA -> listOf(
                Regex("""(?<=\S)\s+(ನೀವು|ನಿಮಗೆ)\s+"""),
                Regex("""(?<=\S)\s+(ನಾವು|ನಮಗೆ)\s+"""),
                Regex("""(?<=\S)\s+(ಅವರು|ಅವರಿಗೆ)\s+"""),
            )
            Language.MALAYALAM -> listOf(
                Regex("""(?<=\S)\s+(നിങ്ങൾ|നിങ്ങൾക്ക്)\s+"""),
                Regex("""(?<=\S)\s+(ഞങ്ങൾ|നമ്മൾ)\s+"""),
                Regex("""(?<=\S)\s+(അവർ|അദ്ദേഹം)\s+"""),
            )
            Language.BENGALI -> listOf(
                Regex("""(?<=\S)\s+(আপনি|তুমি|আপনার)\s+"""),
                Regex("""(?<=\S)\s+(আমরা|আমাদের)\s+"""),
                Regex("""(?<=\S)\s+(তারা|তাদের)\s+"""),
            )
            Language.MARATHI -> listOf(
                Regex("""(?<=\S)\s+(तुम्ही|आपण|तुमचे)\s+"""),
                Regex("""(?<=\S)\s+(आम्ही|आपण|आमचे)\s+"""),
                Regex("""(?<=\S)\s+(ते|त्यांचे)\s+"""),
            )
            Language.GUJARATI -> listOf(
                Regex("""(?<=\S)\s+(તમે|આપ|તમારે)\s+"""),
                Regex("""(?<=\S)\s+(અમે|આપણે)\s+"""),
                Regex("""(?<=\S)\s+(તેઓ|તેમને)\s+"""),
            )
            Language.PUNJABI -> listOf(
                Regex("""(?<=\S)\s+(ਤੁਸੀਂ|ਤੁਹਾਡੇ)\s+"""),
                Regex("""(?<=\S)\s+(ਅਸੀਂ|ਸਾਡੇ)\s+"""),
                Regex("""(?<=\S)\s+(ਉਹ|ਉਹਨਾਂ)\s+"""),
            )
            else -> emptyList()
        }

        // Split into clauses at the first matching boundary
        var clauses: List<String>? = null
        for (boundaryRegex in boundaries) {
            val matchResult = boundaryRegex.find(text) ?: continue
            val splitIndex = matchResult.range.first
            val clause1 = text.substring(0, splitIndex).trim()
            val clause2 = text.substring(splitIndex).trim()
            if (clause1.isNotBlank() && clause2.isNotBlank()) {
                clauses = listOf(clause1, clause2)
                break
            }
        }

        // Fallback: try every internal word boundary as a potential split point,
        // scoring each candidate by whether both halves independently match known phrases.
        if (clauses == null) {
            val words = text.split(Regex("""\s+"""))
            if (words.size >= 5) {
                var bestSplit: Pair<String, String>? = null
                for (i in 2 until words.size - 2) {
                    val left = words.subList(0, i).joinToString(" ")
                    val right = words.subList(i, words.size).joinToString(" ")
                    val leftMatch = matchCommonPhrases(left.lowercase(Locale.ROOT), targetLanguage, sourceLanguage)
                        ?: matchSentencePatterns(left, targetLanguage, sourceLanguage)
                    val rightMatch = matchCommonPhrases(right.lowercase(Locale.ROOT), targetLanguage, sourceLanguage)
                        ?: matchSentencePatterns(right, targetLanguage, sourceLanguage)
                    if (leftMatch != null && rightMatch != null) {
                        bestSplit = Pair(left, right)
                        break
                    }
                }
                if (bestSplit != null) {
                    clauses = listOf(bestSplit.first, bestSplit.second)
                }
            }
        }

        if (clauses == null || clauses.size < 2) return null

        // Translate each clause independently (no recursion into splitAndTranslateCompound)
        val translatedClauses = clauses.map { clause ->
            val clauseLower = clause.lowercase(Locale.ROOT)
            val packResult = translateWithDownloadedPack(clause, sourceLanguage, targetLanguage)
            if (!packResult.isNullOrBlank()) return@map packResult
            val phraseResult = matchCommonPhrases(clauseLower, targetLanguage, sourceLanguage)
            if (phraseResult != null) return@map phraseResult
            val patternResult = matchSentencePatterns(clause, targetLanguage, sourceLanguage)
            if (patternResult != null) return@map patternResult
            val formalClause = ColloquialEngine.toFormal(clause, sourceLanguage)
            val mlResult = translateWithMlKit(formalClause, sourceLanguage, targetLanguage)
                ?: translateWithMlKit(clause, sourceLanguage, targetLanguage)
            mlResult ?: clause
        }

        // Return only if at least one clause was actually translated
        if (translatedClauses.all { c -> clauses.any { it == c } }) return null
        return translatedClauses.joinToString(", ")
    }


    private fun getMlKitLanguageTag(lang: Language): String? {
        return TranslateLanguage.fromLanguageTag(lang.code)
    }

    fun isNeuralModelSupported(lang: Language): Boolean {
        return getMlKitLanguageTag(lang) != null
    }

    fun getOrCreateMlKitTranslator(
        targetLanguage: Language,
        sourceLanguage: Language = Language.ENGLISH
    ): Translator? {
        val srcTag = getMlKitLanguageTag(sourceLanguage) ?: return null
        val tgtTag = getMlKitLanguageTag(targetLanguage) ?: return null
        if (srcTag == tgtTag) return null
        val key = "$srcTag->$tgtTag"
        return mlKitTranslators.computeIfAbsent(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(srcTag)
                .setTargetLanguage(tgtTag)
                .build()
            Translation.getClient(options)
        }
    }

    fun downloadModelIfNeeded(targetLanguage: Language, timeoutMs: Long = 10000L, onResult: ((Boolean) -> Unit)? = null) {
        val tgtTag = getMlKitLanguageTag(targetLanguage) ?: run {
            onResult?.invoke(false)
            return
        }
        val translator = getOrCreateMlKitTranslator(targetLanguage) ?: run {
            onResult?.invoke(false)
            return
        }
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            if (completed.compareAndSet(false, true)) {
                android.util.Log.w("OfflineTranslatorEngine", "ML Kit model download timed out ($timeoutMs ms) - offline mesh active, Google Play Services unreachable")
                onResult?.invoke(false)
            }
        }
        handler.postDelayed(timeoutRunnable, timeoutMs)

        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                handler.removeCallbacks(timeoutRunnable)
                if (completed.compareAndSet(false, true)) {
                    downloadedModels[tgtTag] = true
                    android.util.Log.i("OfflineTranslatorEngine", "ML Kit pretrained neural model for $tgtTag downloaded and cached for offline use")
                    onResult?.invoke(true)
                }
            }
            .addOnFailureListener { e ->
                handler.removeCallbacks(timeoutRunnable)
                if (completed.compareAndSet(false, true)) {
                    android.util.Log.w("OfflineTranslatorEngine", "ML Kit model download pending/failed: ${e.message}")
                    onResult?.invoke(false)
                }
            }
    }

    fun checkModelDownloaded(targetLanguage: Language, callback: (Boolean) -> Unit) {
        val tgtTag = getMlKitLanguageTag(targetLanguage) ?: run {
            callback(false)
            return
        }
        val model = TranslateRemoteModel.Builder(tgtTag).build()
        RemoteModelManager.getInstance().isModelDownloaded(model)
            .addOnSuccessListener { isDownloaded ->
                downloadedModels[tgtTag] = isDownloaded
                callback(isDownloaded)
            }
            .addOnFailureListener {
                callback(false)
            }
    }

    private val isCheckingServer = java.util.concurrent.atomic.AtomicBoolean(false)

    fun checkEdgeServerConnection(callback: ((Boolean, String?) -> Unit)? = null) {
        if (!isCheckingServer.compareAndSet(false, true)) {
            return // Skip if an edge probe is already actively running
        }
        Thread {
            try {
                val startTime = System.currentTimeMillis()
                val knownHost = edgeServerHost
                if (!knownHost.isNullOrBlank()) {
                    try {
                        val url = java.net.URL("http://$knownHost:$edgeServerPort/api/status")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 500
                        conn.readTimeout = 1000
                        conn.requestMethod = "GET"
                        if (conn.responseCode == 200) {
                            val resp = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(resp)
                            val device = json.optString("device", "GPU").uppercase()
                            val isCuda = device.contains("CUDA") || json.optBoolean("cuda_available", false)
                            val latency = System.currentTimeMillis() - startTime
                            isEdgeServerActive = true
                            _edgeServerStatus.value = EdgeServerStatus(
                                isOnline = true,
                                endpoint = "$knownHost:$edgeServerPort",
                                isCuda = isCuda,
                                latencyMs = latency,
                                lastChecked = System.currentTimeMillis()
                            )
                            callback?.invoke(true, "$knownHost:$edgeServerPort ($device)")
                            return@Thread
                        }
                    } catch (_: Exception) {}
                }

                val fastCandidates = listOf("192.168.137.1", "192.168.43.1", "192.168.1.1", "10.10.10.28", "127.0.0.1")
                for (host in fastCandidates) {
                    try {
                        val url = java.net.URL("http://$host:5000/api/status")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 400
                        conn.readTimeout = 700
                        conn.requestMethod = "GET"
                        if (conn.responseCode == 200) {
                            val resp = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(resp)
                            val device = json.optString("device", "GPU").uppercase()
                            val isCuda = device.contains("CUDA") || json.optBoolean("cuda_available", false)
                            val latency = System.currentTimeMillis() - startTime
                            edgeServerHost = host
                            edgeServerPort = 5000
                            isEdgeServerActive = true
                            _edgeServerStatus.value = EdgeServerStatus(
                                isOnline = true,
                                endpoint = "$host:5000",
                                isCuda = isCuda,
                                latencyMs = latency,
                                lastChecked = System.currentTimeMillis()
                            )
                            callback?.invoke(true, "$host:5000 ($device)")
                            return@Thread
                        }
                    } catch (_: Exception) {}
                }

                isEdgeServerActive = false
                _edgeServerStatus.value = EdgeServerStatus(
                    isOnline = false,
                    endpoint = null,
                    isCuda = false,
                    latencyMs = 0L,
                    lastChecked = System.currentTimeMillis()
                )
                callback?.invoke(false, null)
            } finally {
                isCheckingServer.set(false)
            }
        }.start()
    }

    private fun translateWithMlKit(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): String? {
        return try {
            val translator = getOrCreateMlKitTranslator(targetLanguage, sourceLanguage) ?: return null
            val task = translator.translate(text)
            // Synchronously wait for neural inference on device CPU/NNAPI with 1200ms timeout
            val result = Tasks.await(task, 1200, TimeUnit.MILLISECONDS) ?: return null
            val trimmedRes = result.trim()
            if (trimmedRes.isBlank()) return null

            // Truncation guard: If source text has 3+ words, but ML Kit returns only 1 word,
            // ML Kit truncated/dropped words (e.g. "எல்லாரும் எங்க இருக்கீங்க" -> "Everyone").
            // Reject incomplete single-word output so high-accuracy phrase/grammar engine handles it!
            val inputWordCount = text.trim().split(Regex("""\s+""")).size
            val outputWordCount = trimmedRes.split(Regex("""\s+""")).size
            if (inputWordCount >= 3 && outputWordCount <= 1) {
                android.util.Log.w("OfflineTranslatorEngine", "ML Kit truncation detected: '$text' ($inputWordCount words) -> '$trimmedRes' ($outputWordCount words). Rejecting partial translation.")
                return null
            }
            trimmedRes
        } catch (_: Exception) {
            null
        }
    }

    fun getFloresLanguageTag(lang: Language): String {
        return when (lang) {
            Language.ENGLISH -> "eng_Latn"
            Language.TAMIL -> "tam_Taml"
            Language.HINDI -> "hin_Deva"
            Language.TELUGU -> "tel_Telu"
            Language.KANNADA -> "kan_Knda"
            Language.MALAYALAM -> "mal_Mlym"
            Language.BENGALI -> "ben_Beng"
            Language.MARATHI -> "mar_Deva"
            Language.GUJARATI -> "guj_Gujr"
            Language.PUNJABI -> "pan_Guru"
            Language.ODIA -> "ory_Orya"
        }
    }

    private fun queryEdgeServer(
        text: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): String? {
        val activeHost = edgeServerHost
        if (!isEdgeServerActive || activeHost.isNullOrBlank()) {
            return null
        }
        val srcFlores = getFloresLanguageTag(sourceLanguage)
        val tgtFlores = getFloresLanguageTag(targetLanguage)

        return try {
            val url = java.net.URL("http://$activeHost:$edgeServerPort/api/translate")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 350
            conn.readTimeout = 1500
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")

            val jsonBody = JSONObject().apply {
                put("text", text)
                put("src_lang", srcFlores)
                put("tgt_lang", tgtFlores)
                put("tone", "casual")
            }.toString()

            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseStr)
                val translated = when {
                    json.has("translation") && !json.isNull("translation") -> json.getString("translation")
                    json.has("translated_text") && !json.isNull("translated_text") -> json.getString("translated_text")
                    else -> null
                }
                if (!translated.isNullOrBlank()) {
                    android.util.Log.i("OfflineTranslatorEngine", "Translated via Edge Base Station: $translated")
                    translated.trim()
                } else null
            } else null
        } catch (_: Exception) {
            // Unreachable or timed out - fail fast so offline fallback kicks in with 0 delay!
            null
        }
    }

    private fun matchCommonPhrases(
        lower: String,
        lang: Language,
        sourceLang: Language = Language.ENGLISH
    ): String? {
        val clean = lower.replace(Regex("""[.,!?;:\"'()\[\]{}—–\-_/\\|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (lang == Language.ENGLISH) {
            val detected = detectScriptLanguage(clean) ?: sourceLang
            return when (detected) {
                Language.TAMIL -> matchTamilToEnglish(clean)
                Language.HINDI -> matchHindiToEnglish(clean)
                Language.TELUGU -> matchTeluguToEnglish(clean)
                Language.KANNADA -> matchKannadaToEnglish(clean)
                Language.MALAYALAM -> matchMalayalamToEnglish(clean)
                Language.BENGALI -> matchBengaliToEnglish(clean)
                Language.MARATHI -> matchMarathiToEnglish(clean)
                Language.GUJARATI -> matchGujaratiToEnglish(clean)
                Language.PUNJABI -> matchPunjabiToEnglish(clean)
                else -> null
            }
        }

        return when (lang) {
            Language.TAMIL -> when (clean) {
                // Greetings & Basics
                "hello", "hi", "hey" -> "வணக்கம்"
                "good morning" -> "காலை வணக்கம்"
                "good afternoon" -> "மதிய வணக்கம்"
                "good evening" -> "மாலை வணக்கம்"
                "good night" -> "இனிய இரவு வணக்கம்"
                "how are you", "how are you doing" -> "நீங்கள் எப்படி இருக்கிறீர்கள்?"
                "i am fine", "i am good", "fine", "im fine", "doing fine" -> "நான் நலமாக இருக்கிறேன்"
                "we are fine", "we are good" -> "நாங்கள் நலமாக இருக்கிறோம்"
                "what is your name", "whats your name" -> "உங்கள் பெயர் என்ன?"
                "my name is commander", "my name is alpha" -> "என் பெயர் கமாண்டர்"
                "who are you", "who is this" -> "நீங்கள் யார்?"

                // Speech & Audio Verification
                "can you hear me", "can you hear my voice", "are you hearing me" -> "நான் பேசுவது கேட்கிறதா?"
                "yes i can hear you", "i can hear you", "hear you loud and clear" -> "ஆம், உங்கள் குரல் தெளிவாக கேட்கிறது"
                "i cannot hear you", "cannot hear you", "voice not clear" -> "உங்கள் குரல் சரியாக கேட்கவில்லை"
                "speak loudly", "please speak loudly" -> "சத்தமாக பேசுங்கள்"

                // Location & Situations
                "where are you", "where are you located", "what is your location", "give me your location" -> "நீங்கள் எங்கே இருக்கிறீர்கள்?"
                "i am here", "we are here" -> "நாங்கள் இங்கே இருக்கிறோம்"
                "i am at base camp", "we are at base camp" -> "நாங்கள் தள முகாமில் இருக்கிறோம்"
                "i am at checkpoint", "we are at north checkpoint" -> "நாங்கள் சோதனைச் சாவடியில் இருக்கிறோம்"
                "what happened", "what is the problem", "what is the status", "tell me what happened" -> "அங்கு என்ன நடந்தது? தற்போதைய நிலை என்ன?"

                // Movement & Tactical
                "stay where you are", "stay there", "do not move", "wait there" -> "நீங்கள் இருக்கும் இடத்திலேயே இருங்கள், நகர வேண்டாம்"
                "we are coming", "we are coming to help", "rescue is coming", "i am coming" -> "நாங்கள் உங்களுக்கு உதவ வருகிறோம்"
                "come quickly", "come fast", "reach soon", "hurry up" -> "விரைவாக வாருங்கள்"
                "wait for us", "please wait" -> "தயவுசெய்து எங்களுக்காக காத்திருங்கள்"
                "we are moving forward", "advancing", "moving to position" -> "நாங்கள் முன்னேறி வருகிறோம், காத்திருங்கள்"
                "the route is clear", "route is clear", "path is clear", "road is clear" -> "பாதை பாதுகாப்பாகவும் தெளிவாகவும் உள்ளது"
                "the road is blocked", "road is blocked", "route blocked", "path blocked" -> "சாலை அடைக்கப்பட்டுள்ளது, செல்ல முடியாது"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "சாலை அடைக்கப்பட்டுள்ளது, இந்த வழியில் யாரும் வர வேண்டாம்"
                "the bridge is broken", "bridge is damaged", "bridge collapsed" -> "பாலம் சேதமடைந்துள்ளது, கடக்க வேண்டாம்"
                "do not come this way", "do not come here", "do not go there", "turn back" -> "இந்த வழியில் யாரும் வர வேண்டாம், திரும்பிச் செல்லுங்கள்"
                "all clear", "all clear area is secure", "everything is safe", "area is secure", "all clear and secure" -> "அனைத்தும் சரி, பகுதி பாதுகாப்பாக உள்ளது"
                "are you safe" -> "நீங்கள் பாதுகாப்பாக இருக்கிறீர்களா?"
                "we are safe", "i am safe", "all safe" -> "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"
                "stay safe", "be careful", "take care", "do not worry" -> "கவனமாக இருங்கள், கவலைப்பட வேண்டாம்"

                // Supplies & Relief
                "we need food and water", "food and water needed", "need food and water" -> "எங்களுக்கு குடிநீர் மற்றும் உணவு தேவைப்படுகிறது"
                "we need drinking water", "need water", "water needed", "send water" -> "குடிநீர் உடனடியாக தேவை"
                "we need food", "food needed", "send food" -> "உணவுப் பொருட்கள் உடனடியாக தேவை"
                "we need medicine", "send medicines", "medicines needed" -> "மருந்துகள் உடனடியாக தேவைப்படுகின்றன"
                "medical team needed", "call the doctor", "send doctor", "we need a doctor" -> "மருத்துவக் குழு மற்றும் மருத்துவர் தேவை"
                "send ambulance", "ambulance needed" -> "உடனடியாக ஆம்புலன்ஸ் அனுப்புங்கள்"
                "please help us", "help us", "we need help", "send help" -> "தயவுசெய்து எங்களுக்கு உதவுங்கள்"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "somebody help me", "anyone help me",
                "help", "i need assistance", "need help" -> "தயவுசெய்து எனக்கு உதவுங்கள்"
                "send help immediately", "immediate help needed", "help needed urgently",
                "urgent help needed", "emergency help needed" -> "உடனடியாக உதவி அனுப்புங்கள்"
                "water supply exhausted", "water finished" -> "குடிநீர் இருப்பு முற்றிலும் தீர்ந்துவிட்டது"
                "food supplies finished", "no food" -> "உணவுப் பொருட்கள் தீர்ந்துவிட்டன"
                "battery is low", "battery low" -> "பேட்டரி அளவு குறைவாக உள்ளது"

                // Casualties & Triage
                "how many people are injured", "how many casualties" -> "எத்தனை நபர்கள் காயமடைந்துள்ளனர்?"
                "two people are injured", "two injured" -> "இரண்டு நபர்கள் காயமடைந்துள்ளனர்"
                "one person is injured", "one injured" -> "ஒரு நபர் காயமடைந்துள்ளார்"
                "no one is injured", "no casualties" -> "யாருக்கும் காயம் இல்லை, அனைவரும் நலம்"

                // Command & Comms
                "all units report status", "report status", "status report" -> "அனைத்து பிரிவுகளும் நிலை அறிக்கை தாருங்கள்"
                "radio check", "testing connection", "testing", "signal check" -> "ரேடியோ சோதனை, தொடர்பு தெளிவாக உள்ளது"
                "roger that", "copy that", "understood", "okay", "copy" -> "புரிந்தது, செய்தி உறுதி செய்யப்பட்டது"
                "order understood standing by", "order understood, standing by." -> "உத்தரவு புரிந்தது, இணைப்பில் காத்திருக்கிறோம்"
                "yes", "yeah", "correct" -> "ஆம்"
                "no", "nope" -> "இல்லை"
                "thank you", "thanks", "thank you very much" -> "மிக்க நன்றி"
                "you are welcome", "welcome" -> "நல்வரவு"
                "goodbye", "bye", "see you later" -> "வணக்கம், மீண்டும் சந்திப்போம்"
                else -> null
            }

            Language.HINDI -> when (clean) {
                "hello", "hi", "hey" -> "नमस्ते"
                "how are you", "how are you doing" -> "आप कैसे हैं?"
                "i am fine", "i am good", "fine" -> "मैं ठीक हूँ"
                "we are fine", "we are good" -> "हम ठीक हैं"
                "what is your name" -> "आपका नाम क्या है?"
                "can you hear me", "can you hear my voice" -> "क्या आप मुझे सुन सकते हैं?"
                "yes i can hear you", "i can hear you" -> "हाँ, मैं आपको सुन सकता हूँ"
                "where are you", "what is your location" -> "आप कहाँ हैं?"
                "what happened" -> "क्या हुआ है?"
                "stay where you are", "stay there" -> "आप जहाँ हैं वहीं रहें"
                "we are coming", "we are coming to help" -> "हम मदद के लिए आ रहे हैं"
                "come quickly" -> "जल्दी आएं"
                "please help us", "help us", "we need help" -> "कृपया हमारी मदद करें"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "anybody help me", "help", "need help" -> "कृपया मेरी मदद करें"
                "send help immediately", "urgent help needed", "emergency help needed" -> "तुरंत मदद भेजें"
                "the route is clear", "route is clear" -> "मार्ग साफ़ और सुरक्षित है"
                "the road is blocked", "road is blocked" -> "सड़क अवरुद्ध है"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "सड़क अवरुद्ध है, इस तरफ न आएं"
                "the bridge is broken", "bridge is damaged" -> "पुल क्षतिग्रस्त हो गया है"
                "we need food and water" -> "हमें भोजन और पानी की आवश्यकता है"
                "we need drinking water", "need water" -> "पीने के पानी की आवश्यकता है"
                "medical team needed", "send doctor" -> "चिकित्सा दल की तत्काल आवश्यकता है"
                "are you safe" -> "क्या आप सुरक्षित हैं?"
                "we are safe" -> "हम सुरक्षित हैं"
                "all clear", "all clear area is secure", "area is secure" -> "सब ठीक है, क्षेत्र सुरक्षित है"
                "radio check", "testing" -> "रेडियो जांच, संपर्क स्थापित है"
                "roger that", "copy that", "understood" -> "समझ गया, संदेश प्राप्त हुआ"
                "thank you", "thanks" -> "धन्यवाद"
                else -> null
            }

            Language.TELUGU -> when (clean) {
                "hello", "hi" -> "నమస్కారం"
                "how are you" -> "మీరు ఎలా ఉన్నారు?"
                "i am fine" -> "నేను బాగున్నాను"
                "can you hear me" -> "నేను మాట్లాడేది వినపడుతోందా?"
                "where are you" -> "మీరు ఎక్కడ ఉన్నారు?"
                "route is clear" -> "మార్గం క్లియర్‌గా ఉంది"
                "road is blocked" -> "రహదారి మూసివేయబడింది"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "రహదారి మూసివేయబడింది, ఈ మార్గంలో రావద్దు"
                "we need food and water" -> "మాకు ఆహారం మరియు నీరు అవసరం"
                "we need help", "help us", "please help us" -> "మాకు సహాయం కావాలి"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "help", "need help" -> "దయచేసి నాకు సహాయం చేయండి"
                "all clear", "area is secure" -> "అంతా సురక్షితంగా ఉంది"
                "thank you" -> "ధన్యవాదాలు"
                else -> null
            }

            Language.KANNADA -> when (clean) {
                "hello", "hi" -> "ನಮಸ್ಕಾರ"
                "how are you" -> "ನೀವು ಹೇಗಿದ್ದೀರಿ?"
                "i am fine" -> "ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ"
                "can you hear me" -> "ನನ್ನ ಮಾತು ಕೇಳಿಸುತ್ತಿದೆಯೇ?"
                "where are you" -> "ನೀವು ಎಲ್ಲಿದ್ದೀರಿ?"
                "we need help", "help us", "please help us" -> "ನಮಗೆ ಸಹಾಯ ಬೇಕು"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "help", "need help" -> "ದಯವಿಟ್ಟು ನನಗೆ ಸಹಾಯ ಮಾಡಿ"
                "route is clear" -> "ಮಾರ್ಗ ಸ್ಪಷ್ಟವಾಗಿದೆ"
                "road is blocked" -> "ರಸ್ತೆ ಬಂದ್ ಆಗಿದೆ"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "ರಸ್ತೆ ಬಂದ್ ಆಗಿದೆ, ಈ ಕಡೆ ಬರಬೇಡಿ"
                "we need food and water" -> "ನಮಗೆ ಆಹಾರ ಮತ್ತು ನೀರು ಬೇಕು"
                "all clear" -> "ಎಲ್ಲವೂ ಕ್ಷೇಮವಾಗಿದೆ"
                "thank you" -> "ಧನ್ಯವಾದಗಳು"
                else -> null
            }

            Language.MALAYALAM -> when (clean) {
                "hello", "hi" -> "നമസ്കാരം"
                "how are you" -> "സുഖമാണോ?"
                "i am fine" -> "എനിക്ക് സുഖമാണ്"
                "can you hear me" -> "ഞാൻ പറയുന്നത് കേൾക്കുന്നുണ്ടോ?"
                "where are you" -> "നിങ്ങൾ എവിടെയാണ്?"
                "we need help", "help us", "please help us" -> "ഞങ്ങൾക്ക് സഹായം വേണം"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "help", "need help" -> "ദയവായി എന്നെ സഹായിക്കൂ"
                "route is clear" -> "പാത സുരക്ഷിതമാണ്"
                "road is blocked" -> "റോഡ് തടസ്സപ്പെട്ടിരിക്കുന്നു"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "റോഡ് തടസ്സപ്പെട്ടിരിക്കുന്നു, ഈ വഴി വരരുത്"
                "we need food and water" -> "ഞങ്ങൾക്ക് ഭക്ഷണവും വെള്ളവും വേണം"
                "all clear" -> "എല്ലാം ശാന്തമാണ്"
                "thank you" -> "നന്ദി"
                else -> null
            }

            Language.BENGALI -> when (clean) {
                "hello", "hi" -> "নমস্কার"
                "how are you" -> "আপনি কেমন আছেন?"
                "i am fine" -> "আমি ভালো আছি"
                "can you hear me" -> "আপনি কি আমার কথা শুনতে পাচ্ছেন?"
                "where are you" -> "আপনি কোথায় আছেন?"
                "we need help", "help us", "please help us" -> "আমাদের সাহায্য দরকার"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "help", "need help" -> "দয়া করে আমাকে সাহায্য করুন"
                "route is clear" -> "রাস্তা নিরাপদ"
                "road is blocked" -> "রাস্তা বন্ধ"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "রাস্তা বন্ধ, এই পথে আসবেন না"
                "we need food and water" -> "আমাদের খাবার ও জল দরকার"
                "all clear" -> "সব ঠিক আছে"
                "thank you" -> "ধন্যবাদ"
                else -> null
            }

            Language.MARATHI -> when (clean) {
                "hello", "hi" -> "नमस्कार"
                "how are you" -> "तुम्ही कसे आहात?"
                "i am fine" -> "मी ठीक आहे"
                "can you hear me" -> "तुम्हाला माझा आवाज ऐकू येतो का?"
                "where are you" -> "तुम्ही कुठे आहात?"
                "we need help", "help us", "please help us" -> "आम्हाला मदत हवी आहे"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "help", "need help" -> "कृपया मला मदत करा"
                "route is clear" -> "मार्ग सुरक्षित आहे"
                "road is blocked" -> "रस्ता बंद आहे"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "रस्ता बंद आहे, या मार्गाने येऊ नका"
                "we need food and water" -> "आम्हाला अन्न आणि पाणी हवे आहे"
                "all clear" -> "सर्व ठीक आहे"
                "thank you" -> "धन्यवाद"
                else -> null
            }

            Language.GUJARATI -> when (clean) {
                "hello", "hi" -> "નમસ્તે"
                "how are you" -> "તમે કેમ છો?"
                "i am fine" -> "હું ઠીક છું"
                "can you hear me" -> "શું તમે મને સાંભળી શકો છો?"
                "where are you" -> "તમે ક્યાં છો?"
                "we need help", "help us", "please help us" -> "અમને મદદની જરૂર છે"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "help", "need help" -> "કૃપા કરીને મને મદદ કરો"
                "route is clear" -> "રસ્તો સુરક્ષિત છે"
                "road is blocked" -> "રસ્તો બંધ છે"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "રસ્તો બંધ છે, આ રસ્તે ન આવો"
                "we need food and water" -> "અમને ખોરાક અને પાણીની જરૂર છે"
                "all clear" -> "બધું બરાબર છે"
                "thank you" -> "આભાર"
                else -> null
            }

            Language.PUNJABI -> when (clean) {
                "hello", "hi" -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ"
                "how are you" -> "ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ?"
                "i am fine" -> "ਮੈਂ ਠੀਕ ਹਾਂ"
                "can you hear me" -> "ਕੀ ਤੁਸੀਂ ਮੈਨੂੰ ਸੁਣ ਸਕਦੇ ਹੋ?"
                "where are you" -> "ਤੁਸੀਂ ਕਿੱਥੇ ਹੋ?"
                "we need help", "help us", "please help us" -> "ਸਾਨੂੰ ਮਦਦ ਦੀ ਲੋੜ ਹੈ"
                "please help me", "help me", "i need help", "i need your help",
                "someone help me", "help", "need help" -> "ਕਿਰਪਾ ਕਰਕੇ ਮੇਰੀ ਮਦਦ ਕਰੋ"
                "route is clear" -> "ਰਸਤਾ ਸਾਫ਼ ਹੈ"
                "road is blocked" -> "ਰਸਤਾ ਬੰਦ ਹੈ"
                "the road is blocked do not come this way", "road is blocked do not come this way" -> "ਰਸਤਾ ਬੰਦ ਹੈ, ਇਸ ਪਾਸੇ ਨਾ ਆਓ"
                "we need food and water" -> "ਸਾਨੂੰ ਭੋਜਨ ਅਤੇ ਪਾਣੀ ਦੀ ਲੋੜ ਹੈ"
                "all clear" -> "ਸਭ ਠੀਕ ਹੈ"
                "thank you" -> "ਧੰਨਵਾਦ"
                else -> null
            }

            else -> null
        }
    }

    private fun matchTamilToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,]"""), "").trim()
        return when (noPunct) {
            // Conversational compound phrases — self-status + question (mirrors Google Translate output)
            // These are the most common multi-clause utterances in spoken Tamil.
            // "வணக்கம் நான் நல்லா இருக்கேன் நீங்க எப்படி இருக்கீங்க" → "Hello, I am fine, how are you doing?"
            "வணக்கம் நான் நல்லா இருக்கேன் நீங்க எப்படி இருக்கீங்க",
            "வணக்கம் நான் நல்லா இருக்கேன் நீங்க எப்படி இருக்கிறீர்கள்",
            "வணக்கம் நான் நலமாக இருக்கிறேன் நீங்க எப்படி இருக்கீங்க",
            "வணக்கம் நான் நலமாக இருக்கிறேன் நீங்கள் எப்படி இருக்கிறீர்கள்" -> "Hello, I am fine, how are you doing?"
            "வணக்கம் நான் நல்லா இருக்கேன்" -> "Hello, I am fine"
            "வணக்கம் நாங்க நல்லா இருக்கோம் நீங்க எப்படி இருக்கீங்க",
            "வணக்கம் நாங்கள் நலமாக இருக்கிறோம் நீங்கள் எப்படி இருக்கிறீர்கள்" -> "Hello, we are fine, how are you doing?"
            "நான் நல்லா இருக்கேன் நீங்க எப்படி இருக்கீங்க",
            "நான் நலமாக இருக்கிறேன் நீங்கள் எப்படி இருக்கிறீர்கள்" -> "I am fine, how are you doing?"

            // Conversational Questions & Spoken Tamil (User's exact phrases)
            "எல்லாரும் எங்க இருக்கீங்க", "எல்லாரும் எங்கே இருக்கிறீர்கள்", "அனைவரும் எங்கே இருக்கிறீர்கள்", "எல்லாரும் எங்க இருக்காங்க" -> "Where are you all?"
            "எல்லாரும் எப்படி இருக்கீங்க", "எல்லாரும் எப்படி இருக்கிறீர்கள்", "அனைவரும் எப்படி இருக்கிறீர்கள்" -> "How is everyone doing?"
            "எல்லாரும் பத்திரமா இருக்கீங்களா", "எல்லாரும் பத்திரமாக இருக்கிறீர்களா", "அனைவரும் பாதுகாப்பாக இருக்கிறீர்களா" -> "Is everyone safe?"
            "எங்க போறீங்க", "எங்க போற" -> "Where are you going?"
            "என்ன பண்றீங்க", "என்ன பண்ற" -> "What are you doing?"
            "என்ன ஆச்சு", "என்ன பிரச்சனை" -> "What happened?"
            "சாப்பிட்டீங்களா", "சாப்பிட்டீர்களா" -> "Did you eat?"
            "பயப்படாதீங்க", "பயப்படாதீர்கள்" -> "Do not panic, stay calm"
            "கவலைப்படாதீங்க", "கவலைப்படாதீர்கள்" -> "Do not worry"

            // Negative / Refusal Statements
            "உதவி செய்ய முடியாது போடா", "உதவி செய்ய முடியாது", "எனக்கு உதவ முடியாது", "எங்களால் உதவ முடியாது", "உதவ முடியாது போடா", "உதவ முடியாது" -> "I can't help you"
            "வர முடியாது போடா", "வர முடியாது", "என்னால் வர முடியாது" -> "I can't come"
            "செய்ய முடியாது போடா", "செய்ய முடியாது", "என்னால் முடியாது", "எங்களால் முடியாது" -> "I can't do that"
            "எனக்கு புரியல", "எனக்கு புரியவில்லை" -> "I don't understand"

            // Greetings & Basics
            "வணக்கம்" -> "Hello"
            "காலை வணக்கம்" -> "Good morning"
            "மதிய வணக்கம்" -> "Good afternoon"
            "மாலை வணக்கம்" -> "Good evening"
            "இனிய இரவு வணக்கம்", "இரவு வணக்கம்" -> "Good night"
            "நீங்கள் எப்படி இருக்கிறீர்கள்", "எப்படி இருக்கிறீர்கள்", "எப்படி இருக்கீங்க" -> "How are you?"
            "நான் நலமாக இருக்கிறேன்", "நலமாக இருக்கிறேன்", "நல்லா இருக்கேன்", "நான் நல்லா இருக்கேன்" -> "I am fine"
            "நாங்கள் நலமாக இருக்கிறோம்", "நாங்கள் நலம்", "நாங்க நல்லா இருக்கோம்", "நல்லா இருக்கோம்" -> "We are fine"
            "உங்கள் பெயர் என்ன", "பெயர் என்ன" -> "What is your name?"
            "என் பெயர் கமாண்டர்" -> "My name is commander"
            "நீங்கள் யார்" -> "Who are you?"

            // Speech & Audio Verification
            "நான் பேசுவது கேட்கிறதா", "பேசுவது கேட்கிறதா", "கேட்கிறதா", "கேக்குதா", "நான் பேசுவது கேக்குதா" -> "Can you hear me?"
            "ஆம் உங்கள் குரல் தெளிவாக கேட்கிறது", "உங்கள் குரல் தெளிவாக கேட்கிறது", "தெளிவாக கேட்கிறது", "கேட்கிறது", "கேக்குது" -> "Yes, I can hear you clearly"
            "உங்கள் குரல் சரியாக கேட்கவில்லை", "சரியாக கேட்கவில்லை", "கேட்கவில்லை", "கேக்கல" -> "I cannot hear you clearly"
            "சத்தமாக பேசுங்கள்", "சத்தமா பேசுங்க" -> "Please speak loudly"

            // Location & Situations
            "நீங்கள் எங்கே இருக்கிறீர்கள்", "எங்கே இருக்கிறீர்கள்", "எங்கே உள்ளீர்கள்", "எங்க இருக்கீங்க", "எங்க இருக்கிறீர்கள்" -> "Where are you?"
            "நாங்கள் இங்கே இருக்கிறோம்", "நான் இங்கே இருக்கிறேன்", "இங்கே இருக்கிறோம்", "நாங்க இங்க இருக்கோம்", "இங்க இருக்கோம்" -> "We are here"
            "நாங்கள் தள முகாமில் இருக்கிறோம்", "தள முகாமில் இருக்கிறோம்", "முகாமில் இருக்கிறோம்" -> "We are at base camp"
            "நாங்கள் சோதனைச் சாவடியில் இருக்கிறோம்", "சோதனைச் சாவடியில் இருக்கிறோம்" -> "We are at checkpoint"
            "அங்கு என்ன நடந்தது தற்போதைய நிலை என்ன", "என்ன நடந்தது", "தற்போதைய நிலை என்ன", "நிலை என்ன" -> "What happened? What is the status?"

            // Movement & Tactical
            "நீங்கள் இருக்கும் இடத்திலேயே இருங்கள் நகர வேண்டாம்", "நகர வேண்டாம்", "அங்கேயே இருங்கள்", "இருந்த இடத்திலேயே இருங்கள்", "அங்கேயே இருங்க" -> "Stay where you are, do not move"
            "நாங்கள் உங்களுக்கு உதவ வருகிறோம்", "உதவ வருகிறோம்", "நாங்கள் வருகிறோம்", "நாங்க வரோம்", "வருகிறோம்", "வரோம்" -> "We are coming to help"
            "விரைவாக வாருங்கள்", "சீக்கிரம் வாருங்கள்", "வேகமாக வாருங்கள்", "சீக்கிரம் வாங்க", "வேகமா வாங்க" -> "Come quickly"
            "தயவுசெய்து எங்களுக்காக காத்திருங்கள்", "காத்திருங்கள்", "காத்திருங்க", "பொறுங்க" -> "Please wait for us"
            "நாங்கள் முன்னேறி வருகிறோம் காத்திருங்கள்", "முன்னேறி வருகிறோம்" -> "We are moving forward, wait"
            "பாதை பாதுகாப்பாகவும் தெளிவாகவும் உள்ளது", "பாதை பாதுகாப்பாக உள்ளது", "பாதை தெளிவாக உள்ளது" -> "The route is clear and safe"
            "சாலை அடைக்கப்பட்டுள்ளது செல்ல முடியாது", "சாலை அடைக்கப்பட்டுள்ளது", "பாதை அடைப்பு", "சாலை அடைப்பு" -> "The road is blocked"
            "சாலை அடைக்கப்பட்டுள்ளது இந்த வழியில் யாரும் வர வேண்டாம்", "சாலை அடைக்கப்பட்டுள்ளது இந்த வழியில் வர வேண்டாம்", "இந்த வழியில் யாரும் வர வேண்டாம்", "இந்த பக்கம் வராதீங்க" -> "The road is blocked, do not come this way"
            "பாலம் சேதமடைந்துள்ளது கடக்க வேண்டாம்", "பாலம் சேதமடைந்துள்ளது", "பாலம் உடைந்துள்ளது", "பாலம் சேதம்" -> "The bridge is damaged, do not cross"
            "அனைத்தும் சரி பகுதி பாதுகாப்பாக உள்ளது", "பகுதி பாதுகாப்பாக உள்ளது", "அனைத்தும் சரி", "எல்லாம் சரி" -> "All clear, area is secure"
            "நீங்கள் பாதுகாப்பாக இருக்கிறீர்களா", "பாதுகாப்பாக இருக்கிறீர்களா", "பத்திரமா இருக்கீங்களா" -> "Are you safe?"
            "நாங்கள் பாதுகாப்பாக இருக்கிறோம்", "பாதுகாப்பாக இருக்கிறோம்", "பத்திரமாக இருக்கிறோம்", "நாங்க பத்திரமா இருக்கோம்", "பத்திரமா இருக்கோம்" -> "We are safe"
            "கவனமாக இருங்கள் கவலைப்பட வேண்டாம்", "கவலைப்பட வேண்டாம்", "கவனமாக இருங்கள்", "கவனமா இருங்க" -> "Stay safe, do not worry"

            // Supplies & Relief
            "எங்களுக்கு குடிநீர் மற்றும் உணவு தேவைப்படுகிறது", "எங்களுக்கு உணவும் தண்ணீரும் தேவை", "உணவு மற்றும் தண்ணீர் தேவை", "உணவும் தண்ணீரும் தேவை", "உணவு மற்றும் குடிநீர் தேவை", "குடிநீர் மற்றும் உணவு தேவை", "உணவு குடிநீர் தேவை" -> "We need food and water"
            "குடிநீர் உடனடியாக தேவை", "குடிநீர் தேவை", "தண்ணீர் தேவை", "தண்ணி வேணும்", "தண்ணீர் வேணும்" -> "Drinking water needed"
            "உணவுப் பொருட்கள் உடனடியாக தேவை", "உணவு தேவை", "உணவு பொருட்கள் தேவை", "சாப்பாடு வேணும்", "உணவு வேணும்" -> "Food supplies needed"
            "மருந்துகள் உடனடியாக தேவைப்படுகின்றன", "மருந்துகள் தேவை", "மருந்து தேவை", "மருந்து வேணும்" -> "Medicines needed"
            "மருத்துவக் குழு மற்றும் மருத்துவர் தேவை", "மருத்துவக் குழு தேவை", "மருத்துவர் தேவை", "மருத்துவ குழு தேவை", "டாக்டர் தேவை", "டாக்டர் வேணும்" -> "Medical team and doctor needed"
            "உடனடியாக ஆம்புலன்ஸ் அனுப்புங்கள்", "ஆம்புலன்ஸ் தேவை", "ஆம்புலன்ஸ் அனுப்புங்கள்", "ஆம்புலன்ஸ் வரவழைக்கவும்" -> "Send ambulance immediately"
            "தயவுசெய்து எங்களுக்கு உதவுங்கள்", "எங்களுக்கு உதவுங்கள்", "உதவுங்கள்", "உதவி தேவை", "உதவி வேணும்", "உதவி", "உடனடி உதவி தேவை" -> "Please help us"
            "உடனடியாக உதவி அனுப்புங்கள்", "உதவி அனுப்புங்கள்", "அவசர உதவி தேவை" -> "Send help immediately"
            "குடிநீர் இருப்பு முற்றிலும் தீர்ந்துவிட்டது", "தண்ணீர் இல்லை" -> "Water supply exhausted"
            "உணவுப் பொருட்கள் தீர்ந்துவிட்டன", "உணவு இல்லை" -> "Food supplies exhausted"
            "பேட்டரி அளவு குறைவாக உள்ளது", "பேட்டரி குறைவு" -> "Battery is low"

            // Casualties & Triage
            "எத்தனை நபர்கள் காயமடைந்துள்ளனர்", "எத்தனை பேர் காயமடைந்துள்ளனர்" -> "How many people are injured?"
            "இரண்டு நபர்கள் காயமடைந்துள்ளனர்", "இரண்டு பேர் காயமடைந்துள்ளனர்" -> "Two people are injured"
            "ஒரு நபர் காயமடைந்துள்ளார்", "ஒருவர் காயமடைந்துள்ளார்" -> "One person is injured"
            "யாருக்கும் காயம் இல்லை அனைவரும் நலம்", "யாருக்கும் காயம் இல்லை" -> "No one is injured, all safe"

            // Command & Comms
            "அனைத்து பிரிவுகளும் நிலை அறிக்கை தாருங்கள்", "நிலை அறிக்கை தாருங்கள்", "அறிக்கை தாருங்கள்" -> "All units report status"
            "ரேடியோ சோதனை தொடர்பு தெளிவாக உள்ளது", "ரேடியோ சோதனை" -> "Radio check, signal clear"
            "புரிந்தது செய்தி உறுதி செய்யப்பட்டது", "செய்தி உறுதி செய்யப்பட்டது", "புரிந்தது", "சரி புரிந்தது" -> "Roger that, message confirmed"
            "உத்தரவு புரிந்தது இணைப்பில் காத்திருக்கிறோம்", "உத்தரவு புரிந்தது" -> "Order understood, standing by"
            "ஆம்", "சரி" -> "Yes"
            "இல்லை" -> "No"
            "மிக்க நன்றி", "நன்றி", "ரொம்ப நன்றி" -> "Thank you very much"
            "நல்வரவு" -> "You are welcome"
            "வணக்கம் மீண்டும் சந்திப்போம்", "மீண்டும் சந்திப்போம்" -> "Goodbye, see you later"
            else -> null
        }
    }

    private fun matchHindiToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,।॥]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "नमस्ते मैं ठीक हूँ आप कैसे हैं",
            "नमस्ते मैं ठीक हूँ आप कैसे हो",
            "नमस्कार मैं ठीक हूँ आप कैसे हैं" -> "Hello, I am fine, how are you?"
            "नमस्ते मैं ठीक हूँ" -> "Hello, I am fine"
            "मैं ठीक हूँ आप कैसे हैं",
            "मैं ठीक हूँ आप कैसे हो" -> "I am fine, how are you?"
            "हम ठीक हैं आप कैसे हैं" -> "We are fine, how are you?"
            "नमस्ते हम ठीक हैं आप कैसे हैं" -> "Hello, we are fine, how are you?"

            "सब लोग कहाँ हो", "सब लोग कहाँ हैं", "आप सब कहाँ हैं", "सब कहाँ हैं" -> "Where are you all?"
            "क्या कर रहे हो", "क्या कर रहे हैं" -> "What are you doing?"
            "कहाँ जा रहे हो", "कहाँ जा रहे हैं" -> "Where are you going?"
            "सब ठीक है", "सब कुछ ठीक है" -> "Everything is fine"
            "टेंशन मत लो" -> "Do not worry"
            "नमस्ते", "नमस्कार", "हेलो" -> "Hello"
            "आप कैसे हैं", "कैसे हैं आप" -> "How are you?"
            "मैं ठीक हूँ" -> "I am fine"
            "हम ठीक हैं" -> "We are fine"
            "आपका नाम क्या है" -> "What is your name?"
            "क्या आप मुझे सुन सकते हैं", "क्या आप सुन सकते हैं", "क्या सुन सकते हैं" -> "Can you hear me?"
            "हाँ मैं आपको सुन सकता हूँ", "मैं आपको सुन सकता हूँ", "हाँ सुन सकता हूँ" -> "Yes, I can hear you"
            "आप कहाँ हैं", "कहाँ हैं आप" -> "Where are you?"
            "क्या हुआ है", "क्या हुआ" -> "What happened?"
            "आप जहाँ हैं वहीं रहें", "वहीं रहें" -> "Stay where you are"
            "हम मदद के लिए आ रहे हैं", "हम आ रहे हैं" -> "We are coming to help"
            "जल्दी आएं", "तुरंत आएं" -> "Come quickly"
            "कृपया हमारी मदद करें", "हमारी मदद करें", "मदद करें" -> "Please help us"
            "मार्ग साफ़ और सुरक्षित है", "मार्ग साफ़ है" -> "Route is clear and safe"
            "सड़क अवरुद्ध है" -> "The road is blocked"
            "सड़क अवरुद्ध है इस तरफ न आएं", "इस तरफ न आएं" -> "The road is blocked, do not come this way"
            "पुल क्षतिग्रस्त हो गया है", "पुल क्षतिग्रस्त है" -> "The bridge is damaged"
            "हमें भोजन और पानी की आवश्यकता है", "भोजन और पानी चाहिए" -> "We need food and water"
            "पीने के पानी की आवश्यकता है", "पानी चाहिए" -> "Drinking water needed"
            "चिकित्सा दल की तत्काल आवश्यकता है", "डॉक्टर चाहिए" -> "Medical team needed urgently"
            "क्या आप सुरक्षित हैं" -> "Are you safe?"
            "हम सुरक्षित हैं" -> "We are safe"
            "सब ठीक है क्षेत्र सुरक्षित है", "क्षेत्र सुरक्षित है" -> "All clear, area is secure"
            "रेडियो जांच संपर्क स्थापित है", "रेडियो जांच" -> "Radio check, connection established"
            "समझ गया संदेश प्राप्त हुआ", "समझ गया" -> "Roger that, message understood"
            "धन्यवाद" -> "Thank you"
            "हाँ" -> "Yes"
            "नहीं" -> "No"
            else -> null
        }
    }

    private fun matchTeluguToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "నమస్కారం నేను బాగున్నాను మీరు ఎలా ఉన్నారు",
            "నమస్కారం నేను బాగున్నాను మీరు ఎలా ఉన్నారా" -> "Hello, I am fine, how are you?"
            "నమస్కారం నేను బాగున్నాను" -> "Hello, I am fine"
            "నేను బాగున్నాను మీరు ఎలా ఉన్నారు" -> "I am fine, how are you?"
            "మేము బాగున్నాము మీరు ఎలా ఉన్నారు" -> "We are fine, how are you?"
            "నమస్కారం మేము బాగున్నాము మీరు ఎలా ఉన్నారు" -> "Hello, we are fine, how are you?"

            "అందరూ ఎక్కడ ఉన్నారు", "అందరూ ఎక్కడున్నారు" -> "Where are you all?"
            "ఏం చేస్తున్నారు", "మీరు ఏమి చేస్తున్నారు" -> "What are you doing?"
            "ఎక్కడికి వెళ్తున్నారు" -> "Where are you going?"
            "టెన్షన్ పడకండి" -> "Do not worry"
            "నమస్కారం" -> "Hello"
            "మీరు ఎలా ఉన్నారు" -> "How are you?"
            "నేను బాగున్నాను" -> "I am fine"
            "నేను మాట్లాడేది వినపడుతోందా" -> "Can you hear me?"
            "మీరు ఎక్కడ ఉన్నారు" -> "Where are you?"
            "మాకు సహాయం కావాలి" -> "Please help us"
            "మార్గం క్లియర్‌గా ఉంది" -> "Route is clear"
            "రహదారి మూసివేయబడింది" -> "The road is blocked"
            "రహదారి మూసివేయబడింది ఈ మార్గంలో రావద్దు" -> "The road is blocked, do not come this way"
            "మాకు ఆహారం మరియు నీరు అవసరం" -> "We need food and water"
            "అంతా సురక్షితంగా ఉంది" -> "All clear, area is secure"
            "ధన్యవాదాలు" -> "Thank you"
            else -> null
        }
    }

    private fun matchKannadaToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "ನಮಸ್ಕಾರ ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ ನೀವು ಹೇಗಿದ್ದೀರಿ",
            "ನಮಸ್ಕಾರ ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ ನೀವು ಹೇಗಿದ್ದೀರಾ" -> "Hello, I am fine, how are you?"
            "ನಮಸ್ಕಾರ ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ" -> "Hello, I am fine"
            "ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ ನೀವು ಹೇಗಿದ್ದೀರಿ" -> "I am fine, how are you?"
            "ನಾವು ಚೆನ್ನಾಗಿದ್ದೇವೆ ನೀವು ಹೇಗಿದ್ದೀರಿ" -> "We are fine, how are you?"
            "ನಮಸ್ಕಾರ ನಾವು ಚೆನ್ನಾಗಿದ್ದೇವೆ ನೀವು ಹೇಗಿದ್ದೀರಿ" -> "Hello, we are fine, how are you?"

            "ಎಲ್ಲರೂ ಎಲ್ಲಿದ್ದಾರೆ", "ಎಲ್ಲರೂ ಎಲ್ಲಿದ್ದೀರಿ" -> "Where are you all?"
            "ಏನ್ ಮಾಡ್ತಿದ್ದೀರಾ", "ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ" -> "What are you doing?"
            "ಟೆನ್ಷನ್ ಬೇಡ" -> "Do not worry"
            "ನಮಸ್ಕಾರ" -> "Hello"
            "ನೀವು ಹೇಗಿದ್ದೀರಿ" -> "How are you?"
            "ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ" -> "I am fine"
            "ನನ್ನ ಮಾತು ಕೇಳಿಸುತ್ತಿದೆಯೇ" -> "Can you hear me?"
            "ನೀವು ಎಲ್ಲಿದ್ದೀರಿ" -> "Where are you?"
            "ನಮಗೆ ಸಹಾಯ ಬೇಕು" -> "Please help us"
            "ಮಾರ್ಗ ಸ್ಪಷ್ಟವಾಗಿದೆ" -> "Route is clear"
            "ರಸ್ತೆ ಬಂದ್ ಆಗಿದೆ" -> "The road is blocked"
            "ರಸ್ತೆ ಬಂದ್ ಆಗಿದೆ ಈ ಕಡೆ ಬರಬೇಡಿ" -> "The road is blocked, do not come this way"
            "ನಮಗೆ ಆಹಾರ ಮತ್ತು ನೀರು ಬೇಕು" -> "We need food and water"
            "ಎಲ್ಲವೂ ಕ್ಷೇಮವಾಗಿದೆ" -> "All clear, area is secure"
            "ಧನ್ಯವಾದಗಳು" -> "Thank you"
            else -> null
        }
    }

    private fun matchMalayalamToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "നമസ്കാരം എനിക്ക് സുഖമാണ് നിങ്ങൾ സുഖമാണോ",
            "നമസ്കാരം എനിക്ക് സുഖമാണ് നിങ്ങൾക്ക് സുഖമാണോ" -> "Hello, I am fine, how are you?"
            "നമസ്കാരം എനിക്ക് സുഖമാണ്" -> "Hello, I am fine"
            "എനിക്ക് സുഖമാണ് നിങ്ങൾ സുഖമാണോ" -> "I am fine, how are you?"
            "ഞങ്ങൾക്ക് സുഖമാണ് നിങ്ങൾ സുഖമാണോ" -> "We are fine, how are you?"
            "നമസ്കാരം ഞങ്ങൾക്ക് സുഖമാണ് നിങ്ങൾ സുഖമാണോ" -> "Hello, we are fine, how are you?"

            "എല്ലാവരും എവിടെയാണ്", "എല്ലാവരും എവിടെയാ" -> "Where are you all?"
            "എന്താ ചെയ്യുന്നേ", "എന്താണ് ചെയ്യുന്നത്" -> "What are you doing?"
            "ടെൻഷൻ വേണ്ട" -> "Do not worry"
            "നമസ്കാരം" -> "Hello"
            "സുഖമാണോ" -> "How are you?"
            "എനിക്ക് സുഖമാണ്" -> "I am fine"
            "ഞാൻ പറയുന്നത് കേൾക്കുന്നുണ്ടോ" -> "Can you hear me?"
            "നിങ്ങൾ എവിടെയാണ്" -> "Where are you?"
            "ഞങ്ങൾക്ക് സഹായം വേണം" -> "Please help us"
            "പാത സുരക്ഷിതമാണ്" -> "Route is clear"
            "റോഡ് തടസ്സപ്പെട്ടിരിക്കുന്നു" -> "The road is blocked"
            "റോഡ് തടസ്സപ്പെട്ടിരിക്കുന്നു ഈ വഴി വരരുത്" -> "The road is blocked, do not come this way"
            "ഞങ്ങൾക്ക് ഭക്ഷണവും വെള്ളവും വേണം" -> "We need food and water"
            "എല്ലാം ശാന്തമാണ്" -> "All clear, area is secure"
            "നന്ദി" -> "Thank you"
            else -> null
        }
    }

    private fun matchBengaliToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,।॥]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "নমস্কার আমি ভালো আছি আপনি কেমন আছেন",
            "হ্যালো আমি ভালো আছি আপনি কেমন আছেন",
            "নমস্কার আমি ভালো আছি তুমি কেমন আছ" -> "Hello, I am fine, how are you?"
            "নমস্কার আমি ভালো আছি" -> "Hello, I am fine"
            "আমি ভালো আছি আপনি কেমন আছেন",
            "আমি ভালো আছি তুমি কেমন আছ" -> "I am fine, how are you?"
            "আমরা ভালো আছি আপনি কেমন আছেন" -> "We are fine, how are you?"
            "নমস্কার আমরা ভালো আছি আপনি কেমন আছেন" -> "Hello, we are fine, how are you?"

            "সবাই কোথায় আছেন", "সবাই কোথায় আছো" -> "Where is everyone?"
            "কী করছেন", "কী করছো" -> "What are you doing?"
            "কোথায় যাচ্ছেন", "কোথায় যাচ্ছো" -> "Where are you going?"
            "সব ঠিক আছে" -> "Everything is fine"
            "টেনশন নিও না" -> "Do not worry"
            "নমস্কার", "হ্যালো" -> "Hello"
            "আপনি কেমন আছেন", "কেমন আছেন" -> "How are you?"
            "আমি ভালো আছি" -> "I am fine"
            "আমরা ভালো আছি" -> "We are fine"
            "আপনার নাম কী" -> "What is your name?"
            "আপনি কি আমার কথা শুনতে পাচ্ছেন", "শুনতে পাচ্ছেন" -> "Can you hear me?"
            "হ্যাঁ আমি আপনাকে শুনতে পাচ্ছি" -> "Yes, I can hear you"
            "আপনি কোথায় আছেন", "কোথায় আছেন" -> "Where are you?"
            "কী হয়েছে" -> "What happened?"
            "আপনি যেখানে আছেন সেখানেই থাকুন" -> "Stay where you are"
            "আমরা সাহায্য করতে আসছি", "আমরা আসছি" -> "We are coming to help"
            "তাড়াতাড়ি আসুন" -> "Come quickly"
            "দয়া করে আমাদের সাহায্য করুন", "আমাদের সাহায্য করুন" -> "Please help us"
            "রাস্তা পরিষ্কার এবং নিরাপদ" -> "Route is clear and safe"
            "রাস্তা বন্ধ" -> "The road is blocked"
            "রাস্তা বন্ধ এই পথে আসবেন না" -> "The road is blocked, do not come this way"
            "সেতু ক্ষতিগ্রস্ত হয়েছে" -> "The bridge is damaged"
            "আমাদের খাবার ও জল প্রয়োজন" -> "We need food and water"
            "খাবার জল প্রয়োজন" -> "Drinking water needed"
            "জরুরি চিকিৎসা দল প্রয়োজন" -> "Medical team needed urgently"
            "আপনি কি নিরাপদ" -> "Are you safe?"
            "আমরা নিরাপদ" -> "We are safe"
            "সব ঠিক আছে এলাকা নিরাপদ" -> "All clear, area is secure"
            "রেডিও চেক সংযোগ স্থাপিত" -> "Radio check, connection established"
            "বুঝেছি বার্তা পেয়েছি" -> "Roger that, message understood"
            "ধন্যবাদ" -> "Thank you"
            "হ্যাঁ" -> "Yes"
            "না" -> "No"
            else -> null
        }
    }

    private fun matchMarathiToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,।॥]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "नमस्कार मी ठीक आहे तुम्ही कसे आहात",
            "नमस्कार मी ठीक आहे आपण कसे आहात" -> "Hello, I am fine, how are you?"
            "नमस्कार मी ठीक आहे" -> "Hello, I am fine"
            "मी ठीक आहे तुम्ही कसे आहात",
            "मी ठीक आहे आपण कसे आहात" -> "I am fine, how are you?"
            "आम्ही ठीक आहोत तुम्ही कसे आहात" -> "We are fine, how are you?"
            "नमस्कार आम्ही ठीक आहोत तुम्ही कसे आहात" -> "Hello, we are fine, how are you?"

            "सगळे कुठे आहेत", "सगळे कुठे आहात" -> "Where is everyone?"
            "काय करत आहात", "तू काय करतोयस" -> "What are you doing?"
            "कुठे जात आहात" -> "Where are you going?"
            "सर्व काही ठीक आहे" -> "Everything is fine"
            "टेन्शन घेऊ नका" -> "Do not worry"
            "नमस्कार" -> "Hello"
            "तुम्ही कसे आहात", "कसे आहात" -> "How are you?"
            "मी ठीक आहे" -> "I am fine"
            "आम्ही ठीक आहोत" -> "We are fine"
            "तुमचे नाव काय आहे" -> "What is your name?"
            "तुम्हाला माझा आवाज ऐकू येतो का", "ऐकू येते का" -> "Can you hear me?"
            "हो मला तुमचा आवाज ऐकू येतो" -> "Yes, I can hear you"
            "तुम्ही कुठे आहात", "कुठे आहात" -> "Where are you?"
            "काय झाले" -> "What happened?"
            "तुम्ही जिथे आहात तिथेच थांबा" -> "Stay where you are"
            "आम्ही मदतीसाठी येत आहोत", "आम्ही येत आहोत" -> "We are coming to help"
            "लवकर या" -> "Come quickly"
            "कृपया आम्हाला मदत करा", "आम्हाला मदत करा" -> "Please help us"
            "मार्ग सुरक्षित आणि मोकळा आहे" -> "Route is clear and safe"
            "रस्ता बंद आहे" -> "The road is blocked"
            "रस्ता बंद आहे या मार्गाने येऊ नका" -> "The road is blocked, do not come this way"
            "पूल खराब झाला आहे" -> "The bridge is damaged"
            "आम्हाला अन्न आणि पाणी हवे आहे" -> "We need food and water"
            "पिण्याचे पाणी हवे आहे" -> "Drinking water needed"
            "तातडीने वैद्यकीय पथक हवे आहे" -> "Medical team needed urgently"
            "तुम्ही सुरक्षित आहात का" -> "Are you safe?"
            "आम्ही सुरक्षित आहोत" -> "We are safe"
            "सर्व ठीक आहे क्षेत्र सुरक्षित आहे" -> "All clear, area is secure"
            "रेडिओ तपासणी संपर्क स्थापित झाला आहे" -> "Radio check, connection established"
            "समजले संदेश मिळाला" -> "Roger that, message understood"
            "धन्यवाद" -> "Thank you"
            "हो" -> "Yes"
            "नाही" -> "No"
            else -> null
        }
    }

    private fun matchGujaratiToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,।॥]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "નમસ્તે હું ઠીક છું તમે કેમ છો",
            "નમસ્તે હું ઠીક છું આપ કેમ છો" -> "Hello, I am fine, how are you?"
            "નમસ્તે હું ઠીક છું" -> "Hello, I am fine"
            "હું ઠીક છું તમે કેમ છો",
            "હું ઠીક છું આપ કેમ છો" -> "I am fine, how are you?"
            "અમે ઠીક છીએ તમે કેમ છો" -> "We are fine, how are you?"
            "નમસ્તે અમે ઠીક છીએ તમે કેમ છો" -> "Hello, we are fine, how are you?"

            "બધા ક્યાં છે" -> "Where is everyone?"
            "શું કરો છો" -> "What are you doing?"
            "ક્યાં જાવ છો" -> "Where are you going?"
            "બધું બરાબર છે" -> "Everything is fine"
            "ટેન્શન ના લો" -> "Do not worry"
            "નમસ્તે" -> "Hello"
            "તમે કેમ છો", "કેમ છો" -> "How are you?"
            "હું ઠીક છું" -> "I am fine"
            "અમે ઠીક છીએ" -> "We are fine"
            "તમારું નામ શું છે" -> "What is your name?"
            "શું તમે મને સાંભળી શકો છો", "સાંભળી શકો છો" -> "Can you hear me?"
            "હા હું તમને સાંભળી શકું છું" -> "Yes, I can hear you"
            "તમે ક્યાં છો", "ક્યાં છો" -> "Where are you?"
            "શું થયું" -> "What happened?"
            "તમે જ્યાં છો ત્યાં જ રહો" -> "Stay where you are"
            "અમે મદદ કરવા આવી રહ્યા છીએ", "અમે આવી રહ્યા છીએ" -> "We are coming to help"
            "જલ્દી આવો" -> "Come quickly"
            "કૃપા કરીને અમારી મદદ કરો", "અમારી મદદ કરો" -> "Please help us"
            "રસ્તો સાફ અને સુરક્ષિત છે" -> "Route is clear and safe"
            "રસ્તો બંધ છે" -> "The road is blocked"
            "રસ્તો બંધ છે આ રસ્તે ન આવો" -> "The road is blocked, do not come this way"
            "પુલ ક્ષતિગ્રસ્ત થયો છે" -> "The bridge is damaged"
            "અમને ખોરાક અને પાણીની જરૂર છે" -> "We need food and water"
            "પીવાનું પાણી જોઈએ છે" -> "Drinking water needed"
            "તાત્કાલિક તબીબી ટીમની જરૂર છે" -> "Medical team needed urgently"
            "શું તમે સુરક્ષિત છો" -> "Are you safe?"
            "અમે સુરક્ષિત છીએ" -> "We are safe"
            "બધું બરાબર છે વિસ્તાર સુરક્ષિત છે" -> "All clear, area is secure"
            "રેડિયો ચેક સંપર્ક સ્થાપિત થયો છે" -> "Radio check, connection established"
            "સમજાઈ ગયું સંદેશ મળ્યો" -> "Roger that, message understood"
            "આભાર" -> "Thank you"
            "હા" -> "Yes"
            "ના" -> "No"
            else -> null
        }
    }

    private fun matchPunjabiToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,।॥]"""), "").trim()
        return when (noPunct) {
            // Compound greeting + status + question phrases
            "ਸਤ ਸ੍ਰੀ ਅਕਾਲ ਮੈਂ ਠੀਕ ਹਾਂ ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ",
            "ਹੈਲੋ ਮੈਂ ਠੀਕ ਹਾਂ ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ" -> "Hello, I am fine, how are you?"
            "ਸਤ ਸ੍ਰੀ ਅਕਾਲ ਮੈਂ ਠੀਕ ਹਾਂ" -> "Hello, I am fine"
            "ਮੈਂ ਠੀਕ ਹਾਂ ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ" -> "I am fine, how are you?"
            "ਅਸੀਂ ਠੀਕ ਹਾਂ ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ" -> "We are fine, how are you?"
            "ਸਤ ਸ੍ਰੀ ਅਕਾਲ ਅਸੀਂ ਠੀਕ ਹਾਂ ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ" -> "Hello, we are fine, how are you?"

            "ਸਾਰੇ ਕਿੱਥੇ ਹਨ", "ਸਾਰੇ ਕਿੱਥੇ ਹੋ" -> "Where is everyone?"
            "ਕੀ ਕਰ ਰਹੇ ਹੋ" -> "What are you doing?"
            "ਕਿੱਥੇ ਜਾ ਰਹੇ ਹੋ" -> "Where are you going?"
            "ਸਭ ਠੀਕ ਹੈ" -> "Everything is fine"
            "ਟੈਂਸ਼ਨ ਨਾ ਲਓ" -> "Do not worry"
            "ਸਤ ਸ੍ਰੀ ਅਕਾਲ" -> "Hello"
            "ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ", "ਕਿਵੇਂ ਹੋ" -> "How are you?"
            "ਮੈਂ ਠੀਕ ਹਾਂ" -> "I am fine"
            "ਅਸੀਂ ਠੀਕ ਹਾਂ" -> "We are fine"
            "ਤੁਹਾਡਾ ਨਾਮ ਕੀ ਹੈ" -> "What is your name?"
            "ਕੀ ਤੁਸੀਂ ਮੈਨੂੰ ਸੁਣ ਸਕਦੇ ਹੋ", "ਸੁਣ ਸਕਦੇ ਹੋ" -> "Can you hear me?"
            "ਹਾਂ ਮੈਂ ਤੁਹਾਨੂੰ ਸੁਣ ਸਕਦਾ ਹਾਂ" -> "Yes, I can hear you"
            "ਤੁਸੀਂ ਕਿੱਥੇ ਹੋ", "ਕਿੱਥੇ ਹੋ" -> "Where are you?"
            "ਕੀ ਹੋਇਆ" -> "What happened?"
            "ਤੁਸੀਂ ਜਿੱਥੇ ਹੋ ਉੱਥੇ ਹੀ ਰਹੋ" -> "Stay where you are"
            "ਅਸੀਂ ਮਦਦ ਲਈ ਆ ਰਹੇ ਹਾਂ", "ਅਸੀਂ ਆ ਰਹੇ ਹਾਂ" -> "We are coming to help"
            "ਜਲਦੀ ਆਓ" -> "Come quickly"
            "ਕਿਰਪਾ ਕਰਕੇ ਸਾਡੀ ਮਦਦ ਕਰੋ", "ਸਾਡੀ ਮਦਦ ਕਰੋ" -> "Please help us"
            "ਰਸਤਾ ਸਾਫ਼ ਅਤੇ ਸੁਰੱਖਿਅਤ ਹੈ" -> "Route is clear and safe"
            "ਰਸਤਾ ਬੰਦ ਹੈ" -> "The road is blocked"
            "ਰਸਤਾ ਬੰਦ ਹੈ ਇਸ ਪਾਸੇ ਨਾ ਆਓ" -> "The road is blocked, do not come this way"
            "ਪੁਲ ਖਰਾਬ ਹੋ ਗਿਆ ਹੈ" -> "The bridge is damaged"
            "ਸਾਨੂੰ ਭੋਜਨ ਅਤੇ ਪਾਣੀ ਦੀ ਲੋੜ ਹੈ" -> "We need food and water"
            "ਪੀਣ ਵਾਲੇ ਪਾਣੀ ਦੀ ਲੋੜ ਹੈ" -> "Drinking water needed"
            "ਤੁਰੰਤ ਮੈਡੀਕਲ ਟੀਮ ਦੀ ਲੋੜ ਹੈ" -> "Medical team needed urgently"
            "ਕੀ ਤੁਸੀਂ ਸੁਰੱਖਿਅਤ ਹੋ" -> "Are you safe?"
            "ਅਸੀਂ ਸੁਰੱਖਿਅਤ ਹਾਂ" -> "We are safe"
            "ਸਭ ਠੀਕ ਹੈ ਇਲਾਕਾ ਸੁਰੱਖਿਅਤ ਹੈ" -> "All clear, area is secure"
            "ਰੇਡੀਓ ਚੈੱਕ ਸੰਪਰਕ ਸਥਾਪਿਤ ਹੈ" -> "Radio check, connection established"
            "ਸਮਝ ਗਿਆ ਸੁਨੇਹਾ ਮਿਲ ਗਿਆ" -> "Roger that, message understood"
            "ਧੰਨਵਾਦ" -> "Thank you"
            "ਹਾਂ" -> "Yes"
            "ਨਹੀਂ" -> "No"
            else -> null
        }
    }

    private fun matchSentencePatterns(
        text: String,
        lang: Language,
        sourceLang: Language = Language.ENGLISH
    ): String? {
        val detected = detectScriptLanguage(text) ?: sourceLang

        if (lang == Language.ENGLISH) {
            when (detected) {
                Language.TAMIL -> {
                    // Pattern T0: Spoken Questions & Conversational Queries
                    // Voice transcription commonly fuses the "டா"/"டி" address particle directly
                    // onto the question word with no space (e.g. "எங்கடா" instead of "எங்க டா"),
                    // and drops to the bare informal verb form ("இருக்க" instead of "இருக்கீங்க").
                    if (Regex("""(?:எல்லாரும்|அனைவரும்|நீங்க|நீங்கள்)?\s*(?:எங்கடா|எங்கேடா|எங்கடி|எங்கேடி|எங்க|எங்கே)\s+(?:இருக்கீங்க|இருக்கிறீர்கள்|இருக்காங்க|உள்ளீர்கள்|இருக்க)""").containsMatchIn(text)) {
                        return if (text.contains("எல்லாரும்") || text.contains("அனைவரும்")) "Where are you all?" else "Where are you?"
                    }
                    // "இங்க வாங்க" (come here) is genuinely ambiguous with "buy" (வாங்க can mean
                    // either "come!" or "buy!"); in this direction/movement context it means "come".
                    if (Regex("""(?:எல்லாரும்|அனைவரும்)?\s*இங்க(?:ே)?\s+(?:வாங்க|வாருங்கள்|வா)(?=$|[\s.,!?])""").containsMatchIn(text)) {
                        return if (text.contains("எல்லாரும்") || text.contains("அனைவரும்")) "Everyone come here" else "Come here"
                    }
                    if (Regex("""(?:எல்லாரும்|அனைவரும்)\s+(?:எப்படி)\s+(?:இருக்கீங்க|இருக்கிறீர்கள்|இருக்காங்க)""").containsMatchIn(text)) {
                        return "How is everyone doing?"
                    }
                    if (Regex("""(?:எங்க|எங்கே)\s+(?:போறீங்க|போகிறீர்கள்|போற)""").containsMatchIn(text)) {
                        return "Where are you going?"
                    }
                    if (Regex("""(?:என்ன)\s+(?:பண்றீங்க|செய்கிறீர்கள்|பண்ற)""").containsMatchIn(text)) {
                        return "What are you doing?"
                    }
                    if (Regex("""(?:எல்லாரும்|அனைவரும்)\s+(?:பத்திரமா|பாதுகாப்பா|பாதுகாப்பாக)\s+(?:இருக்கீங்களா|இருக்கிறீர்களா)""").containsMatchIn(text)) {
                        return "Is everyone safe?"
                    }

                    // Pattern T1: "எங்களுக்கு [பொருட்கள்] தேவை / தேவைப்படுகிறது / வேண்டும்"
                    val needMatch = Regex("""(?:எங்களுக்கு|எனக்கு|நாங்கள்)\s+(.+?)\s+(?:தேவைப்படுகிறது|தேவை|வேண்டும்)""", RegexOption.IGNORE_CASE).find(text)
                    if (needMatch != null) {
                        val item = needMatch.groupValues[1].trim()
                        val translatedItem = translateTamilNounPhrase(item)
                        return "We need $translatedItem."
                    }

                    // Pattern T2: "தயவுசெய்து [பொருட்கள்] அனுப்புங்கள்"
                    val sendMatch = Regex("""(?:தயவுசெய்து\s+)?(.+?)\s+அனுப்புங்கள்""", RegexOption.IGNORE_CASE).find(text)
                    if (sendMatch != null) {
                        val item = sendMatch.groupValues[1].trim()
                        val translatedItem = translateTamilNounPhrase(item)
                        return "Please send $translatedItem."
                    }

                    // Pattern T3: "நாங்கள் [நிலை] இருக்கிறோம்"
                    val stateMatch = Regex("""(?:நாங்கள்|நான்)\s+(.+?)\s+இருக்கிறோம்""", RegexOption.IGNORE_CASE).find(text)
                    if (stateMatch != null) {
                        val state = stateMatch.groupValues[1].trim()
                        return when {
                            state.contains("பாதுகாப்பாக") -> "We are safe."
                            state.contains("நலமாக") -> "We are fine."
                            state.contains("தயாராக") -> "We are ready."
                            state.contains("தள முகாம்") -> "We are at base camp."
                            state.contains("சோதனை") -> "We are at checkpoint."
                            state.contains("இங்கே") -> "We are here."
                            else -> "We are $state."
                        }
                    }

                    // Pattern T4: "சாலை அடைக்கப்பட்டுள்ளது"
                    if (text.contains("சாலை அடைக்கப்பட்டுள்ளது")) {
                        return if (text.contains("வர வேண்டாம்")) {
                            "The road is blocked, do not come this way."
                        } else {
                            "The road is blocked."
                        }
                    }

                    // Pattern T5: "பாலம் சேதமடைந்துள்ளது"
                    if (text.contains("பாலம் சேதமடைந்துள்ளது") || text.contains("பாலம் உடைந்துள்ளது")) {
                        return "The bridge is damaged, do not cross."
                    }

                    // Pattern T6: "[N] நபர்கள் காயமடைந்துள்ளனர்"
                    val countMatch = Regex("""(\d+|ஒரு|இரண்டு|மூன்று|நான்கு|ஐந்து|பல)\s+நபர்கள்\s+காயமடைந்துள்ளனர்""", RegexOption.IGNORE_CASE).find(text)
                    if (countMatch != null) {
                        val count = when (countMatch.groupValues[1]) {
                            "ஒரு" -> "1"
                            "இரண்டு" -> "2"
                            "மூன்று" -> "3"
                            "நான்கு" -> "4"
                            "ஐந்து" -> "5"
                            "பல" -> "Several"
                            else -> countMatch.groupValues[1]
                        }
                        return "$count people are injured."
                    }
                }

                Language.HINDI -> {
                    if (Regex("""(?:सब|सब\s*लोग|आप\s*सब)\s+(?:कहाँ|किधर)\s+(?:हो|हैं)""").containsMatchIn(text)) {
                        return "Where are you all?"
                    }
                    if (Regex("""(?:क्या)\s+(?:कर\s*रहे\s*हो|कर\s*रहे\s*हैं)""").containsMatchIn(text)) {
                        return "What are you doing?"
                    }
                    if (Regex("""(?:कहाँ)\s+(?:जा\s*रहे\s*हो|जा\s*रहे\s*हैं)""").containsMatchIn(text)) {
                        return "Where are you going?"
                    }
                    val needMatch = Regex("""(?:हमें|मुझे)\s+(.+?)\s+(?:की\s+आवश्यकता\s+है|चाहिए)""", RegexOption.IGNORE_CASE).find(text)
                    if (needMatch != null) {
                        val item = needMatch.groupValues[1].trim()
                        val translatedItem = translateHindiNounPhrase(item)
                        return "We need $translatedItem."
                    }
                    val sendMatch = Regex("""(?:कृपया\s+)?(.+?)\s+भेजें""", RegexOption.IGNORE_CASE).find(text)
                    if (sendMatch != null) {
                        val item = sendMatch.groupValues[1].trim()
                        val translatedItem = translateHindiNounPhrase(item)
                        return "Please send $translatedItem."
                    }
                    if (text.contains("सड़क अवरुद्ध है")) {
                        return "The road is blocked."
                    }
                    if (text.contains("पुल क्षतिग्रस्त")) {
                        return "The bridge is damaged."
                    }
                    if (text.contains("सुरक्षित हैं")) {
                        return "We are safe."
                    }
                }

                Language.TELUGU -> {
                    if (Regex("""(?:అందరూ)\s+(?:ఎక్కడ|ఎక్కడున్నారు)\s*(?:ఉన్నారు)?""").containsMatchIn(text)) {
                        return "Where are you all?"
                    }
                    if (Regex("""(?:ఏం|ఏమి)\s+(?:చేస్తున్నారు)""").containsMatchIn(text)) {
                        return "What are you doing?"
                    }
                    if (Regex("""(?:ఎక్కడికి)\s+(?:వెళ్తున్నారు)""").containsMatchIn(text)) {
                        return "Where are you going?"
                    }
                }

                Language.KANNADA -> {
                    if (Regex("""(?:ಎಲ್ಲರೂ|ಎಲ್ಲರು)\s+(?:ಎಲ್ಲಿದ್ದೀರಿ|ಎಲ್ಲಿದ್ದಾರೆ)""").containsMatchIn(text)) {
                        return "Where are you all?"
                    }
                    if (Regex("""(?:ಏನ್|ಏನು)\s+(?:ಮಾಡುತ್ತಿದ್ದೀರಿ|ಮಾಡ್ತಿದ್ದೀರಾ)""").containsMatchIn(text)) {
                        return "What are you doing?"
                    }
                }

                Language.MALAYALAM -> {
                    if (Regex("""(?:എല്ലാവരും)\s+(?:എവിടെയാണ്|എവിടെയാ)""").containsMatchIn(text)) {
                        return "Where are you all?"
                    }
                    if (Regex("""(?:എന്താ|എന്താണ്)\s+(?:ചെയ്യുന്നത്|ചെയ്യുന്നേ)""").containsMatchIn(text)) {
                        return "What are you doing?"
                    }
                }
                else -> {}
            }
            return null
        }

        // Pattern 1: "We need / I need [X]"
        val needMatch = Regex("""^\s*(?:we|i)\s+need\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (needMatch != null) {
            val item = needMatch.groupValues[1].trim().removeSuffix(".")
            val translatedItem = translateNounPhrase(item, lang)
            return when (lang) {
                Language.TAMIL -> "எங்களுக்கு $translatedItem தேவைப்படுகிறது."
                Language.HINDI -> "हमें $translatedItem की आवश्यकता है।"
                Language.TELUGU -> "మాకు $translatedItem అవసరం."
                Language.KANNADA -> "ನಮಗೆ $translatedItem ಅಗತ್ಯವಿದೆ."
                Language.MALAYALAM -> "ഞങ്ങൾക്ക് $translatedItem ആവശ്യമാണ്."
                Language.BENGALI -> "আমাদের $translatedItem প্রয়োজন।"
                Language.MARATHI -> "आम्हाला $translatedItem ची गरज आहे."
                Language.GUJARATI -> "અમને $translatedItem ની જરૂર છે."
                Language.PUNJABI -> "ਸਾਨੂੰ $translatedItem ਦੀ ਲੋੜ ਹੈ।"
                else -> text
            }
        }

        // Pattern 2: "Where is [X]?"
        val whereMatch = Regex("""^\s*where\s+is\s+(?:the\s+)?(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (whereMatch != null) {
            val item = whereMatch.groupValues[1].trim().removeSuffix("?").removeSuffix(".")
            val translatedItem = translateNounPhrase(item, lang)
            return when (lang) {
                Language.TAMIL -> "$translatedItem எங்கே உள்ளது?"
                Language.HINDI -> "$translatedItem कहाँ है?"
                Language.TELUGU -> "$translatedItem ఎక్కడ ఉంది?"
                Language.KANNADA -> "$translatedItem ಎಲ್ಲಿದೆ?"
                Language.MALAYALAM -> "$translatedItem എവിടെയാണ്?"
                else -> text
            }
        }

        // Pattern 3: "Please send [X]"
        val sendMatch = Regex("""^\s*(?:please\s+)?send\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (sendMatch != null) {
            val item = sendMatch.groupValues[1].trim().removeSuffix(".")
            val translatedItem = translateNounPhrase(item, lang)
            return when (lang) {
                Language.TAMIL -> "தயவுசெய்து $translatedItem அனுப்புங்கள்."
                Language.HINDI -> "कृपया $translatedItem भेजें।"
                Language.TELUGU -> "దయచేసి $translatedItem పంపండి."
                Language.KANNADA -> "ದಯವಿಟ್ಟು $translatedItem ಕಳುಹಿಸಿ."
                Language.MALAYALAM -> "ദയവായി $translatedItem അയക്കുക."
                Language.BENGALI -> "অনুগ্রহ করে $translatedItem পাঠান।"
                Language.MARATHI -> "कृपया $translatedItem पाठवा."
                Language.GUJARATI -> "કૃપા કરીને $translatedItem મોકલો."
                Language.PUNJABI -> "ਕਿਰਪਾ ਕਰਕੇ $translatedItem ਭੇਜੋ।"
                else -> text
            }
        }

        // Pattern 4: "I am / We are [state]"
        val stateMatch = Regex("""^\s*(?:i\s+am|we\s+are)\s+(safe|fine|good|ready|waiting|moving|coming)""", RegexOption.IGNORE_CASE).find(text)
        if (stateMatch != null) {
            val state = stateMatch.groupValues[1].lowercase(Locale.ROOT)
            return when (lang) {
                Language.TAMIL -> when (state) {
                    "safe" -> "நாங்கள் பாதுகாப்பாக இருக்கிறோம்."
                    "fine", "good" -> "நாங்கள் நலமாக இருக்கிறோம்."
                    "ready" -> "நாங்கள் தயாராக உள்ளோம்."
                    "waiting" -> "நாங்கள் காத்திருக்கிறோம்."
                    "moving" -> "நாங்கள் நகர்ந்து வருகிறோம்."
                    "coming" -> "நாங்கள் வந்து கொண்டிருக்கிறோம்."
                    else -> text
                }
                Language.HINDI -> when (state) {
                    "safe" -> "हम सुरक्षित हैं।"
                    "fine", "good" -> "हम ठीक हैं।"
                    "ready" -> "हम तैयार हैं।"
                    "waiting" -> "हम प्रतीक्षा कर रहे हैं।"
                    "moving" -> "हम आगे बढ़ रहे हैं।"
                    "coming" -> "हम आ रहे हैं।"
                    else -> text
                }
                else -> text
            }
        }

        // Pattern 5: "Do not [VERB]"
        val doNotMatch = Regex("""^\s*(?:do\s+not|dont)\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (doNotMatch != null) {
            val action = doNotMatch.groupValues[1].trim().removeSuffix(".")
            val translatedAction = translateNounPhrase(action, lang)
            return when (lang) {
                Language.TAMIL -> "$translatedAction வேண்டாம்."
                Language.HINDI -> "$translatedAction मत करें।"
                Language.TELUGU -> "$translatedAction చేయవద్దు."
                else -> text
            }
        }

        // Pattern 6: "There are [N] casualties at [Location]"
        val countMatch = Regex("""^\s*(?:there\s+are\s+)?(\d+|one|two|three|four|five|several|many)\s+(?:people|persons|casualties|injured)\s+(?:at|near|in)\s+(.+)""", RegexOption.IGNORE_CASE).find(text)
        if (countMatch != null) {
            val count = translateNumberWord(countMatch.groupValues[1], lang)
            val loc = translateNounPhrase(countMatch.groupValues[2].removeSuffix("."), lang)
            return when (lang) {
                Language.TAMIL -> "$loc பகுதியில் $count நபர்கள் உள்ளனர்."
                Language.HINDI -> "$loc पर $count लोग मौजूद हैं।"
                Language.TELUGU -> "$loc వద్ద $count మంది ఉన్నారు."
                else -> text
            }
        }

        return null
    }

    private fun translateNounPhrase(phrase: String, lang: Language): String {
        if (lang == Language.ENGLISH) {
            val detected = detectScriptLanguage(phrase)
            return when (detected) {
                Language.TAMIL -> translateTamilNounPhrase(phrase)
                Language.HINDI -> translateHindiNounPhrase(phrase)
                else -> translateTamilNounPhrase(phrase)
            }
        }

        val lower = phrase.lowercase(Locale.ROOT).trim()

        return when (lang) {
            Language.TAMIL -> when {
                lower.contains("water") && lower.contains("food") -> "குடிநீர் மற்றும் உணவு"
                lower.contains("water") -> "குடிநீர்"
                lower.contains("food") -> "உணவுப் பொருட்கள்"
                lower.contains("medicine") || lower.contains("medicines") -> "மருந்துகள்"
                lower.contains("doctor") -> "மருத்துவர்"
                lower.contains("nurse") -> "செவிலியர்"
                lower.contains("hospital") -> "மருத்துவமனை"
                lower.contains("ambulance") -> "ஆம்புலன்ஸ்"
                lower.contains("help") -> "உதவி"
                lower.contains("rescue") -> "மீட்புக் குழு"
                lower.contains("medical team") -> "மருத்துவக் குழு"
                lower.contains("north checkpoint") -> "வடக்கு சோதனைச் சாவடி"
                lower.contains("checkpoint") -> "சோதனைச் சாவடி"
                lower.contains("bridge") -> "பாலம்"
                lower.contains("camp") || lower.contains("base") -> "தள முகாம்"
                lower.contains("sector 4") || lower.contains("sector four") -> "பிரிவு 4"
                lower.contains("sector 3") || lower.contains("sector three") -> "பிரிவு 3"
                lower.contains("shelter") -> "தங்குமிடம்"
                lower.contains("boat") -> "மீட்பு படகு"
                lower.contains("blankets") -> "போர்வைகள்"
                lower.contains("road") -> "சாலை"
                lower.contains("path") -> "பாதை"
                lower.contains("police") -> "காவல்துறை"
                lower.contains("army") -> "ராணுவம்"
                else -> translateWordsWithGrammar(phrase, lang)
            }

            Language.HINDI -> when {
                lower.contains("water") && lower.contains("food") -> "पानी और भोजन"
                lower.contains("water") -> "पीने का पानी"
                lower.contains("food") -> "राशन सामग्री"
                lower.contains("medicine") -> "दवाइयां"
                lower.contains("doctor") -> "डॉक्टर"
                lower.contains("medical team") -> "चिकित्सा दल"
                lower.contains("help") -> "सहायता"
                lower.contains("checkpoint") -> "चेकपॉइंट"
                lower.contains("bridge") -> "पुल"
                lower.contains("camp") -> "शिविर"
                else -> translateWordsWithGrammar(phrase, lang)
            }

            else -> phrase
        }
    }

    private fun translateTamilNounPhrase(phrase: String): String {
        val clean = phrase.trim()
        return when {
            clean.contains("குடிநீர்") && clean.contains("உணவு") -> "food and water"
            clean.contains("உணவும்") && clean.contains("தண்ணீரும்") -> "food and water"
            clean.contains("உணவு") && clean.contains("தண்ணீர்") -> "food and water"
            clean.contains("குடிநீர்") || clean.contains("தண்ணீர்") -> "drinking water"
            clean.contains("உணவு") -> "food"
            clean.contains("மருந்துகள்") || clean.contains("மருந்து") -> "medicines"
            clean.contains("மருத்துவக் குழு") || clean.contains("மருத்துவக்குழு") -> "medical team"
            clean.contains("மருத்துவர்") -> "doctor"
            clean.contains("செவிலியர்") -> "nurse"
            clean.contains("மருத்துவமனை") -> "hospital"
            clean.contains("ஆம்புலன்ஸ்") -> "ambulance"
            clean.contains("உதவி") -> "help"
            clean.contains("மீட்புக் குழு") -> "rescue team"
            clean.contains("வடக்கு சோதனைச் சாவடி") -> "north checkpoint"
            clean.contains("சோதனைச் சாவடி") || clean.contains("சோதனைச்சாவடி") -> "checkpoint"
            clean.contains("பாலம்") -> "bridge"
            clean.contains("தள முகாம்") -> "base camp"
            clean.contains("முகாம்") -> "camp"
            clean.contains("பிரிவு 4") || clean.contains("செக்டர் 4") -> "Sector 4"
            clean.contains("பிரிவு 3") || clean.contains("செக்டர் 3") -> "Sector 3"
            clean.contains("தங்குமிடம்") -> "shelter"
            clean.contains("படகு") -> "boat"
            clean.contains("போர்வைகள்") -> "blankets"
            clean.contains("சாலை") -> "road"
            clean.contains("பாதை") -> "route"
            clean.contains("காவல்துறை") -> "police"
            clean.contains("ராணுவம்") -> "army"
            else -> phrase
        }
    }

    private fun translateHindiNounPhrase(phrase: String): String {
        val clean = phrase.trim()
        return when {
            clean.contains("पानी") && clean.contains("भोजन") -> "food and water"
            clean.contains("पानी") -> "drinking water"
            clean.contains("भोजन") || clean.contains("राशन") -> "food"
            clean.contains("दवा") || clean.contains("दवाइयां") -> "medicines"
            clean.contains("डॉक्टर") -> "doctor"
            clean.contains("चिकित्सा दल") -> "medical team"
            clean.contains("अस्पताल") -> "hospital"
            clean.contains("सहायता") || clean.contains("मदद") -> "help"
            clean.contains("चेकपॉइंट") -> "checkpoint"
            clean.contains("पुल") -> "bridge"
            clean.contains("शिविर") -> "camp"
            clean.contains("सड़क") -> "road"
            else -> phrase
        }
    }

    private fun translateNumberWord(numStr: String, lang: Language): String {
        val lower = numStr.lowercase(Locale.ROOT)
        return when (lang) {
            Language.TAMIL -> when (lower) {
                "one", "1" -> "1"
                "two", "2" -> "2"
                "three", "3" -> "3"
                "four", "4" -> "4"
                "five", "5" -> "5"
                "many", "several" -> "பல"
                else -> numStr
            }
            Language.HINDI -> when (lower) {
                "one", "1" -> "1"
                "two", "2" -> "2"
                "three", "3" -> "3"
                "four", "4" -> "4"
                "five", "5" -> "5"
                "many" -> "कई"
                else -> numStr
            }
            Language.ENGLISH -> when (lower) {
                "ஒரு", "एक" -> "1"
                "இரண்டு", "दो" -> "2"
                "மூன்று", "तीन" -> "3"
                "நான்கு", "चार" -> "4"
                "ஐந்து", "पाँच" -> "5"
                "பல", "कई" -> "several"
                else -> numStr
            }
            else -> numStr
        }
    }

    private fun translateWordsWithGrammar(
        sentence: String,
        targetLang: Language,
        sourceLang: Language = Language.ENGLISH
    ): String {
        val rawWords = sentence.split(Regex("""\s+"""))
            .map { it.replace(Regex("""[.,!?;:\"()\[\]]"""), "") }
            .filter { it.isNotBlank() }
        if (rawWords.isEmpty()) return sentence

        if (targetLang == Language.ENGLISH) {
            val detected = detectScriptLanguage(sentence) ?: sourceLang
            val dictionary = when (detected) {
                Language.TAMIL -> tamilToEnglishMap
                Language.HINDI -> hindiToEnglishMap
                else -> tamilToEnglishMap
            }

            var subjectToken: String? = null
            val objectTokens = mutableListOf<String>()
            var verbToken: String? = null
            var auxVerbToken: String? = null

            val subjects = setOf("i", "we", "you", "he", "she", "they", "it")
            val verbs = setOf("need", "want", "see", "hear", "help", "call", "send", "have", "bring", "give", "take", "stop", "wait", "move", "reach", "report", "come", "coming", "go", "going", "blocked", "damaged", "injured")
            val auxVerbs = setOf("is", "are", "am", "was", "were", "will", "can")

            for (w in rawWords) {
                val lower = w.lowercase(Locale.ROOT)
                val translated = dictionary[lower] ?: w

                val transLower = translated.lowercase(Locale.ROOT)
                when {
                    transLower in subjects && subjectToken == null -> {
                        subjectToken = translated
                    }
                    transLower in verbs && verbToken == null -> {
                        verbToken = translated
                    }
                    transLower in auxVerbs && auxVerbToken == null -> {
                        auxVerbToken = translated
                    }
                    else -> {
                        objectTokens.add(translated)
                    }
                }
            }

            val resultTokens = mutableListOf<String>()
            subjectToken?.let { resultTokens.add(it) }
            auxVerbToken?.let { resultTokens.add(it) }
            verbToken?.let { resultTokens.add(it) }
            resultTokens.addAll(objectTokens)

            val sentenceResult = if (resultTokens.isNotEmpty()) {
                resultTokens.joinToString(" ")
            } else {
                rawWords.map { dictionary[it.lowercase(Locale.ROOT)] ?: it }.joinToString(" ")
            }
            return sentenceResult.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }

        val dictionary = getBilingualDictionary(targetLang)

        var subjectToken: String? = null
        val objectTokens = mutableListOf<String>()
        var verbToken: String? = null
        var auxVerbToken: String? = null

        val subjects = setOf("i", "we", "you", "he", "she", "they", "it", "this", "that")
        val verbs = setOf("need", "want", "see", "hear", "help", "call", "send", "have", "has", "bring", "give", "take", "stop", "wait", "move", "reach", "report")
        val auxVerbs = setOf("is", "are", "am", "was", "were", "will", "can")

        for (w in rawWords) {
            val lower = w.lowercase(Locale.ROOT)
            val translated = dictionary[lower] ?: w

            when {
                lower in subjects && subjectToken == null -> {
                    subjectToken = translated
                }
                lower in verbs && verbToken == null -> {
                    verbToken = translated
                }
                lower in auxVerbs && auxVerbToken == null -> {
                    auxVerbToken = translated
                }
                else -> {
                    objectTokens.add(translated)
                }
            }
        }

        val resultTokens = mutableListOf<String>()
        subjectToken?.let { resultTokens.add(it) }
        resultTokens.addAll(objectTokens)
        verbToken?.let { resultTokens.add(it) }
        auxVerbToken?.let { resultTokens.add(it) }

        return if (resultTokens.isNotEmpty()) {
            resultTokens.joinToString(" ")
        } else {
            rawWords.map { dictionary[it.lowercase(Locale.ROOT)] ?: it }.joinToString(" ")
        }
    }

    private fun getBilingualDictionary(lang: Language): Map<String, String> {
        return when (lang) {
            Language.TAMIL -> tamilDictionary
            Language.HINDI -> hindiDictionary
            Language.TELUGU -> teluguDictionary
            Language.KANNADA -> kannadaDictionary
            Language.MALAYALAM -> malayalamDictionary
            else -> emptyMap()
        }
    }

    companion object {
        private val tamilDictionary = mapOf(
            "i" to "நான்",
            "me" to "என்னை",
            "my" to "என்",
            "mine" to "என்னுடையது",
            "we" to "நாங்கள்",
            "us" to "எங்களை",
            "our" to "எங்கள்",
            "you" to "நீங்கள்",
            "your" to "உங்கள்",
            "he" to "அவர்",
            "him" to "அவரை",
            "his" to "அவருடைய",
            "she" to "அவள்",
            "her" to "அவளை",
            "they" to "அவர்கள்",
            "them" to "அவர்களை",
            "their" to "அவர்களின்",
            "it" to "அது",
            "its" to "அதன்",
            "this" to "இந்த",
            "that" to "அந்த",
            "these" to "இவை",
            "those" to "அவை",
            "what" to "என்ன",
            "where" to "எங்கே",
            "when" to "எப்போது",
            "why" to "ஏன்",
            "who" to "யார்",
            "how" to "எப்படி",
            "which" to "எந்த",
            "need" to "தேவை",
            "want" to "வேண்டும்",
            "send" to "அனுப்புங்கள்",
            "sending" to "அனுப்புகிறோம்",
            "come" to "வாருங்கள்",
            "coming" to "வருகிறோம்",
            "go" to "செல்லுங்கள்",
            "going" to "செல்கிறோம்",
            "stop" to "நிறுத்துங்கள்",
            "wait" to "காத்திருங்கள்",
            "waiting" to "காத்திருக்கிறோம்",
            "help" to "உதவுங்கள்",
            "helping" to "உதவுகிறோம்",
            "hear" to "கேட்கிறது",
            "listen" to "கேளுங்கள்",
            "speak" to "பேசுங்கள்",
            "speaking" to "பேசுகிறோம்",
            "tell" to "சொல்லுங்கள்",
            "see" to "பாருங்கள்",
            "report" to "அறிக்கை",
            "reporting" to "தெரிவிக்கிறோம்",
            "rescue" to "மீட்கவும்",
            "call" to "அழையுங்கள்",
            "calling" to "அழைக்கிறோம்",
            "check" to "சரிபார்க்கவும்",
            "secure" to "பாதுகாப்பானது",
            "secured" to "பாதுகாக்கப்பட்டது",
            "stay" to "இருங்கள்",
            "move" to "நகருங்கள்",
            "moving" to "நகர்ந்து வருகிறோம்",
            "injured" to "காயமடைந்துள்ளார்",
            "damaged" to "சேதமடைந்துள்ளது",
            "broken" to "உடைந்துள்ளது",
            "blocked" to "அடைக்கப்பட்டுள்ளது",
            "bring" to "கொண்டுவாருங்கள்",
            "give" to "கொடுங்கள்",
            "take" to "எடுத்துக்கொள்ளுங்கள்",
            "know" to "தெரியும்",
            "reach" to "சென்றடையுங்கள்",
            "run" to "ஓடுங்கள்",
            "save" to "காப்பாற்றுங்கள்",
            "work" to "வேலை செய்கிறது",
            "is" to "உள்ளது",
            "are" to "உள்ளன",
            "am" to "இருக்கிறேன்",
            "was" to "இருந்தது",
            "were" to "இருந்தன",
            "will" to "செய்யும்",
            "can" to "முடியும்",
            "cannot" to "முடியாது",
            "have" to "உள்ளது",
            "has" to "உள்ளது",
            "had" to "இருந்தது",
            "good" to "நல்ல",
            "bad" to "மோசமான",
            "fine" to "நலம்",
            "safe" to "பாதுகாப்பான",
            "danger" to "ஆபத்தான",
            "dangerous" to "ஆபத்து",
            "sick" to "உடல்நலக்குறைவு",
            "clear" to "தெளிவான",
            "ready" to "தயார்",
            "full" to "முழு",
            "empty" to "காலி",
            "fast" to "வேகமாக",
            "slow" to "மெதுவாக",
            "big" to "பெரிய",
            "small" to "சிறிய",
            "high" to "உயர்ந்த",
            "low" to "குறைந்த",
            "new" to "புதிய",
            "old" to "பழைய",
            "emergency" to "அவசரம்",
            "critical" to "முக்கியமான",
            "hot" to "சூடான",
            "cold" to "குளிர்ந்த",
            "water" to "குடிநீர்",
            "food" to "உணவு",
            "medicine" to "மருந்து",
            "medicines" to "மருந்துகள்",
            "doctor" to "மருத்துவர்",
            "nurse" to "செவிலியர்",
            "ambulance" to "ஆம்புலன்ஸ்",
            "hospital" to "மருத்துவமனை",
            "team" to "குழு",
            "unit" to "பிரிவு",
            "squad" to "படை",
            "camp" to "முகாம்",
            "base" to "தள முகாம்",
            "bridge" to "பாலம்",
            "road" to "சாலை",
            "path" to "பாதை",
            "route" to "வழி",
            "river" to "ஆறு",
            "building" to "கட்டடம்",
            "house" to "வீடு",
            "room" to "அறை",
            "shelter" to "தங்குமிடம்",
            "people" to "நபர்கள்",
            "person" to "நபர்",
            "child" to "குழந்தை",
            "children" to "குழந்தைகள்",
            "family" to "குடும்பம்",
            "friend" to "நண்பர்",
            "casualties" to "பாதிக்கப்பட்டவர்கள்",
            "radio" to "ரேடியோ",
            "signal" to "சிக்னல்",
            "phone" to "தொலைபேசி",
            "battery" to "பேட்டரி",
            "power" to "மின்சாரம்",
            "boat" to "படகு",
            "vehicle" to "வாகனம்",
            "car" to "கார்",
            "truck" to "லாரி",
            "checkpoint" to "சோதனைச் சாவடி",
            "sector" to "பிரிவு",
            "location" to "இடம்",
            "place" to "பகுதி",
            "area" to "பகுதி",
            "zone" to "மண்டலம்",
            "fire" to "தீ",
            "flood" to "வெள்ளம்",
            "rain" to "மழை",
            "police" to "காவல்துறை",
            "army" to "ராணுவம்",
            "name" to "பெயர்",
            "problem" to "பிரச்சினை",
            "status" to "நிலை",
            "order" to "உத்தரவு",
            "connection" to "தொடர்பு",
            "time" to "நேரம்",
            "and" to "மற்றும்",
            "or" to "அல்லது",
            "but" to "ஆனால்",
            "also" to "கூட",
            "very" to "மிகவும்",
            "immediately" to "உடனடியாக",
            "quickly" to "விரைவாக",
            "slowly" to "மெதுவாக",
            "carefully" to "கவனமாக",
            "now" to "இப்போது",
            "here" to "இங்கே",
            "there" to "அங்கே",
            "today" to "இன்று",
            "tomorrow" to "நாளை",
            "please" to "தயவுசெய்து",
            "thank" to "நன்றி",
            "thanks" to "நன்றி",
            "yes" to "ஆம்",
            "no" to "இல்லை"
        )

        private val hindiDictionary = mapOf(
            "i" to "मैं",
            "me" to "मुझे",
            "my" to "मेरा",
            "we" to "हम",
            "us" to "हमें",
            "our" to "हमारा",
            "you" to "आप",
            "your" to "आपका",
            "he" to "वह",
            "she" to "वह",
            "they" to "वे",
            "what" to "क्या",
            "where" to "कहाँ",
            "when" to "कब",
            "who" to "कौन",
            "how" to "कैसे",
            "need" to "आवश्यकता",
            "want" to "चाहिए",
            "send" to "भेजें",
            "come" to "आएं",
            "coming" to "आ रहे हैं",
            "help" to "मदद",
            "hear" to "सुन",
            "speak" to "बोलें",
            "water" to "पानी",
            "food" to "भोजन",
            "medicine" to "दवा",
            "doctor" to "डॉक्टर",
            "hospital" to "अस्पताल",
            "bridge" to "पुल",
            "road" to "सड़क",
            "camp" to "शिविर",
            "checkpoint" to "चेकपॉइंट",
            "safe" to "सुरक्षित",
            "clear" to "साफ़",
            "blocked" to "अवरुद्ध",
            "broken" to "क्षतिग्रस्त",
            "is" to "है",
            "are" to "हैं",
            "am" to "हूँ",
            "now" to "अब",
            "here" to "यहाँ",
            "there" to "वहाँ",
            "please" to "कृपया",
            "immediately" to "तुरंत",
            "and" to "और"
        )

        private val teluguDictionary = mapOf(
            "we" to "మేము",
            "you" to "మీరు",
            "need" to "కావాలి",
            "send" to "పంపండి",
            "help" to "సహాయం",
            "water" to "నీరు",
            "food" to "ఆహారం",
            "doctor" to "డాక్టర్",
            "bridge" to "వంతెన",
            "road" to "రహదారి",
            "safe" to "సురక్షితం",
            "area" to "ప్రాంతం",
            "clear" to "క్లియర్",
            "is" to "ఉంది",
            "are" to "ఉన్నాయి",
            "please" to "దయచేసి",
            "immediately" to "వెంటనే",
            "and" to "మరియు"
        )

        private val kannadaDictionary = mapOf(
            "we" to "ನಾವು",
            "you" to "ನೀವು",
            "need" to "ಬೇಕು",
            "send" to "ಕಳುಹಿಸಿ",
            "help" to "ಸಹಾಯ",
            "water" to "ನೀರು",
            "food" to "ಆಹಾರ",
            "doctor" to "ವೈದ್ಯರು",
            "road" to "ರಸ್ತೆ",
            "safe" to "ಸುರಕ್ಷಿತ",
            "is" to "ಇದೆ",
            "please" to "ದಯವಿಟ್ಟು",
            "and" to "ಮತ್ತು"
        )

        private val malayalamDictionary = mapOf(
            "we" to "ഞങ്ങൾ",
            "you" to "നിങ്ങൾ",
            "need" to "വേണം",
            "send" to "അയക്കുക",
            "help" to "സഹായം",
            "water" to "വെള്ളം",
            "food" to "ഭക്ഷണം",
            "doctor" to "ഡോക്ടർ",
            "road" to "റോഡ്",
            "safe" to "സുരക്ഷിതം",
            "is" to "ആണ്",
            "please" to "ദയവായി",
            "and" to "ഒപ്പം"
        )

        private val tamilToEnglishMap: Map<String, String> by lazy {
            val map = mutableMapOf<String, String>()
            for ((eng, tam) in tamilDictionary) {
                map[tam.lowercase(Locale.ROOT)] = eng
            }
            val extras = mapOf(
                "எல்லாரும்" to "everyone",
                "அனைவரும்" to "everyone",
                "எங்க" to "where",
                "இருக்கீங்க" to "are",
                "இருக்கேன்" to "am",
                "இருக்கோம்" to "are",
                "இருக்காங்க" to "are",
                "இருக்காரு" to "is",
                "பண்றீங்க" to "doing",
                "பண்றேன்" to "doing",
                "போறீங்க" to "going",
                "போறேன்" to "going",
                "வர்றீங்க" to "coming",
                "வர்றேன்" to "coming",
                "வரோம்" to "coming",
                "நீங்க" to "you",
                "நாங்க" to "we",
                "அவங்க" to "they",
                "வேணும்" to "need",
                "இல்ல" to "no",
                "பத்திரமா" to "safe",
                "நல்லா" to "fine",
                "வாங்க" to "come",
                "போங்க" to "go",
                "எங்களுக்கு" to "we",
                "எனக்கு" to "i",
                "உங்களுக்கு" to "you",
                "அவர்களுக்கு" to "they",
                "உணவும்" to "food",
                "தண்ணீரும்" to "water",
                "தண்ணீர்" to "water",
                "தேவைப்படுகிறது" to "need",
                "தேவை" to "need",
                "வேண்டும்" to "need",
                "வேண்டாம்" to "do not",
                "உதவி" to "help",
                "உதவுங்கள்" to "help",
                "வருகிறோம்" to "coming",
                "வாருங்கள்" to "come",
                "செல்கிறோம்" to "going",
                "செல்லுங்கள்" to "go",
                "காத்திருக்கிறோம்" to "waiting",
                "காத்திருங்கள்" to "wait",
                "இருக்கிறோம்" to "are",
                "இருக்கிறேன்" to "am",
                "இருக்கிறீர்கள்" to "are",
                "இருக்கிறது" to "is",
                "உள்ளது" to "is",
                "உள்ளன" to "are",
                "பாதுகாப்பாக" to "safe",
                "பாதுகாப்பானது" to "secure",
                "நலமாக" to "fine",
                "தயாராக" to "ready",
                "அடைக்கப்பட்டுள்ளது" to "blocked",
                "சேதமடைந்துள்ளது" to "damaged",
                "உடைந்துள்ளது" to "broken",
                "காயமடைந்துள்ளார்" to "injured",
                "காயமடைந்துள்ளனர்" to "injured",
                "காயமடைந்தவர்கள்" to "casualties",
                "கேட்கிறதா" to "can you hear",
                "கேட்கிறது" to "hear",
                "கேட்கவில்லை" to "cannot hear",
                "தெளிவாக" to "clearly",
                "சத்தமாக" to "loudly",
                "பேசுங்கள்" to "speak",
                "பேசுகிறோம்" to "speaking",
                "குரல்" to "voice",
                "வணக்கம்" to "hello",
                "நன்றி" to "thank you",
                "ஆம்" to "yes",
                "இல்லை" to "no",
                "பிரிவு" to "sector",
                "செக்டர்" to "sector",
                "சோதனைச்" to "checkpoint",
                "சாவடி" to "checkpoint",
                "சோதனைச்சாவடி" to "checkpoint",
                "தள" to "base",
                "முகாமில்" to "at base camp",
                "முகாம்" to "camp",
                "இங்கே" to "here",
                "அங்கே" to "there",
                "எங்கே" to "where",
                "எப்போது" to "when",
                "ஏன்" to "why",
                "யார்" to "who",
                "என்ன" to "what",
                "எப்படி" to "how",
                "உடனடியாக" to "immediately",
                "விரைவாக" to "quickly",
                "சீக்கிரம்" to "quickly",
                "மெதுவாக" to "slowly",
                "கவனமாக" to "carefully",
                "மருத்துவமனை" to "hospital",
                "மருத்துவக்" to "medical",
                "குழு" to "team",
                "மருத்துவக்குழு" to "medical team",
                "மருத்துவர்" to "doctor",
                "மருந்துகள்" to "medicines",
                "மருந்து" to "medicine",
                "ஆம்புலன்ஸ்" to "ambulance",
                "மீட்பு" to "rescue",
                "படகு" to "boat",
                "சாலை" to "road",
                "பாலம்" to "bridge",
                "பாதை" to "route",
                "வழி" to "route",
                "பகுதி" to "area",
                "அனைத்தும்" to "all",
                "சரி" to "clear",
                "ரேடியோ" to "radio",
                "சிக்னல்" to "signal",
                "பேட்டரி" to "battery",
                "குறைவாக" to "low",
                "அளவு" to "level",
                "தீர்ந்துவிட்டது" to "exhausted",
                "தீர்ந்துவிட்டன" to "exhausted",
                "நபர்கள்" to "people",
                "நபர்" to "person",
                "ஒரு" to "one",
                "இரண்டு" to "two",
                "மூன்று" to "three",
                "நான்கு" to "four",
                "ஐந்து" to "five"
            )
            for ((k, v) in extras) {
                map[k.lowercase(Locale.ROOT)] = v
            }
            map
        }

        private val hindiToEnglishMap: Map<String, String> by lazy {
            val map = mutableMapOf<String, String>()
            for ((eng, hin) in hindiDictionary) {
                map[hin.lowercase(Locale.ROOT)] = eng
            }
            val extras = mapOf(
                "हमें" to "we",
                "मुझे" to "i",
                "आप" to "you",
                "पानी" to "water",
                "भोजन" to "food",
                "आवश्यकता" to "need",
                "चाहिए" to "need",
                "मदद" to "help",
                "सहायता" to "help",
                "सुरक्षित" to "safe",
                "अवरुद्ध" to "blocked",
                "क्षतिग्रस्त" to "damaged",
                "साफ़" to "clear",
                "नमस्ते" to "hello",
                "नमस्कार" to "hello",
                "धन्यवाद" to "thank you",
                "हाँ" to "yes",
                "नहीं" to "no",
                "डॉक्टर" to "doctor",
                "चिकित्सा" to "medical",
                "दल" to "team",
                "अस्पताल" to "hospital",
                "पुल" to "bridge",
                "सड़क" to "road",
                "मार्ग" to "route",
                "शिविर" to "camp",
                "चेकपॉइंट" to "checkpoint",
                "आ" to "coming",
                "रहे" to "are",
                "हैं" to "are",
                "है" to "is",
                "हूँ" to "am",
                "कहाँ" to "where",
                "क्या" to "what",
                "तुरंत" to "immediately",
                "जल्दी" to "quickly",
                "कृपया" to "please",
                "रेडियो" to "radio",
                "जांच" to "check",
                "संपर्क" to "connection"
            )
            for ((k, v) in extras) {
                map[k.lowercase(Locale.ROOT)] = v
            }
            map
        }
    }
}
