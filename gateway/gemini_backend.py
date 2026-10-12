"""Drop-in Gemini backend for the Phone Whisper dictation gateway.

Mirrors the gateway contract the Android app already speaks:
  POST /v1/dictation (multipart: file=audio.wav, clean=true)
    -> {"text": <verbatim>, "clean_text": <polished>}
  WS  /v1/dictation/stream (binary PCM chunks + {"action":"finalize"})
    -> {"text": ..., "clean_text": ...}  (buffer chunks, run same pipeline)

Verified 2026-10-12 against:
  - gemini-3.5-transcribe  (verbatim; returns audioTranscription.text;
                            silence -> candidate with no parts, no hallucination)
  - gemini-3.5-flash-lite  (cleanup pass, temperature 0.2)

Needs: GEMINI_API_KEY env var. Only dependency: requests.
"""
import base64
import os

import requests

TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
CLEANUP_MODEL = "gemini-3.5-flash-lite"
API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
TIMEOUT = 60

# Transcription must never be refused for profanity in user dictation.
SAFETY_OFF = [
    {"category": c, "threshold": "OFF"}
    for c in (
        "HARM_CATEGORY_HARASSMENT",
        "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
        "HARM_CATEGORY_DANGEROUS_CONTENT",
    )
]


def _post(model: str, body: dict) -> dict:
    key = os.environ["GEMINI_API_KEY"]
    r = requests.post(
        f"{API_BASE}/{model}:generateContent",
        params={"key": key},
        json=body,
        timeout=TIMEOUT,
    )
    r.raise_for_status()
    return r.json()


def transcribe_wav(wav_bytes: bytes) -> str:
    """Verbatim transcript of 16kHz mono WAV. '' when no speech."""
    body = {
        "contents": [
            {
                "parts": [
                    {
                        "text": (
                            "Transcribe this audio verbatim, exactly as spoken, "
                            "including filler words. If there is no speech, "
                            "reply with exactly: EMPTY."
                        )
                    },
                    {
                        "inline_data": {
                            "mime_type": "audio/wav",
                            "data": base64.b64encode(wav_bytes).decode(),
                        }
                    },
                ]
            }
        ],
        "safetySettings": SAFETY_OFF,
    }
    resp = _post(TRANSCRIBE_MODEL, body)
    try:
        text = resp["candidates"][0]["content"]["parts"][0][
            "audioTranscription"
        ]["text"].strip()
    except (KeyError, IndexError):
        return ""  # silence / no candidates: never hallucinate
    return "" if text == "EMPTY" else text


def cleanup_text(text: str, prompt: str | None = None) -> str:
    """Polish pass with the user's post-processing prompt."""
    instruction = prompt or (
        "Clean up this voice transcript for sending as a message. "
        "Remove filler words (um, uh), fix grammar, keep meaning. "
        "Output ONLY the cleaned text, nothing else."
    )
    body = {
        "contents": [{"parts": [{"text": f"{instruction}\n\nTranscript: {text}"}]}],
        "generationConfig": {"temperature": 0.2},
        "safetySettings": SAFETY_OFF,
    }
    resp = _post(CLEANUP_MODEL, body)
    return resp["candidates"][0]["content"]["parts"][0]["text"].strip()


def dictate(wav_bytes: bytes, prompt: str | None = None) -> dict:
    """Full pipeline for POST /v1/dictation. Raises on API errors."""
    text = transcribe_wav(wav_bytes)
    if not text:
        return {"text": "", "clean_text": ""}
    try:
        clean = cleanup_text(text, prompt)
    except Exception:
        clean = text  # cleanup=failed semantics: fall back to raw
    return {"text": text, "clean_text": clean}
