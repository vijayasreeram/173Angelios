package org.sih.itantra.ml.semantic

import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.Priority
import org.sih.itantra.domain.model.SemanticIntent
import org.sih.itantra.domain.model.SemanticMessage

/**
 * On-device NLP Semantic Extraction Engine.
 * Extracts high-level intent and structured named entities from transcript in any of the 10 languages.
 */
class SemanticEncoder {

    fun encode(
        transcript: String,
        sourceLanguage: Language = Language.ENGLISH,
        targetLanguage: Language = Language.ENGLISH
    ): SemanticMessage {
        val lower = transcript.lowercase()

        // 1. Identify Intent
        val (intent, detectedPriority) = when {
            containsAny(lower, "emergency", "medical", "doctor", "आपातकाल", "चिकित्सा", "அவசரம்", "மருத்துவம்", "மருத்துவர்", "అత్యవసరం", "ತುರ್ತು", "അടിയന്തരാവസ്ഥ", "জরুরী", "तातडीची", "કટોકટી", "ਐਮਰਜੈਂਸੀ", "injured", "घायल", "காயம்", "గాయపడ్డాడు", "casualties", "हताहत") -> {
                SemanticIntent.EMERGENCY_MEDICAL to Priority.EMERGENCY
            }
            containsAny(lower, "rescue", "trapped", "बचाव", "மீட்பு", "రక్షణ", "രക്ഷാപ്രവർത്തനം", "উদ্ধার", "flood", "बाढ़", "வெள்ளம்", "fire", "आग", "தீ") -> {
                SemanticIntent.RESCUE_REQUEST to Priority.EMERGENCY
            }
            containsAny(lower, "water", "food", "medicine", "shortage", "कमी", "தட்டுப்பாடு", "కొరత", "ಕೊರತೆ", "ദൗർലഭ്യം", "ঘাটতি", "तुटवडा", "અછત", "ਘਾਟ", "oxygen") -> {
                SemanticIntent.RESOURCE_SHORTAGE to Priority.IMPORTANT
            }
            containsAny(lower, "route clear", "safe", "मार्ग साफ़", "வழி திறந்துள்ளது", "సురక్షితం", "ರಸ್ತೆ ಸ್ಪಷ್ಟ", "सुरक्षित", "સાફ") -> {
                SemanticIntent.ROUTE_CLEAR to Priority.NORMAL
            }
            containsAny(lower, "all clear", "सब ठीक", "அனைத்தும் சரி", "ಅಂತಾ ಕ್ಷೇಮ", "সব ঠিক", "બધું સલામત") -> {
                SemanticIntent.ALL_CLEAR to Priority.NORMAL
            }
            containsAny(lower, "status", "report", "स्थिति", "அறிக்கை", "നിവേదిక", "ವರದಿ") -> {
                SemanticIntent.STATUS_CHECK to Priority.NORMAL
            }
            containsAny(lower, "test", "testing", "hello", "hi", "radio check", "check", "ஹலோ", "சோதனை", "வணக்கம்", "नमस्ते", "परीक्षण", "चेक") -> {
                SemanticIntent.TEST_COMMUNICATION to Priority.NORMAL
            }
            containsAny(lower, "copy", "roger", "acknowledged", "understood", "okay", "ok", "சரி", "புரிந்தது", "ठीक है", "समझ गया") -> {
                SemanticIntent.ACKNOWLEDGEMENT to Priority.NORMAL
            }
            else -> SemanticIntent.GENERAL_REPORT to Priority.NORMAL
        }

        // 2. Extract Named Entities
        val entities = mutableMapOf<String, String>()

        // Location Extraction
        when {
            containsAny(lower, "north checkpoint", "उत्तर चेकपॉइंट", "வடக்கு சோதனைச் சாவடி", "ఉత్తర చెక్‌పోస్ట్", "ಉತ್ತರ ಚೆಕ್‌ಪೋಸ್ಟ್", "വടക്കൻ ചെക്ക്പോസ്റ്റ്", "উত্তর চেকপয়েন্ট") -> {
                entities["location"] = "north_checkpoint"
            }
            containsAny(lower, "flooded bridge", "bridge", "पुल", "பாலும்", "వంతెన", "ಸೇತುವೆ", "പാലം", "ব্রিজ") -> {
                entities["location"] = "flooded_bridge"
            }
            containsAny(lower, "base camp", "camp", "बेस कैंप", "முகாம்", "బేస్ క్యాంప్", "ಬೇಸ್ ಕ್ಯಾಂಪ್", "ബേസ് ക്യാമ്പ്") -> {
                entities["location"] = "base_camp"
            }
            containsAny(lower, "sector four", "sector 4", "सेक्टर चार", "பிரிவு நான்கு", "సెక్టార్ నాలుగు") -> {
                entities["location"] = "sector_4"
            }
            containsAny(lower, "sector three", "sector 3", "सेक्टर तीन", "பிரிவு மூன்று", "సెక్టార్ మూడు") -> {
                entities["location"] = "sector_3"
            }
            else -> entities["location"] = "current_coordinates"
        }

        // Casualty / Person Count Extraction
        when {
            containsAny(lower, "two", "2", "दो", "இருவர்", "ఇద్దరు", "ಇಬ್ಬರು", "രണ്ട്", "দুজন") -> {
                entities["count"] = "2"
                entities["entity_type"] = "person_injured"
            }
            containsAny(lower, "one", "1", "person", "एक", "ஒருவர்", "ఒకరు", "ಒಬ್ಬರು", "ഒരാൾ", "একজন") -> {
                entities["count"] = "1"
                entities["entity_type"] = "person_injured"
            }
            containsAny(lower, "multiple", "several", "अनेक", "பலர்", "చాలామంది") -> {
                entities["count"] = "multiple"
                entities["entity_type"] = "persons"
            }
        }

        // Resource Type Extraction
        when {
            containsAny(lower, "water", "पानी", "குடிநீர்", "నీరు", "ನೀರು", "വെള്ളം", "জল") -> {
                entities["resource"] = "drinking_water"
            }
            containsAny(lower, "oxygen", "ऑक्सीजन", "ஆக்ஸிஜன்") -> {
                entities["resource"] = "medical_oxygen"
            }
            containsAny(lower, "medicine", "दवा", "மருந்து", "మందులు", "ಔಷಧಿ") -> {
                entities["resource"] = "emergency_meds"
            }
        }

        return SemanticMessage(
            intent = intent,
            entities = entities,
            priority = detectedPriority,
            rawText = transcript,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage
        )
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        for (kw in keywords) {
            if (text.contains(kw, ignoreCase = true)) return true
        }
        return false
    }
}
