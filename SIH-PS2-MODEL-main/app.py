import sys
import os
import types

# Fix console encoding
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stdin.reconfigure(encoding="utf-8")
    except Exception:
        pass

# Fix Windows SSL certificates using truststore
try:
    import truststore
    truststore.inject_into_ssl()
except ImportError:
    pass

# =========================================================================
# Transformers 5.x Compatibility Patches for IndicTrans2
# =========================================================================

import transformers

# 1. Mock transformers.onnx (removed in transformers 5.x)
onnx_mock = types.ModuleType("transformers.onnx")
class DummyOnnxConfig:
    default_fixed_batch = 2
    default_fixed_sequence = 8
class DummyOnnxSeq2SeqConfigWithPast:
    pass
onnx_mock.OnnxConfig = DummyOnnxConfig
onnx_mock.OnnxSeq2SeqConfigWithPast = DummyOnnxSeq2SeqConfigWithPast

onnx_utils_mock = types.ModuleType("transformers.onnx.utils")
def compute_effective_axis_dimension(axis_dim, fixed_dimension=None, num_token_to_add=0):
    return fixed_dimension if axis_dim == -1 else axis_dim
onnx_utils_mock.compute_effective_axis_dimension = compute_effective_axis_dimension

onnx_mock.utils = onnx_utils_mock
sys.modules["transformers.onnx"] = onnx_mock
sys.modules["transformers.onnx.utils"] = onnx_utils_mock
transformers.onnx = onnx_mock

# 2. Patch PreTrainedTokenizerBase.__setattr__ for IndicTransTokenizer
from transformers.tokenization_utils_base import PreTrainedTokenizerBase
orig_setattr = PreTrainedTokenizerBase.__setattr__
def safe_setattr(self, name, value):
    if "_special_tokens_map" not in self.__dict__:
        object.__setattr__(self, "_special_tokens_map", {})
    orig_setattr(self, name, value)
PreTrainedTokenizerBase.__setattr__ = safe_setattr

# 3. Patch dynamic module loading for tie_weights recompute_mapping argument
import transformers.dynamic_module_utils as dmu
orig_get_class = dmu.get_class_from_dynamic_module
def patched_get_class(*args, **kwargs):
    cls = orig_get_class(*args, **kwargs)
    if hasattr(cls, "tie_weights"):
        cls.tie_weights = lambda self, *a, **kw: None
    return cls
dmu.get_class_from_dynamic_module = patched_get_class

# =========================================================================

import torch
import huggingface_hub
from flask import Flask, render_template_string, request, jsonify
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
from IndicTransToolkit.processor import IndicProcessor
from colloquial import to_colloquial
from tanglish import is_tanglish, tanglish_to_tamil, tanglish_to_semantic_tamil

app = Flask(__name__)

LANGUAGES = [
    {"code": "eng_Latn", "name": "English", "native": "English"},
    {"code": "tam_Taml", "name": "Tamil", "native": "தமிழ்"},
    {"code": "hin_Deva", "name": "Hindi", "native": "हिन्दी"},
    {"code": "tel_Telu", "name": "Telugu", "native": "తెలుగు"},
    {"code": "mal_Mlym", "name": "Malayalam", "native": "മലയാളം"},
    {"code": "kan_Knda", "name": "Kannada", "native": "ಕನ್ನಡ"},
    {"code": "ben_Beng", "name": "Bengali", "native": "বাংলা"},
    {"code": "mar_Deva", "name": "Marathi", "native": "मराठी"},
    {"code": "guj_Gujr", "name": "Gujarati", "native": "ગુજરાતી"},
    {"code": "pan_Guru", "name": "Punjabi", "native": "ਪੰਜਾਬੀ"},
]

MODELS = {
    "en-indic": "ai4bharat/indictrans2-en-indic-dist-200M",
    "indic-en": "ai4bharat/indictrans2-indic-en-dist-200M",
    "indic-indic": "ai4bharat/indictrans2-indic-indic-dist-320M",
}

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

def get_model_name(src_code, tgt_code):
    if src_code == "eng_Latn" and tgt_code != "eng_Latn":
        return MODELS["en-indic"]
    elif src_code != "eng_Latn" and tgt_code == "eng_Latn":
        return MODELS["indic-en"]
    elif src_code != "eng_Latn" and tgt_code != "eng_Latn":
        return MODELS["indic-indic"]
    else:
        raise ValueError("Source and target languages cannot be identical.")

class TranslationEngine:
    def __init__(self):
        self.processor = IndicProcessor(inference=True)
        self.loaded_models = {}
        self.loaded_tokenizers = {}

    def get_token(self):
        return huggingface_hub.get_token() or os.environ.get("HF_TOKEN")

    def load_model(self, model_name):
        token = self.get_token()
        if not token:
            raise ValueError(
                "Hugging Face token is required to access IndicTrans2 models. Please enter your HF token."
            )

        if model_name not in self.loaded_models:
            print(f"[INFO] Loading model {model_name} onto {DEVICE.upper()}...")
            tokenizer = AutoTokenizer.from_pretrained(
                model_name,
                trust_remote_code=True,
                token=token,
            )
            model = AutoModelForSeq2SeqLM.from_pretrained(
                model_name,
                trust_remote_code=True,
                token=token,
            )
            if DEVICE == "cuda":
                model = model.half().to("cuda")
            else:
                model = model.to("cpu")
            model.eval()

            self.loaded_tokenizers[model_name] = tokenizer
            self.loaded_models[model_name] = model
            print(f"[INFO] Model {model_name} loaded successfully!")

        return self.loaded_models[model_name], self.loaded_tokenizers[model_name]

    def translate(self, text, src_code, tgt_code, tone="casual"):
        text = text.strip()
        if not text:
            return ""

        # Tanglish Handling (auto-detect or explicit choice)
        if src_code == "tanglish" or (src_code in ["eng_Latn", "tam_Taml"] and is_tanglish(text)):
            if tgt_code == "tam_Taml":
                return tanglish_to_tamil(text)
            # If translating to another language (English, Hindi, Telugu, etc.), use semantic Tamil
            src_code = "tam_Taml"
            text = tanglish_to_semantic_tamil(text)

        if src_code == tgt_code:
            return text

        cache_key = f"{src_code}->{tgt_code}:{text}:{tone}"
        if not hasattr(self, "cache"):
            self.cache = {}
        if cache_key in self.cache:
            return self.cache[cache_key]

        model_name = get_model_name(src_code, tgt_code)
        model, tokenizer = self.load_model(model_name)

        batch = self.processor.preprocess_batch([text], src_lang=src_code, tgt_lang=tgt_code)
        inputs = tokenizer(batch, padding="longest", return_tensors="pt").to(DEVICE)

        word_count = len(text.split())
        beams = 1 if word_count <= 8 else 2
        tokens = min(64, max(24, word_count * 3))

        with torch.no_grad():
            outputs = model.generate(
                **inputs,
                max_new_tokens=tokens,
                num_beams=beams,
                use_cache=False,
                repetition_penalty=1.2,
                num_return_sequences=1,
            )

        decoded = tokenizer.batch_decode(outputs, skip_special_tokens=True)
        translations = self.processor.postprocess_batch(decoded, lang=tgt_code)
        raw_result = translations[0]

        if tone == "casual":
            final_res = to_colloquial(raw_result, tgt_code, src_text=text)
        else:
            final_res = raw_result

        self.cache[cache_key] = final_res
        return final_res

engine = TranslationEngine()

HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>IndicTrans2 - Conversational Translator with Tanglish</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Inter', sans-serif; }
        body { background: #f0f4f9; color: #1e293b; min-height: 100vh; display: flex; flex-direction: column; }
        .header { background: #ffffff; border-bottom: 1px solid #e2e8f0; padding: 1.25rem 2rem; display: flex; justify-content: space-between; align-items: center; }
        .header-title { font-size: 1.35rem; font-weight: 700; color: #0f172a; display: flex; align-items: center; gap: 0.5rem; }
        .badge { background: #e0e7ff; color: #4338ca; font-size: 0.75rem; font-weight: 600; padding: 0.25rem 0.6rem; border-radius: 9999px; }
        .tanglish-badge { background: #fef3c7; color: #b45309; font-size: 0.75rem; font-weight: 600; padding: 0.25rem 0.6rem; border-radius: 9999px; }
        .device-badge { background: #dcfce7; color: #166534; font-size: 0.75rem; font-weight: 600; padding: 0.25rem 0.6rem; border-radius: 9999px; }
        .container { max-width: 1060px; width: 100%; margin: 2rem auto; padding: 0 1rem; flex: 1; }
        .token-banner { background: #fffbeb; border: 1px solid #fef3c7; border-left: 4px solid #f59e0b; padding: 1rem; border-radius: 8px; margin-bottom: 1.5rem; display: flex; flex-direction: column; gap: 0.5rem; }
        .token-banner a { color: #d97706; text-decoration: underline; font-weight: 600; }
        .token-input-row { display: flex; gap: 0.5rem; margin-top: 0.5rem; }
        .token-input { flex: 1; padding: 0.6rem 0.75rem; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 0.9rem; }
        .btn-token { background: #f59e0b; color: white; border: none; padding: 0.6rem 1.2rem; border-radius: 6px; font-weight: 600; cursor: pointer; }
        .btn-token:hover { background: #d97706; }
        .card { background: white; border-radius: 12px; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.05), 0 2px 4px -2px rgba(0,0,0,0.05); border: 1px solid #e2e8f0; overflow: hidden; }
        .lang-bar { display: flex; align-items: center; justify-content: space-between; padding: 1rem 1.5rem; background: #fafafa; border-bottom: 1px solid #e2e8f0; flex-wrap: wrap; gap: 0.75rem; }
        .lang-select-group { display: flex; align-items: center; gap: 0.75rem; }
        select { padding: 0.55rem 1rem; border: 1px solid #cbd5e1; border-radius: 8px; background: white; font-size: 0.95rem; font-weight: 500; outline: none; cursor: pointer; }
        select:focus { border-color: #4f46e5; }
        .btn-swap { background: #f1f5f9; border: 1px solid #cbd5e1; border-radius: 50%; width: 38px; height: 38px; display: flex; align-items: center; justify-content: center; cursor: pointer; transition: all 0.2s; }
        .btn-swap:hover { background: #e2e8f0; transform: rotate(180deg); }
        .tone-selector { display: flex; align-items: center; gap: 0.4rem; background: #f1f5f9; padding: 0.3rem 0.4rem; border-radius: 8px; border: 1px solid #e2e8f0; }
        .tone-btn { border: none; background: transparent; padding: 0.4rem 0.8rem; font-size: 0.85rem; font-weight: 600; border-radius: 6px; cursor: pointer; transition: all 0.2s; color: #475569; }
        .tone-btn.active { background: #ffffff; color: #4f46e5; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
        .panels { display: grid; grid-template-columns: 1fr 1fr; border-bottom: 1px solid #e2e8f0; }
        @media (max-width: 768px) { .panels { grid-template-columns: 1fr; } }
        .panel { padding: 1.25rem; display: flex; flex-direction: column; }
        .panel:first-child { border-right: 1px solid #e2e8f0; }
        textarea { width: 100%; height: 240px; border: none; outline: none; resize: none; font-size: 1.05rem; line-height: 1.6; color: #0f172a; background: transparent; }
        .output-box { height: 240px; overflow-y: auto; font-size: 1.05rem; line-height: 1.6; color: #0f172a; white-space: pre-wrap; }
        .bottom-bar { padding: 1rem 1.5rem; display: flex; justify-content: space-between; align-items: center; background: #ffffff; }
        .btn-primary { background: #4f46e5; color: white; border: none; padding: 0.75rem 1.75rem; border-radius: 8px; font-weight: 600; font-size: 1rem; cursor: pointer; transition: background 0.2s; display: flex; align-items: center; gap: 0.5rem; }
        .btn-primary:hover { background: #4338ca; }
        .btn-primary:disabled { background: #94a3b8; cursor: not-allowed; }
        .btn-copy { background: #f8fafc; border: 1px solid #e2e8f0; padding: 0.5rem 0.9rem; border-radius: 6px; font-size: 0.85rem; cursor: pointer; }
        .btn-copy:hover { background: #f1f5f9; }
        .languages-tag-list { display: flex; gap: 0.5rem; margin-top: 1.5rem; flex-wrap: wrap; justify-content: center; }
        .lang-tag { background: white; border: 1px solid #e2e8f0; border-radius: 9999px; padding: 0.35rem 0.85rem; font-size: 0.8rem; color: #64748b; font-weight: 500; }
        .status-msg { font-size: 0.9rem; color: #64748b; }
        .spinner { width: 18px; height: 18px; border: 2px solid #ffffff; border-top-color: transparent; border-radius: 50%; animation: spin 0.8s linear infinite; display: none; }
        @keyframes spin { to { transform: rotate(360deg); } }
    </style>
</head>
<body>
    <header class="header">
        <div class="header-title">
            🌐 IndicTrans2 Translator
            <span class="badge">Conversational</span>
            <span class="tanglish-badge">Tanglish Auto-Detect</span>
        </div>
        <div>
            <span class="device-badge">Device: {{ device.upper() }}</span>
        </div>
    </header>

    <div class="container">
        {% if not has_token %}
        <div class="token-banner" id="tokenBanner">
            <strong>⚠️ Hugging Face Token Required (One-time setup)</strong>
            <span>IndicTrans2 models are gated by AI4Bharat on Hugging Face (free access).</span>
            <span>1. Accept terms at <a href="https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M" target="_blank">model page</a>.</span>
            <span>2. Generate a free read token at <a href="https://huggingface.co/settings/tokens" target="_blank">HF Tokens</a>.</span>
            <div class="token-input-row">
                <input type="password" id="hfTokenInput" class="token-input" placeholder="Paste your token here (hf_...)" />
                <button class="btn-token" onclick="saveToken()">Save Token</button>
            </div>
        </div>
        {% endif %}

        <div class="card">
            <div class="lang-bar">
                <div class="lang-select-group">
                    <label>Source:</label>
                    <select id="srcLang">
                        <option value="eng_Latn" selected>English (English)</option>
                        <option value="tanglish">Tanglish (தங்கிலீஷ் / Tamil in English)</option>
                        {% for lang in languages %}
                        {% if lang.code != 'eng_Latn' %}
                        <option value="{{ lang.code }}">{{ lang.name }} ({{ lang.native }})</option>
                        {% endif %}
                        {% endfor %}
                    </select>
                </div>

                <button class="btn-swap" onclick="swapLanguages()" title="Swap languages">⇄</button>

                <div class="lang-select-group">
                    <label>Target:</label>
                    <select id="tgtLang">
                        {% for lang in languages %}
                        <option value="{{ lang.code }}" {% if lang.code == 'tam_Taml' %}selected{% endif %}>{{ lang.name }} ({{ lang.native }})</option>
                        {% endfor %}
                    </select>
                </div>

                <!-- Tone / Slang Selector -->
                <div class="tone-selector">
                    <button class="tone-btn active" id="toneCasual" onclick="setTone('casual')" title="Normal spoken human interaction / slang">🗣️ Spoken / Slang</button>
                    <button class="tone-btn" id="toneFormal" onclick="setTone('formal')" title="Formal, bookish, literary language">📜 Formal</button>
                </div>
            </div>

            <div class="panels">
                <div class="panel">
                    <textarea id="srcText" placeholder="Type here in English, Tanglish (e.g. 'epdi da irruka', 'enna pandra'), or any Indic language..." oninput="handleInput()"></textarea>
                </div>
                <div class="panel">
                    <div id="tgtText" class="output-box" placeholder="Translation will appear here..."></div>
                </div>
            </div>

            <div class="bottom-bar">
                <span class="status-msg" id="statusMsg">Ready (Tanglish & Slang auto-enabled)</span>
                <div style="display: flex; gap: 0.75rem; align-items: center;">
                    <button class="btn-copy" onclick="copyTranslation()">Copy</button>
                    <button id="translateBtn" class="btn-primary" onclick="doTranslate()">
                        <div class="spinner" id="spinner"></div>
                        <span>Translate</span>
                    </button>
                </div>
            </div>
        </div>

        <div class="languages-tag-list">
            <span class="lang-tag" style="background: #fef3c7; color: #b45309; font-weight: 600;">Tanglish (தங்கிலீஷ்)</span>
            {% for lang in languages %}
            <span class="lang-tag">{{ lang.name }} ({{ lang.native }})</span>
            {% endfor %}
        </div>
    </div>

    <script>
        let currentTone = 'casual';

        function setTone(tone) {
            currentTone = tone;
            document.getElementById('toneCasual').classList.toggle('active', tone === 'casual');
            document.getElementById('toneFormal').classList.toggle('active', tone === 'formal');
            const statusMsg = document.getElementById('statusMsg');
            statusMsg.innerText = tone === 'casual' ? 'Ready (Spoken / Casual mode enabled)' : 'Ready (Formal mode enabled)';
            if (document.getElementById('srcText').value.trim()) {
                doTranslate();
            }
        }

        function swapLanguages() {
            const srcSelect = document.getElementById('srcLang');
            const tgtSelect = document.getElementById('tgtLang');
            const temp = srcSelect.value;
            srcSelect.value = tgtSelect.value;
            tgtSelect.value = temp;

            const srcText = document.getElementById('srcText');
            const tgtText = document.getElementById('tgtText');
            if (tgtText.innerText.trim()) {
                srcText.value = tgtText.innerText.trim();
                tgtText.innerText = '';
            }
        }

        function handleInput() {
            const statusMsg = document.getElementById('statusMsg');
            statusMsg.innerText = currentTone === 'casual' ? 'Ready (Spoken / Casual mode)' : 'Ready (Formal mode)';
        }

        async function saveToken() {
            const token = document.getElementById('hfTokenInput').value.trim();
            if (!token) return alert('Please enter your Hugging Face token');

            try {
                const res = await fetch('/api/set_token', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ token })
                });
                const data = await res.json();
                if (data.success) {
                    alert('Token saved successfully!');
                    const banner = document.getElementById('tokenBanner');
                    if (banner) banner.style.display = 'none';
                } else {
                    alert(data.error || 'Failed to save token');
                }
            } catch (err) {
                alert('Error saving token: ' + err);
            }
        }

        async function doTranslate() {
            const text = document.getElementById('srcText').value.trim();
            const srcLang = document.getElementById('srcLang').value;
            const tgtLang = document.getElementById('tgtLang').value;

            if (!text) return;

            const btn = document.getElementById('translateBtn');
            const spinner = document.getElementById('spinner');
            const statusMsg = document.getElementById('statusMsg');
            const tgtBox = document.getElementById('tgtText');

            btn.disabled = true;
            spinner.style.display = 'inline-block';
            statusMsg.innerText = 'Translating...';

            try {
                const res = await fetch('/api/translate', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ text, src_lang: srcLang, tgt_lang: tgtLang, tone: currentTone })
                });
                const data = await res.json();

                if (data.success) {
                    tgtBox.innerText = data.translation;
                    statusMsg.innerText = 'Translation complete (' + (currentTone === 'casual' ? 'Spoken' : 'Formal') + ')';
                } else {
                    tgtBox.innerText = '';
                    statusMsg.innerText = 'Error: ' + data.error;
                    alert('Translation error: ' + data.error);
                }
            } catch (err) {
                statusMsg.innerText = 'Failed to connect to server';
                alert('Request failed: ' + err);
            } finally {
                btn.disabled = false;
                spinner.style.display = 'none';
            }
        }

        function copyTranslation() {
            const text = document.getElementById('tgtText').innerText;
            if (text) {
                navigator.clipboard.writeText(text);
                const statusMsg = document.getElementById('statusMsg');
                statusMsg.innerText = 'Copied to clipboard!';
            }
        }
    </script>
</body>
</html>
"""

@app.route("/")
def index():
    has_token = bool(engine.get_token())
    return render_template_string(
        HTML_TEMPLATE,
        languages=LANGUAGES,
        device=DEVICE,
        has_token=has_token,
    )

@app.route("/api/set_token", methods=["POST"])
def set_token():
    data = request.get_json() or {}
    token = data.get("token", "").strip()
    if not token:
        return jsonify({"success": False, "error": "Token is required"}), 400
    try:
        huggingface_hub.login(token=token)
        os.environ["HF_TOKEN"] = token
        return jsonify({"success": True})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/translate", methods=["POST"])
def translate():
    data = request.get_json() or {}
    text = data.get("text", "")
    src_lang = data.get("src_lang")
    tgt_lang = data.get("tgt_lang")
    tone = data.get("tone", "casual")

    if not text:
        return jsonify({"success": False, "error": "No text provided"}), 400
    if not src_lang or not tgt_lang:
        return jsonify({"success": False, "error": "Source and target languages are required"}), 400

    try:
        translated = engine.translate(text, src_lang, tgt_lang, tone=tone)
        return jsonify({"success": True, "translation": translated})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

@app.route("/api/status", methods=["GET"])
def status():
    return jsonify({
        "status": "online",
        "device": DEVICE,
        "cuda_available": torch.cuda.is_available(),
        "models_loaded": list(engine.loaded_models.keys()),
        "cached_entries": len(getattr(engine, "cache", {}))
    })

@app.route("/api/pack/<lang>", methods=["GET"])
def get_language_pack(lang):
    import json
    clean_lang = lang.strip().replace(".json", "")
    pack_path = os.path.join(os.path.dirname(__file__), "packs", f"{clean_lang}.json")
    if os.path.exists(pack_path):
        with open(pack_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return jsonify(data)
    # Fallback to app assets packs if present
    alt_pack_path = os.path.join(r"C:\Users\CMRMuthuthiyagarajan\Downloads\PS-2(SIH)\app\src\main\assets\packs", f"{clean_lang}.json")
    if os.path.exists(alt_pack_path):
        with open(alt_pack_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return jsonify(data)
    return jsonify({"success": False, "error": f"Language pack {clean_lang} not found"}), 404

if __name__ == "__main__":
    port = 5000
    print(f"Starting IndicTrans2 Web App at http://127.0.0.1:{port}")
    app.run(host="0.0.0.0", port=port, debug=False)
