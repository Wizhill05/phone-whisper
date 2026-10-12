<p align="center">
  <img src="docs/logo.svg" width="128" height="128" alt="Phone Whisper Logo">
</p>

# Phone Whisper

Push-to-talk dictation for Android.

Phone Whisper lets you speak into most apps without switching keyboards. Tap the floating button, speak, tap again, and your text is inserted into the currently focused text field when the app exposes a standard Android input field.\

It supports:

- **Cloud transcription** through your own gateway (default path)
- **Manual fallback** with direct OpenAI Whisper transcription
- **Optional cleanup** with OpenAI to fix punctuation and grammar

If you try it and it genuinely saves you time, consider [sponsoring](https://github.com/sponsors/kafkasl)


## Why I built this

- I like SwiftKey and want to keep it as keyboard but...
- Most keyboard dictation felt too inaccurate
- Gemini's voice input auto submits your transcription (which is pretty bad) so you can't edit it before sending
- Post processing yields much better results, specially adding a list of keywords and technical terms you often use
- Inserting text into the field you're already using lets you keep editing it like any other draft.

## Install

### Easiest: download the APK

Grab the latest APK from [GitHub Releases](https://github.com/kafkasl/phone-whisper/releases).

Open it on your phone, install it, then launch the app once to finish setup.

### Build from source

Requires JDK 17 and Android SDK.

```bash
git clone https://github.com/kafkasl/phone-whisper.git && cd phone-whisper
make build
```

APK output:

```bash
app/build/outputs/apk/debug/app-debug.apk
```

If you use ADB:

```bash
make adb-install
```

## How it works

1. A small overlay button floats on screen
2. Tap once to start recording
3. Tap again to stop
4. Audio is transcribed in the cloud (gateway by default, OpenAI fallback)
5. The text is inserted into the focused text field
6. If insertion fails, the text is copied to the clipboard

## Setup

### First-time setup

1. Open **Phone Whisper**
2. Grant the **audio recording** permission
3. Enable the **Accessibility Service**
4. Set your **gateway URL and token** (transcription path)
5. Optionally exempt the app from **battery optimization** so recording stays alive
6. Optionally paste your **OpenAI API key** as a manual fallback

Once setup is done, the floating button is ready.

## Why does it need Accessibility?

Phone Whisper uses Android Accessibility Service for one narrow reason: to insert dictated text into the currently focused text field across apps.

It does **not** replace your keyboard. It does **not** run background automation. It only acts after you explicitly tap the overlay button.

## Privacy

Phone Whisper is cloud-only: recorded audio is sent to your configured
transcription gateway (default path), or directly to OpenAI's transcription
API when you use the manual OpenAI fallback.

- **Gateway mode (default)**: audio is streamed/uploaded to the gateway URL
  you configured, which returns the transcript (already cleaned when the
  gateway provides `clean_text`).
- **OpenAI fallback**: audio is sent directly from your device to OpenAI's
  transcription API using your own API key.
- **Optional cleanup**: transcript text may be sent to OpenAI's chat API
  (OpenAI fallback path) or back to your gateway's cleanup endpoint
  (gateway path, only when the result arrived uncleaned).

I don't run a backend for this app. In gateway mode, requests go from your
phone to the gateway you configured. No audio stays on-device after
dictation and no recordings are collected.

Full policy: [PRIVACY.md](PRIVACY.md)

## Speed architecture

Dictation is optimized for Wispr Flow-style latency with the same gateway
protocol (`WS /v1/dictation/stream` with PCM chunks + `{"action":"finalize"}`,
`POST /v1/dictation` multipart fallback, `POST /v1/cleanup` for raw-only
results):

- The gateway stream pre-connects when the service starts and whenever the
  bubble becomes visible, so tapping the mic never waits on a handshake.
- The mic starts immediately on tap; audio queues and flushes on socket
  open. If the socket is not up within the connect budget (8s default),
  recording continues and uploads as one batch at stop time.
- Results that already contain `clean_text` finish in a single pass — the
  second cleanup call only runs for raw-text-only results.
- Chunk sizes and stream timeouts are remotely tunable via `GET /v1/config`,
  with hardcoded defaults when the gateway does not implement it yet.
  Supported keys: `chunk_bytes`, `connect_timeout_ms`, `result_timeout_ms`.

## Gateway backend (reference: Gemini)

The reference gateway implementation sends the 16 kHz mono WAV directly to
Google's Gemini API — no self-hosted model needed:

- Verbatim transcription: `gemini-3.5-transcribe` (returns `clean_text`
  handling: silence yields no transcript parts, never hallucinated text).
- Polish pass: `gemini-3.5-flash-lite` with the user's post-processing
  prompt; its output becomes `clean_text`, so the app finishes in one pass.
- Inline audio limit is 20 MB per request (~10 minutes at 16 kHz mono),
  far above tap-to-stop dictation sizes.

This means gateway audio is processed by Google under your own API key —
see [PRIVACY.md](PRIVACY.md). A minimal, live-tested backend is included
as `gateway/gemini_backend.py`: `dictate(wav_bytes, prompt)` returns
`{"text", "clean_text"}` behind `GEMINI_API_KEY`.

## Development

```bash
make build       # build debug APK
make test        # run unit tests
make adb-install # build + install via ADB
make clean       # clean build artifacts
```

## App compatibility

Phone Whisper works best in apps that use standard Android text fields.
Some apps use custom text surfaces or terminal-style views, which may not support direct accessibility paste.
When insertion is not possible, Phone Whisper falls back to copying the transcript to the clipboard.

### Termux

Termux's main terminal area is not a standard Android text field, so direct insertion may not work there.

To use Phone Whisper in Termux:

1. Focus Termux
2. Swipe the extra keys row (`ESC`, `CTRL`, `ALT`, arrows, etc.) left or right
3. Switch to Termux's native text input box
4. Dictate there

Once text is inserted into the native input box, Termux sends it to the terminal normally.

## Current limitations

- Accessibility permission is required for cross-app insertion
- Some apps may block paste or text injection
- Some apps use custom input surfaces instead of standard Android text fields
- Gateway mode requires your own reachable gateway
- OpenAI fallback requires your own OpenAI API key

## Support the project

If Phone Whisper saves you time, you can sponsor the project on GitHub:

- https://github.com/sponsors/kafkasl

## License

Personal project. Do whatever you want with it.
