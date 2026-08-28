# Voice Lab Smoke Test

Use this checklist for each device build before treating Voice Lab results as product evidence.

Current quality baseline and remaining risk notes are tracked in `docs/voice-lab-quality-baseline.md`.

## Device Setup

- Install the debug APK from `app/build/outputs/apk/debug/app-debug.apk`.
- Open Nabu and navigate to More -> Voice Lab.
- Confirm the screen opens without changing existing app settings.
- Confirm Android volume is audible and Do Not Disturb is not blocking playback.
- Confirm storage/export permissions are granted if prompted.

## Per-Engine Checklist

Run this checklist for Kokoro, Supertonic 2, Supertonic 3, and Soprano where the engine is available.

| Check | Result | Notes |
| --- | --- | --- |
| Engine appears in selector |  |  |
| Availability state is accurate |  |  |
| Missing model state is understandable |  |  |
| Voices/styles appear |  |  |
| Voice metadata is factual |  |  |
| Supported parameter controls appear |  |  |
| Reset restores defaults |  |  |
| Preview renders first sentence/window only |  |  |
| Full render completes |  |  |
| Play works |  |  |
| Pause works |  |  |
| Restart works |  |  |
| WAV export creates a playable file |  |  |
| Export filename includes Voice Lab, engine, and voice |  |  |
| Diagnostics show generation time |  |  |
| Diagnostics show audio duration |  |  |
| Diagnostics show real-time factor |  |  |
| Diagnostics show output size |  |  |
| Diagnostics show model/backend |  |  |
| Repeated previews do not crash or leak obvious resources |  |  |

## Render Samples

Use the default script first:

> Nabu Voice Lab is testing creator narration for Alex Rivera in 2026. The sample includes 42 chapters, $19.95, commas, pauses, and a question: does this voice sound natural? Great, let's render it!

Then run one longer custom script with multiple paragraphs, at least one abbreviation, and at least one number.

## Results To Copy Into Inventory

After testing, update `docs/voice-lab-inventory.md` with:

- render status per engine and voice
- observed generation time and RTF ranges
- crashes or initialization failures
- model directory size after download
- device model and Android version
- backend actually reported by Voice Lab
- export/playback problems

Do not add subjective voice-quality ratings until a human evaluator supplies them.

## Smoke Runs

### 2026-08-27 Emulator: NabuVoiceLabApi35

- Device: `sdk_gphone64_arm64`
- Android: 15 / API 35
- Install: `app-debug.apk` installed successfully
- Navigation: first-run setup completed with default Kokoro selection; More -> Voice Lab opened successfully
- Engine states:
  - Kokoro: Ready, backend reported as `CPU/kokoro_int8`
  - Supertonic 2: Download required
  - Supertonic 3: Download required
  - Soprano: Download required; missing `soprano_backbone_kv.onnx`, `soprano_decoder.onnx`, `soprano_decoder.onnx.data`, `tokenizer.json`
- Kokoro preview:
  - voice: `af_alloy`
  - UI duration: 16.45s
  - log backend/model: `kokoro_int8`, CPU
  - playback started without crash in headless emulator
- Export:
  - exported file: `/sdcard/Music/VOICE_LAB_kokoro_af_alloy_20260827_205244.wav`
  - pulled file size: 789,644 bytes
  - local file inspection: RIFF/WAVE, 16-bit mono PCM, 24,000 Hz
- Storage:
  - app-private `files`: 88 MB
  - copied Kokoro model: `files/models/kokoro-int8/model_int8.onnx`, 92,360,686 bytes
- Not tested:
  - audible voice quality, because emulator was booted headless with `-no-audio`
  - Supertonic 2, Supertonic 3, and Soprano synthesis, because models were not downloaded
