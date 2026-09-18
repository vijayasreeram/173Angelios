"""
===============================================================================
iTANTRA - AI4Bharat Model Downloader
===============================================================================
Downloads and caches the pretrained weights for:
1. ai4bharat/indic-conformer-600m-multilingual (ASR / STT)
2. ai4bharat/indictrans2-en-indic-dist-200M (MT)
3. ai4bharat/IndicF5 (TTS)
===============================================================================
NOTE: These models are open-source but gated on Hugging Face.
You must first accept the agreement on Hugging Face:
1. https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual
2. https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M
3. Run 'huggingface-cli login' or set HF_TOKEN in your environment.
===============================================================================
"""

import os
import sys
def download_models(hf_token: str = None):
    import dotenv
    from pathlib import Path

    # Load from home directory ~/.env or project .env if exists
    home_env = Path.home() / ".env"
    if home_env.exists():
        dotenv.load_dotenv(dotenv_path=home_env)
    dotenv.load_dotenv()

    token = hf_token or os.environ.get("HF_TOKEN")
    
    print("=================================================================")
    print("   iTANTRA - AI4BHARAT PRETRAINED MODEL WEIGHTS DOWNLOADER       ")
    print("=================================================================")

    try:
        from huggingface_hub import login, snapshot_download
    except ImportError:
        print("[!] huggingface_hub is not installed. Run: pip install huggingface_hub")
        return

    if token:
        print("[*] Logging in to Hugging Face with discovered token...")
        login(token=token)
    else:
        print("[*] Checking existing Hugging Face credentials in cache...")

    models = [
        {"repo_id": "ai4bharat/indic-conformer-600m-multilingual", "desc": "IndicConformer 600M ASR (~2.4 GB)"},
        {"repo_id": "ai4bharat/indictrans2-en-indic-dist-200M", "desc": "IndicTrans2 200M Translation (~800 MB)"},
        {"repo_id": "ai4bharat/IndicF5", "desc": "IndicF5 400M Diffusion TTS (~1.6 GB)"}
    ]

    for m in models:
        repo = m["repo_id"]
        desc = m["desc"]
        print(f"\n[*] Downloading {desc} [{repo}]...")
        try:
            snapshot_path = snapshot_download(
                repo_id=repo,
                token=token,
                resume_download=True
            )
            print(f"[OK] Successfully cached {repo} at: {snapshot_path}")
        except Exception as e:
            print(f"[!] Could not download {repo}: {e}")
            print(f"    Please ensure you have accepted the access terms on https://huggingface.co/{repo}")

if __name__ == "__main__":
    token_arg = sys.argv[1] if len(sys.argv) > 1 else None
    download_models(token_arg)
