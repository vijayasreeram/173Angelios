"""
===============================================================================
iTANTRA - Edge Mesh AI Server (FastAPI + PyTorch + AI4Bharat)
===============================================================================
Exposes REST and WebSocket endpoints for:
1. /api/v1/translate_speech : Full S2S Translation (Audio In -> Translated Audio Out)
2. /api/v1/stt              : IndicConformer Speech-to-Text
3. /api/v1/translate_text   : IndicTrans2 Machine Translation
4. /api/v1/tts              : IndicF5 Text-to-Speech
5. /api/v1/health           : GPU Telemetry (NVIDIA RTX 4050), VRAM & Link Status
===============================================================================
"""

import os
import io
import time
import torch
try:
    import torchaudio
except ImportError:
    torchaudio = None
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import Response, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pipeline import IndicSpeechToSpeechPipeline, DEVICE

app = FastAPI(
    title="iTANTRA Edge AI Speech Translation Server",
    version="2.0.0",
    description="Offline Tactical Speech-to-Speech Translation powered by AI4Bharat IndicConformer, IndicTrans2 & IndicF5"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global Pipeline Instance
pipeline: IndicSpeechToSpeechPipeline = None

@app.on_event("startup")
def startup_event():
    global pipeline
    print("[*] Starting iTANTRA AI4Bharat Edge Server on", DEVICE)
    pipeline = IndicSpeechToSpeechPipeline()

@app.get("/api/v1/health")
def health_check():
    cuda_info = {}
    if torch.cuda.is_available():
        cuda_info = {
            "device_name": torch.cuda.get_device_name(0),
            "allocated_vram_mb": round(torch.cuda.memory_allocated(0) / (1024 * 1024), 2),
            "reserved_vram_mb": round(torch.cuda.memory_reserved(0) / (1024 * 1024), 2),
        }
    return {
        "status": "ONLINE",
        "mesh_node_role": "EDGE_AI_GATEWAY",
        "device": DEVICE,
        "cuda": cuda_info,
        "supported_models": {
            "vad": "Silero VAD (2M)",
            "stt": "AI4Bharat IndicConformer (600M / 120M)",
            "mt": "AI4Bharat IndicTrans2 (200M / 320M)",
            "tts": "AI4Bharat IndicF5 (400M)"
        }
    }

@app.post("/api/v1/translate_speech")
async def translate_speech_endpoint(
    audio_file: UploadFile = File(...),
    src_lang: str = Form("en"),
    tgt_lang: str = Form("ta")
):
    """
    Receives voice audio from Phone A (e.g. English), transcribes via IndicConformer,
    translates via IndicTrans2, and returns Phone B's translated voice (e.g. Tamil).
    """
    temp_in = f"temp_in_{int(time.time() * 1000)}.wav"
    temp_out = f"temp_out_{int(time.time() * 1000)}.wav"
    
    try:
        content = await audio_file.read()
        with open(temp_in, "wb") as f:
            f.write(content)
            
        result = pipeline.translate_speech(
            audio_input_path=temp_in,
            source_language=src_lang,
            target_language=tgt_lang,
            output_audio_path=temp_out
        )
        
        # Read output audio
        with open(temp_out, "rb") as f:
            out_bytes = f.read()
            
        return Response(
            content=out_bytes,
            media_type="audio/wav",
            headers={
                "X-Source-Lang": src_lang,
                "X-Target-Lang": tgt_lang,
                "X-Transcript": result.transcription,
                "X-Translated-Text": result.translated_text,
                "X-Total-Latency-Ms": str(round(result.timings_ms.get("total_ms", 0), 1))
            }
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if os.path.exists(temp_in):
            os.remove(temp_in)
        if os.path.exists(temp_out):
            os.remove(temp_out)

@app.post("/api/v1/translate_text")
def translate_text_endpoint(
    text: str = Form(...),
    src_lang: str = Form("en"),
    tgt_lang: str = Form("ta")
):
    """
    Translates text directly using AI4Bharat IndicTrans2 on NVIDIA RTX 4050 GPU.
    """
    if pipeline is None:
        raise HTTPException(status_code=503, detail="Pipeline not initialized")
    translated = pipeline.mt_model.translate(text, src_lang, tgt_lang)
    return {
        "source_text": text,
        "translated_text": translated,
        "src_lang": src_lang,
        "tgt_lang": tgt_lang,
        "device": DEVICE
    }

if __name__ == "__main__":
    import uvicorn
    print("[*] Launching iTANTRA Edge AI Server on port 8000...")
    uvicorn.run(app, host="0.0.0.0", port=8000)
