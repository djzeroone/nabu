# Voice Lab Handoff

This branch contains the internal Voice Lab prototype for evaluating Nabu's existing production-usable text-to-speech engines and voices.

## Repository

- Upstream repository: `https://github.com/mewmix/nabu`
- Working fork: `https://github.com/djzeroone/nabu`
- Branch: `codex/voice-lab-prototype`
- Pull request: `https://github.com/mewmix/nabu/pull/95`

## Get the Code

```bash
git clone https://github.com/djzeroone/nabu.git
cd nabu
git checkout codex/voice-lab-prototype
```

If the repository was already cloned:

```bash
git fetch fork codex/voice-lab-prototype
git checkout codex/voice-lab-prototype
git pull
```

## Local Requirements

- JDK 17
- Android SDK / command line tools
- Android platform/build tools compatible with the Gradle project
- Optional: Android emulator or physical Android device for runtime TTS validation

On the current development Mac, builds used:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
```

## Build

```bash
./gradlew :app:assembleDebug
```

The debug APK is generated locally under:

```text
app/build/outputs/apk/debug/
```

That directory is intentionally not committed to Git. It is generated machine output and should be rebuilt locally from source.

## Quality Gate

Run the Voice Lab quality gate before making or reviewing changes:

```bash
scripts/check-voice-lab.sh
```

The gate currently runs:

- `./gradlew :app:assembleDebug`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :app:lintDebug`

When an Android emulator or physical device is attached, run the connected Voice Lab smoke gate:

```bash
scripts/check-voice-lab-connected.sh
```

The connected gate runs only `VoiceLabSmokeTest`, which verifies that Voice Lab can be reached from the app shell and that the script, engine, voice, parameter, preview, render, and playback controls are addressable by stable test tags.

## Current Validation State

The latest pushed validation notes are in:

- `docs/voice-lab-quality-baseline.md`
- `docs/voice-lab-smoke-test.md`
- `docs/voice-lab-inventory.md`

At this checkpoint, the prototype builds, unit tests pass, lint passes, Kokoro has been smoke-tested, and Supertonic 2 has been emulator-tested through preview, playback/export path, and WAV file inspection.
