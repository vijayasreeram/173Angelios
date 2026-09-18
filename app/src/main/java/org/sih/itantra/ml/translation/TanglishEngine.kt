package org.sih.itantra.ml.translation

import java.util.Locale

/**
 * Native On-Device Tanglish (Romanized Tamil) Processing Engine.
 * Directly ported from SIH-PS2-MODEL-main/tanglish.py.
 * Provides 100% offline, zero-latency detection and bidirectional conversion.
 */
object TanglishEngine {

    private val TANGLISH_MARKERS = setOf(
        "epdi", "eppadi", "iruka", "irruka", "irukka", "irukinga", "iruken", "irukken",
        "pandra", "panra", "pandren", "pandringa", "saaptiya", "sapdiya", "saaptaacha",
        "machan", "machi", "thala", "nanba", "enga", "pora", "poreenga", "varen",
        "nalla", "seri", "therila", "theriyala", "purila", "puriyala", "venum", "venaam",
        "vada", "poda", "onnumilla", "onnum", "kavalapadadha", "semma", "vaanga", "seekiram",
        "thanni", "udhavi", "anupunga", "veetla", "irukom", "eriduchu"
    )

    private val TANGLISH_WORDS = mapOf(
        "aama" to "ஆமா",
        "adada" to "அடடா",
        "akka" to "அக்கா",
        "anga" to "அங்க",
        "ange" to "அங்கே",
        "anna" to "அண்ணா",
        "ava" to "அவ",
        "aval" to "அவ",
        "avan" to "அவன்",
        "avanga" to "அவங்க",
        "avlo" to "அவ்ளோ",
        "ayyo" to "அய்யோ",
        "bro" to "ப்ரோ",
        "cinema" to "சினிமா",
        "college" to "காலேஜ்",
        "da" to "டா",
        "di" to "டி",
        "edhu" to "எது",
        "edhukku" to "எதுக்கு",
        "edhuku" to "எதுக்கு",
        "enga" to "எங்க",
        "enge" to "எங்கே",
        "enna" to "என்ன",
        "ennoda" to "என்னோட",
        "epdi" to "எப்படி",
        "eppadi" to "எப்படி",
        "eppo" to "எப்போ",
        "eppoluthu" to "எப்பொழுது",
        "ethukku" to "எதுக்கு",
        "evlo" to "எவ்ளோ",
        "illa" to "இல்ல",
        "inga" to "இங்க",
        "inge" to "இங்கே",
        "irruka" to "இருக்க",
        "iruka" to "இருக்க",
        "irukaanga" to "இருக்காங்க",
        "irukeenga" to "இருக்கீங்க",
        "iruken" to "இருக்கேன்",
        "irukinga" to "இருக்கீங்க",
        "irukka" to "இருக்க",
        "irukkaru" to "இருக்காரு",
        "irukken" to "இருக்கேன்",
        "iva" to "இவ",
        "ival" to "இவ",
        "ivan" to "இவன்",
        "ivanga" to "இவங்க",
        "ivlo" to "இவ்ளோ",
        "kelambu" to "கெளம்பு",
        "kelambunga" to "கெளம்புங்க",
        "kudu" to "குடு",
        "kududa" to "குடு டா",
        "kudunga" to "குடுங்க",
        "machan" to "மச்சான்",
        "machi" to "மச்சி",
        "mass" to "மாஸ்",
        "naa" to "நான்",
        "naan" to "நான்",
        "naanga" to "நாங்க",
        "nalla" to "நல்லா",
        "namma" to "நம்ம",
        "nanba" to "நண்பா",
        "nee" to "நீ",
        "neenga" to "நீங்க",
        "office" to "ஆபீஸ்",
        "paaru" to "பாரு",
        "paatha" to "பாத்த",
        "paathu" to "பாத்து",
        "paatu" to "பாட்டு",
        "padam" to "படம்",
        "pandra" to "பண்ற",
        "pandraru" to "பண்றாரு",
        "pandren" to "பண்றேன்",
        "pandringa" to "பண்றீங்க",
        "panra" to "பண்ற",
        "panreenga" to "பண்றீங்க",
        "panren" to "பண்றேன்",
        "pesu" to "பேசு",
        "pesunga" to "பேசுங்க",
        "pesura" to "பேசுற",
        "po" to "போ",
        "ponga" to "போங்க",
        "pora" to "போற",
        "poreenga" to "போறீங்க",
        "poren" to "போறேன்",
        "purila" to "புரியல",
        "puriyala" to "புரியல",
        "puriyum" to "புரியும்",
        "romba" to "ரொம்ப",
        "saapdu" to "சாப்பிடு",
        "saaptiya" to "சாப்பிட்டியா",
        "sapdiya" to "சாப்பிட்டியா",
        "sari" to "சரி",
        "school" to "ஸ்கூல்",
        "semma" to "செம்ம",
        "seri" to "சரி",
        "solra" to "சொல்ற",
        "solren" to "சொல்றேன்",
        "solunga" to "சொல்லுங்க",
        "super" to "சூப்பர்",
        "thala" to "தல",
        "thambi" to "தம்பி",
        "thaniya" to "தனியா",
        "therila" to "தெரியல",
        "theriyala" to "தெரியல",
        "theriyum" to "தெரியும்",
        "ungalaoda" to "உங்களோட",
        "ungoda" to "உங்களோட",
        "unoda" to "உன்னோட",
        "va" to "வா",
        "vaanga" to "வாங்க",
        "vara" to "வர்ற",
        "vareenga" to "வர்றீங்க",
        "varen" to "வர்றேன்",
        "veedu" to "வீடு",
        "veetla" to "வீட்ல",
        "vela" to "வேலை",
        "velai" to "வேலை",
        "venaam" to "வேண்டாம்",
        "vendaam" to "வேண்டாம்",
        "venum" to "வேணும்",
        "yaaru" to "யாரு",
        "yaru" to "யாரு",
        "yen" to "ஏன்",
        "yepdi" to "எப்படி",
    )

    // Comprehensive phrase map for conversational Tanglish
    private val TANGLISH_PHRASES: List<Pair<Regex, String>> = listOf(
        Pair(Regex("\\bepdi\\s+da\\s+irru?ka\\b", RegexOption.IGNORE_CASE), "எப்படி டா இருக்க?"),
        Pair(Regex("\\bepdi\\s+di\\s+irru?ka\\b", RegexOption.IGNORE_CASE), "எப்படி டி இருக்க?"),
        Pair(Regex("\\bepdi\\s+irru?ka\\b", RegexOption.IGNORE_CASE), "எப்படி இருக்க?"),
        Pair(Regex("\\bepdi\\s+irru?ki[rn]ga\\b", RegexOption.IGNORE_CASE), "எப்படி இருக்கீங்க?"),
        Pair(Regex("\\beppadi\\s+irukk[ie]+nga\\b", RegexOption.IGNORE_CASE), "எப்படி இருக்கீங்க?"),
        Pair(Regex("\\beppadi\\s+irukke?nga\\b", RegexOption.IGNORE_CASE), "எப்படி இருக்கீங்க?"),
        Pair(Regex("\\beppadi\\s+irukk[ai]r\\b", RegexOption.IGNORE_CASE), "எப்படி இருக்காரு?"),
        Pair(Regex("\\bnalla\\s+irukk?en\\b", RegexOption.IGNORE_CASE), "நல்லா இருக்கேன்"),
        Pair(Regex("\\bnalla\\s+iruki[rn]gala\\b", RegexOption.IGNORE_CASE), "நல்லா இருக்கீங்களா?"),
        Pair(Regex("\\bsoukyama\\b", RegexOption.IGNORE_CASE), "சௌக்கியமா?"),
        Pair(Regex("\\benna\\s+da\\s+pan[dr]+a\\b", RegexOption.IGNORE_CASE), "என்ன டா பண்ற?"),
        Pair(Regex("\\benna\\s+di\\s+pan[dr]+a\\b", RegexOption.IGNORE_CASE), "என்ன டி பண்ற?"),
        Pair(Regex("\\benna\\s+pan[dr]+a\\b", RegexOption.IGNORE_CASE), "என்ன பண்ற?"),
        Pair(Regex("\\benna\\s+pan[dr]+i[rn]ga\\b", RegexOption.IGNORE_CASE), "என்ன பண்றீங்க?"),
        Pair(Regex("\\benna\\s+seiyureenga\\b", RegexOption.IGNORE_CASE), "என்ன பண்றீங்க?"),
        Pair(Regex("\\benna\\s+vishayam\\b", RegexOption.IGNORE_CASE), "என்ன விஷயம்?"),
        Pair(Regex("\\benna\\s+aachu\\b", RegexOption.IGNORE_CASE), "என்ன ஆச்சு?"),
        Pair(Regex("\\benna\\s+nadakuthu\\b", RegexOption.IGNORE_CASE), "என்ன நடக்குது?"),
        Pair(Regex("\\benga\\s+da\\s+po[rn]+a\\b", RegexOption.IGNORE_CASE), "எங்க டா போற?"),
        Pair(Regex("\\benga\\s+po[rn]+a\\b", RegexOption.IGNORE_CASE), "எங்க போற?"),
        Pair(Regex("\\benga\\s+po[rn]+i[rn]ga\\b", RegexOption.IGNORE_CASE), "எங்க போறீங்க?"),
        Pair(Regex("\\bengada\\s+irukka\\b", RegexOption.IGNORE_CASE), "எங்க டா இருக்க?"),
        Pair(Regex("\\benga\\s+irukka\\b", RegexOption.IGNORE_CASE), "எங்க இருக்க?"),
        Pair(Regex("\\benga\\s+iruki[rn]ga\\b", RegexOption.IGNORE_CASE), "எங்க இருக்கீங்க?"),
        Pair(Regex("\\bveetuku\\s+po\\b", RegexOption.IGNORE_CASE), "வீட்டுக்கு போ"),
        Pair(Regex("\\bveetla\\s+iruken\\b", RegexOption.IGNORE_CASE), "வீட்ல இருக்கேன்"),
        Pair(Regex("\\bvelila\\s+poraen\\b", RegexOption.IGNORE_CASE), "வெளில போறேன்"),
        Pair(Regex("\\bseekiram\\s+va\\b", RegexOption.IGNORE_CASE), "சீக்கிரம் வா"),
        Pair(Regex("\\bseekiram\\s+vaanga\\b", RegexOption.IGNORE_CASE), "சீக்கிரம் வாங்க"),
        Pair(Regex("\\bsaa?ptiya\\s+da\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டியா டா?"),
        Pair(Regex("\\bsaa?ptiya\\s+di\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டியா டி?"),
        Pair(Regex("\\bsaa?ptiya\\s+machan\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டியா மச்சான்?"),
        Pair(Regex("\\bsaa?ptiya\\s+machi\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டியா மச்சி?"),
        Pair(Regex("\\bsaa?ptiya\\s+bro\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டியா ப்ரோ?"),
        Pair(Regex("\\bsaa?ptiya\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டியா?"),
        Pair(Regex("\\bsapdiya\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டியா?"),
        Pair(Regex("\\bsaa?ptaacha\\s+da\\b", RegexOption.IGNORE_CASE), "சாப்டாச்சா டா?"),
        Pair(Regex("\\bsaa?ptaacha\\b", RegexOption.IGNORE_CASE), "சாப்டாச்சா?"),
        Pair(Regex("\\bsaa?pt[ie]+ngala\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டீங்களா?"),
        Pair(Regex("\\bthanni\\s+kudi\\b", RegexOption.IGNORE_CASE), "தண்ணி குடி"),
        Pair(Regex("\\bseri\\s+da\\b", RegexOption.IGNORE_CASE), "சரி டா"),
        Pair(Regex("\\bsari\\s+da\\b", RegexOption.IGNORE_CASE), "சரி டா"),
        Pair(Regex("\\bseri\\s+di\\b", RegexOption.IGNORE_CASE), "சரி டி"),
        Pair(Regex("\\bva\\s+da\\b", RegexOption.IGNORE_CASE), "வா டா"),
        Pair(Regex("\\bvada\\b", RegexOption.IGNORE_CASE), "வா டா"),
        Pair(Regex("\\bpo\\s+da\\b", RegexOption.IGNORE_CASE), "போ டா"),
        Pair(Regex("\\bpoda\\b", RegexOption.IGNORE_CASE), "போ டா"),
        Pair(Regex("\\bvidu\\s+da\\b", RegexOption.IGNORE_CASE), "விடு டா"),
        Pair(Regex("\\bviduda\\b", RegexOption.IGNORE_CASE), "விடு டா"),
        Pair(Regex("\\bpesama\\s+iru\\b", RegexOption.IGNORE_CASE), "பேசாம இரு"),
        Pair(Regex("\\bonnum\\s+illa\\b", RegexOption.IGNORE_CASE), "ஒன்னும் இல்ல"),
        Pair(Regex("\\bonnumilla\\b", RegexOption.IGNORE_CASE), "ஒன்னும் இல்ல"),
        Pair(Regex("\\btheri[yl]+a\\b", RegexOption.IGNORE_CASE), "தெரியல"),
        Pair(Regex("\\bpurila\\b", RegexOption.IGNORE_CASE), "புரியல"),
        Pair(Regex("\\bpuriyala\\b", RegexOption.IGNORE_CASE), "புரியல"),
        Pair(Regex("\\bkavalapadadhe?enga\\b", RegexOption.IGNORE_CASE), "கவலைப்படாதீங்க"),
        Pair(Regex("\\bkavalapadadha\\b", RegexOption.IGNORE_CASE), "கவலைப்படாத"),
        Pair(Regex("\\bromba\\s+thanks\\b", RegexOption.IGNORE_CASE), "ரொம்ப தேங்க்ஸ்"),
        Pair(Regex("\\bromba\\s+nandri\\b", RegexOption.IGNORE_CASE), "ரொம்ப நன்றி"),
        Pair(Regex("\\bsemma\\s+mass\\b", RegexOption.IGNORE_CASE), "செம்ம மாஸ்"),
        Pair(Regex("\\bkalakkura\\s+da\\b", RegexOption.IGNORE_CASE), "கலக்குற டா"),
        Pair(Regex("\\bapram\\s+paa?rkk?alam\\b", RegexOption.IGNORE_CASE), "அப்புறம் பாக்கலாம்"),
        Pair(Regex("\\baprom\\s+paaklam\\b", RegexOption.IGNORE_CASE), "அப்புறம் பாக்கலாம்"),
        Pair(Regex("\\bseekiram\\s+vaanga\\s+romba\\s+emergency\\b", RegexOption.IGNORE_CASE), "சீக்கிரம் வாருங்கள், மிக அவசர நிலை!"),
        Pair(Regex("\\bromba\\s+emergency\\b", RegexOption.IGNORE_CASE), "மிக அவசர நிலை!"),
        Pair(Regex("\\budhavi\\s+thevai\\b", RegexOption.IGNORE_CASE), "உடனடி உதவி தேவை"),
        Pair(Regex("\\bnaanga\\s+safe\\b", RegexOption.IGNORE_CASE), "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
        Pair(Regex("\\bsafe\\s+ah\\s+irukom\\b", RegexOption.IGNORE_CASE), "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
        Pair(Regex("\\bveetla\\s+iruken\\s+safe\\s+ah\\s+irukom\\b", RegexOption.IGNORE_CASE), "வீட்டில் இருக்கிறேன், நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
        Pair(Regex("\\bthanni\\s+romba\\s+eriduchu\\b", RegexOption.IGNORE_CASE), "வெள்ள நீர் மிக அதிகமாக உயர்ந்துவிட்டது"),
        Pair(Regex("\\bthanni\\s+adhigam\\s+aayiduchu\\b", RegexOption.IGNORE_CASE), "தண்ணீர் அதிகமாகிவிட்டது"),
        Pair(Regex("\\bmedical\\s+team\\s+anupunga\\b", RegexOption.IGNORE_CASE), "மருத்துவக் குழுவை உடனடியாக அனுப்பவும்"),
        Pair(Regex("\\bambulance\\s+anupunga\\b", RegexOption.IGNORE_CASE), "ஆம்புலன்ஸ் உடனடியாக அனுப்பவும்"),
        Pair(Regex("\\bboat\\s+anupunga\\b", RegexOption.IGNORE_CASE), "மீட்பு படகை உடனடியாக அனுப்பவும்"),
    )

    // Standard semantic mapping for translating Tanglish to other languages
    private val TANGLISH_TO_SEMANTIC_TAMIL: List<Pair<Regex, String>> = listOf(
        Pair(Regex("\\bepdi\\s+da\\s+irru?ka\\b", RegexOption.IGNORE_CASE), "நீ எப்படி இருக்கிறாய்?"),
        Pair(Regex("\\bepdi\\s+di\\s+irru?ka\\b", RegexOption.IGNORE_CASE), "நீ எப்படி இருக்கிறாய்?"),
        Pair(Regex("\\bepdi\\s+irru?ka\\b", RegexOption.IGNORE_CASE), "நீ எப்படி இருக்கிறாய்?"),
        Pair(Regex("\\bepdi\\s+irru?ki[rn]ga\\b", RegexOption.IGNORE_CASE), "நீங்கள் எப்படி இருக்கிறீர்கள்?"),
        Pair(Regex("\\beppadi\\s+irukk[ie]+nga\\b", RegexOption.IGNORE_CASE), "நீங்கள் எப்படி இருக்கிறீர்கள்?"),
        Pair(Regex("\\beppadi\\s+irukke?nga\\b", RegexOption.IGNORE_CASE), "நீங்கள் எப்படி இருக்கிறீர்கள்?"),
        Pair(Regex("\\bnalla\\s+irukk?en\\b", RegexOption.IGNORE_CASE), "நான் நன்றாக இருக்கிறேன்"),
        Pair(Regex("\\bnalla\\s+iruki[rn]gala\\b", RegexOption.IGNORE_CASE), "நீங்கள் நன்றாக இருக்கிறீர்களா?"),
        Pair(Regex("\\bsoukyama\\b", RegexOption.IGNORE_CASE), "சௌக்கியமா?"),
        Pair(Regex("\\benna\\s+da\\s+pan[dr]+a\\b", RegexOption.IGNORE_CASE), "நீ என்ன செய்கிறாய்?"),
        Pair(Regex("\\benna\\s+di\\s+pan[dr]+a\\b", RegexOption.IGNORE_CASE), "நீ என்ன செய்கிறாய்?"),
        Pair(Regex("\\benna\\s+pan[dr]+a\\b", RegexOption.IGNORE_CASE), "நீ என்ன செய்கிறாய்?"),
        Pair(Regex("\\benna\\s+pan[dr]+i[rn]ga\\b", RegexOption.IGNORE_CASE), "நீங்கள் என்ன செய்கிறீர்கள்?"),
        Pair(Regex("\\benna\\s+aachu\\b", RegexOption.IGNORE_CASE), "என்ன ஆயிற்று?"),
        Pair(Regex("\\benna\\s+nadakuthu\\b", RegexOption.IGNORE_CASE), "என்ன நடக்கிறது?"),
        Pair(Regex("\\benga\\s+da\\s+po[rn]+a\\b", RegexOption.IGNORE_CASE), "நீ எங்கே போகிறாய்?"),
        Pair(Regex("\\benga\\s+po[rn]+a\\b", RegexOption.IGNORE_CASE), "நீ எங்கே போகிறாய்?"),
        Pair(Regex("\\benga\\s+po[rn]+i[rn]ga\\b", RegexOption.IGNORE_CASE), "நீங்கள் எங்கே போகிறீர்கள்?"),
        Pair(Regex("\\bengada\\s+irukka\\b", RegexOption.IGNORE_CASE), "நீ எங்கே இருக்கிறாய்?"),
        Pair(Regex("\\benga\\s+irukka\\b", RegexOption.IGNORE_CASE), "நீ எங்கே இருக்கிறாய்?"),
        Pair(Regex("\\bveetla\\s+iruken\\b", RegexOption.IGNORE_CASE), "நான் வீட்டில் இருக்கிறேன்"),
        Pair(Regex("\\bveetuku\\s+po\\b", RegexOption.IGNORE_CASE), "வீட்டுக்கு போ"),
        Pair(Regex("\\bseekiram\\s+va\\b", RegexOption.IGNORE_CASE), "சீக்கிரம் வா"),
        Pair(Regex("\\bsaa?ptiya(?:\\s+da|\\s+di|\\s+machan|\\s+machi|\\s+bro)?\\b", RegexOption.IGNORE_CASE), "நீ சாப்பிட்டாயா?"),
        Pair(Regex("\\bsapdiya(?:\\s+da|\\s+di|\\s+machan|\\s+machi|\\s+bro)?\\b", RegexOption.IGNORE_CASE), "நீ சாப்பிட்டாயா?"),
        Pair(Regex("\\bsaa?ptaacha(?:\\s+da)?\\b", RegexOption.IGNORE_CASE), "சாப்பிட்டாயிற்றா?"),
        Pair(Regex("\\bsaa?pt[ie]+ngala\\b", RegexOption.IGNORE_CASE), "நீங்கள் சாப்பிட்டீர்களா?"),
        Pair(Regex("\\bseri\\s+da\\b", RegexOption.IGNORE_CASE), "சரி"),
        Pair(Regex("\\bsari\\s+da\\b", RegexOption.IGNORE_CASE), "சரி"),
        Pair(Regex("\\bonnum\\s+illa\\b", RegexOption.IGNORE_CASE), "ஒன்றுமில்லை"),
        Pair(Regex("\\bonnumilla\\b", RegexOption.IGNORE_CASE), "ஒன்றுமில்லை"),
        Pair(Regex("\\btheri[yl]+a\\b", RegexOption.IGNORE_CASE), "எனக்கு தெரியவில்லை"),
        Pair(Regex("\\bpurila\\b", RegexOption.IGNORE_CASE), "எனக்கு புரியவில்லை"),
        Pair(Regex("\\bpuriyala\\b", RegexOption.IGNORE_CASE), "எனக்கு புரியவில்லை"),
        Pair(Regex("\\bromba\\s+thanks\\b", RegexOption.IGNORE_CASE), "மிக்க நன்றி"),
        Pair(Regex("\\bromba\\s+nandri\\b", RegexOption.IGNORE_CASE), "மிக்க நன்றி"),
        Pair(Regex("\\bseekiram\\s+vaanga\\s+romba\\s+emergency\\b", RegexOption.IGNORE_CASE), "சீக்கிரம் வாருங்கள், மிக அவசர நிலை!"),
        Pair(Regex("\\bromba\\s+emergency\\b", RegexOption.IGNORE_CASE), "மிக அவசர நிலை!"),
        Pair(Regex("\\budhavi\\s+thevai\\b", RegexOption.IGNORE_CASE), "உடனடி உதவி தேவை"),
        Pair(Regex("\\bnaanga\\s+safe\\b", RegexOption.IGNORE_CASE), "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
        Pair(Regex("\\bsafe\\s+ah\\s+irukom\\b", RegexOption.IGNORE_CASE), "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
        Pair(Regex("\\bveetla\\s+iruken\\s+safe\\s+ah\\s+irukom\\b", RegexOption.IGNORE_CASE), "வீட்டில் இருக்கிறேன், நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
        Pair(Regex("\\bthanni\\s+romba\\s+eriduchu\\b", RegexOption.IGNORE_CASE), "வெள்ள நீர் மிக அதிகமாக உயர்ந்துவிட்டது"),
        Pair(Regex("\\bthanni\\s+adhigam\\s+aayiduchu\\b", RegexOption.IGNORE_CASE), "தண்ணீர் அதிகமாகிவிட்டது"),
        Pair(Regex("\\bmedical\\s+team\\s+anupunga\\b", RegexOption.IGNORE_CASE), "மருத்துவக் குழுவை உடனடியாக அனுப்பவும்"),
        Pair(Regex("\\bambulance\\s+anupunga\\b", RegexOption.IGNORE_CASE), "ஆம்புலன்ஸ் அனுப்பவும்"),
        Pair(Regex("\\bboat\\s+anupunga\\b", RegexOption.IGNORE_CASE), "மீட்பு படகை உடனே அனுப்பவும்"),
    )

    fun isTanglish(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        val words = Regex("\\b[a-zA-Z]+\\b").findAll(trimmed.lowercase(Locale.ROOT))
            .map { it.value }.toSet()
        if (words.isEmpty()) return false
        if (words.any { it in TANGLISH_MARKERS }) return true
        if (setOf("da", "di", "machan", "thala", "machi").any { it in words } && words.size > 1) return true
        return false
    }

    fun tanglishToTamil(text: String): String {
        var t = text.trim()
        for ((pattern, replacement) in TANGLISH_PHRASES) {
            t = pattern.replace(t, replacement)
        }
        val tokens = t.split(Regex("(?<=[\\s.,!?;:])|(?=[\\s.,!?;:])"))
        val out = StringBuilder()
        for (token in tokens) {
            val clean = token.lowercase(Locale.ROOT).trim()
            val repl = TANGLISH_WORDS[clean]
            if (repl != null) {
                out.append(repl)
            } else {
                out.append(token)
            }
        }
        return out.toString().trim()
    }

    fun tanglishToSemanticTamil(text: String): String {
        val t = text.trim()
        for ((pattern, replacement) in TANGLISH_TO_SEMANTIC_TAMIL) {
            if (pattern.containsMatchIn(t)) {
                return pattern.replace(t, replacement)
            }
        }
        return tanglishToTamil(text)
    }
}
