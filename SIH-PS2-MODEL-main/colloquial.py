# -*- coding: utf-8 -*-
"""
Colloquial and conversational language post-processor for IndicTrans2.
Converts formal, bookish, literary translations into natural, everyday spoken language / slang.
"""

import re

# Python's \b treats Unicode combining marks (matras, virama - categories Mn/Mc)
# as "non-word" characters, so \b silently fails to match at the start/end of most
# Indic-script words (which almost always end in such a mark). _wb()/_end_wb() build
# boundary checks against an explicit separator class instead, so they work correctly
# for Tamil/Devanagari/Telugu/Kannada/Malayalam/Bengali/Gujarati/Gurmukhi script.
_SEPARATORS = " \t\n\r,.!?;:।॥\"'()[]{}<>/\\|–—…-‑'’‚‘“”"
_SEP_CLASS = "[" + re.escape(_SEPARATORS) + "]"


def _wb(word: str) -> str:
    """Script-aware equivalent of r"\bWORD\b"."""
    return r"(?:^|(?<=" + _SEP_CLASS + r"))(?:" + word + r")(?:$|(?=" + _SEP_CLASS + r"))"


def _end_wb(word: str) -> str:
    """Script-aware equivalent of r"WORD\b" (right boundary only)."""
    return r"(?:" + word + r")(?:$|(?=" + _SEP_CLASS + r"))"


def to_colloquial(text: str, target_lang: str, src_text: str = "") -> str:
    """
    Transforms formal translations into everyday conversational human speech.
    """
    if not text:
        return text

    is_casual_src = any(w in src_text.lower() for w in ["hi", "hey", "hello", "bro", "buddy", "dude", "yaar", "machan"])

    if target_lang == "tam_Taml":
        return _colloquial_tamil(text, is_casual_src)
    elif target_lang == "hin_Deva":
        return _colloquial_hindi(text, is_casual_src)
    elif target_lang == "tel_Telu":
        return _colloquial_telugu(text, is_casual_src)
    elif target_lang == "mal_Mlym":
        return _colloquial_malayalam(text, is_casual_src)
    elif target_lang == "kan_Knda":
        return _colloquial_kannada(text, is_casual_src)
    elif target_lang == "ben_Beng":
        return _colloquial_bengali(text, is_casual_src)
    elif target_lang == "mar_Deva":
        return _colloquial_marathi(text, is_casual_src)
    elif target_lang == "guj_Gujr":
        return _colloquial_gujarati(text, is_casual_src)
    elif target_lang == "pan_Guru":
        return _colloquial_punjabi(text, is_casual_src)

    return text


def _colloquial_tamil(text: str, is_casual_src: bool) -> str:
    t = text

    # Common full phrase replacements
    phrase_map = [
        (r"வணக்கம்,\s*நீங்கள் எப்படி இருக்கிறீர்கள்\?", "ஹாய், எப்படி இருக்கீங்க?"),
        (r"நீங்கள் எப்படி இருக்கிறீர்கள்\?", "எப்படி இருக்கீங்க?"),
        (r"நீங்கள் என்ன செய்கிறீர்கள்\?", "என்ன பண்றீங்க?"),
        (r"நீ என்ன செய்கிறாய்\?", "என்ன பண்ற?"),
        (r"நீங்கள் எங்கே போகிறீர்கள்\?", "எங்க போறீங்க?"),
        (r"நீ எங்கே போகிறாய்\?", "எங்க போற?"),
        (r"சாப்பிட்டீர்களா\?", "சாப்பிட்டீங்களா?"),
        (r"சாப்பிட்டாயா\?", "சாப்பிட்டியா?"),
        (r"உங்களுக்கு என்ன வேண்டும்\?", "உங்களுக்கு என்ன வேணும்?"),
        (r"உனக்கு என்ன வேண்டும்\?", "உனக்கு என்ன வேணும்?"),
        (r"எனக்கு புரியவில்லை", "எனக்கு புரியல"),
        (r"பரவாயில்லை", "பரவால்ல"),
        (r"கவலைப்படாதீர்கள்", "கவலைப்படாதீங்க"),
        (r"கவலைப்படாதே", "கவலைப்படாத"),
        (r"மன்னிக்கவும்", "சாரி"),
        (r"தயவுசெய்து", "ப்ளீஸ்"),
        (r"மிக்க நன்றி", "ரொம்ப தேங்க்ஸ்"),
        (r"நன்றி", "தேங்க்ஸ்"),
        (r"போய் வருகிறேன்", "போயிட்டு வர்றேன்"),
        (r"சந்திப்போம்", "பாக்கலாம்"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    # Greeting adaptation
    if is_casual_src:
        t = re.sub("^" + _end_wb("வணக்கம்"), "ஹாய்", t)

    # Word-level spoken transformations (Senthamizh -> Pechu Thamizh)
    word_map = [
        # Pronouns
        (_wb("நீங்கள்"), "நீங்க"),
        (_wb("அவர்கள்"), "அவங்க"),
        (_wb("இவர்கள்"), "இவங்க"),
        (_wb("நாங்கள்"), "நாங்க"),
        (_wb("என்னுடைய"), "என்னோட"),
        (_wb("உங்களுடைய"), "உங்களோட"),
        (_wb("அவருடைய"), "அவரோட"),
        (_wb("அவர்களுடைய"), "அவங்களோட"),
        (_wb("அவள்"), "அவ"),

        # Question & direction words
        (_wb("எங்கே"), "எங்க"),
        (_wb("இங்கே"), "இங்க"),
        (_wb("அங்கே"), "அங்க"),
        (_wb("எதற்கு"), "எதுக்கு"),
        (_wb("எப்பொழுது"), "எப்போ"),
        (_wb("எப்போது"), "எப்போ"),
        (_wb("இப்பொழுது"), "இப்போ"),
        (_wb("இப்போது"), "இப்போ"),
        (_wb("அப்பொழுது"), "அப்போ"),
        (_wb("அப்போது"), "அப்போ"),
        (_wb("எவ்வளவு"), "எவ்ளோ"),
        (_wb("அவ்வளவு"), "அவ்ளோ"),
        (_wb("இவ்வளவு"), "இவ்ளோ"),
        (_wb("இல்லை"), "இல்ல"),
        (_wb("வேண்டும்"), "வேணும்"),

        # Verb conjugations (Formal written -> Spoken colloquial)
        (r"இருக்கிறீர்கள்", "இருக்கீங்க"),
        (r"இருக்கிறேன்", "இருக்கேன்"),
        (r"இருக்கிறார்", "இருக்காரு"),
        (r"இருக்கிறார்கள்", "இருக்காங்க"),
        (r"செய்கிறீர்கள்", "பண்றீங்க"),
        (r"செய்கிறேன்", "பண்றேன்"),
        (r"செய்கிறார்", "பண்றாரு"),
        (r"செய்கிறார்கள்", "பண்றாங்க"),
        (r"வருகிறீர்கள்", "வர்றீங்க"),
        (r"வருகிறேன்", "வர்றேன்"),
        (r"வருகிறார்", "வர்றாரு"),
        (r"போகிறீர்கள்", "போறீங்க"),
        (r"போகிறேன்", "போறேன்"),
        (r"போகிறார்", "போறாரு"),
        (r"பார்க்கிறேன்", "பாக்குறேன்"),
        (r"பார்க்கிறீர்கள்", "பாக்குறீங்க"),
        (r"சொல்கிறேன்", "சொல்றேன்"),
        (r"சொல்கிறீர்கள்", "சொல்றீங்க"),
        (r"கொடுக்கிறேன்", "தர்றேன்"),
    ]

    for p, r in word_map:
        t = re.sub(p, r, t)

    return t


def _colloquial_hindi(text: str, is_casual_src: bool) -> str:
    t = text

    # Common full phrase replacements
    phrase_map = [
        (r"नमस्ते,\s*आप कैसे हैं\?", "हाय, क्या हाल है?"),
        (r"आप कैसे हैं\?", "कैसे हो भाई?"),
        (r"तुम कैसे हो\?", "क्या हाल चाल?"),
        (r"आप क्या कर रहे हैं\?", "क्या कर रहे हो?"),
        (r"आप कहाँ जा रहे हैं\?", "कहाँ जा रहे हो?"),
        (r"मुझे ज्ञात नहीं है", "मुझे नहीं पता"),
        (r"मुझे मालूम नहीं है", "मुझे नहीं पता"),
        (r"मुझे समझ में नहीं आया", "समझ नहीं आया यार"),
        (r"चिंता मत करो", "टेंशन मत लो"),
        (r"चिंता न करें", "टेंशन मत लो"),
        (r"बहुत धन्यवाद", "बहुत बहुत शुक्रिया"),
        (r"धन्यवाद", "थैंक्स यार"),
        (r"कृपया", "प्लीज़"),
        (r"क्षमा करें", "सॉरी"),
        (r"मुझे खेद है", "सॉरी यार"),
        (r"अलविदा", "बाय"),
        (r"शुभ रात्रि", "गुड नाइट"),
        (r"कोई बात नहीं", "कोई बात नहीं, चिल कर"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("अत्यंत"), "बहुत"),
        (_wb("किंतु"), "लेकिन"),
        (_wb("परंतु"), "लेकिन"),
        (_wb("तथा"), "और"),
        (_wb("यद्यपि"), "हालांकि"),
        (_wb("भोजन"), "खाना"),
        (_wb("जल"), "पानी"),
        (_wb("गृह"), "घर"),
        (_wb("मित्र"), "दोस्त"),
        (_wb("प्रसन्न"), "खुश"),
        (_wb("तुरंत"), "फटाफट"),
        (_wb("निवास"), "घर"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    if is_casual_src:
        t = re.sub("^" + _end_wb("नमस्ते"), "हाय", t)

    return t


def _colloquial_telugu(text: str, is_casual_src: bool) -> str:
    t = text

    phrase_map = [
        (r"నమస్కారం,\s*మీరు ఎలా ఉన్నారు\?", "హాయ్, ఎలా ఉన్నారు?"),
        (r"మీరు ఎలా ఉన్నారు\?", "ఎలా ఉన్నారు? ఏంటి సంగతులు?"),
        (r"నువ్వు ఎలా ఉన్నావు\?", "ఎలా ఉన్నావ్?"),
        (r"ఏమి చేస్తున్నారు\?", "ఏం చేస్తున్నావ్?"),
        (r"మీరు ఏమి చేస్తున్నారు\?", "ఏం చేస్తున్నారు?"),
        (r"ఎక్కడికి వెళ్తున్నారు\?", "ఎక్కడికి వెళ్తున్నారు?"),
        (r"నాకు అర్థం కాలేదు", "నాకు అర్థం కాలేదు రా"),
        (r"నాకు తెలియదు", "నాకు తెలీదు"),
        (r"చింతించకండి", "టెన్షన్ పడకండి"),
        (r"ధన్యవాదాలు", "థాంక్స్"),
        (r"క్షమించండి", "సారీ"),
        (r"దయచేసి", "ప్లీజ్"),
        (r"సెలవు", "బై"),
        (r"శుభరాత్రి", "గుడ్ నైట్"),
        (r"చాలా బాగుంది", "సూపర్"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("మిత్రుడు"), "ఫ్రెండ్"),
        (_wb("గృహం"), "ఇల్లు"),
        (_wb("భోజనం"), "తిండి"),
        (_wb("జలం"), "నీళ్ళు"),
        (_wb("అత్యంత"), "చాలా"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    t = re.sub(_wb("ఏమిటి"), "ఏంటి", t)
    t = re.sub(_wb("నమస్కారం"), "హాయ్" if is_casual_src else "నమస్తే", t)
    return t


def _colloquial_malayalam(text: str, is_casual_src: bool) -> str:
    t = text

    phrase_map = [
        (r"നമസ്കാരം,\s*നിങ്ങൾ എങ്ങനെയിരിക്കുന്നു\?", "ഹായ്, സുഖമാണോ?"),
        (r"നിങ്ങൾ എങ്ങനെയിരിക്കുന്നു\?", "എങ്ങനെയുണ്ട്? സുഖമാണോ?"),
        (r"എന്തുചെയ്യുന്നു\?", "എന്തൊക്കെയുണ്ട് വിശേഷം?"),
        (r"എനിക്ക് അറിയില്ല", "എനിക്കറിയില്ല"),
        (r"വിഷമിക്കേണ്ട", "ടെൻഷൻ അടിക്കണ്ട"),
        (r"നന്ദി", "താങ്ക്സ്"),
        (r"ക്ഷമിക്കണം", "സോറി"),
        (r"ദയവായി", "പ്ലീസ്"),
        (r"വളരെ നന്ദി", "വളരെ താങ്ക്സ്"),
        (r"വിട", "ബൈ"),
        (r"ശുഭരാത്രി", "ഗുഡ് നൈറ്റ്"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("ഭവനം"), "വീട്"),
        (_wb("മിത്രം"), "കൂട്ടുകാരൻ"),
        (_wb("ജലം"), "വെള്ളം"),
        (_wb("അതിവേഗം"), "പെട്ടെന്ന്"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    if is_casual_src:
        t = re.sub("^" + _end_wb("നമസ്കാരം"), "ഹായ്", t)

    return t


def _colloquial_kannada(text: str, is_casual_src: bool) -> str:
    t = text

    phrase_map = [
        (r"ನಮಸ್ಕಾರ,\s*ನೀವು ಹೇಗಿದ್ದೀರಿ\?", "ಹಾಯ್, ಹೇಗಿದ್ದೀರಾ?"),
        (r"ನೀವು ಹೇಗಿದ್ದೀರಿ\?", "ಹೇಗಿದ್ದೀರಾ? ಏನ್ ಸಮಾಚಾರ?"),
        (r"ನೀನು ಹೇಗಿದ್ದೀಯಾ\?", "ಹೇಗಿದ್ದೀಯಾ?"),
        (r"ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ\?", "ಏನ್ ಮಾಡ್ತಿದ್ದೀರಾ?"),
        (r"ನೀವು ಏನು ಮಾಡುತ್ತಿದ್ದೀರಿ\?", "ಏನ್ ಮಾಡ್ತಿದ್ದೀರಿ?"),
        (r"ನನಗೆ ಗೊತ್ತಿಲ್ಲ", "ನನಗೆ ಗೊತ್ತಿಲ್ಲಪ್ಪ"),
        (r"ಚಿಂತಿಸಬೇಡಿ", "ಟೆನ್ಷನ್ ತಗೋಬೇಡಿ"),
        (r"ಧನ್ಯವಾದಗಳು", "ಥ್ಯಾಂಕ್ಸ್"),
        (r"ಕ್ಷಮಿಸಿ", "ಸಾರಿ"),
        (r"ದಯವಿಟ್ಟು", "ಪ್ಲೀಸ್"),
        (r"ವಿದಾಯ", "ಬೈ"),
        (r"ಶುಭ ರಾತ್ರಿ", "ಗುಡ್ ನೈಟ್"),
        (r"ತುಂಬಾ ಧನ್ಯವಾದಗಳು", "ತುಂಬಾ ಥ್ಯಾಂಕ್ಸ್"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("ಸ್ನೇಹಿತ"), "ಫ್ರೆಂಡ್"),
        (_wb("ಆಹಾರ"), "ಊಟ"),
        (_wb("ಜಲ"), "ನೀರು"),
        (_wb("ಅತ್ಯಂತ"), "ತುಂಬಾ"),
        (r"ಬರುತ್ತೀರಾ\?", "ಬರ್ತೀರಾ?"),
        (r"ಹೋಗುತ್ತೀರಾ\?", "ಹೋಗ್ತೀರಾ?"),
        (r"ನೋಡುತ್ತೀರಾ\?", "ನೋಡ್ತೀರಾ?"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    t = re.sub(_wb("ಏನು"), "ಏನ್", t)
    if is_casual_src:
        t = re.sub("^" + _end_wb("ನಮಸ್ಕಾರ"), "ಹಾಯ್", t)

    return t


def _colloquial_bengali(text: str, is_casual_src: bool) -> str:
    t = text

    phrase_map = [
        (r"নমস্কার,\s*আপনি কেমন আছেন\?", "হাই, কেমন আছ?"),
        (r"আপনি কেমন আছেন\?", "কী খবর? কেমন আছো?"),
        (r"তুমি কেমন আছো\?", "কী খবর? কেমন আছিস?"),
        (r"আপনি কী করছেন\?", "কী করছ?"),
        (r"তুমি কী করছো\?", "কী করছিস?"),
        (r"আমি জানি না", "আমার জানা নেই রে"),
        (r"আমি বুঝতে পারিনি", "আমি বুঝতে পারিনি রে"),
        (r"চিন্তা করবেন না", "টেনশন নিও না"),
        (r"ধন্যবাদ", "থ্যাঙ্কস"),
        (r"দুঃখিত", "সরি"),
        (r"দয়া করে", "প্লিজ"),
        (r"বিদায়", "বাই"),
        (r"শুভ রাত্রি", "গুড নাইট"),
        (r"অনেক ধন্যবাদ", "অনেক থ্যাঙ্কস"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("অত্যন্ত"), "অনেক"),
        (_wb("আহার"), "খাওয়া"),
        (_wb("সত্বর"), "তাড়াতাড়ি"),
        (_wb("গৃহ"), "বাড়ি"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    if is_casual_src:
        t = re.sub("^" + _end_wb("নমস্কার"), "হাই", t)

    return t


def _colloquial_marathi(text: str, is_casual_src: bool) -> str:
    t = text
    phrase_map = [
        (r"नमस्कार,\s*तुम्ही कसे आहात\?", "हाय, काय चाललंय?"),
        (r"तुम्ही कसे आहात\?", "काय मग, कसं काय?"),
        (r"तू कसा आहेस\?", "काय चाललंय भावा?"),
        (r"तुम्ही काय करत आहात\?", "काय करताय?"),
        (r"तू काय करत आहेस\?", "काय करतोयस?"),
        (r"मला माहित नाही", "मला काय माहीत नाही"),
        (r"मला समजले नाही", "मला काय समजलं नाही"),
        (r"काळजी करू नका", "टेन्शन घेऊ नका"),
        (r"धन्यवाद", "थँक्स"),
        (r"माफ करा", "सॉरी"),
        (r"कृपया", "प्लीज"),
        (r"निरोप", "बाय"),
        (r"शुभ रात्री", "गुड नाईट"),
        (r"खूप आभारी आहे", "खूप थँक्स"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("गृह"), "घर"),
        (_wb("अन्न"), "जेवण"),
        (_wb("अत्यंत"), "खूप"),
        (_wb("त्वरित"), "पटकन"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    if is_casual_src:
        t = re.sub("^" + _end_wb("नमस्कार"), "हाय", t)
    return t


def _colloquial_gujarati(text: str, is_casual_src: bool) -> str:
    t = text
    phrase_map = [
        (r"નમસ્તે,\s*તમે કેમ છો\?", "હાય, કેમ છો? શું હાલે?"),
        (r"તમે કેમ છો\?", "શું હાલે ભાઈ? કેમ છો?"),
        (r"તું કેમ છે\?", "શું ચાલે છે?"),
        (r"તમે શું કરી રહ્યા છો\?", "શું કરો છો?"),
        (r"મને ખબર નથી", "મને નથી ખબર"),
        (r"મને સમજાયું નહીં", "મને કંઈ સમજાયું નહીં"),
        (r"ચિંતા કરશો નહીં", "ટેન્શન ના લો"),
        (r"આભાર", "થેંક્સ"),
        (r"માફ કરશો", "સોરી"),
        (r"કૃપા કરીને", "પ્લીઝ"),
        (r"આવજો", "બાય"),
        (r"શુભ રાત્રી", "ગુડ નાઈટ"),
        (r"ખૂબ ખૂબ આભાર", "ખૂબ થેંક્સ"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("મિત્ર"), "દોસ્ત"),
        (_wb("ભોજન"), "જમવાનું"),
        (_wb("જળ"), "પાણી"),
        (_wb("અત્યંત"), "બહુ"),
        (_wb("ત્વરિત"), "જલ્દી"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    if is_casual_src:
        t = re.sub("^" + _end_wb("નમસ્તે"), "હાય", t)
    return t


def _colloquial_punjabi(text: str, is_casual_src: bool) -> str:
    t = text
    phrase_map = [
        (r"ਸਤ ਸ੍ਰੀ ਅਕਾਲ,\s*ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ\?", "ਹੈਲੋ, ਕੀ ਹਾਲ ਹੈ ਜੀ?"),
        (r"ਤੁਸੀਂ ਕਿਵੇਂ ਹੋ\?", "ਕੀ ਹਾਲ ਚਾਲ ਹੈ?"),
        (r"ਤੂੰ ਕਿਵੇਂ ਹੈਂ\?", "ਸਭ ਠੀਕ ਠਾਕ?"),
        (r"ਤੁਸੀਂ ਕੀ ਕਰ ਰਹੇ ਹੋ\?", "ਕੀ ਚੱਲ ਰਿਹਾ ਹੈ?"),
        (r"ਤੂੰ ਕੀ ਕਰ ਰਿਹਾ ਹੈਂ\?", "ਕੀ ਕਰਦਾ ਪਿਆ ਹੈਂ?"),
        (r"ਮੈਨੂੰ ਨਹੀਂ ਪਤਾ", "ਮੈਨੂੰ ਨੀ ਪਤਾ"),
        (r"ਚਿੰਤਾ ਨਾ ਕਰੋ", "ਟੈਂਸ਼ਨ ਨਾ ਲੈ"),
        (r"ਧੰਨਵਾਦ", "ਥੈਂਕਸ ਵੀਰੇ"),
        (r"ਮੁਆਫ਼ ਕਰਨਾ", "ਸੌਰੀ"),
        (r"ਮਾਫ਼ ਕਰਨਾ", "ਸੌਰੀ"),
        (r"ਕਿਰਪਾ ਕਰਕੇ", "ਪਲੀਜ਼"),
        (r"ਅਲਵਿਦਾ", "ਬਾਏ"),
        (r"ਸ਼ੁਭ ਰਾਤ", "ਗੁੱਡ ਨਾਈਟ"),
        (r"ਬਹੁਤ ਬਹੁਤ ਧੰਨਵਾਦ", "ਬਹੁਤ ਥੈਂਕਸ ਵੀਰੇ"),
        (r"ਕੋਈ ਗੱਲ ਨਹੀਂ", "ਕੋਈ ਨਹੀਂ"),
    ]
    for p, r in phrase_map:
        t = re.sub(p, r, t)

    word_map = [
        (_wb("ਮਿੱਤਰ"), "ਯਾਰ"),
        (_wb("ਭੋਜਨ"), "ਖਾਣਾ"),
        (_wb("ਜਲ"), "ਪਾਣੀ"),
        (_wb("ਅਤਿਅੰਤ"), "ਬਹੁਤ"),
        (_wb("ਨਿਵਾਸ"), "ਘਰ"),
    ]
    for p, r in word_map:
        t = re.sub(p, r, t)

    if is_casual_src:
        t = re.sub("^" + _end_wb("ਸਤ ਸ੍ਰੀ ਅਕਾਲ"), "ਹੈਲੋ", t)
    return t
