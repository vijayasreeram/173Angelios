package org.sih.itantra

import org.junit.Test
import org.sih.itantra.domain.model.Language
import org.sih.itantra.ml.translation.OfflineTranslatorEngine

class CrashInvestigationTest {

    private val engine = OfflineTranslatorEngine()

    @Test
    fun testLengthyStatementsAllLanguages() {
        val statements = listOf(
            "(we had lunch )",
            "we had lunch",
            "we had lunch today",
            "we had lunch and we are safe",
            "hello, we had lunch and we are resting now",
            "we had lunch, how are you doing?",
            "today we had lunch at the base camp and everyone is safe here",
            "we had lunch, please send more food and water to north checkpoint",
            "we had lunch; we are waiting for medical team",
            "(hello) [test] {we had lunch!}",
            "we had lunch... are you safe?",
            "we had lunch and we need water and medicine immediately"
        )

        val targetLanguages = listOf(
            Language.TAMIL,
            Language.HINDI,
            Language.TELUGU,
            Language.KANNADA,
            Language.MALAYALAM,
            Language.BENGALI,
            Language.MARATHI,
            Language.GUJARATI,
            Language.PUNJABI,
            Language.ODIA,
            Language.ENGLISH
        )

        for (stmt in statements) {
            for (tgt in targetLanguages) {
                try {
                    val res1 = engine.translate(stmt, tgt, Language.ENGLISH)
                    println("[$stmt] -> EN to ${tgt.name}: $res1")
                    val res2 = engine.translate(stmt, tgt, Language.TAMIL)
                    println("[$stmt] -> TA to ${tgt.name}: $res2")
                } catch (t: Throwable) {
                    System.err.println("CRASHED on statement: '$stmt' to ${tgt.name}: ${t.javaClass.name}: ${t.message}")
                    t.printStackTrace()
                    throw t
                }
            }
        }
    }

    @Test
    fun testWeHadLunchTranslations() {
        val statements = listOf("(we had lunch )", "we had lunch", "we had our lunch", "we ate lunch")
        for (stmt in statements) {
            val tamil = engine.translate(stmt, Language.TAMIL, Language.ENGLISH)
            println("Tamil: '$stmt' -> '$tamil'")
            org.junit.Assert.assertTrue("Tamil translation should contain சாப்பிட்டோம்: $tamil", tamil.contains("சாப்பிட்டோம்"))

            val hindi = engine.translate(stmt, Language.HINDI, Language.ENGLISH)
            println("Hindi: '$stmt' -> '$hindi'")
            org.junit.Assert.assertTrue("Hindi translation should contain भोजन or खाना: $hindi", hindi.contains("भोजन") || hindi.contains("खाना"))

            val telugu = engine.translate(stmt, Language.TELUGU, Language.ENGLISH)
            org.junit.Assert.assertTrue("Telugu translation should contain భోజనం or తిండి: $telugu", telugu.contains("భోజనం") || telugu.contains("తిండి"))
        }

        // Test reverse translation to English
        val backFromTamil = engine.translate("நாங்கள் மதிய உணவு சாப்பிட்டோம்", Language.ENGLISH, Language.TAMIL)
        org.junit.Assert.assertEquals("We had lunch", backFromTamil)

        val backFromHindi = engine.translate("हमने दोपहर का भोजन कर लिया", Language.ENGLISH, Language.HINDI)
        org.junit.Assert.assertEquals("We had lunch", backFromHindi)
    }

    @Test
    fun testUserReportedSentencesInAllLanguages() {
        val statements = listOf(
            "ஹரிசுக்கு மூளை குழம்பியது",
            "ஹரிசுக்கு மூளை குழம்பிடுச்சு",
            "விஜய் உடைய கால் வெட்டப்பட்டுள்ளது",
            "விஜய்க்கு கால் வெட்டிக்கிச்சு",
            "விஜய்க்கு கால் வெட்டப்பட்டது"
        )

        val targetLanguages = listOf(
            Language.ENGLISH,
            Language.TAMIL,
            Language.HINDI,
            Language.TELUGU,
            Language.KANNADA,
            Language.MALAYALAM,
            Language.BENGALI,
            Language.MARATHI,
            Language.GUJARATI,
            Language.PUNJABI,
            Language.ODIA
        )

        for (stmt in statements) {
            val isHaris = stmt.contains("ஹரி")
            val isVijay = stmt.contains("விஜய்")

            // Test 1: Translation to English
            val en = engine.translate(stmt, Language.ENGLISH, Language.TAMIL)
            println("[$stmt] -> EN: '$en'")
            if (isHaris) {
                org.junit.Assert.assertTrue("Haris sentence to EN must be 'Haris is confused': $en", en.contains("Haris is confused"))
            } else if (isVijay) {
                org.junit.Assert.assertTrue("Vijay sentence to EN must contain 'Vijay' and 'cut': $en", en.contains("Vijay") && en.contains("cut"))
            }

            // Test 2: Cross-translation to all other languages
            for (tgt in targetLanguages) {
                val res = engine.translate(stmt, tgt, Language.TAMIL)
                println("[$stmt] -> ${tgt.name}: '$res'")
                org.junit.Assert.assertTrue("Result must not be blank for ${tgt.name}", res.isNotBlank())
                if (tgt != Language.TAMIL) {
                    org.junit.Assert.assertNotEquals("Should not return unchanged raw text for ${tgt.name}", stmt, res)
                }
            }
        }
    }

    @Test
    fun testBidirectionalCrossLanguageTraumaTranslations() {
        val targetLanguages = listOf(
            Language.TAMIL,
            Language.HINDI,
            Language.TELUGU,
            Language.KANNADA,
            Language.MALAYALAM,
            Language.BENGALI,
            Language.MARATHI,
            Language.GUJARATI,
            Language.PUNJABI,
            Language.ODIA,
            Language.ENGLISH
        )

        // 1. English to all 10 Indic languages
        for (tgt in targetLanguages.filter { it != Language.ENGLISH }) {
            val harisTgt = engine.translate("Haris is confused", tgt, Language.ENGLISH)
            println("EN 'Haris is confused' -> ${tgt.name}: $harisTgt")
            org.junit.Assert.assertTrue("Haris translation to ${tgt.name} should not be blank", harisTgt.isNotBlank())

            val vijayTgt = engine.translate("Vijay's leg is cut", tgt, Language.ENGLISH)
            println("EN 'Vijay\\'s leg is cut' -> ${tgt.name}: $vijayTgt")
            org.junit.Assert.assertTrue("Vijay translation to ${tgt.name} should not be blank", vijayTgt.isNotBlank())
        }

        // 2. Hindi to Tamil, Telugu, Kannada, Malayalam, Bengali, etc.
        val hindiCut = "विजय का पैर कट गया है"
        val taFromHi = engine.translate(hindiCut, Language.TAMIL, Language.HINDI)
        println("HI '$hindiCut' -> TA: '$taFromHi'")
        org.junit.Assert.assertTrue("Hindi to Tamil should translate leg cut: $taFromHi", taFromHi.contains("கால்") || taFromHi.contains("வெட்ட"))

        val hindiConfused = "हरीश भ्रमित है"
        val teFromHi = engine.translate(hindiConfused, Language.TELUGU, Language.HINDI)
        println("HI '$hindiConfused' -> TE: '$teFromHi'")
        org.junit.Assert.assertTrue("Hindi to Telugu should translate confused: $teFromHi", teFromHi.contains("హరీష్") || teFromHi.contains("గందరగోళం"))
    }
}

