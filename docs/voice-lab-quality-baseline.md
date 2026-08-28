# Voice Lab Quality Baseline

Date: 2026-08-28

This document records the current engineering health of the internal Voice Lab prototype. It is not a commercial readiness assessment.

## Current Score

Overall foundation health: 8.1 / 10

Voice Lab is healthy for continued internal TTS evaluation. It is not yet a standalone Creator Voice Studio foundation.

| Area | Score | Notes |
| --- | ---: | --- |
| Scope control | 9 | Voice Lab remains an internal prototype and does not implement out-of-scope commercial features. |
| Reuse of existing Nabu TTS paths | 8 | Kokoro, Supertonic, Soprano, playback, validation, and WAV export paths are reused instead of duplicated. |
| Isolation | 8 | New prototype code is mostly under `voicelab` plus one screen and navigation hooks. |
| Build health | 9 | `assembleDebug`, `testDebugUnitTest`, and `lintDebug` pass after the hardening pass. |
| Runtime confidence | 6 | Kokoro has emulator smoke coverage. Supertonic 2, Supertonic 3, and Soprano still need downloaded-model device smoke tests. |
| Automated coverage | 6 | Pure Voice Lab text/metric behavior is tested. Engine synthesis still depends on device/model smoke testing. |
| Reusability for Creator Voice Studio | 7 | The adapter shape is useful, but UI state should move behind a ViewModel before productizing. |
| Licensing clarity | 7 | Initial factual inventory exists. Human/legal review remains required before commercial distribution. |

## Hardening Completed

- Fixed app-wide `lintDebug` blockers caused by unguarded newer Android accessibility APIs.
- Kept newer accessibility actions available only behind runtime API checks or API-scoped helpers.
- Suppressed intentional inlined API constant warnings where runtime action availability is checked before use.
- Kept the modern `TileService.startActivityAndCollapse(PendingIntent)` path for Android 14+ and explicitly documented the legacy path for older devices.
- Removed noisy `GlobalRuntimeViewModelTest` background initialization by adding an internal test constructor that preserves the production AndroidViewModel constructor.
- Added shared Voice Lab script normalization so blank preview/full-render requests fail with a clear message before engine invocation.
- Added unit coverage for blank script handling and trimming.

## Verification Baseline

Passing checks:

- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:assembleDebug`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:testDebugUnitTest`
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:lintDebug`

Known non-blocking warnings:

- Kotlin compile still reports unrelated deprecations in existing app code, including one accessibility `isHeading` deprecation and several MediaPipe/Compose/API warnings.
- Lint still reports warnings, but no lint errors.

## Remaining Risks

| Risk | Severity | Recommended timing | Notes |
| --- | --- | --- | --- |
| Supertonic 2, Supertonic 3, and Soprano have not been smoke-tested with downloaded models | High | Before using Voice Lab results for product decisions | These engines remain the biggest unknown. |
| Voice Lab UI owns render state directly | Medium | Before adding more comparison workflow complexity | Move to a ViewModel if the prototype grows. |
| Engine synthesis paths are not covered by deterministic unit tests | Medium | After device smoke confirms target behavior | Add fakes or an engine adapter seam before extracting reusable components. |
| Commercial licensing remains unresolved | High | Before any Creator Voice Studio extraction | OpenRAIL/Supertonic and GPL lineage require human/legal review. |
| Model footprint and performance data are incomplete | Medium | During real-device smoke testing | Current measured footprint only covers Kokoro in the emulator run. |

## Next Quality Gate

Before expanding Voice Lab beyond internal engine evaluation:

1. Download Supertonic 2, Supertonic 3, and Soprano models on a real device.
2. Run the smoke checklist in `docs/voice-lab-smoke-test.md`.
3. Record one render result per engine in `docs/voice-lab-inventory.md`.
4. Re-run `assembleDebug`, `testDebugUnitTest`, and `lintDebug`.
5. Decide whether to keep Voice Lab behind a debug/internal gate.
