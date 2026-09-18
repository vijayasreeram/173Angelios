import os

files = [
    r"C:\Users\CMRMuthuthiyagarajan\Downloads\PS-2(SIH)\core\src\main\kotlin\org\sih\itantra\ml\semantic\SemanticDecoder.kt",
    r"C:\Users\CMRMuthuthiyagarajan\Downloads\PS-2(SIH)\app\src\main\java\org\sih\itantra\ml\semantic\SemanticDecoder.kt"
]

for fpath in files:
    with open(fpath, "r", encoding="utf-8") as f:
        code = f.read()

    code = code.replace(
        'Language.PUNJABI -> "ਐਮਰਜੈਂਸੀ: $location ਨੇੜੇ $count ਵਿਅਕਤੀ ਜ਼ਖਮੀ ਹੈ। ਤੁਰੰਤ ਡਾਕਟਰੀ ਸਹਾਇਤਾ ਚਾਹੀਦੀ ਹੈ।"',
        'Language.PUNJABI -> "ਐਮਰਜੈਂਸੀ: $location ਨੇੜੇ $count ਵਿਅਕਤੀ ਜ਼ਖਮੀ ਹੈ। ਤੁਰੰਤ ਡਾਕਟਰੀ ਸਹਾਇਤਾ ਚਾਹੀਦੀ ਹੈ।"\n                Language.ODIA -> "ଆପାତକାଳୀନ: $location ନିକଟରେ $count ଜଣ ଆହତ। ତୁରନ୍ତ ଡାକ୍ତରୀ ସହାୟତା ଆବଶ୍ୟକ।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਬਚਾਅ ਬੇਨਤੀ: $location ਤੇ ਤੁਰੰਤ ਬਚਾਅ ਮੁਹਿੰਮ ਸ਼ੁਰੂ ਕਰੋ।"',
        'Language.PUNJABI -> "ਬਚਾਅ ਬੇਨਤੀ: $location ਤੇ ਤੁਰੰਤ ਬਚਾਅ ਮੁਹਿੰਮ ਸ਼ੁਰੂ ਕਰੋ।"\n                Language.ODIA -> "ଉଦ୍ଧାର ଅନୁରୋଧ: $location ଠାରେ ତୁରନ୍ତ ଉଦ୍ଧାର କାର୍ଯ୍ୟ ଆରମ୍ଭ କରନ୍ତୁ।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਸਰੋਤ ਚੇਤਾਵਨੀ: $location ਤੇ $resource ਦੀ ਭਾਰੀ ਘਾਟ ਹੈ।"',
        'Language.PUNJABI -> "ਸਰੋਤ ਚੇਤਾਵਨੀ: $location ਤੇ $resource ਦੀ ਭਾਰੀ ਘਾਟ ਹੈ।"\n                Language.ODIA -> "ସମ୍ବଳ ଚେତାବନୀ: $location ଠାରେ $resource ର ଘୋର ଅଭାବ ଅଛି।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਰਸਤਾ ਰਿਪੋਰਟ: $location ਦਾ ਰਸਤਾ ਸਾਫ਼ ਅਤੇ ਸੁਰੱਖਿਅਤ ਹੈ।"',
        'Language.PUNJABI -> "ਰਸਤਾ ਰਿਪੋਰਟ: $location ਦਾ ਰਸਤਾ ਸਾਫ਼ ਅਤੇ ਸੁਰੱਖਿਅਤ ਹੈ।"\n                Language.ODIA -> "ମାର୍ଗ ରିପୋର୍ଟ: $location ଦେଇ ରାସ୍ତା ସୁରକ୍ଷିତ ଏବଂ ପରିଷ୍କାର।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਸਭ ਠੀਕ: $location ਖੇਤਰ ਪੂਰੀ ਤਰ੍ਹਾਂ ਸੁਰੱਖਿਅਤ ਹੈ।"',
        'Language.PUNJABI -> "ਸਭ ਠੀਕ: $location ਖੇਤਰ ਪੂਰੀ ਤਰ੍ਹਾਂ ਸੁਰੱਖਿਅਤ ਹੈ।"\n                Language.ODIA -> "ସବୁ ଠିକ୍ ଅଛି: $location ଅଞ୍ଚଳ ସମ୍ପୂର୍ଣ୍ଣ ସୁରକ୍ଷିତ।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਸਥਿਤੀ ਰਿਪੋਰਟ: ਆਪਣੀ ਮੌਜੂਦਾ ਸਥਿਤੀ ਅਤੇ ਸਥਾਨ ਦੀ ਜਾਣਕਾਰੀ ਦਿਓ।"',
        'Language.PUNJABI -> "ਸਥਿਤੀ ਰਿਪੋਰਟ: ਆਪਣੀ ਮੌਜੂਦਾ ਸਥਿਤੀ ਅਤੇ ਸਥਾਨ ਦੀ ਜਾਣਕਾਰੀ ਦਿਓ।"\n                Language.ODIA -> "ସ୍ଥିତି ଯାଞ୍ଚ: ଆପଣଙ୍କର ବର୍ତ୍ତମାନର ସ୍ଥିତି ଏବଂ ସ୍ଥାନ ଜଣାନ୍ତୁ।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਰੇਡੀਓ ਜਾਂਚ: ਸੰਪਰਕ ਸਫਲਤਾਪੂਰਵਕ ਸਥਾਪਿਤ ਹੋਇਆ ਹੈ। ਆਵਾਜ਼ ਸਪਸ਼ਟ ਹੈ।"',
        'Language.PUNJABI -> "ਰੇਡੀਓ ਜਾਂਚ: ਸੰਪਰਕ ਸਫਲਤਾਪੂਰਵਕ ਸਥਾਪਿਤ ਹੋਇਆ ਹੈ। ਆਵਾਜ਼ ਸਪਸ਼ਟ ਹੈ।"\n                Language.ODIA -> "ରେଡିଓ ଯାଞ୍ଚ: ଯୋଗାଯୋଗ ସଫଳତାର ସହ ସ୍ଥାପିତ ହୋଇଛି।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਰੇਡੀਓ ਜਾਂਚ: ਸੰਪਰਕ ਸਫਲਤਾਪੂਰਵਕ ਸਥਾਪਿਤ ਹੋਇਆ ਹੈ।"',
        'Language.PUNJABI -> "ਰੇਡੀਓ ਜਾਂਚ: ਸੰਪਰਕ ਸਫਲਤਾਪੂਰਵਕ ਸਥਾਪਿਤ ਹੋਇਆ ਹੈ।"\n                Language.ODIA -> "ରେଡିଓ ଯାଞ୍ଚ: ଯୋଗାଯୋଗ ସଫଳତାର ସହ ସ୍ଥାପିତ ହୋଇଛି।"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਪੁਸ਼ਟੀ: ਸੁਨੇਹਾ ਮਿਲਿਆ। ਹੁਕਮ ਦੀ ਪੁਸ਼ਟੀ ਕੀਤੀ ਗਈ।"',
        'Language.PUNJABI -> "ਪੁਸ਼ਟੀ: ਸੁਨੇਹਾ ਮਿਲਿਆ। ਹੁਕਮ ਦੀ ਪੁਸ਼ਟੀ ਕੀਤੀ ਗਈ।"\n                Language.ODIA -> "ସ୍ୱୀକୃତି: ବାର୍ତ୍ତା ଗ୍ରହଣ କରାଗଲା। ନିର୍ଦ୍ଦେଶ ନିଶ୍ଚିତ ହେଲା।"'
    )

    # Locations
    code = code.replace(
        'Language.PUNJABI -> "ਉੱਤਰੀ ਚੈੱਕਪੋਸਟ"',
        'Language.PUNJABI -> "ਉੱਤਰੀ ਚੈੱਕਪੋਸਟ"\n            Language.ODIA -> "ଉତ୍ତର ଚେକପଏଣ୍ଟ"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਹੜ੍ਹ ਵਾਲਾ ਪੁਲ"',
        'Language.PUNJABI -> "ਹੜ੍ਹ ਵਾਲਾ ਪੁਲ"\n            Language.ODIA -> "ବନ୍ୟା ପ୍ରଭାବିତ ପୋଲ"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਬੇਸ ਕੈਂਪ"',
        'Language.PUNJABI -> "ਬੇਸ ਕੈਂਪ"\n            Language.ODIA -> "ବେସ୍ କ୍ୟାମ୍ପ"'
    )

    # Resources
    code = code.replace(
        'Language.PUNJABI -> "ਪੀਣ ਵਾਲਾ ਪਾਣੀ"',
        'Language.PUNJABI -> "ਪੀਣ ਵਾਲਾ ਪਾਣੀ"\n            Language.ODIA -> "ପିଇବା ପାଣି"'
    )
    code = code.replace(
        'Language.PUNJABI -> "ਮੈਡੀਕਲ ਆਕਸੀਜਨ"',
        'Language.PUNJABI -> "ਮੈਡੀਕਲ ਆਕਸੀਜਨ"\n            Language.ODIA -> "ଡାକ୍ତରୀ ଅକ୍ସିଜେନ"'
    )

    with open(fpath, "w", encoding="utf-8") as f:
        f.write(code)
    print("Updated:", fpath)
