# Privacy Policy for Phone Whisper

Phone Whisper is an Android dictation app that records speech, transcribes it in the cloud, and inserts the result into text fields across apps.

## Data handling

Phone Whisper is cloud-only. There is no on-device transcription mode.

### Gateway mode (default)

Recorded audio is streamed or uploaded from the device to the transcription
gateway you configured (gateway URL + token in the app) to generate text.
The reference gateway backend uses Google's Gemini API (`gemini-3.5-transcribe`
for transcription, `gemini-3.5-flash-lite` for cleanup) under the gateway
operator's API key, so gateway audio is processed by Google.
When the gateway returns an already-cleaned transcript, no further request
is made; when it returns raw text only, that text is sent back to the
gateway's cleanup endpoint for polishing.

### OpenAI fallback

If you disable the gateway, recorded audio is sent directly from the device
to OpenAI's transcription API to generate text using your own API key.

If optional cleanup is enabled on this path, the transcribed text is also
sent directly from the device to OpenAI's chat API to improve punctuation,
capitalization, and clarity.

## API keys

Your gateway token and (optionally) your OpenAI API key are stored locally
on your device in app storage and used to authenticate requests.

I do not operate a relay server for these requests.

## Accessibility Service

Phone Whisper uses Android Accessibility Service only to identify the currently focused text field and insert dictated text after you explicitly interact with the floating overlay button.

Phone Whisper is not designed to monitor browsing, collect screen content for analytics, or perform background automation.

## Data collection

I do not run a backend for Phone Whisper and do not collect user accounts, analytics, or uploaded recordings myself.

Third-party services you choose to use, such as your gateway host or OpenAI, may process data according to their own terms and privacy policies.

## Contact

For questions about privacy, contact: pol.avms@gmail.com
