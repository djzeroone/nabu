# Voice Lab Smoke Test

Use this checklist for each device build before treating Voice Lab results as product evidence.

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
