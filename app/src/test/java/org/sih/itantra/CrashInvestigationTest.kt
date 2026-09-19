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
}
