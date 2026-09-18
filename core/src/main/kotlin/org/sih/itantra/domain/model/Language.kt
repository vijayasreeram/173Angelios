package org.sih.itantra.domain.model

/**
 * 10 Indian languages required by the SIH26173 Problem Statement.
 * Each language has a single-byte ID (0-9) for ultra-compact packet headers.
 */
enum class Language(
    val id: Byte,
    val code: String,
    val displayName: String,
    val nativeName: String
) {
    HINDI(0, "hi", "Hindi", "हिन्दी"),
    TAMIL(1, "ta", "Tamil", "தமிழ்"),
    TELUGU(2, "te", "Telugu", "తెలుగు"),
    KANNADA(3, "kn", "Kannada", "ಕನ್ನಡ"),
    MALAYALAM(4, "ml", "Malayalam", "മലയാളം"),
    BENGALI(5, "bn", "Bengali", "বাংলা"),
    MARATHI(6, "mr", "Marathi", "मराठी"),
    GUJARATI(7, "gu", "Gujarati", "ગુજરાતી"),
    PUNJABI(8, "pa", "Punjabi", "ਪੰਜਾਬੀ"),
    ENGLISH(9, "en", "English", "English"),
    ODIA(10, "or", "Odia", "ଓଡ଼ିଆ");

    companion object {
        fun fromId(id: Byte): Language = entries.firstOrNull { it.id == id } ?: ENGLISH
        fun fromCode(code: String): Language = entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
    }
}
