# Interactive and CLI Translator for IndicTrans2 across 10 languages:
# English, Hindi, Tamil, Telugu, Malayalam, Kannada, Bengali, Marathi, Gujarati, Punjabi, Tanglish

import sys
import os
import types
import argparse

# Fix console encoding
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stdin.reconfigure(encoding="utf-8")
    except Exception:
        pass

# Fix Windows SSL certificates using native truststore
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
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
from IndicTransToolkit.processor import IndicProcessor
from colloquial import to_colloquial
from tanglish import is_tanglish, tanglish_to_tamil, tanglish_to_semantic_tamil

LANGUAGES = {
    '1': ('English', 'eng_Latn'),
    '2': ('Tamil', 'tam_Taml'),
    '3': ('Hindi', 'hin_Deva'),
    '4': ('Telugu', 'tel_Telu'),
    '5': ('Malayalam', 'mal_Mlym'),
    '6': ('Kannada', 'kan_Knda'),
    '7': ('Bengali', 'ben_Beng'),
    '8': ('Marathi', 'mar_Deva'),
    '9': ('Gujarati', 'guj_Gujr'),
    '10': ('Punjabi', 'pan_Guru'),
    '11': ('Tanglish', 'tanglish'),
}

CODE_TO_NAME = {code: name for name, code in LANGUAGES.values()}
NAME_TO_CODE = {name.lower(): code for name, code in LANGUAGES.values()}
NAME_TO_CODE.update({code.lower(): code for name, code in LANGUAGES.values()})

MODELS = {
    'en-indic': 'ai4bharat/indictrans2-en-indic-dist-200M',
    'indic-en': 'ai4bharat/indictrans2-indic-en-dist-200M',
    'indic-indic': 'ai4bharat/indictrans2-indic-indic-dist-320M',
}

DEVICE = 'cuda' if torch.cuda.is_available() else 'cpu'

def get_model_name(src_code, tgt_code):
    if src_code == 'eng_Latn' and tgt_code != 'eng_Latn':
        return MODELS['en-indic']
    elif src_code != 'eng_Latn' and tgt_code == 'eng_Latn':
        return MODELS['indic-en']
    elif src_code != 'eng_Latn' and tgt_code != 'eng_Latn':
        return MODELS['indic-indic']
    else:
        raise ValueError('Source and target languages cannot be identical.')

class IndicTranslator:
    def __init__(self, hf_token=None):
        self.hf_token = hf_token or huggingface_hub.get_token() or os.environ.get('HF_TOKEN')
        self.processor = IndicProcessor(inference=True)
        self.loaded_models = {}
        self.loaded_tokenizers = {}

    def load_model(self, model_name):
        if model_name not in self.loaded_models:
            print(f'\n[INFO] Loading {model_name} onto {DEVICE.upper()} (downloaded and cached on first run)...')
            tokenizer = AutoTokenizer.from_pretrained(
                model_name,
                trust_remote_code=True,
                token=self.hf_token,
            )
            model = AutoModelForSeq2SeqLM.from_pretrained(
                model_name,
                trust_remote_code=True,
                token=self.hf_token,
            )
            if DEVICE == 'cuda':
                model = model.half().to('cuda')
            else:
                model = model.to('cpu')
            model.eval()

            self.loaded_tokenizers[model_name] = tokenizer
            self.loaded_models[model_name] = model
            print('[INFO] Model loaded successfully!\n')

        return self.loaded_models[model_name], self.loaded_tokenizers[model_name]

    def translate(self, text, src_code, tgt_code, tone='casual'):
        text = text.strip()
        if not text:
            return ''

        # Tanglish handling (explicit or auto-detected)
        if src_code == 'tanglish' or (src_code in ['eng_Latn', 'tam_Taml'] and is_tanglish(text)):
            if tgt_code == 'tam_Taml':
                return tanglish_to_tamil(text)
            src_code = 'tam_Taml'
            text = tanglish_to_semantic_tamil(text)

        if src_code == tgt_code:
            return text

        model_name = get_model_name(src_code, tgt_code)
        model, tokenizer = self.load_model(model_name)

        batch = self.processor.preprocess_batch([text], src_lang=src_code, tgt_lang=tgt_code)
        inputs = tokenizer(batch, padding='longest', return_tensors='pt').to(DEVICE)

        with torch.no_grad():
            outputs = model.generate(
                **inputs,
                max_length=256,
                num_beams=4,
                use_cache=False,
                num_return_sequences=1,
            )

        decoded = tokenizer.batch_decode(outputs, skip_special_tokens=True)
        translations = self.processor.postprocess_batch(decoded, lang=tgt_code)
        raw_result = translations[0]

        if tone == 'casual':
            return to_colloquial(raw_result, tgt_code, src_text=text)
        return raw_result

def interactive_mode(translator):
    print('\n' + '=' * 68)
    print('   IndicTrans2 Translation System (10 Languages + Tanglish)')
    print('   Mode: Conversational / Everyday Spoken Language & Slang')
    print('   Device:', DEVICE.upper())
    print('=' * 68)

    tone = 'casual'
    while True:
        print('\nSupported Languages:')
        for key, (name, code) in LANGUAGES.items():
            print(f'  [{key:>2}] {name:15} ({code})')

        src_choice = input('\nSelect Source Language [1-11] (or q to quit, t to toggle tone): ').strip()
        if src_choice.lower() in ['q', 'exit', 'quit']:
            print('Exiting translator.')
            break
        if src_choice.lower() == 't':
            tone = 'formal' if tone == 'casual' else 'casual'
            print(f'Tone switched to: {tone.upper()}')
            continue
        if src_choice not in LANGUAGES:
            print('Invalid choice, please enter 1 to 11.')
            continue

        tgt_choice = input('Select Target Language [1-10]: ').strip()
        if tgt_choice not in LANGUAGES or tgt_choice == '11':
            print('Invalid target choice, please select 1 to 10.')
            continue

        src_name, src_code = LANGUAGES[src_choice]
        tgt_name, tgt_code = LANGUAGES[tgt_choice]

        text = input(f'\nEnter text in {src_name} to translate into {tgt_name}: ').strip()
        if not text:
            continue

        try:
            translation = translator.translate(text, src_code, tgt_code, tone=tone)
            print(f'\n[Result - {tone.capitalize()}] ({tgt_name}): {translation}')
        except Exception as e:
            print(f'\n[ERROR] Translation failed: {e}')

def main():
    parser = argparse.ArgumentParser(description='IndicTrans2 Translator with Tanglish & Slang support')
    parser.add_argument('--src', type=str, default='english', help='Source language (e.g. english, tanglish, tamil, etc.)')
    parser.add_argument('--tgt', type=str, default='tamil', help='Target language (e.g. tamil, english, hindi, etc.)')
    parser.add_argument('--text', type=str, help='Text to translate')
    parser.add_argument('--tone', type=str, default='casual', choices=['casual', 'formal'], help='Tone: casual or formal')
    parser.add_argument('--token', type=str, help='HuggingFace token')
    args = parser.parse_args()

    token = args.token or huggingface_hub.get_token() or os.environ.get('HF_TOKEN')
    translator = IndicTranslator(hf_token=token)

    if args.text:
        src_key = args.src.lower()
        tgt_key = args.tgt.lower()
        src_code = NAME_TO_CODE.get(src_key, 'eng_Latn')
        tgt_code = NAME_TO_CODE.get(tgt_key, 'tam_Taml')
        result = translator.translate(args.text, src_code, tgt_code, tone=args.tone)
        print(result)
    else:
        interactive_mode(translator)

if __name__ == '__main__':
    main()
