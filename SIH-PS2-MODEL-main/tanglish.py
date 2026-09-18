# -*- coding: utf-8 -*-
"""
Tanglish (Tamil in English/Latin script) processor.
Detects Tanglish expressions and translates them into natural Tamil, English, or other Indic languages.
"""

import re

# Comprehensive phrase map for conversational Tanglish
TANGLISH_PHRASES = [
    # Greetings & wellbeing
    (r"\bepdi\s+da\s+irru?ka\b", "எப்படி டா இருக்க?"),
    (r"\bepdi\s+di\s+irru?ka\b", "எப்படி டி இருக்க?"),
    (r"\bepdi\s+irru?ka\b", "எப்படி இருக்க?"),
    (r"\bepdi\s+irru?ki[rn]ga\b", "எப்படி இருக்கீங்க?"),
    (r"\beppadi\s+irukk[ie]+nga\b", "எப்படி இருக்கீங்க?"),
    (r"\beppadi\s+irukke?nga\b", "எப்படி இருக்கீங்க?"),
    (r"\beppadi\s+irukk[ai]r\b", "எப்படி இருக்காரு?"),
    (r"\bnalla\s+irukk?en\b", "நல்லா இருக்கேன்"),
    (r"\bnalla\s+iruki[rn]gala\b", "நல்லா இருக்கீங்களா?"),
    (r"\bsoukyama\b", "சௌக்கியமா?"),

    # Activity questions
    (r"\benna\s+da\s+pan[dr]+a\b", "என்ன டா பண்ற?"),
    (r"\benna\s+di\s+pan[dr]+a\b", "என்ன டி பண்ற?"),
    (r"\benna\s+pan[dr]+a\b", "என்ன பண்ற?"),
    (r"\benna\s+pan[dr]+i[rn]ga\b", "என்ன பண்றீங்க?"),
    (r"\benna\s+seiyureenga\b", "என்ன பண்றீங்க?"),
    (r"\benna\s+vishayam\b", "என்ன விஷயம்?"),
    (r"\benna\s+aachu\b", "என்ன ஆச்சு?"),
    (r"\benna\s+nadakuthu\b", "என்ன நடக்குது?"),

    # Direction & movement
    (r"\benga\s+da\s+po[rn]+a\b", "எங்க டா போற?"),
    (r"\benga\s+po[rn]+a\b", "எங்க போற?"),
    (r"\benga\s+po[rn]+i[rn]ga\b", "எங்க போறீங்க?"),
    (r"\bengada\s+irukka\b", "எங்க டா இருக்க?"),
    (r"\benga\s+irukka\b", "எங்க இருக்க?"),
    (r"\benga\s+iruki[rn]ga\b", "எங்க இருக்கீங்க?"),
    (r"\bveetuku\s+po\b", "வீட்டுக்கு போ"),
    (r"\bveetla\s+iruken\b", "வீட்ல இருக்கேன்"),
    (r"\bvelila\s+poraen\b", "வெளில போறேன்"),
    (r"\bseekiram\s+va\b", "சீக்கிரம் வா"),
    (r"\bseekiram\s+vaanga\b", "சீக்கிரம் வாங்க"),

    # Food & eating
    (r"\bsaa?ptiya\s+da\b", "சாப்பிட்டியா டா?"),
    (r"\bsaa?ptiya\s+di\b", "சாப்பிட்டியா டி?"),
    (r"\bsaa?ptiya\s+machan\b", "சாப்பிட்டியா மச்சான்?"),
    (r"\bsaa?ptiya\s+machi\b", "சாப்பிட்டியா மச்சி?"),
    (r"\bsaa?ptiya\s+bro\b", "சாப்பிட்டியா ப்ரோ?"),
    (r"\bsaa?ptiya\b", "சாப்பிட்டியா?"),
    (r"\bsapdiya\b", "சாப்பிட்டியா?"),
    (r"\bsaa?ptaacha\s+da\b", "சாப்டாச்சா டா?"),
    (r"\bsaa?ptaacha\b", "சாப்டாச்சா?"),
    (r"\bsaa?pt[ie]+ngala\b", "சாப்பிட்டீங்களா?"),
    (r"\bthanni\s+kudi\b", "தண்ணி குடி"),

    # Common reactions & slang
    (r"\bseri\s+da\b", "சரி டா"),
    (r"\bsari\s+da\b", "சரி டா"),
    (r"\bseri\s+di\b", "சரி டி"),
    (r"\bva\s+da\b", "வா டா"),
    (r"\bvada\b", "வா டா"),
    (r"\bpo\s+da\b", "போ டா"),
    (r"\bpoda\b", "போ டா"),
    (r"\bvidu\s+da\b", "விடு டா"),
    (r"\bviduda\b", "விடு டா"),
    (r"\bpesama\s+iru\b", "பேசாம இரு"),
    (r"\bonnum\s+illa\b", "ஒன்னும் இல்ல"),
    (r"\bonnumilla\b", "ஒன்னும் இல்ல"),
    (r"\btheri[yl]+a\b", "தெரியல"),
    (r"\bpurila\b", "புரியல"),
    (r"\bpuriyala\b", "புரியல"),
    (r"\bkavalapadadhe?enga\b", "கவலைப்படாதீங்க"),
    (r"\bkavalapadadha\b", "கவலைப்படாத"),
    (r"\bromba\s+thanks\b", "ரொம்ப தேங்க்ஸ்"),
    (r"\bromba\s+nandri\b", "ரொம்ப நன்றி"),
    (r"\bsemma\s+mass\b", "செம்ம மாஸ்"),
    (r"\bkalakkura\s+da\b", "கலக்குற டா"),
    (r"\bapram\s+paa?rkk?alam\b", "அப்புறம் பாக்கலாம்"),
    (r"\baprom\s+paaklam\b", "அப்புறம் பாக்கலாம்"),
    (r"\bseekiram\s+vaanga\s+romba\s+emergency\b", "சீக்கிரம் வாருங்கள், மிக அவசர நிலை!"),
    (r"\bseekiram\s+vaanga\b", "சீக்கிரம் வாருங்கள்"),
    (r"\bseekiram\s+va\b", "சீக்கிரம் வா"),
    (r"\bromba\s+emergency\b", "மிக அவசர நிலை!"),
    (r"\budhavi\s+thevai\b", "உடனடி உதவி தேவை"),
    (r"\bnaanga\s+safe\b", "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
    (r"\bsafe\s+ah\s+irukom\b", "நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
    (r"\bveetla\s+iruken\s+safe\s+ah\s+irukom\b", "வீட்டில் இருக்கிறேன், நாங்கள் பாதுகாப்பாக இருக்கிறோம்"),
    (r"\bthanni\s+romba\s+eriduchu\b", "வெள்ள நீர் மிக அதிகமாக உயர்ந்துவிட்டது"),
    (r"\bthanni\s+adhigam\s+aayiduchu\b", "தண்ணீர் அதிகமாகிவிட்டது"),
    (r"\bmedical\s+team\s+anupunga\b", "மருத்துவக் குழுவை உடனடியாக அனுப்பவும்"),
    (r"\bambulance\s+anupunga\b", "ஆம்புலன்ஸ் உடனடியாக அனுப்பவும்"),
    (r"\bboat\s+anupunga\b", "மீட்பு படகை உடனடியாக அனுப்பவும்"),
]

# Standard semantic mapping for translating Tanglish to other languages (English, Hindi, etc.)
TANGLISH_TO_SEMANTIC_TAMIL = [
    (r"\bepdi\s+da\s+irru?ka\b", "நீ எப்படி இருக்கிறாய்?"),
    (r"\bepdi\s+di\s+irru?ka\b", "நீ எப்படி இருக்கிறாய்?"),
    (r"\bepdi\s+irru?ka\b", "நீ எப்படி இருக்கிறாய்?"),
    (r"\bepdi\s+irru?ki[rn]ga\b", "நீங்கள் எப்படி இருக்கிறீர்கள்?"),
    (r"\beppadi\s+irukk[ie]+nga\b", "நீங்கள் எப்படி இருக்கிறீர்கள்?"),
    (r"\beppadi\s+irukke?nga\b", "நீங்கள் எப்படி இருக்கிறீர்கள்?"),
    (r"\bnalla\s+irukk?en\b", "நான் நன்றாக இருக்கிறேன்"),
    (r"\bnalla\s+iruki[rn]gala\b", "நீங்கள் நன்றாக இருக்கிறீர்களா?"),
    (r"\bsoukyama\b", "சௌக்கியமா?"),
    (r"\benna\s+da\s+pan[dr]+a\b", "நீ என்ன செய்கிறாய்?"),
    (r"\benna\s+di\s+pan[dr]+a\b", "நீ என்ன செய்கிறாய்?"),
    (r"\benna\s+pan[dr]+a\b", "நீ என்ன செய்கிறாய்?"),
    (r"\benna\s+pan[dr]+i[rn]ga\b", "நீங்கள் என்ன செய்கிறீர்கள்?"),
    (r"\benna\s+aachu\b", "என்ன ஆயிற்று?"),
    (r"\benna\s+nadakuthu\b", "என்ன நடக்கிறது?"),
    (r"\benga\s+da\s+po[rn]+a\b", "நீ எங்கே போகிறாய்?"),
    (r"\benga\s+po[rn]+a\b", "நீ எங்கே போகிறாய்?"),
    (r"\benga\s+po[rn]+i[rn]ga\b", "நீங்கள் எங்கே போகிறீர்கள்?"),
    (r"\bengada\s+irukka\b", "நீ எங்கே இருக்கிறாய்?"),
    (r"\benga\s+irukka\b", "நீ எங்கே இருக்கிறாய்?"),
    (r"\bveetla\s+iruken\b", "நான் வீட்டில் இருக்கிறேன்"),
    (r"\bveetuku\s+po\b", "வீட்டுக்கு போ"),
    (r"\bseekiram\s+va\b", "சீக்கிரம் வா"),
    (r"\bsaa?ptiya(?:\s+da|\s+di|\s+machan|\s+machi|\s+bro)?\b", "நீ சாப்பிட்டாயா?"),
    (r"\bsapdiya(?:\s+da|\s+di|\s+machan|\s+machi|\s+bro)?\b", "நீ சாப்பிட்டாயா?"),
    (r"\bsaa?ptaacha(?:\s+da)?\b", "சாப்பிட்டாயிற்றா?"),
    (r"\bsaa?pt[ie]+ngala\b", "நீங்கள் சாப்பிட்டீர்களா?"),
    (r"\bseri\s+da\b", "சரி"),
    (r"\bsari\s+da\b", "சரி"),
    (r"\bonnum\s+illa\b", "ஒன்றுமில்லை"),
    (r"\bonnumilla\b", "ஒன்றுமில்லை"),
    (r"\btheri[yl]+a\b", "எனக்கு தெரியவில்லை"),
    (r"\bpurila\b", "எனக்கு புரியவில்லை"),
    (r"\bpuriyala\b", "எனக்கு புரியவில்லை"),
    (r"\bromba\s+thanks\b", "மிக்க நன்றி"),
    (r"\bromba\s+nandri\b", "மிக்க நன்றி"),
]

# Common word dictionary
TANGLISH_WORDS = {
    "epdi": "எப்படி", "eppadi": "எப்படி", "yepdi": "எப்படி",
    "da": "டா", "di": "டி", "thala": "தல", "machan": "மச்சான்",
    "machi": "மச்சி", "bro": "ப்ரோ", "nanba": "நண்பா", "anna": "அண்ணா",
    "thambi": "தம்பி", "akka": "அக்கா",
    "enna": "என்ன", "edhu": "எது", "yaaru": "யாரு", "yaru": "யாரு",
    "enga": "எங்க", "enge": "எங்கே", "inga": "இங்க", "inge": "இங்கே",
    "anga": "அங்க", "ange": "அங்கே", "eppo": "எப்போ", "eppoluthu": "எப்பொழுது",
    "yen": "ஏன்", "edhukku": "எதுக்கு", "edhuku": "எதுக்கு", "ethukku": "எதுக்கு",
    "evlo": "எவ்ளோ", "avlo": "அவ்ளோ", "ivlo": "இவ்ளோ",
    "naan": "நான்", "naa": "நான்", "nee": "நீ", "neenga": "நீங்க",
    "avan": "அவன்", "aval": "அவ", "ava": "அவ", "avanga": "அவங்க",
    "ivan": "இவன்", "ival": "இவ", "iva": "இவ", "ivanga": "இவங்க",
    "namma": "நம்ம", "naanga": "நாங்க",
    "ennoda": "என்னோட", "unoda": "உன்னோட", "ungalaoda": "உங்களோட", "ungoda": "உங்களோட",
    "iruka": "இருக்க", "irruka": "இருக்க", "irukka": "இருக்க",
    "irukinga": "இருக்கீங்க", "irukeenga": "இருக்கீங்க",
    "iruken": "இருக்கேன்", "irukken": "இருக்கேன்",
    "irukkaru": "இருக்காரு", "irukaanga": "இருக்காங்க",
    "pandra": "பண்ற", "panra": "பண்ற", "pandren": "பண்றேன்", "panren": "பண்றேன்",
    "pandringa": "பண்றீங்க", "panreenga": "பண்றீங்க", "pandraru": "பண்றாரு",
    "pora": "போற", "poren": "போறேன்", "poreenga": "போறீங்க",
    "vara": "வர்ற", "varen": "வர்றேன்", "vareenga": "வர்றீங்க",
    "va": "வா", "po": "போ", "vaanga": "வாங்க", "ponga": "போங்க",
    "saaptiya": "சாப்பிட்டியா", "sapdiya": "சாப்பிட்டியா", "saapdu": "சாப்பிடு",
    "paaru": "பாரு", "paathu": "பாத்து", "paatha": "பாத்த",
    "solra": "சொல்ற", "solren": "சொல்றேன்", "solunga": "சொல்லுங்க",
    "kududa": "குடு டா", "kudu": "குடு", "kudunga": "குடுங்க",
    "pesu": "பேசு", "pesura": "பேசுற", "pesunga": "பேசுங்க",
    "kelambu": "கெளம்பு", "kelambunga": "கெளம்புங்க",
    "seri": "சரி", "sari": "சரி", "illa": "இல்ல", "aama": "ஆமா",
    "theriyum": "தெரியும்", "therila": "தெரியல", "theriyala": "தெரியல",
    "puriyum": "புரியும்", "purila": "புரியல", "puriyala": "புரியல",
    "venum": "வேணும்", "venaam": "வேண்டாம்", "vendaam": "வேண்டாம்",
    "romba": "ரொம்ப", "nalla": "நல்லா", "super": "சூப்பர்", "mass": "மாஸ்",
    "semma": "செம்ம", "adada": "அடடா", "ayyo": "அய்யோ", "thaniya": "தனியா",
    "veetla": "வீட்ல", "veedu": "வீடு", "velai": "வேலை", "vela": "வேலை",
    "office": "ஆபீஸ்", "college": "காலேஜ்", "school": "ஸ்கூல்",
    "padam": "படம்", "cinema": "சினிமா", "paatu": "பாட்டு",
}

TANGLISH_MARKERS = {
    "epdi", "eppadi", "iruka", "irruka", "irukka", "irukinga", "iruken", "irukken",
    "pandra", "panra", "pandren", "pandringa", "saaptiya", "sapdiya", "saaptaacha",
    "machan", "machi", "thala", "nanba", "enga", "pora", "poreenga", "varen",
    "nalla", "seri", "therila", "theriyala", "purila", "puriyala", "venum", "venaam",
    "vada", "poda", "onnumilla", "onnum", "kavalapadadha", "semma", "seekiram", "vaanga",
    "thanni", "udhavi", "anupunga", "veetla", "irukom", "eriduchu", "romba"
}

def is_tanglish(text: str) -> bool:
    """
    Returns True if the text is identified as Tanglish (Romanized Tamil).
    """
    words = set(re.findall(r"\b[a-zA-Z]+\b", text.lower()))
    if not words:
        return False
    # Check for direct marker overlap
    if words.intersection(TANGLISH_MARKERS):
        return True
    # Check for 'da' / 'di' / 'pa' combined with other words
    if {"da", "di", "machan", "thala", "machi"}.intersection(words) and len(words) > 1:
        return True
    return False

def tanglish_to_tamil(text: str) -> str:
    """
    Converts a Tanglish sentence into natural colloquial Tamil script.
    """
    t = text.strip()

    # Step 1: Replace multi-word phrases
    for p, r in TANGLISH_PHRASES:
        t = re.sub(p, r, t, flags=re.IGNORECASE)

    # Step 2: Word-by-word replacement for remaining tokens
    tokens = re.split(r"(\s+|[.,!?;:])", t)
    out = []
    for token in tokens:
        clean = token.lower().strip()
        if clean in TANGLISH_WORDS:
            out.append(TANGLISH_WORDS[clean])
        else:
            out.append(token)

    return "".join(out).strip()

def tanglish_to_semantic_tamil(text: str) -> str:
    """
    Converts Tanglish to standard Tamil script suitable for high-accuracy
    translation into other target languages (English, Hindi, Telugu, etc.).
    """
    t = text.strip()
    for p, r in TANGLISH_TO_SEMANTIC_TAMIL:
        if re.search(p, t, flags=re.IGNORECASE):
            return re.sub(p, r, t, flags=re.IGNORECASE)
    # If not matched in semantic phrases, fall back to colloquial Tamil
    return tanglish_to_tamil(text)

