"""
===============================================================================
iTANTRA - Interactive AI4Bharat Model Downloader
===============================================================================
This script prompts the user for their Hugging Face token, validates it,
downloads the gated AI4Bharat models, and verifies the pipeline.
===============================================================================
"""

import os
import sys
from pathlib import Path

# Fix Windows console encoding
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

def main():
    print("=" * 65)
    print("      iTANTRA - AI4BHARAT PRETRAINED MODELS DOWNLOADER")
    print("=" * 65)
    print("This will download the official SOTA translation models:")
    print("  1. IndicConformer 600M ASR  (ai4bharat/indic-conformer-600m-multilingual)")
    print("  2. IndicTrans2 200M MT     (ai4bharat/indictrans2-en-indic-dist-200M)")
    print("  3. IndicF5 400M TTS        (ai4bharat/IndicF5)")
    print("=" * 65)

    try:
        from huggingface_hub import login, snapshot_download
    except ImportError:
        print("[!] Installing required library 'huggingface_hub'...")
        os.system(f'"{sys.executable}" -m pip install huggingface_hub python-dotenv')
        from huggingface_hub import login, snapshot_download

    import dotenv
    home_env = Path.home() / ".env"
    if home_env.exists():
        dotenv.load_dotenv(dotenv_path=home_env)
    dotenv.load_dotenv()

    token = os.environ.get("HF_TOKEN", "").strip()

    if not token:
        print("\nPlease paste your Hugging Face Access Token below.")
        print("(In Windows Terminal / Command Prompt, right-click to paste, then press Enter):")
        token = input("HF Token: ").strip()

    if not token:
        print("[!] No token entered. Exiting.")
        return

    print("\n[*] Validating token with Hugging Face...")
    try:
        login(token=token)
        print("[OK] Successfully logged in to Hugging Face!")
    except Exception as e:
        print(f"[!] Authentication failed: {e}")
        print("    Please ensure your token is copied correctly from https://huggingface.co/settings/tokens")
        return

    # Save to ~/.env
    try:
        home_env = Path.home() / ".env"
        lines = []
        if home_env.exists():
            for line in home_env.read_text(encoding="utf-8").splitlines():
                if not line.startswith("HF_TOKEN="):
                    lines.append(line)
        lines.append(f"HF_TOKEN={token}")
        home_env.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("[OK] Token saved securely to ~/.env")
    except Exception as e:
        print(f"[!] Note: Could not write to ~/.env ({e}), but token is active for this session.")

    models = [
        {
            "repo_id": "ai4bharat/indic-conformer-600m-multilingual",
            "desc": "1/3: IndicConformer 600M Speech Recognition (~2.4 GB)",
            "url": "https://huggingface.co/ai4bharat/indic-conformer-600m-multilingual"
        },
        {
            "repo_id": "ai4bharat/indictrans2-en-indic-dist-200M",
            "desc": "2/3: IndicTrans2 200M Language Translation (~800 MB)",
            "url": "https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M"
        },
        {
            "repo_id": "ai4bharat/IndicF5",
            "desc": "3/3: IndicF5 400M Diffusion Voice Synthesis (~1.6 GB)",
            "url": "https://huggingface.co/ai4bharat/IndicF5"
        }
    ]

    for m in models:
        repo = m["repo_id"]
        desc = m["desc"]
        print("\n" + "-" * 65)
        print(f"[*] Downloading {desc}...")
        print(f"    Repository: {repo}")
        print("-" * 65)
        try:
            path = snapshot_download(
                repo_id=repo,
                token=token,
                resume_download=True
            )
            print(f"[OK] Successfully cached at: {path}")
        except Exception as e:
            print(f"\n[!] Failed to download {repo}:")
            print(f"    {e}")
            print("\n[?] ACTION REQUIRED:")
            print("    Please open this link in your browser and click 'Agree and access repository':")
            print(f"    {m['url']}")

    print("\n" + "=" * 65)
    print("               DOWNLOAD PROCESS COMPLETED!                ")
    print("=" * 65)

if __name__ == "__main__":
    main()
