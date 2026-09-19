package org.sih.itantra.ml.translation

import org.sih.itantra.domain.model.Language
import java.util.Locale

/**
 * Native On-Device Colloquial & Conversational Post-Processor.
 * Directly ported from SIH-PS2-MODEL-main/colloquial.py.
 * Converts formal bookish translations into natural spoken language & slang
 * across all Indic languages (Tamil, Hindi, Telugu, Malayalam, Kannada, Bengali, Marathi, Gujarati, Punjabi).
 */
object ColloquialEngine {

    private const val SEPARATORS = " \t\n\r,.!?;:।॥\"'()[]{}<>/\\|–—…-‑'’‚‘“”"
    private val SEP_CLASS = "[" + Regex.escape(SEPARATORS) + "]"

    private fun wb(word: String): Regex {
        return Regex("(?:^|(?<=$SEP_CLASS))(?:${Regex.escape(word)})(?:$|(?=$SEP_CLASS))")
    }

    private fun endWb(word: String): Regex {
        return Regex("(?:${Regex.escape(word)})(?:$|(?=$SEP_CLASS))")
    }

    fun toColloquial(text: String, targetLang: Language, srcText: String = ""): String {
        if (text.isBlank()) return text
        val lowerSrc = srcText.lowercase(Locale.ROOT)
        val isCasualSrc = listOf("hi", "hey", "hello", "bro", "buddy", "dude", "yaar", "machan").any { lowerSrc.contains(it) }

        return when (targetLang) {
            Language.TAMIL -> colloquialTamil(text, isCasualSrc)
            Language.HINDI -> colloquialHindi(text, isCasualSrc)
            Language.TELUGU -> colloquialTelugu(text, isCasualSrc)
            Language.MALAYALAM -> colloquialMalayalam(text, isCasualSrc)
            Language.KANNADA -> colloquialKannada(text, isCasualSrc)
            Language.BENGALI -> colloquialBengali(text, isCasualSrc)
            Language.MARATHI -> colloquialMarathi(text, isCasualSrc)
            Language.GUJARATI -> colloquialGujarati(text, isCasualSrc)
            Language.PUNJABI -> colloquialPunjabi(text, isCasualSrc)
            else -> text
        }
    }

    fun toColloquialByCode(text: String, langCode: String, srcText: String = ""): String {
        val lang = when (langCode) {
            "tam_Taml" -> Language.TAMIL
            "hin_Deva" -> Language.HINDI
            "tel_Telu" -> Language.TELUGU
            "mal_Mlym" -> Language.MALAYALAM
            "kan_Knda" -> Language.KANNADA
            "ben_Beng" -> Language.BENGALI
            "mar_Deva" -> Language.MARATHI
            "guj_Gujr" -> Language.GUJARATI
            "pan_Guru" -> Language.PUNJABI
            "ory_Orya" -> Language.ODIA
            else -> Language.ENGLISH
        }
        return toColloquial(text, lang, srcText)
    }

    /**
     * Normalizes informal/colloquial speech into formal literary Indic text.
     * Essential for feeding on-device neural translators (ML Kit) and grammar parsers
     * so colloquial voice transcriptions (e.g. "எல்லாரும் எங்க இருக்கீங்க")
     * map cleanly into standard sentence structures ("அனைவரும் எங்கே இருக்கிறீர்கள்").
     */
    fun toFormal(text: String, lang: Language): String {
        if (text.isBlank()) return text
        return when (lang) {
            Language.TAMIL -> formalTamil(text)
            Language.HINDI -> formalHindi(text)
            Language.TELUGU -> formalTelugu(text)
            Language.KANNADA -> formalKannada(text)
            Language.MALAYALAM -> formalMalayalam(text)
            else -> text
        }
    }

    fun toFormalByCode(text: String, langCode: String): String {
        val lang = when (langCode) {
            "tam_Taml" -> Language.TAMIL
            "hin_Deva" -> Language.HINDI
            "tel_Telu" -> Language.TELUGU
            "mal_Mlym" -> Language.MALAYALAM
            "kan_Knda" -> Language.KANNADA
            "ben_Beng" -> Language.BENGALI
            "mar_Deva" -> Language.MARATHI
            "guj_Gujr" -> Language.GUJARATI
            "pan_Guru" -> Language.PUNJABI
            "ory_Orya" -> Language.ODIA
            else -> Language.ENGLISH
        }
        return toFormal(text, lang)
    }

    private fun formalTamil(text: String): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("(?i)ஹாய்,\\s*எப்படி இருக்கீங்க\\?"), "வணக்கம், நீங்கள் எப்படி இருக்கிறீர்கள்?"),
            Pair(Regex("எல்லாரும் எங்க இருக்கீங்க\\??"), "அனைவரும் எங்கே இருக்கிறீர்கள்?"),
            Pair(Regex("எல்லாரும் எப்படி இருக்கீங்க\\??"), "அனைவரும் எப்படி இருக்கிறீர்கள்?"),
            Pair(Regex("எல்லாரும் பத்திரமா இருக்கீங்களா\\??"), "அனைவரும் பாதுகாப்பாக இருக்கிறீர்களா?"),
            Pair(Regex("எப்படி இருக்கீங்க\\?"), "நீங்கள் எப்படி இருக்கிறீர்கள்?"),
            Pair(Regex("என்ன பண்றீங்க\\?"), "நீங்கள் என்ன செய்கிறீர்கள்?"),
            Pair(Regex("என்ன பண்ற\\?"), "நீ என்ன செய்கிறாய்?"),
            Pair(Regex("எங்க போறீங்க\\?"), "நீங்கள் எங்கே போகிறீர்கள்?"),
            Pair(Regex("எங்க போற\\?"), "நீ எங்கே போகிறாய்?"),
            Pair(Regex("சாப்பிட்டீங்களா\\?"), "சாப்பிட்டீர்களா?"),
            Pair(Regex("சாப்பிட்டியா\\?"), "சாப்பிட்டாயா?"),
            Pair(Regex("உங்களுக்கு என்ன வேணும்\\?"), "உங்களுக்கு என்ன வேண்டும்?"),
            Pair(Regex("உனக்கு என்ன வேணும்\\?"), "உனக்கு என்ன வேண்டும்?"),
            Pair(Regex("எனக்கு புரியல"), "எனக்கு புரியவில்லை"),
            Pair(Regex("பரவால்ல"), "பரவாயில்லை"),
            Pair(Regex("கவலைப்படாதீங்க"), "கவலைப்படாதீர்கள்"),
            Pair(Regex("கவலைப்படாத"), "கவலைப்படாதே"),
            Pair(Regex("பயப்படாதீங்க"), "பயப்படாதீர்கள்"),
            Pair(Regex("சீக்கிரம் வாங்க"), "விரைவாக வாருங்கள்"),
            Pair(Regex("நாங்க வரோம்"), "நாங்கள் வருகிறோம்"),
            Pair(Regex("நாங்க நல்லா இருக்கோம்"), "நாங்கள் நலமாக இருக்கிறோம்"),
            Pair(Regex("ரொம்ப தேங்க்ஸ்"), "மிக்க நன்றி"),
            Pair(Regex("தேங்க்ஸ்"), "நன்றி")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("எல்லாரும்"), "அனைவரும்"),
            Pair(wb("நீங்க"), "நீங்கள்"),
            Pair(wb("அவங்க"), "அவர்கள்"),
            Pair(wb("இவங்க"), "இவர்கள்"),
            Pair(wb("நாங்க"), "நாங்கள்"),
            Pair(wb("என்னோட"), "என்னுடைய"),
            Pair(wb("உங்களோட"), "உங்களுடைய"),
            Pair(wb("அவரோட"), "அவருடைய"),
            Pair(wb("அவங்களோட"), "அவர்களுடைய"),
            Pair(wb("அவ"), "அவள்"),
            Pair(wb("எங்க"), "எங்கே"),
            Pair(wb("இங்க"), "இங்கே"),
            Pair(wb("அங்க"), "அங்கே"),
            Pair(wb("எதுக்கு"), "எதற்கு"),
            Pair(wb("எப்போ"), "எப்போது"),
            Pair(wb("இப்போ"), "இப்போது"),
            Pair(wb("அப்போ"), "அப்போது"),
            Pair(wb("எவ்ளோ"), "எவ்வளவு"),
            Pair(wb("அவ்ளோ"), "அவ்வளவு"),
            Pair(wb("இவ்ளோ"), "இவ்வளவு"),
            Pair(wb("இல்ல"), "இல்லை"),
            Pair(wb("வேணும்"), "வேண்டும்"),
            Pair(Regex("இருக்கீங்க"), "இருக்கிறீர்கள்"),
            Pair(Regex("இருக்கேன்"), "இருக்கிறேன்"),
            Pair(Regex("இருக்காரு"), "இருக்கிறார்"),
            Pair(Regex("இருக்காங்க"), "இருக்கிறார்கள்"),
            Pair(Regex("பண்றீங்க"), "செய்கிறீர்கள்"),
            Pair(Regex("பண்றேன்"), "செய்கிறேன்"),
            Pair(Regex("பண்றாரு"), "செய்கிறார்"),
            Pair(Regex("பண்றாங்க"), "செய்கிறார்கள்"),
            Pair(Regex("வர்றீங்க"), "வருகிறீர்கள்"),
            Pair(Regex("வர்றேன்"), "வருகிறேன்"),
            Pair(Regex("வர்றாரு"), "வருகிறார்"),
            Pair(Regex("போறீங்க"), "போகிறீர்கள்"),
            Pair(Regex("போறேன்"), "போகிறேன்"),
            Pair(Regex("போறாரு"), "போகிறார்"),
            Pair(Regex("பாக்குறேன்"), "பார்க்கிறேன்"),
            Pair(Regex("பாக்குறீங்க"), "பார்க்கிறீர்கள்"),
            Pair(Regex("சொல்றேன்"), "சொல்கிறேன்"),
            Pair(Regex("சொல்றீங்க"), "சொல்கிறீர்கள்"),
            Pair(Regex("தர்றேன்"), "கொடுக்கிறேன்")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }
        return t
    }

    private fun formalHindi(text: String): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("सब लोग कहाँ हो\\??"), "आप सब कहाँ हैं?"),
            Pair(Regex("सब लोग कहाँ हैं\\??"), "आप सब कहाँ हैं?"),
            Pair(Regex("कहाँ हो भाई\\??"), "आप कहाँ हैं?"),
            Pair(Regex("कहाँ हो\\??"), "आप कहाँ हैं?"),
            Pair(Regex("क्या कर रहे हो\\??"), "आप क्या कर रहे हैं?"),
            Pair(Regex("कहाँ जा रहे हो\\??"), "आप कहाँ जा रहे हैं?"),
            Pair(Regex("टेंशन मत लो"), "चिंता मत करो"),
            Pair(Regex("मुझे नहीं पता"), "मुझे मालूम नहीं है"),
            Pair(Regex("समझ नहीं आया yaar"), "मुझे समझ में नहीं आया"),
            Pair(Regex("थैंक्स yaar"), "धन्यवाद"),
            Pair(Regex("थैंक्स"), "धन्यवाद")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }
        val wordMap = listOf(
            Pair(wb("कहाँ हो"), "कहाँ हैं"),
            Pair(wb("कर रहे हो"), "कर रहे हैं"),
            Pair(wb("जा रहे हो"), "जा रहे हैं")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }
        return t
    }

    private fun formalTelugu(text: String): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("అందరూ ఎక్కడున్నారు\\??"), "అందరూ ఎక్కడ ఉన్నారు?"),
            Pair(Regex("ఎక్కడున్నారు\\??"), "ఎక్కడ ఉన్నారు?"),
            Pair(Regex("ఏం చేస్తున్నారు\\??"), "మీరు ఏమి చేస్తున్నారు?"),
            Pair(Regex("టెన్షన్ పడకండి"), "ఆందోళన చెందకండి")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }
        return t
    }

    private fun formalKannada(text: String): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("ಎಲ್ಲರೂ ಎಲ್ಲಿದ್ದಾರೆ\\??"), "ಎಲ್ಲರೂ ಎಲ್ಲಿ ಇದ್ದಾರೆ?"),
            Pair(Regex("ಏನ್ ಮಾಡ್ತಿದ್ದೀರಾ\\??"), "ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ?"),
            Pair(Regex("ಟೆನ್ಷನ್ ಬೇಡ"), "ಚಿಂತೆ ಮಾಡಬೇಡಿ")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }
        return t
    }

    private fun formalMalayalam(text: String): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("എല്ലാവരും എവിടെയാ\\??"), "എല്ലാവരും എവിടെയാണ്?"),
            Pair(Regex("എന്താ ചെയ്യുന്നേ\\??"), "നിങ്ങൾ എന്താണ് ചെയ്യുന്നത്?"),
            Pair(Regex("ടെൻഷൻ വേണ്ട"), "വിഷമിക്കേണ്ട")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }
        return t
    }

    private fun colloquialTamil(text: String, _isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("வணக்கம்,\\s*நீங்கள் எப்படி இருக்கிறீர்கள்\\??"), "வணக்கம், எப்படி இருக்கீங்க?"),
            Pair(Regex("நீங்கள் எப்படி இருக்கிறீர்கள்\\??"), "எப்படி இருக்கீங்க?"),
            Pair(Regex("நீங்கள் என்ன செய்கிறீர்கள்\\??"), "என்ன பண்றீங்க?"),
            Pair(Regex("நீ என்ன செய்கிறாய்\\??"), "என்ன பண்ற?"),
            Pair(Regex("நீங்கள் எங்கே போகிறீர்கள்\\??"), "எங்க போறீங்க?"),
            Pair(Regex("நீ எங்கே போகிறாய்\\??"), "எங்க போற?"),
            Pair(Regex("சாப்பிட்டீர்களா\\??"), "சாப்பிட்டீங்களா?"),
            Pair(Regex("சாப்பிட்டாயா\\??"), "சாப்பிட்டியா?"),
            Pair(Regex("உங்களுக்கு என்ன வேண்டும்\\??"), "உங்களுக்கு என்ன வேணும்?"),
            Pair(Regex("உனக்கு என்ன வேண்டும்\\??"), "உனக்கு என்ன வேணும்?"),
            Pair(Regex("எனக்கு புரியவில்லை"), "எனக்கு புரியல"),
            Pair(Regex("பரவாயில்லை"), "பரவால்ல"),
            Pair(Regex("கவலைப்படாதீர்கள்"), "கவலைப்படாதீங்க"),
            Pair(Regex("கவலைப்படாதே"), "கவலைப்படாத"),
            Pair(Regex("போய் வருகிறேன்"), "போயிட்டு வர்றேன்"),
            Pair(Regex("சந்திப்போம்"), "பாக்கலாம்")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }


        val wordMap = listOf(
            Pair(wb("நீங்கள்"), "நீங்க"),
            Pair(wb("அவர்கள்"), "அவங்க"),
            Pair(wb("இவர்கள்"), "இவங்க"),
            Pair(wb("நாங்கள்"), "நாங்க"),
            Pair(wb("என்னுடைய"), "என்னோட"),
            Pair(wb("உங்களுடைய"), "உங்களோட"),
            Pair(wb("அவருடைய"), "அவரோட"),
            Pair(wb("அவர்களுடைய"), "அவங்களோட"),
            Pair(wb("அவள்"), "அவ"),
            Pair(wb("எங்கே"), "எங்க"),
            Pair(wb("இங்கே"), "இங்க"),
            Pair(wb("அங்கே"), "அங்க"),
            Pair(wb("எதற்கு"), "எதுக்கு"),
            Pair(wb("எப்பொழுது"), "எப்போ"),
            Pair(wb("எப்போது"), "எப்போ"),
            Pair(wb("இப்பொழுது"), "இப்போ"),
            Pair(wb("இப்போது"), "இப்போ"),
            Pair(wb("அப்பொழுது"), "அப்போ"),
            Pair(wb("அப்போது"), "அப்போ"),
            Pair(wb("எவ்வளவு"), "எவ்ளோ"),
            Pair(wb("அவ்வளவு"), "அவ்ளோ"),
            Pair(wb("இவ்வளவு"), "இவ்ளோ"),
            Pair(wb("இல்லை"), "இல்ல"),
            Pair(wb("வேண்டும்"), "வேணும்"),
            Pair(Regex("இருக்கிறீர்கள்"), "இருக்கீங்க"),
            Pair(Regex("இருக்கிறேன்"), "இருக்கேன்"),
            Pair(Regex("இருக்கிறார்"), "இருக்காரு"),
            Pair(Regex("இருக்கிறார்கள்"), "இருக்காங்க"),
            Pair(Regex("செய்கிறீர்கள்"), "பண்றீங்க"),
            Pair(Regex("செய்கிறேன்"), "பண்றேன்"),
            Pair(Regex("செய்கிறார்"), "பண்றாரு"),
            Pair(Regex("செய்கிறார்கள்"), "பண்றாங்க"),
            Pair(Regex("வருகிறீர்கள்"), "வர்றீங்க"),
            Pair(Regex("வருகிறேன்"), "வர்றேன்"),
            Pair(Regex("வருகிறார்"), "வர்றாரு"),
            Pair(Regex("போகிறீர்கள்"), "போறீங்க"),
            Pair(Regex("போகிறேன்"), "போறேன்"),
            Pair(Regex("போகிறார்"), "போறாரு"),
            Pair(Regex("பார்க்கிறேன்"), "பாக்குறேன்"),
            Pair(Regex("பார்க்கிறீர்கள்"), "பாக்குறீங்க"),
            Pair(Regex("சொல்கிறேன்"), "சொல்றேன்"),
            Pair(Regex("சொல்கிறீர்கள்"), "சொல்றீங்க"),
            Pair(Regex("கொடுக்கிறேன்"), "தர்றேன்")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }
        return t
    }

    private fun colloquialHindi(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("नमस्ते,\\s*आप कैसे हैं\\?"), "हाय, क्या हाल है?"),
            Pair(Regex("आप कैसे हैं\\?"), "कैसे हो भाई?"),
            Pair(Regex("तुम कैसे हो\\?"), "क्या हाल चाल?"),
            Pair(Regex("आप क्या कर रहे हैं\\?"), "क्या कर रहे हो?"),
            Pair(Regex("आप कहाँ जा रहे हैं\\?"), "कहाँ जा रहे हो?"),
            Pair(Regex("मुझे ज्ञात नहीं है"), "मुझे नहीं पता"),
            Pair(Regex("मुझे मालूम नहीं है"), "मुझे नहीं पता"),
            Pair(Regex("मुझे समझ में नहीं आया"), "समझ नहीं आया यार"),
            Pair(Regex("चिंता मत करो"), "टेंशन मत लो"),
            Pair(Regex("चिंता न करें"), "टेंशन मत लो"),
            Pair(Regex("बहुत धन्यवाद"), "बहुत बहुत शुक्रिया"),
            Pair(Regex("धन्यवाद"), "थैंक्स यार"),
            Pair(Regex("कृपया"), "प्लीज़"),
            Pair(Regex("क्षमा करें"), "सॉरी"),
            Pair(Regex("मुझे खेद है"), "सॉरी यार"),
            Pair(Regex("अलविदा"), "बाय"),
            Pair(Regex("शुभ रात्रि"), "गुड नाइट"),
            Pair(Regex("कोई बात नहीं"), "कोई बात नहीं, चिल कर")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("अत्यंत"), "बहुत"),
            Pair(wb("किंतु"), "लेकिन"),
            Pair(wb("परंतु"), "लेकिन"),
            Pair(wb("तथा"), "और"),
            Pair(wb("यद्यपि"), "हालांकि"),
            Pair(wb("भोजन"), "खाना"),
            Pair(wb("जल"), "पानी"),
            Pair(wb("गृह"), "घर"),
            Pair(wb("मित्र"), "दोस्त"),
            Pair(wb("प्रसन्न"), "खुश"),
            Pair(wb("तुरंत"), "फटाफट"),
            Pair(wb("निवास"), "घर")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("नमस्ते").pattern).replace(t, "हाय")
        }
        return t
    }

    private fun colloquialTelugu(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("నమస్కారం,\\s*మీరు ఎలా ఉన్నారు\\?"), "హాయ్, ఎలా ఉన్నారు?"),
            Pair(Regex("మీరు ఎలా ఉన్నారు\\?"), "ఎలా ఉన్నారు? ఏంటి సంగతులు?"),
            Pair(Regex("నువ్వు ఎలా ఉన్నావు\\?"), "ఎలా ఉన్నావ్?"),
            Pair(Regex("ఏమి చేస్తున్నారు\\?"), "ఏం చేస్తున్నావ్?"),
            Pair(Regex("మీరు ఏమి చేస్తున్నారు\\?"), "ఏం చేస్తున్నారు?"),
            Pair(Regex("ఎక్కడికి వెళ్తున్నారు\\?"), "ఎక్కడికి వెళ్తున్నారు?"),
            Pair(Regex("నాకు అర్థం కాలేదు"), "నాకు అర్థం కాలేదు రా"),
            Pair(Regex("నాకు తెలియదు"), "నాకు తెలీదు"),
            Pair(Regex("చింతించకండి"), "టెన్షన్ పడకండి"),
            Pair(Regex("ధన్యవాదాలు"), "థాంక్స్"),
            Pair(Regex("క్షమించండి"), "సారీ"),
            Pair(Regex("దయచేసి"), "ప్లీజ్"),
            Pair(Regex("సెలవు"), "బై"),
            Pair(Regex("శుభరాత్రి"), "గుడ్ నైట్"),
            Pair(Regex("చాలా బాగుంది"), "సూపర్")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("మిత్రుడు"), "ఫ్రెండ్"),
            Pair(wb("గృహం"), "ఇల్లు"),
            Pair(wb("భోజనం"), "తిండి"),
            Pair(wb("జలం"), "నీళ్ళు"),
            Pair(wb("అత్యంత"), "చాలా"),
            Pair(wb("ఏమిటి"), "ఏంటి")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("నమస్కారం").pattern).replace(t, "హాయ్")
        }
        return t
    }

    private fun colloquialMalayalam(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("നമസ്കാരം,\\s*നിങ്ങൾ എങ്ങനെയിരിക്കുന്നു\\?"), "ഹായ്, സുഖമാണോ?"),
            Pair(Regex("നിങ്ങൾ എങ്ങനെയിരിക്കുന്നു\\?"), "എങ്ങനെയുണ്ട്? സുഖമാണോ?"),
            Pair(Regex("എന്തുചെയ്യുന്നു\\?"), "എന്തൊക്കെയുണ്ട് വിശേഷം?"),
            Pair(Regex("എനിക്ക് അറിയില്ല"), "എനിക്കറിയില്ല"),
            Pair(Regex("വിഷമിക്കേണ്ട"), "ടെൻഷൻ അടിക്കണ്ട"),
            Pair(Regex("നന്ദി"), "താങ്ക്സ്"),
            Pair(Regex("ക്ഷമിക്കണം"), "സോറി"),
            Pair(Regex("ദയവായി"), "പ്ലീസ്"),
            Pair(Regex("വളരെ നന്ദി"), "വളരെ താങ്ക്സ്"),
            Pair(Regex("വിട"), "ബൈ"),
            Pair(Regex("ശുഭരാത്രി"), "ഗുഡ് നൈറ്റ്")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("ഭവനം"), "വീട്"),
            Pair(wb("മിത്രം"), "കൂട്ടുകാരൻ"),
            Pair(wb("ജലം"), "വെള്ളം"),
            Pair(wb("അതിവേഗം"), "പെട്ടെന്ന്")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("നമസ്കാരം").pattern).replace(t, "ഹായ്")
        }
        return t
    }

    private fun colloquialKannada(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("ನಮಸ್ಕಾರ,\\s*ನೀವು ಹೇಗಿದ್ದೀರಿ\\?"), "ಹಾಯ್, ಹೇಗಿದ್ದೀರಾ?"),
            Pair(Regex("ನೀವು ಹೇಗಿದ್ದೀರಿ\\?"), "ಹೇಗಿದ್ದೀರಾ? ಏನ್ ಸಮಾಚಾರ?"),
            Pair(Regex("ನೀನು ಹೇಗಿದ್ದೀಯಾ\\?"), "ಹೇಗಿದ್ದೀಯಾ?"),
            Pair(Regex("ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ\\?"), "ಏನ್ ಮಾಡ್ತಿದ್ದೀರಾ?"),
            Pair(Regex("ನೀವು ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ\\?"), "ಏನ್ ಮಾಡ್ತಿದ್ದೀರಿ?"),
            Pair(Regex("ನನಗೆ ಗೊತ್ತಿಲ್ಲ"), "ನನಗೆ ಗೊತ್ತಿಲ್ಲಪ್ಪ"),
            Pair(Regex("ಚಿಂತಿಸಬೇಡಿ"), "ಟೆನ್ಷನ್ ತಗೋಬೇಡಿ"),
            Pair(Regex("ಧನ್ಯವಾದಗಳು"), "ಥ್ಯಾಂಕ್ಸ್"),
            Pair(Regex("ಕ್ಷಮಿಸಿ"), "ಸಾರಿ"),
            Pair(Regex("ದಯವಿಟ್ಟು"), "ಪ್ಲೀಸ್"),
            Pair(Regex("ವಿದಾಯ"), "ಬೈ"),
            Pair(Regex("ಶುಭ ರಾತ್ರಿ"), "ಗುಡ್ ನೈಟ್"),
            Pair(Regex("ತುಂಬಾ ಧನ್ಯವಾದಗಳು"), "ತುಂಬಾ ಥ್ಯಾಂಕ್ಸ್")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("ಸ್ನೇಹಿತ"), "ಫ್ರೆಂಡ್"),
            Pair(wb("ಆಹಾರ"), "ಊಟ"),
            Pair(wb("ಜಲ"), "ನೀರು"),
            Pair(wb("ಅತ್ಯಂತ"), "ತುಂಬಾ"),
            Pair(wb("ಏನು"), "ಏನ್"),
            Pair(Regex("ಬರುತ್ತೀರಾ\\?"), "ಬರ್ತೀರಾ?"),
            Pair(Regex("ಹೋಗುತ್ತೀರಾ\\?"), "ಹೋಗ್ತೀರಾ?"),
            Pair(Regex("ನೋಡುತ್ತೀರಾ\\?"), "ನೋಡ್ತೀರಾ?")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("ನಮಸ್ಕಾರ").pattern).replace(t, "ಹಾಯ್")
        }
        return t
    }

    private fun colloquialBengali(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("নমস্কার,\\s*আপনি কেমন আছেন\\?"), "হাই, কেমন আছ?"),
            Pair(Regex("আপনি কেমন আছেন\\?"), "কী খবর? কেমন আছো?"),
            Pair(Regex("তুমি কেমন আছো\\?"), "কী খবর? কেমন আছিস?"),
            Pair(Regex("আপনি কী করছেন\\?"), "কী করছ?"),
            Pair(Regex("তুমি কী করছো\\?"), "কী করছিস?"),
            Pair(Regex("আমি জানি না"), "আমার জানা নেই রে"),
            Pair(Regex("আমি বুঝতে পারিনি"), "আমি বুঝতে পারিনি রে"),
            Pair(Regex("চিন্তা করবেন না"), "টেনশন নিও না"),
            Pair(Regex("ধন্যবাদ"), "থ্যাঙ্কস"),
            Pair(Regex("দুঃখিত"), "সরি"),
            Pair(Regex("দয়া করে"), "প্লিজ"),
            Pair(Regex("বিদায়"), "বাই"),
            Pair(Regex("শুভ রাত্রি"), "গুড নাইট"),
            Pair(Regex("অনেক ধন্যবাদ"), "অনেক থ্যাঙ্কস")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("অত্যন্ত"), "অনেক"),
            Pair(wb("আহার"), "খাওয়া"),
            Pair(wb("সত্বর"), "তাড়াতাড়ি"),
            Pair(wb("গৃহ"), "বাড়ি")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("নমস্কার").pattern).replace(t, "হাই")
        }
        return t
    }

    private fun colloquialMarathi(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("नमस्कार,\\s*तुम्ही कसे आहात\\?"), "हाय, काय चाललंय?"),
            Pair(Regex("तुम्ही कसे आहात\\?"), "काय मग, कसं काय?"),
            Pair(Regex("तू कसा आहेस\\?"), "काय चाललंय भावा?"),
            Pair(Regex("तुम्ही काय करत आहात\\?"), "काय करताय?"),
            Pair(Regex("तू काय करत आहेस\\?"), "काय करतोयस?"),
            Pair(Regex("मला माहित नाही"), "मला काय माहीत नाही"),
            Pair(Regex("मला समजले नाही"), "मला काय समजलं नाही"),
            Pair(Regex("काळजी करू नका"), "टेन्शन घेऊ नका"),
            Pair(Regex("धन्यवाद"), "थँक्स"),
            Pair(Regex("माफ करा"), "सॉरी"),
            Pair(Regex("कृपया"), "प्लीज"),
            Pair(Regex("निरोप"), "बाय"),
            Pair(Regex("शुभ रात्री"), "गुड नाईट"),
            Pair(Regex("खूप आभारी आहे"), "खूप थँक्स")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("गृह"), "घर"),
            Pair(wb("अन्न"), "जेवण"),
            Pair(wb("अत्यंत"), "खूप"),
            Pair(wb("त्वरित"), "पटकन")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("नमस्कार").pattern).replace(t, "हाय")
        }
        return t
    }

    private fun colloquialGujarati(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("નમસ્તે,\\s*તમે કેમ છો\\?"), "હાય, કેમ છો? શું હાલે?"),
            Pair(Regex("તમે કેમ છો\\?"), "શું હાલે ભાઈ? કેમ છો?"),
            Pair(Regex("તું કેમ છે\\?"), "શું ચાલે છે?"),
            Pair(Regex("તમે શું કરી રહ્યા છો\\?"), "શું કરો છો?"),
            Pair(Regex("મને ખબર નથી"), "મને નથી ખબર"),
            Pair(Regex("મને સમજાયું નહીં"), "મને કંઈ સમજાયું નહીં"),
            Pair(Regex("ચિંતા કરશો નહીં"), "ટેન્શન ના લો"),
            Pair(Regex("આભાર"), "થેંક્સ"),
            Pair(Regex("માફ કરશો"), "સોરી"),
            Pair(Regex("કૃપા કરીને"), "પ્લીઝ"),
            Pair(Regex("આવજો"), "બાય"),
            Pair(Regex("શુભ રાત્રી"), "ગુડ નાઈટ"),
            Pair(Regex("ખૂબ ખૂબ આભાર"), "ખૂબ થેંક્સ")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("મિત્ર"), "દોસ્ત"),
            Pair(wb("ભોજન"), "જમવાનું"),
            Pair(wb("જળ"), "પાણી"),
            Pair(wb("અત્યંત"), "બહુ"),
            Pair(wb("ત્વરિત"), "જલ્દી")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("નમસ્તે").pattern).replace(t, "હાય")
        }
        return t
    }

    private fun colloquialPunjabi(text: String, isCasualSrc: Boolean): String {
        var t = text
        val phraseMap = listOf(
            Pair(Regex("ਸਤ ਸ੍ਰੀ ਅਕਾਲ,\\s*ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ\\?"), "ਹੈਲੋ, ਕੀ ਹਾਲ ਹੈ ਜੀ?"),
            Pair(Regex("ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ\\?"), "ਕੀ ਹਾਲ ਚਾਲ ਹੈ?"),
            Pair(Regex("ਤੂੰ ਕਿਵੇਂ ਹੈਂ\\?"), "ਸਭ ਠੀਕ ਠਾਕ?"),
            Pair(Regex("ਤੁਸੀਂ ਕੀ ਕਰ ਰਹੇ ਹੋ\\?"), "ਕੀ ਚੱਲ ਰਿਹਾ ਹੈ?"),
            Pair(Regex("ਤੂੰ ਕੀ ਕਰ ਰਿਹਾ ਹੈਂ\\?"), "ਕੀ ਕਰਦਾ ਪਿਆ ਹੈਂ?"),
            Pair(Regex("ਮੈਨੂੰ ਨਹੀਂ ਪਤਾ"), "ਮੈਨੂੰ ਨੀ ਪਤਾ"),
            Pair(Regex("ਚਿੰਤਾ ਨਾ ਕਰੋ"), "ਟੈਂਸ਼ਨ ਨਾ ਲੈ"),
            Pair(Regex("ਧੰਨਵਾਦ"), "ਥੈਂਕਸ ਵੀਰੇ"),
            Pair(Regex("ਮੁਆਫ਼ ਕਰਨਾ"), "ਸੌਰੀ"),
            Pair(Regex("ਮਾਫ਼ ਕਰਨਾ"), "ਸੌਰੀ"),
            Pair(Regex("ਕਿਰਪਾ ਕਰਕੇ"), "ਪਲੀਜ਼"),
            Pair(Regex("ਅਲਵਿਦਾ"), "ਬਾਏ"),
            Pair(Regex("ਸ਼ੁਭ ਰਾਤ"), "ਗੁੱਡ ਨਾਈਟ"),
            Pair(Regex("ਬਹੁਤ ਬਹੁਤ ਧੰਨਵਾਦ"), "ਬਹੁਤ ਥੈਂਕਸ ਵੀਰੇ"),
            Pair(Regex("ਕੋਈ ਗੱਲ ਨਹੀਂ"), "ਕੋਈ ਨਹੀਂ")
        )
        for ((p, r) in phraseMap) {
            t = p.replace(t, r)
        }

        val wordMap = listOf(
            Pair(wb("ਮਿੱਤਰ"), "ਯਾਰ"),
            Pair(wb("ਭੋਜਨ"), "ਖਾਣਾ"),
            Pair(wb("ਜਲ"), "ਪਾਣੀ"),
            Pair(wb("ਅਤਿਅੰਤ"), "ਬਹੁਤ"),
            Pair(wb("ਨਿਵਾਸ"), "ਘਰ")
        )
        for ((p, r) in wordMap) {
            t = p.replace(t, r)
        }

        if (isCasualSrc) {
            t = Regex("^" + endWb("ਸਤ ਸ੍ਰੀ ਅਕਾਲ").pattern).replace(t, "ਹੈਲੋ")
        }
        return t
    }
}
