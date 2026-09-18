package org.sih.itantra.ml.semantic

import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.model.SemanticIntent
import org.sih.itantra.domain.model.SemanticMessage

/**
 * Reconstructs natural spoken text in the receiver's preferred language
 * directly from compact semantic intents and entities.
 */
class SemanticDecoder {

    fun decode(semantic: SemanticMessage, targetLanguage: Language = semantic.targetLanguage): String {
        val location = formatLocation(semantic.entities["location"] ?: "field", targetLanguage)
        val count = semantic.entities["count"] ?: "1"
        val resource = formatResource(semantic.entities["resource"] ?: "supplies", targetLanguage)

        return when (semantic.intent) {
            SemanticIntent.EMERGENCY_MEDICAL -> when (targetLanguage) {
                Language.HINDI -> "आपातकाल: $location पर $count व्यक्ति घायल है। तत्काल चिकित्सा सहायता चाहिए।"
                Language.TAMIL -> "அவசரம்: $location அருகில் $count நபர் காயமடைந்துள்ளார். உடனடி மருத்துவ உதவி தேவை."
                Language.TELUGU -> "అత్యవసరం: $location వద్ద $count వ్యక్తి గాయపడ్డాడు. తక్షణ వైద్య సహాయం కావాలి."
                Language.KANNADA -> "ತುರ್ತು: $location ಬಳಿ $count ವ್ಯಕ್ತಿ ಗಾಯಗೊಂಡಿದ್ದಾರೆ. ತಕ್ಷಣ ವೈದ್ಯಕೀಯ ನೆರವು ಬೇಕಾಗಿದೆ."
                Language.MALAYALAM -> "അടിയന്തരാവസ്ഥ: $location ന് സമീപം $count പേർക്ക് പരിക്കേറ്റു. അടിയന്തര മെഡിക്കൽ സഹായം ആവശ്യമാണ്."
                Language.BENGALI -> "জরুরী: $location এর কাছে $count জন আহত। দ্রুত চিকিৎসা সহায়তা প্রয়োজন।"
                Language.MARATHI -> "तातडीची मदत: $location जवळ $count व्यक्ती जखमी आहे. तात्काळ वैद्यकीय मदत आवश्यक आहे."
                Language.GUJARATI -> "કટોકટી: $location પાસે $count વ્યક્તિ ઘાયલ છે. તાત્કાલિક તબીબી સહાય જરૂરી છે."
                Language.PUNJABI -> "ਐਮਰਜੈਂਸੀ: $location ਨੇੜੇ $count ਵਿਅਕਤੀ ਜ਼ਖਮੀ ਹੈ। ਤੁਰੰਤ ਡਾਕਟਰੀ ਸਹਾਇਤਾ ਚਾਹੀਦੀ ਹੈ।"
                Language.ODIA -> "ଆପାତକାଳୀନ: $location ନିକଟରେ $count ଜଣ ଆହତ। ତୁରନ୍ତ ଡାକ୍ତରୀ ସହାୟତା ଆବଶ୍ୟକ।"
                Language.ENGLISH -> "Emergency: $count person injured near $location. Immediate medical assistance required."
            }

            SemanticIntent.RESCUE_REQUEST -> when (targetLanguage) {
                Language.HINDI -> "बचाव अनुरोध: $location पर तत्काल बचाव अभियान प्रारंभ करें।"
                Language.TAMIL -> "மீட்பு கோரிக்கை: $location பகுதியில் உடனடி மீட்பு நடவடிக்கை தேவை."
                Language.TELUGU -> "రక్షణ అభ్యర్థన: $location వద్ద తక్షణ రక్షణ చర్యలు ప్రారంభించండి."
                Language.KANNADA -> "ರಕ್ಷಣಾ ವಿನಂತಿ: $location ನಲ್ಲಿ ತಕ್ಷಣ ರಕ್ಷಣಾ ಕಾರ್ಯಾಚರಣೆ ಅಗತ್ಯವಿದೆ."
                Language.MALAYALAM -> "രക്ഷാപ്രവർത്തന അപേക്ഷ: $location ൽ അടിയന്തര രക്ഷാപ്രവർത്തനം നടത്തുക."
                Language.BENGALI -> "উদ্ধার অনুরোধ: $location এ অবিলম্বে উদ্ধার অভিযান শুরু করুন।"
                Language.MARATHI -> "बचाव विनंती: $location येथे तात्काळ बचाव कार्य सुरू करा."
                Language.GUJARATI -> "બચાવ વિનંતી: $location પર તાત્કાલિક બચાવ કામગીરી શરૂ કરો."
                Language.PUNJABI -> "ਬਚਾਅ ਬੇਨਤੀ: $location ਤੇ ਤੁਰੰਤ ਬਚਾਅ ਮੁਹਿੰਮ ਸ਼ੁਰੂ ਕਰੋ।"
                Language.ODIA -> "ଉଦ୍ଧାର ଅନୁରୋଧ: $location ଠାରେ ତୁରନ୍ତ ଉଦ୍ଧାର କାର୍ଯ୍ୟ ଆରମ୍ଭ କରନ୍ତୁ।"
                Language.ENGLISH -> "Rescue Request: Immediate rescue operation needed at $location."
            }

            SemanticIntent.RESOURCE_SHORTAGE -> when (targetLanguage) {
                Language.HINDI -> "संसाधन चेतावनी: $location पर $resource की गंभीर कमी है।"
                Language.TAMIL -> "வளங்கள் எச்சரிக்கை: $location பகுதியில் $resource தட்டுப்பாடு நிலவுகிறது."
                Language.TELUGU -> "వనరుల కొరత: $location వద్ద $resource కొరత ఉంది."
                Language.KANNADA -> "ಸಂಪನ್ಮೂಲ ಎಚ್ಚರಿಕೆ: $location ನಲ್ಲಿ $resource ತೀವ್ರ ಕೊರತೆಯಿದೆ."
                Language.MALAYALAM -> "വിഭവ ദൗർലഭ്യം: $location ൽ $resource കുറവുണ്ട്."
                Language.BENGALI -> "রসদ সতর্কতা: $location এ $resource এর সংকট।"
                Language.MARATHI -> "साहित्य तुटवडा: $location येथे $resource ची टंचाई आहे."
                Language.GUJARATI -> "સંસાધન ચેતવણી: $location પર $resource ની અછત છે."
                Language.PUNJABI -> "ਸਰੋਤ ਚੇਤਾਵਨੀ: $location ਤੇ $resource ਦੀ ਭਾਰੀ ਘਾਟ ਹੈ।"
                Language.ODIA -> "ସମ୍ବଳ ଚେତାବନୀ: $location ଠାରେ $resource ର ଘୋର ଅଭାବ ଅଛି।"
                Language.ENGLISH -> "Resource Alert: Critical shortage of $resource at $location."
            }

            SemanticIntent.ROUTE_CLEAR -> when (targetLanguage) {
                Language.HINDI -> "मार्ग रिपोर्ट: $location का मार्ग सुरक्षित और साफ़ है।"
                Language.TAMIL -> "பாதை அறிக்கை: $location வழி பாதுகாப்பானது மற்றும் திறந்துள்ளது."
                Language.TELUGU -> "మార్గం నివేదిక: $location మార్గం సురక్షితంగా మరియు క్లియర్‌గా ఉంది."
                Language.KANNADA -> "ಮಾರ್ಗ ವರದಿ: $location ಮಾರ್ಗ ಸುರಕ್ಷಿತವಾಗಿದೆ."
                Language.MALAYALAM -> "പാത റിപ്പോർട്ട്: $location പാത സുരക്ഷിതമാണ്."
                Language.BENGALI -> "রাস্তা রিপোর্ট: $location এর রাস্তা নিরাপদ ও পরিষ্কার।"
                Language.MARATHI -> "रस्ता अहवाल: $location चा रस्ता सुरक्षित आहे."
                Language.GUJARATI -> "રસ્તો અહેવાલ: $location નો રસ્તો સલામત અને સાફ છે."
                Language.PUNJABI -> "ਰਸਤਾ ਰਿਪੋਰਟ: $location ਦਾ ਰਸਤਾ ਸਾਫ਼ ਅਤੇ ਸੁਰੱਖਿਅਤ ਹੈ।"
                Language.ODIA -> "ମାର୍ଗ ରିପୋର୍ଟ: $location ଦେଇ ରାସ୍ତା ସୁରକ୍ଷିତ ଏବଂ ପରିଷ୍କାର।"
                Language.ENGLISH -> "Route Update: Path via $location is confirmed clear and safe."
            }

            SemanticIntent.ALL_CLEAR -> when (targetLanguage) {
                Language.HINDI -> "ऑल क्लियर: $location क्षेत्र पूर्णतः सुरक्षित है।"
                Language.TAMIL -> "அனைத்தும் சரி: $location பகுதி பாதுகாப்பாக உள்ளது."
                Language.TELUGU -> "అంతా సురక్షితం: $location ప్రాంతం క్లియర్ చేయబడింది."
                Language.KANNADA -> "ಎಲ್ಲವೂ ಸುರಕ್ಷಿತ: $location ಪ್ರದೇಶ ಕ್ಷೇಮವಾಗಿದೆ."
                Language.MALAYALAM -> "എല്ലാം സുരക്ഷിതം: $location പ്രദേശം സുരക്ഷിതമാണ്."
                Language.BENGALI -> "অল ক্লিয়ার: $location এলাকা সম্পূর্ণ সুরক্ষিত।"
                Language.MARATHI -> "सर्व सुरक्षित: $location परिसर सुरक्षित आहे."
                Language.GUJARATI -> "ઓલ ક્લિયર: $location વિસ્તાર સંપૂર્ણ સલામત છે."
                Language.PUNJABI -> "ਸਭ ਠੀਕ: $location ਖੇਤਰ ਪੂਰੀ ਤਰ੍ਹਾਂ ਸੁਰੱਖਿਅਤ ਹੈ।"
                Language.ODIA -> "ସବୁ ଠିକ୍ ଅଛି: $location ଅଞ୍ଚଳ ସମ୍ପୂର୍ଣ୍ଣ ସୁରକ୍ଷିତ।"
                Language.ENGLISH -> "All Clear: Sector $location is secured and stable."
            }

            else -> if (semantic.rawText.isNotBlank()) semantic.rawText else "Situation reported at $location."
        }
    }

    private fun formatLocation(loc: String, lang: Language): String = when (loc) {
        "north_checkpoint" -> when (lang) {
            Language.HINDI -> "उत्तर चेकपॉइंट"
            Language.TAMIL -> "வடக்கு சோதனைச் சாவடி"
            Language.TELUGU -> "ఉత్తర చెక్‌పోస్ట్"
            Language.KANNADA -> "ಉತ್ತರ ಚೆಕ್‌ಪೋಸ್ಟ್"
            Language.MALAYALAM -> "വടക്കൻ ചെക്ക്പോസ്റ്റ്"
            Language.BENGALI -> "উত্তর চেকপয়েন্ট"
            Language.MARATHI -> "उत्तर चेकपोस्ट"
            Language.GUJARATI -> "ઉત્તર ચેકપોઇન્ટ"
            Language.PUNJABI -> "ਉੱਤਰੀ ਚੈੱਕਪੋਸਟ"
            Language.ODIA -> "ଉତ୍ତର ଚେକପଏଣ୍ଟ"
            Language.ENGLISH -> "North Checkpoint"
        }
        "flooded_bridge" -> when (lang) {
            Language.HINDI -> "बाढ़ वाला पुल"
            Language.TAMIL -> "வெள்ளப் பாலம்"
            Language.TELUGU -> "వరద వంతెన"
            Language.KANNADA -> "ಮುಳುಗಿದ ಸೇತುವೆ"
            Language.MALAYALAM -> "വെള്ളപ്പൊക്കമുള്ള പാലം"
            Language.BENGALI -> "বন্যা ব্রিজে"
            Language.MARATHI -> "पुराचा पूल"
            Language.GUJARATI -> "પૂરગ્રસ્ત પુલ"
            Language.PUNJABI -> "ਹੜ੍ਹ ਵਾਲਾ ਪੁਲ"
            Language.ODIA -> "ବନ୍ୟା ପ୍ରଭାବିତ ପୋଲ"
            Language.ENGLISH -> "Flooded Bridge"
        }
        "base_camp" -> when (lang) {
            Language.HINDI -> "बेस कैंप"
            Language.TAMIL -> "தள முகாம்"
            Language.TELUGU -> "బేస్ క్యాంప్"
            Language.KANNADA -> "ಬೇಸ್ ಕ್ಯಾಂಪ್"
            Language.MALAYALAM -> "ബേസ് ക്യാമ്പ്"
            Language.BENGALI -> "বেস ক্যাম্প"
            Language.MARATHI -> "बेस कॅम्प"
            Language.GUJARATI -> "બેઝ કેમ્પ"
            Language.PUNJABI -> "ਬੇਸ ਕੈਂਪ"
            Language.ODIA -> "ବେସ୍ କ୍ୟାମ୍ପ"
            Language.ENGLISH -> "Base Camp"
        }
        else -> loc.replace('_', ' ')
    }

    private fun formatResource(res: String, lang: Language): String = when (res) {
        "drinking_water" -> when (lang) {
            Language.HINDI -> "पीने का पानी"
            Language.TAMIL -> "குடிநீர்"
            Language.TELUGU -> "త్రాగునీరు"
            Language.KANNADA -> "ಕುಡಿಯುವ ನೀರು"
            Language.MALAYALAM -> "കുടിവെള്ളം"
            Language.BENGALI -> "পানীয় জল"
            Language.MARATHI -> "पिण्याचे पाणी"
            Language.GUJARATI -> "પીવાનું પાણી"
            Language.PUNJABI -> "ਪੀਣ ਵਾਲਾ ਪਾਣੀ"
            Language.ODIA -> "ପିଇବା ପାଣି"
            Language.ENGLISH -> "drinking water"
        }
        "medical_oxygen" -> when (lang) {
            Language.HINDI -> "मेडिकल ऑक्सीजन"
            Language.TAMIL -> "மருத்துவ ஆக்ஸிஜன்"
            Language.TELUGU -> "వైద్య ఆక్సిజన్"
            Language.KANNADA -> "ವೈದ್ಯಕೀಯ ಆಮ್ಲಜನಕ"
            Language.MALAYALAM -> "മെഡിക്കൽ ഓക്സിജൻ"
            Language.BENGALI -> "মেডিকেল অক্সিজেন"
            Language.MARATHI -> "वैद्यकीय ऑक्सिजन"
            Language.GUJARATI -> "મેડિકલ ઓક્સિજન"
            Language.PUNJABI -> "ਮੈਡੀਕਲ ਆਕਸੀਜਨ"
            Language.ODIA -> "ଡାକ୍ତରୀ ଅକ୍ସିଜେନ"
            Language.ENGLISH -> "medical oxygen"
        }
        else -> res.replace('_', ' ')
    }
}
