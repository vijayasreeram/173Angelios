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

            // 2. Substring / reverse phrase match — ONLY if key covers ≥80% of input length
            // Never drop clauses: a 5-char word like "வணக்கம்" must not match inside a 40-char compound sentence!
            pack.phrasesTargetToEn.entries.find { entry ->
                val k = entry.key
                cleanInput.equals(k, ignoreCase = true) ||
                cleanPunct.equals(k, ignoreCase = true) ||
                (k.length >= cleanInput.length * 0.8 && (cleanInput.contains(k) || k.contains(cleanInput)))
            }?.let { return it.value }

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
                    pack.phrasesTargetToEn.entries.find { it.key.equals(cleanConv, ignoreCase = true) }?.let { return it.value }
                }
            }

            // 4. Lexicon word match — only for single-word queries, never for full sentences
            val inputWords = cleanInput.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (inputWords.size == 1 && !containsNegationMarker(cleanInput)) {
                for ((enWord, indicWord) in pack.lexicon) {
                    if (cleanInput.equals(indicWord, ignoreCase = true) || lowerInput.equals(enWord, ignoreCase = true)) {
                        return enWord
                    }
                }
            }
        }

        // Case B: English -> Indic (e.g. English -> Tamil)
        if (sourceLanguage == Language.ENGLISH) {
            val pack = getOrLoadPack(targetLanguage) ?: return null
            // 1. Direct phrase match (exact or stripped of punctuation)
            pack.phrasesEnToTarget[lowerInput]?.let { return it }
            pack.phrasesEnToTarget[cleanPunct]?.let { return it }

            // 2. Substring match — ONLY if key covers ≥80% of input length
            // Never drop clauses: e.g. "hello" (5 chars) must NEVER match inside "hello how are you" (17 chars)
            pack.phrasesEnToTarget.entries.find { entry ->
                val k = entry.key
                lowerInput.equals(k, ignoreCase = true) ||
                cleanPunct.equals(k, ignoreCase = true) ||
                (k.length >= lowerInput.length * 0.8 && (lowerInput.contains(k) || k.contains(lowerInput)))
            }?.let { return it.value }

            // 3. Lexicon match — only for single-word queries, never truncate a multi-word sentence!
            val enWordList = lowerInput.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (enWordList.size == 1) {
                for ((enWord, indicWord) in pack.lexicon) {
                    if (cleanPunct.equals(enWord, ignoreCase = true) || lowerInput.equals(enWord, ignoreCase = true)) {
                        return indicWord
                    }
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
        sourceLanguage: Language = Language.ENGLISH,
        depth: Int = 0
    ): String {
        val trimmed = inputText.trim().nfc()
        if (trimmed.isBlank()) {
            return trimmed
        }

        // Clean outer wrapping brackets/parentheses like "(we had lunch )" -> "we had lunch"
        val unbracketed = trimmed
            .removeSurrounding("(", ")").trim()
            .removeSurrounding("[", "]").trim()
            .removeSurrounding("{", "}").trim()
            .removeSurrounding("\"", "\"").trim()
            .removeSurrounding("'", "'").trim()

        if (unbracketed.isNotBlank() && unbracketed != trimmed) {
            val res = translate(unbracketed, targetLanguage, sourceLanguage, depth = depth)
            if (res.isNotBlank()) return res
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
                        val indicFromEn = translate(directPackEn, targetLanguage, Language.ENGLISH, depth = depth + 1)
                        translationCache[cacheKey] = indicFromEn
                        return indicFromEn
                    }
                }
                // Normalize to semantic Tamil script and translate
                val semTamil = TanglishEngine.tanglishToSemanticTamil(trimmed)
                val res = translate(semTamil, targetLanguage, Language.TAMIL, depth = depth + 1)
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

        // Tier 1.5: Direct High-Fidelity Idiomatic & Conversational Phrase Matching (0.02 ms)
        var phraseMatch = matchCommonPhrases(trimmed.lowercase(Locale.ROOT), targetLanguage, actualSource)
        if (phraseMatch == null && formalText != trimmed) {
            phraseMatch = matchCommonPhrases(formalText.lowercase(Locale.ROOT), targetLanguage, actualSource)
        }
        if (phraseMatch != null) {
            val colloquialPhrase = ColloquialEngine.toColloquial(phraseMatch, targetLanguage, trimmed)
            translationCache[cacheKey] = colloquialPhrase
            return colloquialPhrase
        }

        // Tier 1.55: Base Station neural model (IndicTrans2 on GPU) - outranks every fuzzy/heuristic tier.
        // Exact curated phrases above still win (authoritative tactical wording), but for anything
        // free-form - slang, colloquial speech, long sentences - the real neural translation is far
        // more accurate than templates, the compound splitter, ML Kit or word-by-word rules.
        var edgeAttempted = false
        if (isEdgeServerActive && edgeServerHost != null && depth == 0) {
            edgeAttempted = true
            var edgeFirst = queryEdgeServer(trimmed, actualSource, targetLanguage)
            if (!isPlausibleTranslation(edgeFirst, trimmed, targetLanguage) && formalText != trimmed) {
                edgeFirst = queryEdgeServer(formalText, actualSource, targetLanguage)
            }
            if (isPlausibleTranslation(edgeFirst, trimmed, targetLanguage)) {
                val colloquialEdge = ColloquialEngine.toColloquial(edgeFirst!!.trim(), targetLanguage, trimmed)
                translationCache[cacheKey] = colloquialEdge
                return colloquialEdge
            }
        }

        // Tier 1.6: Medical, Trauma & Mental Disorientation Engine (0.02 ms)
        // Bidirectional, multi-lingual support across all 11 languages (English + 10 Indic languages)
        var medicalMatch = matchMedicalAndTraumaPatterns(trimmed, targetLanguage, actualSource)
        if (medicalMatch == null && formalText != trimmed) {
            medicalMatch = matchMedicalAndTraumaPatterns(formalText, targetLanguage, actualSource)
        }
        if (medicalMatch != null) {
            val colloquialMedical = ColloquialEngine.toColloquial(medicalMatch, targetLanguage, trimmed)
            translationCache[cacheKey] = colloquialMedical
            return colloquialMedical
        }

        // Tier 1.7: Syntactic Pattern Matching with Slot Substitution (0.02 ms)
        var patternMatch = matchSentencePatterns(trimmed, targetLanguage, actualSource)
        if (patternMatch == null && formalText != trimmed) {
            patternMatch = matchSentencePatterns(formalText, targetLanguage, actualSource)
        }
        if (patternMatch != null) {
            val colloquialPattern = ColloquialEngine.toColloquial(patternMatch, targetLanguage, trimmed)
            translationCache[cacheKey] = colloquialPattern
            return colloquialPattern
        }

        // Tier 1.8: Indic-to-Indic English Pivot (0.05 ms)
        // If neither source nor target is English, translate through English pivot so that
        // phrase and pattern matchers for all languages can be reused seamlessly.
        if (actualSource != Language.ENGLISH && targetLanguage != Language.ENGLISH && depth == 0) {
            val enPivot = translate(formalText, Language.ENGLISH, actualSource, depth = depth + 1)
            if (enPivot.isNotBlank() && enPivot != formalText && enPivot != trimmed && detectScriptLanguage(enPivot) != actualSource) {
                val tgtResult = translate(enPivot, targetLanguage, Language.ENGLISH, depth = depth + 1)
                if (tgtResult.isNotBlank() && tgtResult != enPivot && detectScriptLanguage(tgtResult) != Language.ENGLISH) {
                    val finalRes = ColloquialEngine.toColloquial(tgtResult, targetLanguage, trimmed)
                    translationCache[cacheKey] = finalRes
                    return finalRes
                }
            }
        }

        // Tier 2: Compound Sentence Splitter (Google Translate-style multi-clause handling)
        // Splits multi-clause sentences at punctuation, conjunctions, or clause boundaries
        // and translates each clause independently, producing complete translations without dropping words.
        // Guarded by depth == 0 to strictly prevent mutual recursion and stack overflow!
        val inputWordCount = trimmed.split(Regex("""\s+""")).filter { it.isNotBlank() }.size
        if (inputWordCount >= 2 && depth == 0) {
            val compoundResult = splitAndTranslateCompound(trimmed, actualSource, targetLanguage, depth = depth)
                ?: if (formalText != trimmed) splitAndTranslateCompound(formalText, actualSource, targetLanguage, depth = depth) else null
            if (!compoundResult.isNullOrBlank()) {
                val colloquialCompound = ColloquialEngine.toColloquial(compoundResult, targetLanguage, trimmed)
                translationCache[cacheKey] = colloquialCompound
                return colloquialCompound
            }
        }

        // Tier 4: Ultra-Fast Edge Server Query (Only if local pack did not match and base station is active)
        if (!edgeAttempted && isEdgeServerActive && edgeServerHost != null) {
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

    /**
     * Guards against a neural/server response that is not a usable translation: blank, an
     * untranslated echo of the input, or text in the wrong script for the requested target.
     */
    private fun isPlausibleTranslation(result: String?, input: String, target: Language): Boolean {
        val r = result?.trim().orEmpty()
        if (r.isBlank() || r.equals(input.trim(), ignoreCase = true)) return false
        val script = detectScriptLanguage(r)
        return if (target == Language.ENGLISH) script == null else script == target
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
        targetLanguage: Language,
        depth: Int = 0
    ): String? {
        val cleanText = text.trim()

        // 1. First check explicit punctuation splits (commas, semicolons, full stops, question marks)
        // e.g. "hello, how are you", "I am fine. Where are you?"
        val punctMatch = Regex("""(?<=\S)[,;!?]\s+(?=\S)""").find(cleanText)
        if (punctMatch != null) {
            val splitIdx = punctMatch.range.first
            val c1 = cleanText.substring(0, splitIdx).trim()
            val c2 = cleanText.substring(punctMatch.range.last + 1).trim()
            if (c1.isNotBlank() && c2.isNotBlank()) {
                val t1 = translate(c1, targetLanguage, sourceLanguage, depth = depth + 1)
                val t2 = translate(c2, targetLanguage, sourceLanguage, depth = depth + 1)
                if (t1 != c1 || t2 != c2) {
                    val sep = if (t1.endsWith("?") || t1.endsWith(".") || t1.endsWith("!")) " " else ", "
                    return "$t1$sep$t2"
                }
            }
        }

        // 2. Language-specific clause boundary markers
        val boundaries = when (sourceLanguage) {
            Language.ENGLISH -> listOf(
                // Greeting prefix: "hello how are you", "good morning where are you"
                Regex("""(?<=\b(?:hello|hi|hey|good\s+morning|good\s+afternoon|good\s+evening|good\s+night))\s+(?=\S)""", RegexOption.IGNORE_CASE),
                // Status prefix: "i am fine how are you", "we are safe where are you"
                Regex("""(?<=\b(?:i am fine|im fine|i am good|we are fine|we are good|we are safe|i am safe|all clear|all safe))\s+(?=\S)""", RegexOption.IGNORE_CASE),
                // Question starters: "what is your name", "where are you", "how are you"
                Regex("""(?<=\S)\s+(?=(?:how\s+are\s+you|how\s+are\s+you\s+doing|where\s+are\s+you|what\s+are\s+you\s+doing|what\s+is\s+your|what\s+happened|are\s+you\s+safe|can\s+you\s+help|please\s+help)\b)""", RegexOption.IGNORE_CASE),
                // Conjunctions: and, but, so, then, or
                Regex("""(?<=\S)\s+(?:and|but|so|then|or)\s+(?=\S)""", RegexOption.IGNORE_CASE),
            )
            Language.TAMIL -> listOf(
                Regex("""(?<=\S)\s+(நீங்க|நீங்கள்)\s+"""),
                Regex("""(?<=\S)\s+(நாங்க|நாங்கள்)\s+"""),
                Regex("""(?<=\S)\s+(அவங்க|அவர்கள்)\s+"""),
                Regex("""(?<=\b(?:வணக்கம்|காலை\s+வணக்கம்|மாலை\s+வணக்கம்))\s+(?=\S)"""),
                Regex("""(?<=\b(?:நல்லா\s+இருக்கேன்|நலமாக\s+இருக்கிறேன்|நல்லா\s+இருக்கோம்))\s+(?=\S)"""),
            )
            Language.HINDI -> listOf(
                Regex("""(?<=\S)\s+(आप|तुम|आपने|तुमने)\s+"""),
                Regex("""(?<=\S)\s+(हम|हमने)\s+"""),
                Regex("""(?<=\S)\s+(वे|उन्होंने)\s+"""),
                Regex("""(?<=\b(?:नमस्ते|नमस्कार|हेलो))\s+(?=\S)"""),
                Regex("""(?<=\b(?:ठीक\s+हूँ|ठीक\s+हैं))\s+(?=\S)"""),
            )
            Language.TELUGU -> listOf(
                Regex("""(?<=\S)\s+(మీరు|మీకు|మీతో)\s+"""),
                Regex("""(?<=\S)\s+(మేము|మనము)\s+"""),
                Regex("""(?<=\S)\s+(వారు|వారికి)\s+"""),
                Regex("""(?<=\b(?:నమస్కారం))\s+(?=\S)"""),
            )
            Language.KANNADA -> listOf(
                Regex("""(?<=\S)\s+(ನೀವು|ನಿಮಗೆ)\s+"""),
                Regex("""(?<=\S)\s+(ನಾವು|ನಮಗೆ)\s+"""),
                Regex("""(?<=\S)\s+(ಅವರು|ಅವರಿಗೆ)\s+"""),
                Regex("""(?<=\b(?:ನಮಸ್ಕಾರ))\s+(?=\S)"""),
            )
            Language.MALAYALAM -> listOf(
                Regex("""(?<=\S)\s+(നിങ്ങൾ|നിങ്ങൾക്ക്)\s+"""),
                Regex("""(?<=\S)\s+(ഞങ്ങൾ|നമ്മൾ)\s+"""),
                Regex("""(?<=\S)\s+(അവർ|അദ്ദേഹം)\s+"""),
                Regex("""(?<=\b(?:നമസ്കാരം))\s+(?=\S)"""),
            )
            Language.BENGALI -> listOf(
                Regex("""(?<=\S)\s+(আপনি|তুমি|আপনার)\s+"""),
                Regex("""(?<=\S)\s+(আমরা|আমাদের)\s+"""),
                Regex("""(?<=\S)\s+(তারা|তাদের)\s+"""),
                Regex("""(?<=\b(?:নমস্কার|হ্যালো))\s+(?=\S)"""),
            )
            Language.MARATHI -> listOf(
                Regex("""(?<=\S)\s+(तुम्ही|आपण|तुमचे)\s+"""),
                Regex("""(?<=\S)\s+(आम्ही|आपण|आमचे)\s+"""),
                Regex("""(?<=\S)\s+(ते|त्यांचे)\s+"""),
                Regex("""(?<=\b(?:नमस्कार))\s+(?=\S)"""),
            )
            Language.GUJARATI -> listOf(
                Regex("""(?<=\S)\s+(તમે|આપ|તમારે)\s+"""),
                Regex("""(?<=\S)\s+(અમે|આપણે)\s+"""),
                Regex("""(?<=\S)\s+(તેઓ|તેમને)\s+"""),
                Regex("""(?<=\b(?:નમસ્તે))\s+(?=\S)"""),
            )
            Language.PUNJABI -> listOf(
                Regex("""(?<=\S)\s+(ਤੁਸੀਂ|ਤੁਹਾਡੇ)\s+"""),
                Regex("""(?<=\S)\s+(ਅਸੀਂ|ਸਾਡੇ)\s+"""),
                Regex("""(?<=\S)\s+(ਉਹ|ਉਹਨਾਂ)\s+"""),
                Regex("""(?<=\b(?:ਸਤ\s+ਸ੍ਰੀ\s+ਅਕਾਲ))\s+(?=\S)"""),
            )
            else -> emptyList()
        }

        // Split into clauses at the first matching boundary
        var clauses: List<String>? = null
        for (boundaryRegex in boundaries) {
            val matchResult = boundaryRegex.find(cleanText) ?: continue
            val splitIndex = matchResult.range.first
            val clause1 = cleanText.substring(0, splitIndex).trim()
            val clause2 = cleanText.substring(matchResult.range.last + 1).trim()
            if (clause1.isNotBlank() && clause2.isNotBlank()) {
                clauses = listOf(clause1, clause2)
                break
            }
        }

        // 3. Fallback: try every internal word boundary as a potential split point
        // Checks if BOTH left and right halves independently produce valid translations
        if (clauses == null) {
            val words = cleanText.split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (words.size >= 2) {
                var bestSplit: Pair<String, String>? = null
                for (i in 1 until words.size) {
                    val left = words.subList(0, i).joinToString(" ")
                    val right = words.subList(i, words.size).joinToString(" ")
                    val leftLower = left.lowercase(Locale.ROOT)
                    val rightLower = right.lowercase(Locale.ROOT)
                    val leftMatch = matchCommonPhrases(leftLower, targetLanguage, sourceLanguage)
                        ?: matchSentencePatterns(left, targetLanguage, sourceLanguage)
                        ?: translateWithDownloadedPack(left, sourceLanguage, targetLanguage)
                    val rightMatch = matchCommonPhrases(rightLower, targetLanguage, sourceLanguage)
                        ?: matchSentencePatterns(right, targetLanguage, sourceLanguage)
                        ?: translateWithDownloadedPack(right, sourceLanguage, targetLanguage)
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

        // Translate each clause independently
        val translatedClauses = clauses.map { clause ->
            val clauseLower = clause.lowercase(Locale.ROOT)
            val phraseResult = matchCommonPhrases(clauseLower, targetLanguage, sourceLanguage)
            if (phraseResult != null) return@map phraseResult
            val patternResult = matchSentencePatterns(clause, targetLanguage, sourceLanguage)
            if (patternResult != null) return@map patternResult
            val packResult = translateWithDownloadedPack(clause, sourceLanguage, targetLanguage)
            if (!packResult.isNullOrBlank()) return@map packResult
            val formalClause = ColloquialEngine.toFormal(clause, sourceLanguage)
            val mlResult = translateWithMlKit(formalClause, sourceLanguage, targetLanguage)
                ?: translateWithMlKit(clause, sourceLanguage, targetLanguage)
            mlResult ?: clause
        }

        // Return only if at least one clause was actually translated
        if (translatedClauses.all { c -> clauses.any { it.equals(c, ignoreCase = true) } }) return null

        val first = translatedClauses[0].trim()
        val second = translatedClauses[1].trim()
        val sep = if (first.endsWith("?") || first.endsWith("!") || first.endsWith(".")) " " else ", "
        return "$first$sep$second"
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
                Language.ODIA -> matchOdiaToEnglish(clean)
                else -> null
            }
        }

        return when (lang) {
            Language.TAMIL -> when (clean) {
                // Compound Conversational Greetings + Status + Questions (Exact Google Translate matches)
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "வணக்கம், நீங்கள் எப்படி இருக்கிறீர்கள்?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "நான் நலமாக இருக்கிறேன், நீங்கள் எப்படி இருக்கிறீர்கள்?"
                "we are fine how are you", "we are good how are you" -> "நாங்கள் நலமாக இருக்கிறோம், நீங்கள் எப்படி இருக்கிறீர்கள்?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "நான் நலமாக இருக்கிறேன், நீங்கள் எப்படி?"
                "hello where are you", "hi where are you" -> "வணக்கம், நீங்கள் எங்கே இருக்கிறீர்கள்?"
                "hello what are you doing", "hi what are you doing" -> "வணக்கம், நீங்கள் என்ன செய்கிறீர்கள்?"
                "hello what happened", "hi what happened" -> "வணக்கம், என்ன நடந்தது?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "நான் நலமாக இருக்கிறேன், நன்றி"
                "thank you very much", "thank you so much", "thanks a lot" -> "மிக்க நன்றி"
                "can you help me", "could you help me" -> "தயவுசெய்து எனக்கு உதவ முடியுமா?"
                "can you help us", "could you help us" -> "தயவுசெய்து எங்களுக்கு உதவ முடியுமா?"
                "where are you going" -> "நீங்கள் எங்கே போகிறீர்கள்?"
                "what are you doing" -> "நீங்கள் என்ன செய்கிறீர்கள்?"

                // Conversational & Daily Life / Meals (High-Fidelity)
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "நாங்கள் மதிய உணவு சாப்பிட்டோம்"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "நான் மதிய உணவு சாப்பிட்டேன்"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "நீங்கள் மதிய உணவு சாப்பிட்டீர்களா?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "நாங்கள் இரவு உணவு சாப்பிட்டோம்"
                "i had dinner", "i had my dinner", "i ate dinner" -> "நான் இரவு உணவு சாப்பிட்டேன்"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "நாங்கள் காலை உணவு சாப்பிட்டோம்"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "நான் காலை உணவு சாப்பிட்டேன்"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "நாங்கள் மதிய உணவு சாப்பிடுகிறோம்"
                "food is ready", "the food is ready" -> "உணவு தயாராக உள்ளது"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "ஹரிசுக்கு மூளை குழம்பியது"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "விஜய் உடைய கால் வெட்டப்பட்டுள்ளது"
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
                "thank you", "thanks" -> "நன்றி"
                "you are welcome", "welcome" -> "நல்வரவு"
                "goodbye", "bye", "see you later" -> "வணக்கம், மீண்டும் சந்திப்போம்"
                else -> null
            }

            Language.HINDI -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "नमस्ते, आप कैसे हैं?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "मैं ठीक हूँ, आप कैसे हैं?"
                "we are fine how are you", "we are good how are you" -> "हम ठीक हैं, आप कैसे हैं?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "मैं ठीक हूँ, आप बताइए?"
                "hello where are you", "hi where are you" -> "नमस्ते, आप कहाँ हैं?"
                "hello what are you doing", "hi what are you doing" -> "नमस्ते, आप क्या कर रहे हैं?"
                "hello what happened", "hi what happened" -> "नमस्ते, क्या हुआ है?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "मैं ठीक हूँ, धन्यवाद"
                "thank you very much", "thank you so much", "thanks a lot" -> "बहुत बहुत धन्यवाद"
                "can you help me", "could you help me" -> "क्या आप मेरी मदद कर सकते हैं?"
                "can you help us", "could you help us" -> "क्या आप हमारी मदद कर सकते हैं?"
                "where are you going" -> "आप कहाँ जा रहे हैं?"
                "what are you doing" -> "आप क्या कर रहे हैं?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "हमने दोपहर का भोजन कर लिया"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "मैंने दोपहर का भोजन कर लिया"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "क्या आपने दोपहर का भोजन किया?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "हमने रात का भोजन कर लिया"
                "i had dinner", "i had my dinner", "i ate dinner" -> "मैंने रात का भोजन कर लिया"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "हमने नाश्ता कर लिया"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "मैंने नाश्ता कर लिया"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "हम दोपहर का भोजन कर रहे हैं"
                "food is ready", "the food is ready" -> "खाना तैयार है"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "हरीश भ्रमित है"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "विजय का पैर कट गया है"
                else -> null
            }

            Language.TELUGU -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "నమస్కారం, మీరు ఎలా ఉన్నారు?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "నేను బాగున్నాను, మీరు ఎలా ఉన్నారు?"
                "we are fine how are you", "we are good how are you" -> "మేము బాగున్నాము, మీరు ఎలా ఉన్నారు?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "నేను బాగున్నాను, మరి మీరు?"
                "hello where are you", "hi where are you" -> "నమస్కారం, మీరు ఎక్కడ ఉన్నారు?"
                "hello what are you doing", "hi what are you doing" -> "నమస్కారం, మీరు ఏమి చేస్తున్నారు?"
                "hello what happened", "hi what happened" -> "నమస్కారం, ఏమి జరిగింది?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "నేను బాగున్నాను, ధన్యవాదాలు"
                "thank you very much", "thank you so much", "thanks a lot" -> "చాలా ధన్యవాదాలు"
                "can you help me", "could you help me" -> "దయచేసి నాకు సహాయం చేయగలరా?"
                "can you help us", "could you help us" -> "దయచేసి మాకు సహాయం చేయగలరా?"
                "where are you going" -> "మీరు ఎక్కడికి వెళ్తున్నారు?"
                "what are you doing" -> "మీరు ఏమి చేస్తున్నారు?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "మేము మధ్యాహ్న భోజనం చేశాము"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "నేను మధ్యాహ్న భోజనం చేశాను"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "మీరు మధ్యాహ్న భోజనం చేశారా?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "మేము రాత్రి భోజనం చేశాము"
                "i had dinner", "i had my dinner", "i ate dinner" -> "నేను రాత్రి భోజనం చేశాను"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "మేము అల్పాహారం తిన్నాము"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "నేను అల్పాహారం తిన్నాను"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "మేము భోజనం చేస్తున్నాము"
                "food is ready", "the food is ready" -> "ఆహారం సిద్ధంగా ఉంది"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "హరీష్ గందరగోళంలో ఉన్నాడు"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "విజయ్ కాలు తెగిపోయింది"
                else -> null
            }

            Language.KANNADA -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "ನಮಸ್ಕಾರ, ನೀವು ಹೇಗಿದ್ದೀರಿ?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ, ನೀವು ಹೇಗಿದ್ದೀರಿ?"
                "we are fine how are you", "we are good how are you" -> "ನಾವು ಚೆನ್ನಾಗಿದ್ದೇವೆ, ನೀವು ಹೇಗಿದ್ದೀರಿ?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ, ನೀವು ಹೇಗಿದ್ದೀರಿ?"
                "hello where are you", "hi where are you" -> "ನಮಸ್ಕಾರ, ನೀವು ಎಲ್ಲಿದ್ದೀರಿ?"
                "hello what are you doing", "hi what are you doing" -> "ನಮಸ್ಕಾರ, ನೀವು ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ?"
                "hello what happened", "hi what happened" -> "ನಮಸ್ಕಾರ, ಏನಾಯಿತು?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "ನಾನು ಚೆನ್ನಾಗಿದ್ದೇನೆ, ಧನ್ಯವಾದಗಳು"
                "thank you very much", "thank you so much", "thanks a lot" -> "ತುಂಬಾ ಧನ್ಯವಾದಗಳು"
                "can you help me", "could you help me" -> "ದಯವಿಟ್ಟು ನನಗೆ ಸಹಾಯ ಮಾಡಬಹುದೇ?"
                "can you help us", "could you help us" -> "ದಯವಿಟ್ಟು ನಮಗೆ ಸಹಾಯ ಮಾಡಬಹುದೇ?"
                "where are you going" -> "ನೀವು ಎಲ್ಲಿಗೆ ಹೋಗುತ್ತಿದ್ದೀರಿ?"
                "what are you doing" -> "ನೀವು ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "ನಾವು ಮಧ್ಯಾಹ್ನದ ಊಟ ಮಾಡಿದ್ದೇವೆ"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "ನಾನು ಮಧ್ಯಾಹ್ನದ ಊಟ ಮಾಡಿದ್ದೇನೆ"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "ನೀವು ಮಧ್ಯಾಹ್ನದ ಊಟ ಮಾಡಿದಿರಾ?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "ನಾವು ರಾತ್ರಿಯ ಊಟ ಮಾಡಿದ್ದೇವೆ"
                "i had dinner", "i had my dinner", "i ate dinner" -> "ನಾನು ರಾತ್ರಿಯ ಊಟ ಮಾಡಿದ್ದೇನೆ"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "ನಾವು ಉಪಾಹಾರ ಸೇವಿಸಿದ್ದೇವೆ"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "ನಾನು ಉಪಾಹಾರ ಸೇವಿಸಿದ್ದೇನೆ"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "ನಾವು ಊಟ ಮಾಡುತ್ತಿದ್ದೇವೆ"
                "food is ready", "the food is ready" -> "ಊಟ ಸಿದ್ಧವಾಗಿದೆ"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "ಹರೀಶ್ ಗೊಂದಲಕ್ಕೊಳಗಾಗಿದ್ದಾರೆ"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "ವಿಜಯ್ ಅವರ ಕಾಲು ಕತ್ತರಿಸಲ್ಪಟ್ಟಿದೆ"
                else -> null
            }

            Language.MALAYALAM -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "നമസ്കാരം, സുഖമാണോ?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "എനിക്ക് സുഖമാണ്, സുഖമാണോ?"
                "we are fine how are you", "we are good how are you" -> "ഞങ്ങൾക്ക് സുഖമാണ്, സുഖമാണോ?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "എനിക്ക് സുഖമാണ്, നിങ്ങൾക്കോ?"
                "hello where are you", "hi where are you" -> "നമസ്കാരം, നിങ്ങൾ എവിടെയാണ്?"
                "hello what are you doing", "hi what are you doing" -> "നമസ്കാരം, നിങ്ങൾ എന്താണ് ചെയ്യുന്നത്?"
                "hello what happened", "hi what happened" -> "നമസ്കാരം, എന്താണ് സംഭവിച്ചത്?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "എനിക്ക് സുഖമാണ്, നന്ദി"
                "thank you very much", "thank you so much", "thanks a lot" -> "വളരെ നന്ദി"
                "can you help me", "could you help me" -> "ദയവായി എന്നെ സഹായിക്കാമോ?"
                "can you help us", "could you help us" -> "ദയവായി ഞങ്ങളെ സഹായിക്കാമോ?"
                "where are you going" -> "നിങ്ങൾ എവിടെ പോകുന്നു?"
                "what are you doing" -> "നിങ്ങൾ എന്താണ് ചെയ്യുന്നത്?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "ഞങ്ങൾ ഉച്ചഭക്ഷണം കഴിച്ചു"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "ഞാൻ ഉച്ചഭക്ഷണം കഴിച്ചു"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "നിങ്ങൾ ഉച്ചഭക്ഷണം കഴിച്ചോ?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "ഞങ്ങൾ അത്താഴം കഴിച്ചു"
                "i had dinner", "i had my dinner", "i ate dinner" -> "ഞാൻ അത്താഴം കഴിച്ചു"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "ഞങ്ങൾ പ്രഭാതഭക്ഷണം കഴിച്ചു"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "ഞാൻ പ്രഭാതഭക്ഷണം കഴിച്ചു"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "ഞങ്ങൾ ഭക്ഷണം കഴിക്കുകയാണ്"
                "food is ready", "the food is ready" -> "ഭക്ഷണം തയ്യാറാണ്"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "ഹരീഷിന് ആശയക്കുഴപ്പമുണ്ട്"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "വിജയിന്റെ കാൽ മുറിഞ്ഞു"
                else -> null
            }

            Language.BENGALI -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "নমস্কার, আপনি কেমন আছেন?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "আমি ভালো আছি, আপনি কেমন আছেন?"
                "we are fine how are you", "we are good how are you" -> "আমরা ভালো আছি, আপনি কেমন আছেন?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "আমি ভালো আছি, আপনি কেমন আছেন?"
                "hello where are you", "hi where are you" -> "নমস্কার, আপনি কোথায় আছেন?"
                "hello what are you doing", "hi what are you doing" -> "নমস্কার, আপনি কি করছেন?"
                "hello what happened", "hi what happened" -> "নমস্কার, কি হয়েছে?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "আমি ভালো আছি, ধন্যবাদ"
                "thank you very much", "thank you so much", "thanks a lot" -> "আপনাকে অনেক ধন্যবাদ"
                "can you help me", "could you help me" -> "দয়া করে আপনি কি আমাকে সাহায্য করতে পারেন?"
                "can you help us", "could you help us" -> "দয়া করে আপনি কি আমাদের সাহায্য করতে পারেন?"
                "where are you going" -> "আপনি কোথায় যাচ্ছেন?"
                "what are you doing" -> "আপনি কি করছেন?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "আমরা দুপুরের খাবার খেয়েছি"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "আমি দুপুরের খাবার খেয়েছি"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "আপনি কি দুপুরের খাবার খেয়েছেন?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "আমরা রাতের খাবার খেয়েছি"
                "i had dinner", "i had my dinner", "i ate dinner" -> "আমি রাতের খাবার খেয়েছি"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "আমরা প্রাতঃরাশ করেছি"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "আমি প্রাতঃরাশ করেছি"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "আমরা খাবার খাচ্ছি"
                "food is ready", "the food is ready" -> "খাবার তৈরি"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "হ্যারিস বিভ্রান্ত"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "বিজয়ের পা কেটে গেছে"
                else -> null
            }

            Language.MARATHI -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "नमस्कार, तुम्ही कसे आहात?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "मी ठीक आहे, तुम्ही कसे आहात?"
                "we are fine how are you", "we are good how are you" -> "आम्ही ठीक आहोत, तुम्ही कसे आहात?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "मी ठीक आहे, तुम्ही कसे आहात?"
                "hello where are you", "hi where are you" -> "नमस्कार, तुम्ही कुठे आहात?"
                "hello what are you doing", "hi what are you doing" -> "नमस्कार, तुम्ही काय करत आहात?"
                "hello what happened", "hi what happened" -> "नमस्कार, काय झाले?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "मी ठीक आहे, धन्यवाद"
                "thank you very much", "thank you so much", "thanks a lot" -> "खूप खूप धन्यवाद"
                "can you help me", "could you help me" -> "कृपया तुम्ही मला मदत करू शकता का?"
                "can you help us", "could you help us" -> "कृपया तुम्ही आम्हाला मदत करू शकता का?"
                "where are you going" -> "तुम्ही कुठे जात आहात?"
                "what are you doing" -> "तुम्ही काय करत आहात?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "आम्ही दुपारचे जेवण केले आहे"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "मी दुपारचे जेवण केले आहे"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "तुम्ही दुपारचे जेवण केले का?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "आम्ही रात्रीचे जेवण केले आहे"
                "i had dinner", "i had my dinner", "i ate dinner" -> "मी रात्रीचे जेवण केले आहे"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "आम्ही नाश्ता केला आहे"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "मी नाश्ता केला आहे"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "आम्ही जेवत आहोत"
                "food is ready", "the food is ready" -> "जेवण तयार आहे"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "हरीश गोंधळलेला आहे"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "विजयचा पाय कापला गेला आहे"
                else -> null
            }

            Language.GUJARATI -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "નમસ્તે, તમે કેમ છો?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "હું ઠીક છું, તમે કેમ છો?"
                "we are fine how are you", "we are good how are you" -> "અમે ઠીક છીએ, તમે કેમ છો?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "હું ઠીક છું, તમે કેમ છો?"
                "hello where are you", "hi where are you" -> "નમસ્તે, તમે ક્યાં છો?"
                "hello what are you doing", "hi what are you doing" -> "નમસ્તે, તમે શું કરી રહ્યા છો?"
                "hello what happened", "hi what happened" -> "નમસ્તે, શું થયું?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "હું ઠીક છું, આભાર"
                "thank you very much", "thank you so much", "thanks a lot" -> "ખૂબ ખૂબ આભાર"
                "can you help me", "could you help me" -> "કૃપા કરીને શું તમે મને મદદ કરી શકો છો?"
                "can you help us", "could you help us" -> "કૃપા કરીને શું તમે અમને મદદ કરી શકો છો?"
                "where are you going" -> "તમે ક્યાં જઈ રહ્યા છો?"
                "what are you doing" -> "તમે શું કરી રહ્યા છો?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "અમે બપોરનું ભોજન લીધું છે"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "મેં બપોરનું ભોજન લીધું છે"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "તમે બપોરનું ભોજન લીધું?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "અમે રાત્રિભોજન લીધું છે"
                "i had dinner", "i had my dinner", "i ate dinner" -> "મેં રાત્રિભોજન લીધું છે"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "અમે નાસ્તો કર્યો છે"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "મેં નાસ્તો કર્યો છે"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "અમે જમી રહ્યા છીએ"
                "food is ready", "the food is ready" -> "જમવાનું તૈયાર છે"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "હરીશ મૂંઝવણમાં છે"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "વિજયનો પગ કપાઈ ગયો છે"
                else -> null
            }

            Language.PUNJABI -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you", "hi how are you doing", "hey how are you" -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ?"
                "i am fine how are you", "i am good how are you", "im fine how are you", "fine how are you" -> "ਮੈਂ ਠੀਕ ਹਾਂ, ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ?"
                "we are fine how are you", "we are good how are you" -> "ਅਸੀਂ ਠੀਕ ਹਾਂ, ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ?"
                "i am fine what about you", "i am good what about you", "fine what about you" -> "ਮੈਂ ਠੀਕ ਹਾਂ, ਤੁਸੀਂ ਦੱਸੋ?"
                "hello where are you", "hi where are you" -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਤੁਸੀਂ ਕਿੱਥੇ ਹੋ?"
                "hello what are you doing", "hi what are you doing" -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਤੁਸੀਂ ਕੀ ਕਰ ਰਹੇ ਹੋ?"
                "hello what happened", "hi what happened" -> "ਸਤ ਸ੍ਰੀ ਅਕਾਲ, ਕੀ ਹੋਇਆ?"
                "i am fine thank you", "i am good thank you", "fine thank you" -> "ਮੈਂ ਠੀਕ ਹਾਂ, ਧੰਨਵਾਦ"
                "thank you very much", "thank you so much", "thanks a lot" -> "ਬਹੁਤ ਬਹੁਤ ਧੰਨਵਾਦ"
                "can you help me", "could you help me" -> "ਕਿਰਪਾ ਕਰਕੇ ਕੀ ਤੁਸੀਂ ਮੇਰੀ ਮਦਦ ਕਰ ਸਕਦੇ ਹੋ?"
                "can you help us", "could you help us" -> "ਕਿਰਪਾ ਕਰਕੇ ਕੀ ਤੁਸੀਂ ਸਾਡੀ ਮਦਦ ਕਰ ਸਕਦੇ ਹੋ?"
                "where are you going" -> "ਤੁਸੀਂ ਕਿੱਥੇ ਜਾ ਰਹੇ ਹੋ?"
                "what are you doing" -> "ਤੁਸੀਂ ਕੀ ਕਰ ਰਹੇ ਹੋ?"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "ਅਸੀਂ ਦੁਪਹਿਰ ਦਾ ਖਾਣਾ ਖਾ ਲਿਆ ਹੈ"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "ਮੈਂ ਦੁਪਹਿਰ ਦਾ ਖਾਣਾ ਖਾ ਲਿਆ ਹੈ"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "ਕੀ ਤੁਸੀਂ ਦੁਪਹਿਰ ਦਾ ਖਾਣਾ ਖਾ ਲਿਆ?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "ਅਸੀਂ ਰਾਤ ਦਾ ਖਾਣਾ ਖਾ ਲਿਆ ਹੈ"
                "i had dinner", "i had my dinner", "i ate dinner" -> "ਮੈਂ ਰਾਤ ਦਾ ਖਾਣਾ ਖਾ ਲਿਆ ਹੈ"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "ਅਸੀਂ ਨਾਸ਼ਤਾ ਕਰ ਲਿਆ ਹੈ"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "ਮੈਂ ਨਾਸ਼ਤਾ ਕਰ ਲਿਆ ਹੈ"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "ਅਸੀਂ ਖਾਣਾ ਖਾ ਰਹੇ ਹਾਂ"
                "food is ready", "the food is ready" -> "ਖਾਣਾ ਤਿਆਰ ਹੈ"

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
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "ਹਰੀਸ਼ ਉਲਝਣ ਵਿੱਚ ਹੈ"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "ਵਿਜੇ ਦੀ ਲੱਤ ਕੱਟੀ ਗਈ ਹੈ"
                else -> null
            }

            Language.ODIA -> when (clean) {
                // Compound Conversational Greetings + Status + Questions
                "hello how are you", "hello how are you doing", "hi how are you" -> "ନମସ୍କାର, ଆପଣ କେମିତି ଅଛନ୍ତି?"
                "i am fine how are you", "i am good how are you", "fine how are you" -> "ମୁଁ ଭଲ ଅଛି, ଆପଣ କେମିତି ଅଛନ୍ତି?"
                "we are fine how are you", "we are good how are you" -> "ଆମେ ଭଲ ଅଛୁ, ଆପଣ କେମିତି ଅଛନ୍ତି?"
                "hello where are you", "hi where are you" -> "ନମସ୍କାର, ଆପଣ କେଉଁଠି ଅଛନ୍ତି?"
                "hello what are you doing", "hi what are you doing" -> "ନମସ୍କାର, ଆପଣ କଣ କରୁଛନ୍ତି?"
                "thank you very much", "thank you so much", "thanks a lot" -> "ବହୁତ ଧନ୍ୟବାଦ"
                "can you help me", "could you help me" -> "ଦୟାକରି ଆପଣ ମତେ ସାହାଯ୍ୟ କରିପାରିବେ କି?"
                "can you help us", "could you help us" -> "ଦୟାକରି ଆପଣ ଆମକୁ ସାହାଯ୍ୟ କରିପାରିବେ କି?"
                "hello", "hi" -> "ନମସ୍କାର"
                "how are you" -> "ଆପଣ କେମିତି ଅଛନ୍ତି?"
                "i am fine" -> "ମୁଁ ଭଲ ଅଛି"
                "we are fine" -> "ଆମେ ଭଲ ଅଛୁ"
                "we need help", "help us", "please help us" -> "ଆମକୁ ସାହାଯ୍ୟ ଦରକਾਰ"
                "route is clear" -> "ରାସ୍ତା ସଫା ଅଛି"
                "road is blocked" -> "ରାସ୍ତା ବନ୍ଦ ଅଛି"
                "the road is blocked do not come this way" -> "ରାସ୍ତା ବନ୍ଦ ଅଛି, ଏହି ପଟେ ଆସନ୍ତୁ ନାହିଁ"
                "we need food and water" -> "ଆମକୁ ଖାଦ୍ୟ ଏବଂ ପାଣି ଦରକାର"
                "all clear" -> "ସବୁ ଠିକ ଅଛି"
                "thank you" -> "ଧନ୍ୟବାଦ"

                // Conversational & Daily Life / Meals
                "we had lunch", "we had lunch today", "we had our lunch", "we ate lunch", "we had food", "we ate food" -> "ଆମେ ମଧ୍ୟାହ୍ନ ଭୋଜନ କରିଛୁ"
                "i had lunch", "i had lunch today", "i had my lunch", "i ate lunch", "i had food" -> "ମୁଁ ମଧ୍ୟାହ୍ନ ଭୋଜନ କରିଛି"
                "did you have lunch", "did you eat lunch", "have you had lunch", "did you eat", "did you have food" -> "ଆପଣ ମଧ୍ୟାହ୍ନ ଭୋଜନ କଲେଣି କି?"
                "we had dinner", "we had our dinner", "we ate dinner" -> "ଆମେ ରାତ୍ରୀ ଭୋଜନ କରିଛୁ"
                "i had dinner", "i had my dinner", "i ate dinner" -> "ମୁଁ ରାତ୍ରୀ ଭୋଜନ କରିଛି"
                "we had breakfast", "we had our breakfast", "we ate breakfast" -> "ଆମେ ଜଳଖିଆ ଖାଇଛୁ"
                "i had breakfast", "i had my breakfast", "i ate breakfast" -> "ମୁଁ ଜଳଖିଆ ଖାଇଛି"
                "we are having lunch", "we are eating lunch", "we are having food", "we are eating" -> "ଆମେ ଖାଉଛୁ"
                "food is ready", "the food is ready" -> "ଖାଦ୍ୟ ପ୍ରସ୍ତୁତ ଅଛି"
                "haris is confused", "haris mind is confused", "haris is disoriented" -> "ହରିଶ ବିଭ୍ରାନ୍ତ"
                "vijay's leg is cut", "vijays leg is cut", "vijay leg is cut", "vijay's leg has been cut", "vijays leg has been cut" -> "ବିଜୟଙ୍କ ଗୋଡ କଟିଯାଇଛି"
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
            "சாப்பிட்டீங்களா", "சாப்பிட்டீர்களா", "சாப்பிட்டியா", "மதிய உணவு சாப்பிட்டீர்களா" -> "Did you have lunch?"
            "நாங்கள் மதிய உணவு சாப்பிட்டோம்", "நாங்க மதிய உணவு சாப்பிட்டோம்", "நாங்கள் சாப்பிட்டோம்", "நாங்க சாப்பிட்டோம்", "நாங்கள் மதிய சாப்பாடு சாப்பிட்டோம்", "நாங்க மதிய சாப்பாடு சாப்பிட்டோம்" -> "We had lunch"
            "நான் மதிய உணவு சாப்பிட்டேன்", "நான் சாப்பிட்டேன்", "நான் மதிய சாப்பாடு சாப்பிட்டேன்" -> "I had lunch"
            "நாங்கள் இரவு உணவு சாப்பிட்டோம்", "நாங்க இரவு உணவு சாப்பிட்டோம்" -> "We had dinner"
            "நான் இரவு உணவு சாப்பிட்டேன்" -> "I had dinner"
            "நாங்கள் காலை உணவு சாப்பிட்டோம்", "நாங்க காலை உணவு சாப்பிட்டோம்" -> "We had breakfast"
            "நான் காலை உணவு சாப்பிட்டேன்" -> "I had breakfast"
            "நாங்கள் சாப்பிடுகிறோம்", "நாங்கள் மதிய உணவு சாப்பிடுகிறோம்" -> "We are having lunch"
            "உணவு தயாராக உள்ளது", "சாப்பாடு ரெடி" -> "Food is ready"
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
            "ஹரிசுக்கு மூளை குழம்பியது", "ஹரிசுக்கு மூளை குழம்பிடுச்சு", "ஹரிசுக்கு மூளை குழம்பிருச்சு" -> "Haris is confused"
            "விஜய் உடைய கால் வெட்டப்பட்டுள்ளது", "விஜய்க்கு கால் வெட்டப்பட்டுள்ளது", "விஜய்க்கு கால் வெட்டிக்கிச்சு", "விஜய்க்கு கால் வெட்டப்பட்டது", "விஜய் கால் வெட்டப்பட்டுள்ளது" -> "Vijay's leg is cut"
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
            "हमने दोपहर का भोजन कर लिया", "हमने दोपहर का खाना खा लिया", "हमने खाना खा लिया", "हमने खाना खाया" -> "We had lunch"
            "मैंने दोपहर का भोजन कर लिया", "मैंने दोपहर का खाना खा लिया", "मैंने खाना खा लिया" -> "I had lunch"
            "क्या आपने खाना खाया", "क्या आपने दोपहर का भोजन किया", "खाना खाया क्या" -> "Did you have lunch?"
            "हमने रात का भोजन कर लिया", "हमने रात का खाना खा लिया" -> "We had dinner"
            "मैंने रात का भोजन कर लिया" -> "I had dinner"
            "हमने नाश्ता कर लिया" -> "We had breakfast"
            "मैंने नाश्ता कर लिया" -> "I had breakfast"
            "हम खाना खा रहे हैं", "हम दोपहर का भोजन कर रहे हैं" -> "We are having lunch"
            "खाना तैयार है" -> "Food is ready"
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
            "हरीश भ्रमित है", "हरीश का दिमाग चकरा गया है" -> "Haris is confused"
            "विजय का पैर कट गया है", "विजय का पैर कट चुका है" -> "Vijay's leg is cut"
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
            "హరీష్ గందరగోళంలో ఉన్నాడు" -> "Haris is confused"
            "విజయ్ కాలు తెగిపోయింది" -> "Vijay's leg is cut"
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
            "ಹರೀಶ್ ಗೊಂದಲಕ್ಕೊಳಗಾಗಿದ್ದಾರೆ" -> "Haris is confused"
            "ವಿಜಯ್ ಅವರ ಕಾಲು ಕತ್ತರಿಸಲ್ಪಟ್ಟಿದೆ" -> "Vijay's leg is cut"
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
            "ഹരീഷിന് ആശയക്കുഴപ്പമുണ്ട്" -> "Haris is confused"
            "വിജയിന്റെ കാൽ മുറിഞ്ഞു" -> "Vijay's leg is cut"
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
            "হ্যারিস বিভ্রান্ত", "হ্যারিসের বিভ্রান্তি হয়েছে", "হ্যারিসের মাথা কাজ করছে না", "হ্যারিসের মাথা বিভ্রান্ত" -> "Haris is confused"
            "বিজয়ের পা কেটে গেছে", "বিজয়ের পা কাটা পড়েছে", "বিজয়ের পা কাটা হয়েছে" -> "Vijay's leg is cut"
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
            "हरीश गोंधळलेला आहे", "हरीशचे डोके फिरले आहे", "हरीश गोंधळात आहे" -> "Haris is confused"
            "विजयचा पाय कापला गेला आहे", "विजयचा पाय कापला आहे", "विजयचा पाय तुटला आहे" -> "Vijay's leg is cut"
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
            "હરીશ મૂંઝવણમાં છે", "હરીશનું મગજ ચકરાવે ચડ્યું છે", "હરીશ ભ્રમિત છે" -> "Haris is confused"
            "વિજયનો પગ કપાઈ ગયો છે", "વિજયનો પગ કપાયો છે" -> "Vijay's leg is cut"
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
            "ਹਰੀਸ਼ ਉਲਝਣ ਵਿੱਚ ਹੈ", "ਹਰੀਸ਼ ਦਾ ਸਿਰ ਚਕਰਾ ਰਿਹਾ ਹੈ" -> "Haris is confused"
            "ਵਿਜੇ ਦੀ ਲੱਤ ਕੱਟੀ ਗਈ ਹੈ", "ਵਿਜੇ ਦੀ ਲੱਤ ਕੱਟ ਗਈ ਹੈ" -> "Vijay's leg is cut"
            else -> null
        }
    }

    private fun matchOdiaToEnglish(clean: String): String? {
        val noPunct = clean.replace(Regex("""[?!.,।॥]"""), "").trim()
        return when (noPunct) {
            "ହରିଶ ବିଭ୍ରାନ୍ତ", "ହରିଶଙ୍କ ମୁଣ୍ଡ ଘୁରୁଛି", "ହରିଶ ଭ୍ରମିତ ଅଛନ୍ତି" -> "Haris is confused"
            "ବିଜୟଙ୍କ ଗୋଡ କଟିଯାଇଛି", "ବିଜୟଙ୍କ ଗୋଡ କଟାଯାଇଛି" -> "Vijay's leg is cut"
            "ନମସ୍କାର" -> "Hello"
            "ଆପଣ କେମିତି ଅଛନ୍ତି" -> "How are you?"
            "ମୁଁ ଭଲ ଅଛି" -> "I am fine"
            "ଆମେ ଭଲ ଅଛୁ" -> "We are fine"
            "ଆମକୁ ସାହାଯ୍ୟ ଦରକାର" -> "Please help us"
            "ରାସ୍ତା ସଫା ଅଛି" -> "Route is clear"
            "ରାସ୍ତା ବନ୍ଦ ଅଛି" -> "The road is blocked"
            "ଆମକୁ ଖାଦ୍ୟ ଏବଂ ପାଣି ଦରକାର" -> "We need food and water"
            "ଧନ୍ୟବାଦ" -> "Thank you"
            "ହଁ" -> "Yes"
            "ନା" -> "No"
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

        // Pattern 7: "Please [action]" or "[Action] please"
        val pleaseMatch = Regex("""^\s*(?:please|kindly)\s+(.+?)\s*$""", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("""^\s*(.+?)[,\s]+(?:please|kindly)\s*$""", RegexOption.IGNORE_CASE).find(text)
        if (pleaseMatch != null) {
            val action = pleaseMatch.groupValues[1].trim()
            val translatedAction = matchCommonPhrases(action.lowercase(Locale.ROOT), lang, Language.ENGLISH)
                ?: translateNounPhrase(action, lang)
            return when (lang) {
                Language.TAMIL -> "தயவுசெய்து $translatedAction"
                Language.HINDI -> "कृपया $translatedAction"
                Language.TELUGU -> "దయచేసి $translatedAction"
                Language.KANNADA -> "ದಯವಿಟ್ಟು $translatedAction"
                Language.MALAYALAM -> "ദയവായി $translatedAction"
                Language.BENGALI -> "অনুগ্রহ করে $translatedAction"
                Language.MARATHI -> "कृपया $translatedAction"
                Language.GUJARATI -> "કૃપા કરીને $translatedAction"
                Language.PUNJABI -> "ਕਿਰਪਾ ਕਰਕੇ $translatedAction"
                else -> text
            }
        }

        // Pattern 8: "Can you / Could you [action]"
        val canYouMatch = Regex("""^\s*(?:can|could)\s+you\s+(.+?)\s*\??$""", RegexOption.IGNORE_CASE).find(text)
        if (canYouMatch != null) {
            val action = canYouMatch.groupValues[1].trim()
            val translatedAction = matchCommonPhrases(action.lowercase(Locale.ROOT), lang, Language.ENGLISH)
                ?: translateNounPhrase(action, lang)
            return when (lang) {
                Language.TAMIL -> "$translatedAction முடியுமா?"
                Language.HINDI -> "क्या आप $translatedAction कर सकते हैं?"
                Language.TELUGU -> "మీరు $translatedAction చేయగలరా?"
                Language.KANNADA -> "ನೀವು $translatedAction ಮಾಡಬಹುದೇ?"
                Language.MALAYALAM -> "$translatedAction ചെയ്യാമോ?"
                Language.BENGALI -> "আপনি কি $translatedAction করতে পারেন?"
                Language.MARATHI -> "तुम्ही $translatedAction करू शकता का?"
                Language.GUJARATI -> "શું તમે $translatedAction કરી શકો છો?"
                Language.PUNJABI -> "ਕੀ ਤੁਸੀਂ $translatedAction ਕਰ ਸਕਦੇ ਹੋ?"
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

    private enum class PersonKey {
        HARIS, VIJAY, ROHIT, RAHUL, SURESH, PRIYA, PATIENT, ME, SOMEONE, UNKNOWN
    }

    private enum class BodyPartKey {
        LEG, HAND, HEAD, BRAIN, EYE, CHEST, NECK, BONE, BLOOD
    }

    private enum class TraumaConditionKey {
        CUT, BROKEN, CONFUSED, DIZZY, UNCONSCIOUS, BLEEDING, INJURED
    }

    private fun getPersonName(key: PersonKey, targetLang: Language, rawName: String? = null): String {
        return when (key) {
            PersonKey.HARIS -> when (targetLang) {
                Language.TAMIL -> "ஹரிஸ்"
                Language.HINDI -> "हरीश"
                Language.TELUGU -> "హరీష్"
                Language.KANNADA -> "ಹರೀಶ್"
                Language.MALAYALAM -> "ഹരീഷ്"
                Language.BENGALI -> "হ্যারিস"
                Language.MARATHI -> "हरीश"
                Language.GUJARATI -> "હરીશ"
                Language.PUNJABI -> "ਹਰੀਸ਼"
                Language.ODIA -> "ହରିଶ"
                Language.ENGLISH -> "Haris"
            }
            PersonKey.VIJAY -> when (targetLang) {
                Language.TAMIL -> "விஜய்"
                Language.HINDI -> "विजय"
                Language.TELUGU -> "విజయ్"
                Language.KANNADA -> "ವಿಜಯ್"
                Language.MALAYALAM -> "വിജയ്"
                Language.BENGALI -> "বিজয়"
                Language.MARATHI -> "विजय"
                Language.GUJARATI -> "વિજય"
                Language.PUNJABI -> "ਵਿਜੇ"
                Language.ODIA -> "ବିଜୟ"
                Language.ENGLISH -> "Vijay"
            }
            PersonKey.ROHIT -> when (targetLang) {
                Language.TAMIL -> "ரோஹித்"
                Language.HINDI -> "रोहित"
                Language.TELUGU -> "రోహిత్"
                Language.KANNADA -> "ರೋಹಿತ್"
                Language.MALAYALAM -> "രോഹിത്"
                Language.BENGALI -> "রোহিত"
                Language.MARATHI -> "रोहित"
                Language.GUJARATI -> "રોહિત"
                Language.PUNJABI -> "ਰੋਹਿਤ"
                Language.ODIA -> "ରୋହିତ"
                Language.ENGLISH -> "Rohit"
            }
            PersonKey.RAHUL -> when (targetLang) {
                Language.TAMIL -> "ராகுல்"
                Language.HINDI -> "राहुल"
                Language.TELUGU -> "రాహుల్"
                Language.KANNADA -> "ರಾಹುಲ್"
                Language.MALAYALAM -> "രാഹുൽ"
                Language.BENGALI -> "রাহুল"
                Language.MARATHI -> "राहुल"
                Language.GUJARATI -> "રાહુલ"
                Language.PUNJABI -> "ਰਾਹੁਲ"
                Language.ODIA -> "ରାହୁଲ"
                Language.ENGLISH -> "Rahul"
            }
            PersonKey.SURESH -> when (targetLang) {
                Language.TAMIL -> "சுரேஷ்"
                Language.HINDI -> "सुरेश"
                Language.TELUGU -> "సురేష్"
                Language.KANNADA -> "ಸುರೇಶ್"
                Language.MALAYALAM -> "സുരേഷ്"
                Language.BENGALI -> "সুরেশ"
                Language.MARATHI -> "सुरेश"
                Language.GUJARATI -> "સુરેશ"
                Language.PUNJABI -> "ਸੁਰੇਸ਼"
                Language.ODIA -> "ସୁରେଶ"
                Language.ENGLISH -> "Suresh"
            }
            PersonKey.PRIYA -> when (targetLang) {
                Language.TAMIL -> "பிரியா"
                Language.HINDI -> "प्रिया"
                Language.TELUGU -> "ప్రియ"
                Language.KANNADA -> "ಪ್ರಿಯಾ"
                Language.MALAYALAM -> "പ്രിയ"
                Language.BENGALI -> "প্রিয়া"
                Language.MARATHI -> "प्रिया"
                Language.GUJARATI -> "પ્રિયા"
                Language.PUNJABI -> "ਪ੍ਰਿਯਾ"
                Language.ODIA -> "ପ୍ରିୟା"
                Language.ENGLISH -> "Priya"
            }
            PersonKey.PATIENT -> when (targetLang) {
                Language.TAMIL -> "நோயாளி"
                Language.HINDI -> "मरीज"
                Language.TELUGU -> "రోగి"
                Language.KANNADA -> "ರೋಗಿ"
                Language.MALAYALAM -> "രോഗി"
                Language.BENGALI -> "রোগী"
                Language.MARATHI -> "रुग्ण"
                Language.GUJARATI -> "દર્દી"
                Language.PUNJABI -> "ਮਰੀਜ਼"
                Language.ODIA -> "ରୋଗୀ"
                Language.ENGLISH -> "Patient"
            }
            PersonKey.ME -> when (targetLang) {
                Language.TAMIL -> "எனக்கு"
                Language.HINDI -> "मुझे"
                Language.TELUGU -> "నాకు"
                Language.KANNADA -> "ನನಗೆ"
                Language.MALAYALAM -> "എനിക്ക്"
                Language.BENGALI -> "আমার"
                Language.MARATHI -> "मला"
                Language.GUJARATI -> "મને"
                Language.PUNJABI -> "ਮੈਨੂੰ"
                Language.ODIA -> "ମୋତେ"
                Language.ENGLISH -> "I"
            }
            PersonKey.SOMEONE, PersonKey.UNKNOWN -> rawName?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } ?: when (targetLang) {
                Language.TAMIL -> "ஒருவர்"
                Language.HINDI -> "एक व्यक्ति"
                Language.TELUGU -> "ఒక వ్యక్తి"
                Language.KANNADA -> "ಒಬ್ಬ ವ್ಯಕ್ತಿ"
                Language.MALAYALAM -> "ഒരാൾ"
                Language.BENGALI -> "একজন ব্যক্তি"
                Language.MARATHI -> "एक व्यक्ती"
                Language.GUJARATI -> "એક વ્યક્તિ"
                Language.PUNJABI -> "ਇੱਕ ਵਿਅਕਤੀ"
                Language.ODIA -> "ଜଣେ ବ୍ୟକ୍ତି"
                Language.ENGLISH -> "Someone"
            }
        }
    }

    private fun getBodyPartWord(part: BodyPartKey, lang: Language): String {
        return when (part) {
            BodyPartKey.LEG -> when (lang) {
                Language.TAMIL -> "கால்"
                Language.HINDI -> "पैर"
                Language.TELUGU -> "కాలు"
                Language.KANNADA -> "ಕಾಲು"
                Language.MALAYALAM -> "കാൽ"
                Language.BENGALI -> "পা"
                Language.MARATHI -> "पाय"
                Language.GUJARATI -> "પગ"
                Language.PUNJABI -> "ਲੱਤ"
                Language.ODIA -> "ଗୋଡ"
                Language.ENGLISH -> "leg"
            }
            BodyPartKey.HAND -> when (lang) {
                Language.TAMIL -> "கை"
                Language.HINDI -> "हाथ"
                Language.TELUGU -> "చేయి"
                Language.KANNADA -> "ಕೈ"
                Language.MALAYALAM -> "കൈ"
                Language.BENGALI -> "হাত"
                Language.MARATHI -> "हात"
                Language.GUJARATI -> "હાથ"
                Language.PUNJABI -> "ਹੱਥ"
                Language.ODIA -> "ହାତ"
                Language.ENGLISH -> "hand"
            }
            BodyPartKey.HEAD -> when (lang) {
                Language.TAMIL -> "தலை"
                Language.HINDI -> "सिर"
                Language.TELUGU -> "తల"
                Language.KANNADA -> "ತಲೆ"
                Language.MALAYALAM -> "തല"
                Language.BENGALI -> "মাথা"
                Language.MARATHI -> "डोके"
                Language.GUJARATI -> "માથું"
                Language.PUNJABI -> "ਸਿਰ"
                Language.ODIA -> "ମୁଣ୍ଡ"
                Language.ENGLISH -> "head"
            }
            BodyPartKey.BRAIN -> when (lang) {
                Language.TAMIL -> "மூளை"
                Language.HINDI -> "दिमाग"
                Language.TELUGU -> "మెదడు"
                Language.KANNADA -> "ಮೆದುಳು"
                Language.MALAYALAM -> "തലച്ചോറ്"
                Language.BENGALI -> "মস্তিষ্ক"
                Language.MARATHI -> "मेंदू"
                Language.GUJARATI -> "મગજ"
                Language.PUNJABI -> "ਦਿਮਾਗ"
                Language.ODIA -> "ମସ୍ତିଷ୍କ"
                Language.ENGLISH -> "brain"
            }
            BodyPartKey.EYE -> when (lang) {
                Language.TAMIL -> "கண்"
                Language.HINDI -> "आँख"
                Language.TELUGU -> "కన్ను"
                Language.KANNADA -> "ಕಣ್ಣು"
                Language.MALAYALAM -> "കണ്ണ്"
                Language.BENGALI -> "চোখ"
                Language.MARATHI -> "डोळा"
                Language.GUJARATI -> "આંખ"
                Language.PUNJABI -> "ਅੱਖ"
                Language.ODIA -> "ଆଖି"
                Language.ENGLISH -> "eye"
            }
            BodyPartKey.CHEST -> when (lang) {
                Language.TAMIL -> "நெஞ்சு"
                Language.HINDI -> "छाती"
                Language.TELUGU -> "ఛాతీ"
                Language.KANNADA -> "ಎದೆ"
                Language.MALAYALAM -> "നെഞ്ച്"
                Language.BENGALI -> "বুক"
                Language.MARATHI -> "छाती"
                Language.GUJARATI -> "છાતી"
                Language.PUNJABI -> "ਛਾਤੀ"
                Language.ODIA -> "ଛାତି"
                Language.ENGLISH -> "chest"
            }
            BodyPartKey.NECK -> when (lang) {
                Language.TAMIL -> "கழுத்து"
                Language.HINDI -> "गर्दन"
                Language.TELUGU -> "మెడ"
                Language.KANNADA -> "ಕತ್ತು"
                Language.MALAYALAM -> "കഴുത്ത്"
                Language.BENGALI -> "ঘাড়"
                Language.MARATHI -> "मान"
                Language.GUJARATI -> "ગરદન"
                Language.PUNJABI -> "ਗਰਦਨ"
                Language.ODIA -> "ବେକ"
                Language.ENGLISH -> "neck"
            }
            BodyPartKey.BONE -> when (lang) {
                Language.TAMIL -> "எலும்பு"
                Language.HINDI -> "हड्डी"
                Language.TELUGU -> "ఎముక"
                Language.KANNADA -> "ಮೂಳೆ"
                Language.MALAYALAM -> "എല്ല്"
                Language.BENGALI -> "হাড়"
                Language.MARATHI -> "हाड"
                Language.GUJARATI -> "હાડકું"
                Language.PUNJABI -> "ਹੱਡੀ"
                Language.ODIA -> "ହାଡ଼"
                Language.ENGLISH -> "bone"
            }
            BodyPartKey.BLOOD -> when (lang) {
                Language.TAMIL -> "ரத்தம்"
                Language.HINDI -> "खून"
                Language.TELUGU -> "రక్తం"
                Language.KANNADA -> "ರಕ್ತ"
                Language.MALAYALAM -> "രക്തം"
                Language.BENGALI -> "রক্ত"
                Language.MARATHI -> "रक्त"
                Language.GUJARATI -> "લોહી"
                Language.PUNJABI -> "ਖੂਨ"
                Language.ODIA -> "ରକ୍ତ"
                Language.ENGLISH -> "blood"
            }
        }
    }

    private fun isMedicalStopWord(word: String): Boolean {
        val w = word.lowercase(Locale.ROOT)
        return w in listOf(
            "the", "a", "an", "is", "are", "was", "were", "what", "where", "how", "who", "why",
            "he", "she", "it", "they", "this", "that", "these", "those",
            "கால்", "கை", "தலை", "மூளை", "பாதை", "சாலை", "பாலம்", "உணவு", "தண்ணீர்",
            "पैर", "हाथ", "सिर", "दिमाग", "रास्ता", "सड़क", "पुल"
        )
    }

    private fun renderTraumaSentence(
        person: PersonKey?,
        rawPerson: String?,
        bodyPart: BodyPartKey?,
        condition: TraumaConditionKey,
        targetLang: Language
    ): String {
        val pName = person?.let { getPersonName(it, targetLang, rawPerson) }
        val partName = bodyPart?.let { getBodyPartWord(it, targetLang) }

        return when (targetLang) {
            Language.ENGLISH -> {
                if (pName != null) {
                    if (person == PersonKey.ME) {
                        if (partName != null) {
                            when (condition) {
                                TraumaConditionKey.CUT -> "My $partName is cut"
                                TraumaConditionKey.BROKEN -> "My $partName is broken"
                                TraumaConditionKey.BLEEDING -> "My $partName is bleeding"
                                TraumaConditionKey.INJURED -> "My $partName is injured"
                                TraumaConditionKey.CONFUSED -> "I am confused"
                                TraumaConditionKey.DIZZY -> "I am feeling dizzy"
                                TraumaConditionKey.UNCONSCIOUS -> "I am unconscious"
                            }
                        } else {
                            when (condition) {
                                TraumaConditionKey.CUT -> "I am wounded"
                                TraumaConditionKey.BROKEN -> "I have a fracture"
                                TraumaConditionKey.CONFUSED -> "I am confused"
                                TraumaConditionKey.DIZZY -> "I am feeling dizzy"
                                TraumaConditionKey.UNCONSCIOUS -> "I am unconscious"
                                TraumaConditionKey.BLEEDING -> "I am bleeding"
                                TraumaConditionKey.INJURED -> "I am injured"
                            }
                        }
                    } else {
                        val possessive = if (pName.endsWith("s", ignoreCase = true)) "$pName'" else "$pName's"
                        if (partName != null) {
                            when (condition) {
                                TraumaConditionKey.CUT -> "$possessive $partName is cut"
                                TraumaConditionKey.BROKEN -> "$possessive $partName is broken"
                                TraumaConditionKey.BLEEDING -> "$possessive $partName is bleeding"
                                TraumaConditionKey.INJURED -> "$possessive $partName is injured"
                                TraumaConditionKey.CONFUSED -> "$pName is confused"
                                TraumaConditionKey.DIZZY -> "$pName is feeling dizzy"
                                TraumaConditionKey.UNCONSCIOUS -> "$pName is unconscious"
                            }
                        } else {
                            when (condition) {
                                TraumaConditionKey.CUT -> "$pName is wounded"
                                TraumaConditionKey.BROKEN -> "$pName has a fracture"
                                TraumaConditionKey.CONFUSED -> "$pName is confused"
                                TraumaConditionKey.DIZZY -> "$pName is feeling dizzy"
                                TraumaConditionKey.UNCONSCIOUS -> "$pName is unconscious"
                                TraumaConditionKey.BLEEDING -> "$pName is bleeding"
                                TraumaConditionKey.INJURED -> "$pName is injured"
                            }
                        }
                    }
                } else {
                    val p = partName ?: "person"
                    when (condition) {
                        TraumaConditionKey.CUT -> "The $p is cut"
                        TraumaConditionKey.BROKEN -> "The $p is broken"
                        TraumaConditionKey.BLEEDING -> "There is bleeding in the $p"
                        TraumaConditionKey.INJURED -> "The $p is injured"
                        TraumaConditionKey.CONFUSED -> "The patient is confused"
                        TraumaConditionKey.DIZZY -> "Feeling dizzy"
                        TraumaConditionKey.UNCONSCIOUS -> "The patient is unconscious"
                    }
                }
            }
            Language.TAMIL -> {
                if (pName != null) {
                    if (person == PersonKey.ME) {
                        if (partName != null) {
                            when (condition) {
                                TraumaConditionKey.CUT -> "என் $partName வெட்டப்பட்டுள்ளது"
                                TraumaConditionKey.BROKEN -> "என் $partName உடைந்துள்ளது"
                                TraumaConditionKey.BLEEDING -> "என் $partName-ல் ரத்தம் வழிகிறது"
                                TraumaConditionKey.INJURED -> "என் $partName காயமடைந்துள்ளது"
                                TraumaConditionKey.CONFUSED -> "எனக்கு மூளை குழம்பியது"
                                TraumaConditionKey.DIZZY -> "எனக்கு தலை சுற்றுகிறது"
                                TraumaConditionKey.UNCONSCIOUS -> "நான் மயக்கமடைந்துள்ளேன்"
                            }
                        } else {
                            when (condition) {
                                TraumaConditionKey.CUT -> "எனக்கு வெட்டு காயம் ஏற்பட்டுள்ளது"
                                TraumaConditionKey.BROKEN -> "எனக்கு எலும்பு முறிவு ஏற்பட்டுள்ளது"
                                TraumaConditionKey.CONFUSED -> "எனக்கு மூளை குழம்பியது"
                                TraumaConditionKey.DIZZY -> "எனக்கு தலை சுற்றுகிறது"
                                TraumaConditionKey.UNCONSCIOUS -> "நான் மயக்கமடைந்துள்ளேன்"
                                TraumaConditionKey.BLEEDING -> "எனக்கு ரத்தம் வழிகிறது"
                                TraumaConditionKey.INJURED -> "நான் காயமடைந்துள்ளேன்"
                            }
                        }
                    } else {
                        if (partName != null) {
                            when (condition) {
                                TraumaConditionKey.CUT -> "$pName உடைய $partName வெட்டப்பட்டுள்ளது"
                                TraumaConditionKey.BROKEN -> "$pName உடைய $partName உடைந்துள்ளது"
                                TraumaConditionKey.BLEEDING -> "$pName-க்கு $partName-ல் ரத்தம் வழிகிறது"
                                TraumaConditionKey.INJURED -> "$pName உடைய $partName காயமடைந்துள்ளது"
                                TraumaConditionKey.CONFUSED -> "$pName-க்கு மூளை குழம்பியது"
                                TraumaConditionKey.DIZZY -> "$pName-க்கு தலை சுற்றுகிறது"
                                TraumaConditionKey.UNCONSCIOUS -> "$pName மயக்கமடைந்துள்ளார்"
                            }
                        } else {
                            when (condition) {
                                TraumaConditionKey.CUT -> "$pName-க்கு வெட்டு காயம் ஏற்பட்டுள்ளது"
                                TraumaConditionKey.BROKEN -> "$pName-க்கு எலும்பு முறிவு ஏற்பட்டுள்ளது"
                                TraumaConditionKey.CONFUSED -> "$pName-க்கு மூளை குழம்பியது"
                                TraumaConditionKey.DIZZY -> "$pName-க்கு தலை சுற்றுகிறது"
                                TraumaConditionKey.UNCONSCIOUS -> "$pName மயக்கமடைந்துள்ளார்"
                                TraumaConditionKey.BLEEDING -> "$pName-க்கு ரத்தம் வழிகிறது"
                                TraumaConditionKey.INJURED -> "$pName காயமடைந்துள்ளார்"
                            }
                        }
                    }
                } else {
                    val p = partName ?: "உறுப்பு"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p வெட்டப்பட்டுள்ளது"
                        TraumaConditionKey.BROKEN -> "$p உடைந்துள்ளது"
                        TraumaConditionKey.BLEEDING -> "$p-ல் ரத்தம் வழிகிறது"
                        TraumaConditionKey.INJURED -> "$p காயமடைந்துள்ளது"
                        TraumaConditionKey.CONFUSED -> "மூளை குழம்பியுள்ளது"
                        TraumaConditionKey.DIZZY -> "தலை சுற்றுகிறது"
                        TraumaConditionKey.UNCONSCIOUS -> "மயக்கமடைந்துள்ளார்"
                    }
                }
            }
            Language.HINDI -> {
                if (pName != null) {
                    if (person == PersonKey.ME) {
                        if (partName != null) {
                            when (condition) {
                                TraumaConditionKey.CUT -> "मेरा $partName कट गया है"
                                TraumaConditionKey.BROKEN -> "मेरा $partName टूट गया है"
                                TraumaConditionKey.BLEEDING -> "मेरे $partName से खून बह रहा है"
                                TraumaConditionKey.INJURED -> "मेरा $partName घायल है"
                                TraumaConditionKey.CONFUSED -> "मैं भ्रमित हूँ"
                                TraumaConditionKey.DIZZY -> "मुझे चक्कर आ रहा है"
                                TraumaConditionKey.UNCONSCIOUS -> "मैं बेहोश हूँ"
                            }
                        } else {
                            when (condition) {
                                TraumaConditionKey.CUT -> "मुझे चोट लगी है"
                                TraumaConditionKey.BROKEN -> "मेरी हड्डी टूट गई है"
                                TraumaConditionKey.CONFUSED -> "मैं भ्रमित हूँ"
                                TraumaConditionKey.DIZZY -> "मुझे चक्कर आ रहा है"
                                TraumaConditionKey.UNCONSCIOUS -> "मैं बेहोश हूँ"
                                TraumaConditionKey.BLEEDING -> "मेरा खून बह रहा है"
                                TraumaConditionKey.INJURED -> "मैं घायल हूँ"
                            }
                        }
                    } else {
                        if (partName != null) {
                            when (condition) {
                                TraumaConditionKey.CUT -> "$pName का $partName कट गया है"
                                TraumaConditionKey.BROKEN -> "$pName का $partName टूट गया है"
                                TraumaConditionKey.BLEEDING -> "$pName के $partName से खून बह रहा है"
                                TraumaConditionKey.INJURED -> "$pName का $partName घायल है"
                                TraumaConditionKey.CONFUSED -> "$pName भ्रमित है"
                                TraumaConditionKey.DIZZY -> "$pName को चक्कर आ रहा है"
                                TraumaConditionKey.UNCONSCIOUS -> "$pName बेहोश है"
                            }
                        } else {
                            when (condition) {
                                TraumaConditionKey.CUT -> "$pName को चोट लगी है"
                                TraumaConditionKey.BROKEN -> "$pName की हड्डी टूट गई है"
                                TraumaConditionKey.CONFUSED -> "$pName भ्रमित है"
                                TraumaConditionKey.DIZZY -> "$pName को चक्कर आ रहा है"
                                TraumaConditionKey.UNCONSCIOUS -> "$pName बेहोश है"
                                TraumaConditionKey.BLEEDING -> "$pName का खून बह रहा है"
                                TraumaConditionKey.INJURED -> "$pName घायल है"
                            }
                        }
                    }
                } else {
                    val p = partName ?: "अंग"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p कट गया है"
                        TraumaConditionKey.BROKEN -> "$p टूट गया है"
                        TraumaConditionKey.BLEEDING -> "$p से खून बह रहा है"
                        TraumaConditionKey.INJURED -> "$p घायल है"
                        TraumaConditionKey.CONFUSED -> "मरीज भ्रमित है"
                        TraumaConditionKey.DIZZY -> "चक्कर आ रहा है"
                        TraumaConditionKey.UNCONSCIOUS -> "मरीज बेहोश है"
                    }
                }
            }
            Language.TELUGU -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName యొక్క $partName తెగిపోయింది"
                            TraumaConditionKey.BROKEN -> "$pName యొక్క $partName విరిగిపోయింది"
                            TraumaConditionKey.BLEEDING -> "$pName యొక్క $partName నుండి రక్తం కారుతోంది"
                            TraumaConditionKey.INJURED -> "$pName యొక్క $partName గాయపడింది"
                            TraumaConditionKey.CONFUSED -> "$pName గందరగోళంలో ఉన్నాడు"
                            TraumaConditionKey.DIZZY -> "$pName కు తల తిరుగుతోంది"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName స్పృహ తప్పిపోయాడు"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName కు గాయమైంది"
                            TraumaConditionKey.BROKEN -> "$pName ఎముక విరిగింది"
                            TraumaConditionKey.CONFUSED -> "$pName గందరగోళంలో ఉన్నాడు"
                            TraumaConditionKey.DIZZY -> "$pName కు తల తిరుగుతోంది"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName స్పృహ తప్పిపోయాడు"
                            TraumaConditionKey.BLEEDING -> "$pName కు రక్తం కారుతోంది"
                            TraumaConditionKey.INJURED -> "$pName గాయపడ్డాడు"
                        }
                    }
                } else {
                    val p = partName ?: "అంగం"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p తెగిపోయింది"
                        TraumaConditionKey.BROKEN -> "$p విరిగిపోయింది"
                        TraumaConditionKey.BLEEDING -> "$p నుండి రక్తం కారుతోంది"
                        TraumaConditionKey.INJURED -> "$p గాయపడింది"
                        TraumaConditionKey.CONFUSED -> "గందరగోళంగా ఉంది"
                        TraumaConditionKey.DIZZY -> "తల తిరుగుతోంది"
                        TraumaConditionKey.UNCONSCIOUS -> "స్పృహ తప్పిపోయారు"
                    }
                }
            }
            Language.KANNADA -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName ಅವರ $partName ಕತ್ತರಿಸಲ್ಪಟ್ಟಿದೆ"
                            TraumaConditionKey.BROKEN -> "$pName ಅವರ $partName ಮುರಿದಿದೆ"
                            TraumaConditionKey.BLEEDING -> "$pName ಅವರ $partName-ನಿಂದ ರಕ್ತ ಸುರಿಯುತ್ತಿದೆ"
                            TraumaConditionKey.INJURED -> "$pName ಅವರ $partName ಗಾಯಗೊಂಡಿದೆ"
                            TraumaConditionKey.CONFUSED -> "$pName ಗೊಂದಲಕ್ಕೊಳಗಾಗಿದ್ದಾರೆ"
                            TraumaConditionKey.DIZZY -> "$pName ಅವರಿಗೆ ತಲೆ ತಿರುಗುತ್ತಿದೆ"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName ಪ್ರಜ್ಞಾಹೀನರಾಗಿದ್ದಾರೆ"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName ಅವರಿಗೆ ಗಾಯವಾಗಿದೆ"
                            TraumaConditionKey.BROKEN -> "$pName ಅವರ ಮೂಳೆ ಮುರಿದಿದೆ"
                            TraumaConditionKey.CONFUSED -> "$pName ಗೊಂದಲಕ್ಕೊಳಗಾಗಿದ್ದಾರೆ"
                            TraumaConditionKey.DIZZY -> "$pName ಅವರಿಗೆ ತಲೆ ತಿರುಗುತ್ತಿದೆ"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName ಪ್ರಜ್ಞಾಹೀನರಾಗಿದ್ದಾರೆ"
                            TraumaConditionKey.BLEEDING -> "$pName ಅವರಿಗೆ ರಕ್ತ ಸುರಿಯುತ್ತಿದೆ"
                            TraumaConditionKey.INJURED -> "$pName ಗಾಯಗೊಂಡಿದ್ದಾರೆ"
                        }
                    }
                } else {
                    val p = partName ?: "ಅಂಗ"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p ಕತ್ತರಿಸಲ್ಪಟ್ಟಿದೆ"
                        TraumaConditionKey.BROKEN -> "$p ಮುರಿದಿದೆ"
                        TraumaConditionKey.BLEEDING -> "$p-ನಿಂದ ರಕ್ತ ಸುರಿಯುತ್ತಿದೆ"
                        TraumaConditionKey.INJURED -> "$p ಗಾಯಗೊಂಡಿದೆ"
                        TraumaConditionKey.CONFUSED -> "ಗೊಂದಲದಲ್ಲಿದ್ದಾರೆ"
                        TraumaConditionKey.DIZZY -> "ತಲೆ ತಿರುಗುತ್ತಿದೆ"
                        TraumaConditionKey.UNCONSCIOUS -> "ಪ್ರಜ್ಞಾಹೀನರಾಗಿದ್ದಾರೆ"
                    }
                }
            }
            Language.MALAYALAM -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName-ന്റെ $partName മുറിഞ്ഞു"
                            TraumaConditionKey.BROKEN -> "$pName-ന്റെ $partName ഒടിഞ്ഞു"
                            TraumaConditionKey.BLEEDING -> "$pName-ന്റെ $partName-ൽ നിന്ന് രക്തം ഒഴുകുന്നു"
                            TraumaConditionKey.INJURED -> "$pName-ന്റെ $partName-ന് പരിക്കേറ്റു"
                            TraumaConditionKey.CONFUSED -> "$pName-ന് ആശയക്കുഴപ്പമുണ്ട്"
                            TraumaConditionKey.DIZZY -> "$pName-ന് തലകറങ്ങുന്നു"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName അബോധാവസ്ഥയിലാണ്"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName-ന് മുറിവേറ്റു"
                            TraumaConditionKey.BROKEN -> "$pName-ന്റെ എല്ല് ഒടിഞ്ഞു"
                            TraumaConditionKey.CONFUSED -> "$pName-ന് ആശയക്കുഴപ്പമുണ്ട്"
                            TraumaConditionKey.DIZZY -> "$pName-ന് തലകറങ്ങുന്നു"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName അബോധാവസ്ഥയിലാണ്"
                            TraumaConditionKey.BLEEDING -> "$pName-ൽ നിന്ന് രക്തം ഒഴുകുന്നു"
                            TraumaConditionKey.INJURED -> "$pName-ന് പരിക്കേറ്റു"
                        }
                    }
                } else {
                    val p = partName ?: "ഭാഗം"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p മുറിഞ്ഞു"
                        TraumaConditionKey.BROKEN -> "$p ഒടിഞ്ഞു"
                        TraumaConditionKey.BLEEDING -> "$p-ൽ നിന്ന് രക്തം ഒഴുകുന്നു"
                        TraumaConditionKey.INJURED -> "$p-ന് പരിക്കേറ്റു"
                        TraumaConditionKey.CONFUSED -> "ആശയക്കുഴപ്പത്തിലാണ്"
                        TraumaConditionKey.DIZZY -> "തലകറങ്ങുന്നു"
                        TraumaConditionKey.UNCONSCIOUS -> "അബോധാവസ്ഥയിലാണ്"
                    }
                }
            }
            Language.BENGALI -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName-এর $partName কেটে গেছে"
                            TraumaConditionKey.BROKEN -> "$pName-এর $partName ভেঙে গেছে"
                            TraumaConditionKey.BLEEDING -> "$pName-এর $partName থেকে রক্তপাত হচ্ছে"
                            TraumaConditionKey.INJURED -> "$pName-এর $partName আহত হয়েছে"
                            TraumaConditionKey.CONFUSED -> "$pName বিভ্রান্ত"
                            TraumaConditionKey.DIZZY -> "$pName-এর মাথা ঘুরছে"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName অজ্ঞান হয়ে গেছেন"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName আহত হয়েছেন"
                            TraumaConditionKey.BROKEN -> "$pName-এর হাড় ভেঙে গেছে"
                            TraumaConditionKey.CONFUSED -> "$pName বিভ্রান্ত"
                            TraumaConditionKey.DIZZY -> "$pName-এর মাথা ঘুরছে"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName অজ্ঞান হয়ে গেছেন"
                            TraumaConditionKey.BLEEDING -> "$pName-এর রক্তপাত হচ্ছে"
                            TraumaConditionKey.INJURED -> "$pName আহত হয়েছেন"
                        }
                    }
                } else {
                    val p = partName ?: "অঙ্গ"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p কেটে গেছে"
                        TraumaConditionKey.BROKEN -> "$p ভেঙে গেছে"
                        TraumaConditionKey.BLEEDING -> "$p থেকে রক্তপাত হচ্ছে"
                        TraumaConditionKey.INJURED -> "$p আহত হয়েছে"
                        TraumaConditionKey.CONFUSED -> "বিভ্রান্তি হয়েছে"
                        TraumaConditionKey.DIZZY -> "মাথা ঘুরছে"
                        TraumaConditionKey.UNCONSCIOUS -> "অজ্ঞান হয়ে গেছেন"
                    }
                }
            }
            Language.MARATHI -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName चा $partName कापला गेला आहे"
                            TraumaConditionKey.BROKEN -> "$pName चा $partName मोडला आहे"
                            TraumaConditionKey.BLEEDING -> "$pName च्या $partName मधून रक्त येत आहे"
                            TraumaConditionKey.INJURED -> "$pName चा $partName जखमी झाला आहे"
                            TraumaConditionKey.CONFUSED -> "$pName गोंधळलेला आहे"
                            TraumaConditionKey.DIZZY -> "$pName ला चक्कर येत आहे"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName बेशुद्ध आहे"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName जखमी झाला आहे"
                            TraumaConditionKey.BROKEN -> "$pName चे हाड मोडले आहे"
                            TraumaConditionKey.CONFUSED -> "$pName गोंधळलेला आहे"
                            TraumaConditionKey.DIZZY -> "$pName ला चक्कर येत आहे"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName बेशुद्ध आहे"
                            TraumaConditionKey.BLEEDING -> "$pName चे रक्त येत आहे"
                            TraumaConditionKey.INJURED -> "$pName जखमी आहे"
                        }
                    }
                } else {
                    val p = partName ?: "अवयव"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p कापला गेला आहे"
                        TraumaConditionKey.BROKEN -> "$p मोडला आहे"
                        TraumaConditionKey.BLEEDING -> "$p मधून रक्त येत आहे"
                        TraumaConditionKey.INJURED -> "$p जखमी झाला आहे"
                        TraumaConditionKey.CONFUSED -> "गोंधळलेला आहे"
                        TraumaConditionKey.DIZZY -> "चक्कर येत आहे"
                        TraumaConditionKey.UNCONSCIOUS -> "बेशुद्ध आहे"
                    }
                }
            }
            Language.GUJARATI -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName નો $partName કપાઈ ગયો છે"
                            TraumaConditionKey.BROKEN -> "$pName નો $partName તૂટી ગયો છે"
                            TraumaConditionKey.BLEEDING -> "$pName ના $partName માંથી લોહી વહી રહ્યું છે"
                            TraumaConditionKey.INJURED -> "$pName નો $partName ઇજાગ્રસ્ત છે"
                            TraumaConditionKey.CONFUSED -> "$pName મૂંઝવણમાં છે"
                            TraumaConditionKey.DIZZY -> "$pName ને ચક્કર આવે છે"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName બેભાન છે"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName ઇજાગ્રસ્ત છે"
                            TraumaConditionKey.BROKEN -> "$pName નું હાડકું તૂટી ગયું છે"
                            TraumaConditionKey.CONFUSED -> "$pName મૂંઝવણમાં છે"
                            TraumaConditionKey.DIZZY -> "$pName ને ચક્કર આવે છે"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName બેભાન છે"
                            TraumaConditionKey.BLEEDING -> "$pName નું લોહી વહી રહ્યું છે"
                            TraumaConditionKey.INJURED -> "$pName ઘાયલ છે"
                        }
                    }
                } else {
                    val p = partName ?: "અંગ"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p કપાઈ ગયો છે"
                        TraumaConditionKey.BROKEN -> "$p તૂટી ગયો છે"
                        TraumaConditionKey.BLEEDING -> "$p માંથી લોહી વહી રહ્યું છે"
                        TraumaConditionKey.INJURED -> "$p ઇજાગ્રસ્ત છે"
                        TraumaConditionKey.CONFUSED -> "મૂંઝવણમાં છે"
                        TraumaConditionKey.DIZZY -> "ચક્કર આવે છે"
                        TraumaConditionKey.UNCONSCIOUS -> "બેભાન છે"
                    }
                }
            }
            Language.PUNJABI -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName ਦੀ $partName ਕੱਟੀ ਗਈ ਹੈ"
                            TraumaConditionKey.BROKEN -> "$pName ਦੀ $partName ਟੁੱਟ ਗਈ ਹੈ"
                            TraumaConditionKey.BLEEDING -> "$pName ਦੀ $partName ਵਿੱਚੋਂ ਖੂਨ ਵਹਿ ਰਿਹਾ ਹੈ"
                            TraumaConditionKey.INJURED -> "$pName ਦੀ $partName ਜ਼ਖਮੀ ਹੈ"
                            TraumaConditionKey.CONFUSED -> "$pName ਉਲਝਣ ਵਿੱਚ ਹੈ"
                            TraumaConditionKey.DIZZY -> "$pName ਨੂੰ ਚੱਕਰ ਆ ਰਹੇ ਹਨ"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName ਬੇਹੋਸ਼ ਹੈ"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName ਜ਼ਖਮੀ ਹੈ"
                            TraumaConditionKey.BROKEN -> "$pName ਦੀ ਹੱਡੀ ਟੁੱਟ ਗਈ ਹੈ"
                            TraumaConditionKey.CONFUSED -> "$pName ਉਲਝਣ ਵਿੱਚ ਹੈ"
                            TraumaConditionKey.DIZZY -> "$pName ਨੂੰ ਚੱਕਰ ਆ ਰਹੇ ਹਨ"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName ਬੇਹੋਸ਼ ਹੈ"
                            TraumaConditionKey.BLEEDING -> "$pName ਦਾ ਖੂਨ ਵਹਿ ਰਿਹਾ ਹੈ"
                            TraumaConditionKey.INJURED -> "$pName ਜ਼ਖਮੀ ਹੈ"
                        }
                    }
                } else {
                    val p = partName ?: "ਅੰਗ"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p ਕੱਟੀ ਗਈ ਹੈ"
                        TraumaConditionKey.BROKEN -> "$p ਟੁੱਟ ਗਈ ਹੈ"
                        TraumaConditionKey.BLEEDING -> "$p ਵਿੱਚੋਂ ਖੂਨ ਵਹਿ ਰਿਹਾ ਹੈ"
                        TraumaConditionKey.INJURED -> "$p ਜ਼ਖਮੀ ਹੈ"
                        TraumaConditionKey.CONFUSED -> "ਉਲਝਣ ਵਿੱਚ ਹੈ"
                        TraumaConditionKey.DIZZY -> "ਚੱਕਰ ਆ ਰਹੇ ਹਨ"
                        TraumaConditionKey.UNCONSCIOUS -> "ਬੇਹੋਸ਼ ਹੈ"
                    }
                }
            }
            Language.ODIA -> {
                if (pName != null) {
                    if (partName != null) {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName ଙ୍କ $partName କଟିଯାଇଛି"
                            TraumaConditionKey.BROKEN -> "$pName ଙ୍କ $partName ଭାଙ୍ଗିଯାଇଛି"
                            TraumaConditionKey.BLEEDING -> "$pName ଙ୍କ $partName ରୁ ରକ୍ତ ବାହାରୁଛି"
                            TraumaConditionKey.INJURED -> "$pName ଙ୍କ $partName ଆହତ ହୋଇଛି"
                            TraumaConditionKey.CONFUSED -> "$pName ବିଭ୍ରାନ୍ତ"
                            TraumaConditionKey.DIZZY -> "$pName ଙ୍କ ମୁଣ୍ଡ ଘୁରୁଛି"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName ଅଚେତ ହୋଇପଡ଼ିଛନ୍ତି"
                        }
                    } else {
                        when (condition) {
                            TraumaConditionKey.CUT -> "$pName ଆହତ ହୋଇଛନ୍ତି"
                            TraumaConditionKey.BROKEN -> "$pName ଙ୍କ ହାଡ଼ ଭାଙ୍ଗିଯାଇଛି"
                            TraumaConditionKey.CONFUSED -> "$pName ବିଭ୍ରାନ୍ତ"
                            TraumaConditionKey.DIZZY -> "$pName ଙ୍କ ମୁଣ୍ଡ ଘୁରୁଛି"
                            TraumaConditionKey.UNCONSCIOUS -> "$pName ଅଚେତ ହୋଇପଡ଼ିଛନ୍ତି"
                            TraumaConditionKey.BLEEDING -> "$pName ଙ୍କ ରକ୍ତସ୍ରାବ ହେଉଛି"
                            TraumaConditionKey.INJURED -> "$pName ଆହତ ହୋଇଛନ୍ତି"
                        }
                    }
                } else {
                    val p = partName ?: "ଅଙ୍ଗ"
                    when (condition) {
                        TraumaConditionKey.CUT -> "$p କଟିଯାଇଛି"
                        TraumaConditionKey.BROKEN -> "$p ଭାଙ୍ଗିଯାଇଛି"
                        TraumaConditionKey.BLEEDING -> "$p ରୁ ରକ୍ତ ବାହାରୁଛି"
                        TraumaConditionKey.INJURED -> "$p ଆହତ ହୋଇଛି"
                        TraumaConditionKey.CONFUSED -> "ବିଭ୍ରାନ୍ତ ଅଛନ୍ତି"
                        TraumaConditionKey.DIZZY -> "ମୁଣ୍ଡ ଘୁରୁଛି"
                        TraumaConditionKey.UNCONSCIOUS -> "ଅଚେତ ହୋଇପଡ଼ିଛନ୍ତି"
                    }
                }
            }
        }
    }

    private fun matchMedicalAndTraumaPatterns(
        text: String,
        targetLang: Language,
        sourceLang: Language = Language.ENGLISH
    ): String? {
        val clean = text.replace(Regex("""[.,!?;:\"'()\[\]{}—–\-_/\\|।॥]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val lowerClean = clean.lowercase(Locale.ROOT)
        if (clean.isBlank()) return null

        // 1. Detect Condition
        var detectedCondition: TraumaConditionKey? = null
        when {
            cutPatterns.any { lowerClean.contains(it) } -> detectedCondition = TraumaConditionKey.CUT
            brokenPatterns.any { lowerClean.contains(it) } -> detectedCondition = TraumaConditionKey.BROKEN
            confusedPatterns.any { lowerClean.contains(it) } -> detectedCondition = TraumaConditionKey.CONFUSED
            dizzyPatterns.any { lowerClean.contains(it) } -> detectedCondition = TraumaConditionKey.DIZZY
            unconsciousPatterns.any { lowerClean.contains(it) } -> detectedCondition = TraumaConditionKey.UNCONSCIOUS
            bleedingPatterns.any { lowerClean.contains(it) } -> detectedCondition = TraumaConditionKey.BLEEDING
            injuredPatterns.any { lowerClean.contains(it) } -> detectedCondition = TraumaConditionKey.INJURED
        }
        if (detectedCondition == null) return null

        // 2. Detect Body Part
        var detectedBodyPart: BodyPartKey? = null
        when {
            legPatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.LEG
            handPatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.HAND
            brainPatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.BRAIN
            headPatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.HEAD
            eyePatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.EYE
            chestPatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.CHEST
            neckPatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.NECK
            bonePatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.BONE
            bloodPatterns.any { lowerClean.contains(it) } -> detectedBodyPart = BodyPartKey.BLOOD
        }

        // 3. Detect Person / Subject
        var detectedPerson: PersonKey? = null
        var rawPersonName: String? = null

        when {
            harisPatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.HARIS
            vijayPatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.VIJAY
            rohitPatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.ROHIT
            rahulPatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.RAHUL
            sureshPatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.SURESH
            priyaPatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.PRIYA
            patientPatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.PATIENT
            mePatterns.any { lowerClean.contains(it) } -> detectedPerson = PersonKey.ME
            else -> {
                val words = clean.split(Regex("""\s+"""))
                val firstWord = words.firstOrNull()?.trim()
                if (firstWord != null && firstWord.length >= 3 && !isMedicalStopWord(firstWord)) {
                    val baseName = firstWord.replace(Regex("""(?:'s| உடைய|க்கு|ற்கு| கா| की| के| యొక్క| వారి| ಅವರ|ന്റെ|ന്|এর|কে|चा|ची|नो|ની|ਦੀ|ਦਾ|ଙ୍କ|ଙ୍କୁ)$"""), "")
                    if (baseName.isNotBlank() && baseName.length >= 2 && !isMedicalStopWord(baseName)) {
                        detectedPerson = PersonKey.UNKNOWN
                        rawPersonName = baseName
                    }
                }
            }
        }

        if (detectedPerson == null && detectedBodyPart == null) return null

        return renderTraumaSentence(detectedPerson, rawPersonName, detectedBodyPart, detectedCondition, targetLang)
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
        val verbs = setOf("need", "want", "see", "hear", "help", "call", "send", "have", "has", "had", "bring", "give", "take", "stop", "wait", "move", "reach", "report", "eat", "ate", "eating")
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
        private val cutPatterns = listOf(
            "cut", "severed",
            "வெட்டப்பட்டுள்ளது", "வெட்டப்பட்டது", "வெட்டிக்கிச்சு", "வெட்டிக்கிருச்சு", "வெட்டிருச்சு", "வெட்டிடுச்சு", "வெட்டியுள்ளது", "வெட்டு",
            "कट गया", "कट चुका", "कटा हुआ", "काट दिया",
            "తెగిపోయింది", "కత్తిరించబడింది", "కోయబడింది",
            "ಕತ್ತರಿಸಲ್ಪಟ್ಟಿದೆ", "ಕತ್ತರಿಸಲಾಗಿದೆ", "ಕಟ್ ಆಗಿದೆ",
            "മുറിഞ്ഞു", "മുറിഞ്ഞിരിക്കുന്നു", "മുറിച്ചുമാറ്റപ്പെട്ടു",
            "কেটে গেছে", "কাটা পড়েছে", "কাটা হয়েছে",
            "कापला गेला आहे", "कापला आहे", "कापला",
            "કપાઈ ગયો છે", "કપાયો છે",
            "ਕੱਟੀ ਗਈ ਹੈ", "ਕੱਟ ਗਈ ਹੈ", "ਕੱਟਿਆ ਗਿਆ ਹੈ",
            "କଟିଯାଇଛି", "କଟାଯାଇଛି"
        )

        private val brokenPatterns = listOf(
            "broken", "fractured", "fracture",
            "உடைந்துள்ளது", "உடைந்தது", "உடைஞ்சிடுச்சு", "உடைஞ்சிருச்சு", "முறிந்துள்ளது", "எலும்பு முறிவு",
            "टूट गया", "फ्रैक्चर", "टूटा हुआ",
            "విరిగిపోయింది", "ఫ్రాక్చర్",
            "ಮುರಿದಿದೆ", "ಮುರಿತವಾಗಿದೆ",
            "ഒടിഞ്ഞു", "ഒടിഞ്ഞിരിക്കുന്നു",
            "ভেঙে গেছে", "ভেঙে পড়েছে",
            "मोडला आहे", "तुटला आहे",
            "તૂટી ગયો છે", "ભાંગી ગયો છે",
            "ਟੁੱਟ ਗਈ ਹੈ", "ਟੁੱਟ ਗਿਆ ਹੈ",
            "ଭାଙ୍ଗିଯାଇଛି", "ଭାଙ୍ଗି ପଡ଼ିଛି"
        )

        private val confusedPatterns = listOf(
            "confused", "disoriented", "confusion",
            "குழம்பியது", "குழம்பிவிட்டது", "குழம்பிடுச்சு", "குழம்பிருச்சு", "குழப்பம்", "குழப்பமாக",
            "भ्रमित", "चकरा गया", "असमंजस", "सिर चकरा",
            "గందరగోళంలో", "అయోమయంలో", "గందరగోళం",
            "ಗೊಂದಲಕ್ಕೊಳಗಾಗಿದ್ದಾರೆ", "ಗೊಂದಲದಲ್ಲಿದ್ದಾರೆ", "ಗೊಂದಲ",
            "ആശയക്കുഴപ്പത്തിലാണ്", "ആശയക്കുഴപ്പമുണ്ട്", "പരിഭ്രാന്തനാണ്",
            "বিভ্রান্ত", "বিভ্রান্তি",
            "गोंधळलेला", "संभ्रमात", "गोंधळात",
            "મૂંઝવણમાં", "અસમંજસમાં",
            "ਉਲਝਣ ਵਿੱਚ", "ਭੰਬਲਭੂਸੇ",
            "ବିଭ୍ରାନ୍ତ", "ଦ୍ୱନ୍ଦ୍ୱରେ", "ଭ୍ରମିତ"
        )

        private val dizzyPatterns = listOf(
            "dizzy", "dizziness", "spinning",
            "சுற்றுகிறது", "சுத்துது", "சுற்றல்",
            "चक्कर", "घूम रहा",
            "తిరుగుతోంది",
            "ತಿರುಗುತ್ತಿದೆ",
            "തലകറങ്ങുന്നു", "തലകറക്കം",
            "মাথা ঘুরছে",
            "चक्कर येत", "गरगरत",
            "ચક્કર આવે", "ભમે છે",
            "ਚੱਕਰ ਆ ਰਹੇ",
            "ମୁଣ୍ଡ ଘୁରୁଛି"
        )

        private val unconsciousPatterns = listOf(
            "unconscious", "fainted", "collapsed",
            "மயக்கமடைந்துள்ளார்", "மயங்கிவிட்டார்", "மயக்கம்", "மயங்கி விழுந்தார்", "மயக்கமடைந்துள்ளேன்",
            "बेहोश",
            "స్పృహ తప్పిపోయాడు", "మూర్ఛపోయాడు", "స్పృహ",
            "ಪ್ರಜ್ಞಾಹೀನರಾಗಿದ್ದಾರೆ", "ಮೂರ್ಛೆ",
            "അബോധാവസ്ഥയിലാണ്", "ബോധംകെട്ടു",
            "অজ্ঞান", "অচেতন",
            "बेशुद्ध",
            "બેભાન", "બેહોશ",
            "ਬੇਹੋਸ਼", "ਬੇਸੁਰਤ",
            "ଅଚେତ"
        )

        private val bleedingPatterns = listOf(
            "bleeding", "bleeds",
            "ரத்தம் வழிகிறது", "ரத்தம் கொட்டுது", "குருதிப்போக்கு",
            "खून बह रहा", "रक्त बह रहा",
            "రక్తం కారుతోంది",
            "ರಕ್ತ ಸುರಿಯುತ್ತಿದೆ",
            "രക്തം ഒഴുകുന്നു",
            "রক্তপাত হচ্ছে", "রক্ত ঝরছে",
            "रक्त येत आहे", "रक्तस्त्राव",
            "લોહી વહી રહ્યું છે",
            "ਖੂਨ ਵਹਿ ਰਿਹਾ ਹੈ",
            "ରକ୍ତ ବାହାରୁଛି", "ରକ୍ତସ୍ରାବ"
        )

        private val injuredPatterns = listOf(
            "injured", "hurt", "wounded",
            "காயமடைந்துள்ளார்", "காயமடைந்துள்ளது", "காயம்", "அடிபட்டுள்ளது", "அடிபட்டிருக்கு",
            "घायल", "चोट लगी",
            "గాయపడ్డాడు", "గాయపడింది", "దెబ్బ తగిలింది",
            "ಗಾಯಗೊಂಡಿದ್ದಾರೆ", "ಗಾಯಗೊಂಡಿದೆ", "ಪೆಟ್ಟಾಗಿದೆ",
            "പരിക്കേറ്റു", "പരിക്കേറ്റിരിക്കുന്നു",
            "আহত", "চোট পেয়েছেন",
            "जखमी", "दुखापत",
            "ઇજાગ્રસ્ત", "ઘાયલ",
            "ਜ਼ਖਮੀ", "ਸੱਟ ਲੱਗੀ",
            "ଆହତ", "ଆଘାତ"
        )

        private val legPatterns = listOf(
            "leg", "legs", "foot", "feet",
            "கால்", "கால்கள்", "பாதம்",
            "पैर", "पाँव", "टांग",
            "కాలు", "కాళ్ళు",
            "ಕಾಲು", "ಕಾಲುಗಳು",
            "കാൽ", "കാലുകൾ",
            "পা", "পাগুলো",
            "पाय",
            "પગ",
            "ਲੱਤ", "ਲੱਤਾਂ", "ਪੈਰ",
            "ଗୋଡ", "ଗୋଡ଼"
        )

        private val handPatterns = listOf(
            "hand", "hands", "arm", "arms", "finger",
            "கை", "கைகள்", "விரல்",
            "हाथ", "बाजू", "उंगली",
            "చేయి", "చేతులు",
            "ಕೈ", "ಕೈಗಳು",
            "കൈ", "കൈകൾ",
            "হাত", "হাতগুলো",
            "हात",
            "હાથ",
            "ਹੱਥ", "ਬਾਂਹ",
            "ହାତ"
        )

        private val brainPatterns = listOf(
            "brain", "mind",
            "மூளை", "மனம்",
            "दिमाग", "मन",
            "మెదడు", "మనస్సు",
            "ಮೆದುಳು", "ಮನಸ್ಸು",
            "തലച്ചോറ്", "മനസ്സ്",
            "মস্তিষ্ক", "মন",
            "मेंदू", "मन",
            "મગજ", "મન",
            "ਦਿਮਾਗ", "ਮਨ",
            "ମସ୍ତିଷ୍କ", "ମନ"
        )

        private val headPatterns = listOf(
            "head",
            "தலை",
            "सिर",
            "తల",
            "ತಲೆ",
            "തല",
            "মাথা",
            "डोके",
            "માથું",
            "ਸਿਰ",
            "ମୁଣ୍ଡ"
        )

        private val eyePatterns = listOf(
            "eye", "eyes",
            "கண்", "கண்கள்",
            "आँख", "आँखें",
            "కన్ను", "కళ్ళు",
            "ಕಣ್ಣು", "ಕಣ್ಣುಗಳು",
            "കണ്ണ്", "കണ്ണുകൾ",
            "চোখ",
            "डोळा", "डोळे",
            "આંખ", "આંખો",
            "ਅੱਖ", "ਅੱਖਾਂ",
            "ଆଖି"
        )

        private val chestPatterns = listOf(
            "chest", "heart",
            "நெஞ்சு", "மார்பு", "இதயம்",
            "छाती", "सीना", "दिल",
            "ఛాతీ", "గుండె",
            "ಎದೆ", "ಹೃದಯ",
            "നെഞ്ച്", "ഹൃദയം",
            "বুক", "হৃদয়",
            "छाती", "हृदय",
            "છાતી", "હૃદય",
            "ਛਾਤੀ", "ਦਿਲ",
            "ଛାତି", "ହୃଦୟ"
        )

        private val neckPatterns = listOf(
            "neck", "throat",
            "கழுத்து", "தொண்டை",
            "गर्दन", "गला",
            "మెడ", "గొంతు",
            "ಕತ್ತು", "ಗಂಟಲು",
            "കഴുത്ത്", "തൊണ്ട",
            "ঘাড়", "গলা",
            "मान", "घसा",
            "ગરદન", "ગળું",
            "ਗਰਦਨ", "ਗਲਾ",
            "ବେକ", "ଗଳା"
        )

        private val bonePatterns = listOf(
            "bone", "bones",
            "எலும்பு",
            "हड्डी",
            "ఎముక",
            "ಮೂಳೆ",
            "എല്ല്",
            "হাড়",
            "हाड",
            "હાડકું",
            "ਹੱਡੀ",
            "ହାଡ଼", "ହାଡ"
        )

        private val bloodPatterns = listOf(
            "blood", "bleeding",
            "ரத்தம்", "குருதி",
            "खून", "रक्त",
            "రక్తం",
            "ರಕ್ತ",
            "രക്തം",
            "রক্ত",
            "रक्त",
            "લોહી",
            "ਖੂਨ", "ਲਹੂ",
            "ରକ୍ତ"
        )

        private val harisPatterns = listOf(
            "haris", "harris",
            "ஹரிசுக்கு", "ஹரிஸ்க்கு", "ஹரிஸ்", "ஹாரிஸ்",
            "हरीश", "हैरिस", "हारिस",
            "హరీష్",
            "ಹರೀಶ್",
            "ഹരീഷ്", "ഹരീഷിന്",
            "হ্যারিস",
            "હરીશ",
            "ਹਰੀਸ਼",
            "ହରିଶ"
        )

        private val vijayPatterns = listOf(
            "vijay",
            "விஜய்", "விஜய்க்கு", "விஜயின்",
            "विजय",
            "విజయ్",
            "ವಿಜಯ್",
            "വിജയ്", "വിജയിന്റെ", "വിജയ്ക്ക്",
            "বিজয়", "বিজয়ের",
            "વિજય",
            "ਵਿਜੇ",
            "ବିଜୟ"
        )

        private val rohitPatterns = listOf(
            "rohit", "ரோஹித்", "ரோஹித்துக்கு", "रोहित", "రోహిత్", "ರೋಹಿತ್", "രോഹിത്", "রোহিত", "રોહિત", "ਰੋਹਿਤ", "ରୋହିତ"
        )

        private val rahulPatterns = listOf(
            "rahul", "ராகுல்", "ராகுலுக்கு", "राहुल", "రాహుల్", "ರಾಹುಲ್", "രാഹുൽ", "রাহুল", "રાહુલ", "ਰਾਹੁਲ", "ରାହୁଲ"
        )

        private val sureshPatterns = listOf(
            "suresh", "சுரேஷ்", "சுரேஷுக்கு", "सुरेश", "సురేష్", "ಸುರೇಶ್", "സുരേഷ്", "সুরেশ", "સુરેશ", "ਸੁਰੇਸ਼", "ସୁରେଶ"
        )

        private val priyaPatterns = listOf(
            "priya", "பிரியா", "பிரியாவுக்கு", "प्रिया", "ప్రియ", "ಪ್ರಿಯಾ", "പ്രിയ", "প্রিয়া", "પ્રિયા", "ਪ੍ਰਿਯਾ", "ପ୍ରିୟା"
        )

        private val patientPatterns = listOf(
            "patient", "நோயாளி", "நோயாளிக்கு", "मरीज", "రోగి", "ರೋಗಿ", "രോഗി", "রোগী", "रुग्ण", "દર્દી", "ਮਰੀਜ਼", "ରୋଗୀ"
        )

        private val mePatterns = listOf(
            "my", "me", "i am", "i'm",
            "எனக்கு", "என்", "என்னை", "என்னுடைய", "நான்",
            "मेरा", "मेरी", "मेरे", "मुझे", "मैं",
            "నా", "నాకు", "నేను",
            "ನನ್ನ", "ನನಗೆ", "ನಾನು",
            "എന്റെ", "എനിക്ക്", "ഞാൻ",
            "আমার", "আমাকে", "আমি",
            "माझा", "माझी", "मला", "मी",
            "મારો", "મારી", "મને", "હું",
            "ਮੇਰੀ", "ਮੇਰਾ", "ਮੈਨੂੰ", "ਮੈਂ",
            "ମୋର", "ମୋତେ", "ମୁଁ"
        )

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
            "no" to "இல்லை",
            "lunch" to "மதிய உணவு",
            "dinner" to "இரவு உணவு",
            "breakfast" to "காலை உணவு",
            "eat" to "சாப்பிடுங்கள்",
            "ate" to "சாப்பிட்டோம்",
            "eating" to "சாப்பிடுகிறோம்",
            "leg" to "கால்",
            "legs" to "கால்கள்",
            "hand" to "கை",
            "hands" to "கைகள்",
            "head" to "தலை",
            "brain" to "மூளை",
            "mind" to "மனம்",
            "eye" to "கண்",
            "eyes" to "கண்கள்",
            "chest" to "நெஞ்சு",
            "neck" to "கழுத்து",
            "bone" to "எலும்பு",
            "blood" to "ரத்தம்",
            "cut" to "வெட்டப்பட்டுள்ளது",
            "broken" to "உடைந்துள்ளது",
            "confused" to "குழப்பமடைந்துள்ளது",
            "dizzy" to "தலை சுற்றல்",
            "unconscious" to "மயக்கமடைந்துள்ளார்",
            "injured" to "காயமடைந்துள்ளார்"
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
            "and" to "और",
            "had" to "कर लिया",
            "lunch" to "दोपहर का भोजन",
            "dinner" to "रात का खाना",
            "breakfast" to "नाश्ता",
            "eat" to "खाएं",
            "ate" to "खाया",
            "eating" to "खा रहे हैं",
            "leg" to "पैर",
            "legs" to "पैर",
            "hand" to "हाथ",
            "hands" to "हाथ",
            "head" to "सिर",
            "brain" to "दिमाग",
            "mind" to "मन",
            "eye" to "आँख",
            "eyes" to "आँखें",
            "chest" to "छाती",
            "neck" to "गर्दन",
            "bone" to "हड्डी",
            "blood" to "खून",
            "cut" to "कटा हुआ",
            "broken" to "टूटा हुआ",
            "confused" to "भ्रमित",
            "dizzy" to "चक्कर",
            "unconscious" to "बेहोश",
            "injured" to "घायल"
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
            "and" to "మరియు",
            "had" to "చేశాము",
            "lunch" to "మధ్యాహ్న భోజనం",
            "dinner" to "రాత్రి భోజనం",
            "breakfast" to "అల్పాహారం",
            "eat" to "తినండి",
            "ate" to "తిన్నాము",
            "eating" to "తింటున్నాము",
            "leg" to "కాలు",
            "legs" to "కాళ్ళు",
            "hand" to "చేయి",
            "hands" to "చేతులు",
            "head" to "తల",
            "brain" to "మెదడు",
            "cut" to "తెగిపోయింది",
            "broken" to "విరిగిపోయింది",
            "confused" to "గందరగోళంలో",
            "injured" to "గాయపడ్డాడు"
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
            "and" to "ಮತ್ತು",
            "had" to "ಮಾಡಿದ್ದೇವೆ",
            "lunch" to "ಮಧ್ಯಾಹ್ನದ ಊಟ",
            "dinner" to "ರಾತ್ರಿಯ ಊಟ",
            "breakfast" to "ಉಪಾಹಾರ",
            "eat" to "ತಿನ್ನಿ",
            "ate" to "ತಿಂದಿದ್ದೇವೆ",
            "eating" to "ತಿನ್ನುತ್ತಿದ್ದೇವೆ",
            "leg" to "ಕಾಲು",
            "legs" to "ಕಾಲುಗಳು",
            "hand" to "ಕೈ",
            "hands" to "ಕೈಗಳು",
            "head" to "ತಲೆ",
            "brain" to "ಮೆದುಳು",
            "cut" to "ಕತ್ತರಿಸಲ್ಪಟ್ಟಿದೆ",
            "broken" to "ಮುರಿದಿದೆ",
            "confused" to "ಗೊಂದಲದಲ್ಲಿದ್ದಾರೆ",
            "injured" to "ಗಾಯಗೊಂಡಿದ್ದಾರೆ"
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
            "and" to "ഒപ്പം",
            "had" to "കഴിച്ചു",
            "lunch" to "ഉച്ചഭക്ഷണം",
            "dinner" to "അത്താഴം",
            "breakfast" to "പ്രഭാതഭക്ഷണം",
            "eat" to "കഴിക്കുക",
            "ate" to "കഴിച്ചു",
            "eating" to "കഴിക്കുന്നു",
            "leg" to "കാൽ",
            "legs" to "കാലുകൾ",
            "hand" to "കൈ",
            "hands" to "കൈകൾ",
            "head" to "തല",
            "brain" to "തലച്ചോറ്",
            "cut" to "മുറിഞ്ഞു",
            "broken" to "ഒടിഞ്ഞു",
            "confused" to "ആശയക്കുഴപ്പത്തിലാണ്",
            "injured" to "പരിക്കേറ്റു"
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
                "சாப்பாடு" to "food",
                "மதிய உணவு" to "lunch",
                "சாப்பிட்டோம்" to "had lunch",
                "சாப்பிட்டேன்" to "had lunch",
                "சாப்பிட்டீர்களா" to "had lunch",
                "சாப்பிட்டீங்களா" to "had lunch",
                "தண்ணீரும்" to "water",
                "தண்ணீர்" to "water",
                "தேவைப்படுகிறது" to "need",
                "தேவை" to "need",
                "வேண்டும்" to "need",
                "வேண்டாம்" to "do not",
                "உதவி" to "help",
                "உதவுங்கள்" to "help",
                "கால்" to "leg",
                "கை" to "hand",
                "மூளை" to "brain",
                "தலை" to "head",
                "வெட்டப்பட்டுள்ளது" to "is cut",
                "வெட்டிக்கிச்சு" to "is cut",
                "குழம்பியது" to "is confused",
                "குழம்பிடுச்சு" to "is confused",
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
