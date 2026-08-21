# Android Action Runtime Engineering Handoff

Date: 2026-08-20  
Branch: `latest`  
Device: OnePlus CPH2583 (`eb10bd5f`)

## FOUND

- Control surfaces were split across Chat tool execution, the Accessibility overlay,
  `ActionReceiver`, Quick Settings, and voice entry. The overlay and Chat each had partial
  conversation behavior.
- `ActionRequestDispatcher` and `AutomationSessionManager` could assign different IDs to one
  logical run. Dispatcher callbacks could also outlive ownership and publish into a newer run.
- Observation used `UiSnapshotStore`, but dispatch authority was effectively a reusable screen
  identifier. It did not bind package, window, fingerprint, rotation, or display geometry.
- The planner action schema covered a small fixed subset of Android while the executor contained
  additional mechanisms. Accessibility custom action IDs were not represented safely.
- Snapshot nodes did not expose their actual `AccessibilityAction` list, custom labels, range,
  collection, selection, or movement-granularity capabilities to the action model.
- Execution used typed Android intents, selected semantic node actions, global actions, and a
  limited gesture fallback. Gesture construction was not a complete bounded action layer.
- Confirmation existed in Chat and the overlay, but coordinate/custom-action consequences were
  not uniformly bound to the exact observation and destination.
- Verification was strongest for click, text, scroll, and window transitions; several additional
  semantic actions could be treated as successful from `performAction()` alone.
- Tracing existed, but external session identity and latency boundaries were inconsistent.
- The dispatcher accepted an action-model preference, but backend selection could fall through to
  a recent/general chat model. There was no persistent dedicated Action Model setting.
- The production manifest exported a shell-oriented action receiver, release signing could fall
  back to debug signing, and Chat handoff copied the current goal into a new prompt instead of
  attaching to the live action session.

## SHIPPED

### `096ab5b` — Build observation-bound Android action runtime

Files: 26 runtime/test files under `accessibility`, `uiagent`, and `goal`.

Invariant established: every physical action requires a fresh, exact, single-use observation
lease; model-selectable actions are drawn from current observed capabilities or bounded catalogs;
meaningful actions require post-action evidence.

### `3eabd97` — Enforce single ActionSession runner ownership

Files: `ActionRequestDispatcher.kt`, `ActionRequestOwnership.kt`, `SettingsManager.kt`, and
ownership tests.

Invariant established: one external ActionSession ID spans dispatcher, orchestrator,
`AutomationSessionManager`, trace, and callbacks. A monotonic ownership epoch prevents stale jobs
or callbacks from mutating the current session. The configured Action Model is resolved explicitly
and fails closed when unavailable.

### `f2779c7` — Secure release control entry and collapse overlay

Files: release/debug manifests, `app/build.gradle.kts`, `ActionReceiver.kt`, and
`ActionSessionOverlay.kt`.

Invariant established: the shell receiver is debug-only and guarded by `BuildConfig.DEBUG`; a
release without configured release credentials is unsigned rather than debug-signed; the overlay
shrinks to a status chip while physical interaction is active.

### `198397e` — Continue action sessions after Chat handoff

Files: `ActionRequestDispatcher.kt` and `ChatViewModel.kt`.

Invariant established: handoff invalidates the old runner, attaches Chat to the same session ID,
imports bounded action turns once, and routes follow-up text back through
`submitFollowUp()` with the action model and confirmation UI. It no longer re-sends the current
goal through `EXTRA_INITIAL_PROMPT`. Only one action runner can own the session.

## ARCHITECTURE

- **ActionSession:** immutable `StateFlow` snapshots owned process-wide by
  `ActionRequestDispatcher`. `ActionRequestOwnership` grants a monotonic epoch to exactly one job.
  The same ID is passed into the orchestrator, lifecycle manager, trace, overlay, and Chat handoff.
- **Observation:** `UiSnapshotBuilder` captures package/window/display state and node capabilities.
  `ActionObservationLease` binds observation ID, package, window, capability-sensitive
  fingerprint, rotation, and display geometry. The service reconstructs live evidence immediately
  before dispatch and consumes the lease atomically. UI events invalidate outstanding authority.
- **Action catalog:** `AndroidActionCatalog` maps canonical tokens to Android constants and
  metadata. Snapshot-only custom actions are represented as opaque `caN` references; the model
  cannot submit a raw integer action ID.
- **Planner:** sees a compact current-screen projection, current node action tokens/custom refs,
  ranges, and runtime system actions. Only the first mutation plus its immediate assertion may be
  executed before re-observation and replanning.
- **Semantic executor:** validates the target and arguments against the leased observation, then
  invokes standard or custom `AccessibilityNodeInfo` actions. Drag/drop prefers semantic
  start/drop/cancel when both nodes support it.
- **Gesture executor:** `BoundedGestureCatalog` accepts normalized points, at most two strokes,
  eight points per stroke, five seconds per stroke, and six seconds total. Kotlin constructs and
  submits one atomic `GestureDescription` transaction.
- **Global actions:** the snapshot publishes actions returned by `getSystemActions()`. The planner
  may use only catalogued, runtime-available actions. Scheduled policy excludes lock, power, and
  split-screen actions.
- **Verifier:** re-observes and evaluates focus, accessibility focus, selection, text, range,
  visibility, scroll, mutation, drag result, package/window, and system-surface postconditions.
  `performAction() == true` is invocation evidence, not goal completion.
- **Recovery:** stale leases, capability drift, failed invocation, and failed verification return
  structured evidence to the orchestrator. It re-observes and replans within bounded action/model
  budgets; ownership cancellation prevents ghost continuation.
- **Confirmation:** consequence policy binds the requested effect, target/label, destination, and
  current observation. Raw coordinate/custom mechanisms do not bypass confirmation.
- **Tracing:** records action family, semantic action, mechanism, verification status, source and
  result windows, model boundaries, action dispatch/completion, verification, first-action, step,
  and workflow latency fields.

## ACTION SURFACE

Standard node actions supported when advertised by the current node/API:

- focus/accessibility focus and clear variants; select/clear/set selection;
- set text, copy, cut, paste, IME enter, movement granularity, and HTML navigation;
- click, long click, context click, press-and-hold;
- expand, collapse, show-on-screen, show/hide tooltip, dismiss;
- forward/back/up/down/left/right scrolling, paging, position/direction scrolling;
- set progress, move window, show text suggestions; and semantic drag start/drop/cancel.

Gesture primitives: tap point, double tap, long press point, press-and-hold, swipe path,
drag/drop, polyline drag, pinch in/out, and two-finger swipe.

Planner-visible global actions when the device advertises them: Back, Home, Recents,
notifications, Quick Settings, dismiss notification shade, DPAD directions/center, power dialog,
lock screen, and split screen. Power, lock, and split screen are interactive-only.

Typed Android actions preserved: open app, settings page, URL, camera, share text, and share
captured media.

Intentionally excluded from the model: raw action IDs, screenshot, headset-hook, accessibility
button/chooser/shortcut/all-apps. These are sensitive, ambiguous, or control the accessibility
channel itself.

## RELEASE STATE PRESERVATION

- Package: `com.mewmix.nabu`, versionCode `22`, versionName `0.6.0`.
- Installed and candidate certificate SHA-256:
  `ff63128ba761b251d8c8e045280b136f6e5ccb9cd16185ad8fbc738d0c23bd89`.
- Final candidate and pulled installed APK SHA-256:
  `d2026a38790cf7b56fec4c107ab7e90020f9c60405ee09b97947bf1c2adb98e6`.
- Build: `./gradlew app:assembleRelease --no-daemon` — PASS (minified/R8).
- Update: `adb install -r app/build/outputs/apk/release/app-release.apk` — SUCCESS.
- `firstInstallTime` remained `2026-06-28 10:04:19`; final `lastUpdateTime` is
  `2026-08-20 23:54:00`; data remains `/data/user/0/com.mewmix.nabu`.
- `READ_CONTACTS`, `RECORD_AUDIO`, `READ_MEDIA_AUDIO`, and `POST_NOTIFICATIONS` remain granted.
- No uninstall, `pm clear`, or differently signed/debug APK was used.
- Production manifest/package inspection contains no `ActionReceiver` or `ACTION_EXECUTE`.
- Final cold launch: PASS, `MainActivity`, 419 ms; recent logs contained no fatal Nabu crash.

## TESTS

- `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --no-daemon`: PASS.
- Actual JUnit XML totals: **291 tests, 0 failures, 0 errors, 0 skipped**.
- Coverage added for observation leases, capability-sensitive fingerprints, action-catalog/raw-ID
  invariants, compact snapshot projection, bounded gestures, validators, postcondition verifier,
  and dispatcher ownership.
- One asynchronous Robolectric resource warning from `GlobalRuntimeViewModel` appeared after the
  successful suite; it did not fail a test and is unrelated to the action-runtime changes.
- `git diff --check`: PASS for the implementation changes.

## DEVICE VALIDATION

Release/install validation used the physical CPH2583. Nabu Accessibility was disabled before the
first update and remained disabled after the final update (`enabled_accessibility_services` was
empty). No secure setting was changed through ADB, so no live action could obtain an observation or
ActionSession ID.

| Scenario | Starting state / entry | Session ID | Result and evidence | Latency | Status |
|---|---|---:|---|---:|---|
| Same-signer in-place update | Existing release with user data; `adb install -r` | N/A | Candidate signer matched installed signer; install succeeded; first-install time, data directory, and grants preserved | N/A | PASS |
| Release cold launch | Updated package stopped; explicit MainActivity launch | N/A | Activity status `ok`; no matching fatal crash | 419 ms | PASS |
| Production receiver hardening | Final release APK/package inspection | N/A | No `ActionReceiver`/`ACTION_EXECUTE` entry | N/A | PASS |
| Tap through stale-state rejection | Accessibility disabled | Not created | Runtime correctly cannot observe/act | Not measured | BLOCKED |
| Coordinate fallback / long press / double tap | Accessibility disabled | Not created | Not executed | Not measured | BLOCKED |
| Text/focus/selection/copy/paste | Accessibility disabled | Not created | Not executed | Not measured | BLOCKED |
| Semantic/gesture scroll, show-on-screen, progress | Accessibility disabled | Not created | Not executed | Not measured | BLOCKED |
| Expand/collapse/dismiss/context click | Accessibility disabled | Not created | Not executed | Not measured | BLOCKED |
| Semantic and gesture drag/drop; pinch | Accessibility disabled | Not created | Not executed | Not measured | BLOCKED |
| Notifications/Quick Settings/dismiss | Accessibility disabled | Not created | Not executed | Not measured | BLOCKED |
| Home/Recents/Back/DPAD/app launch/switch | Accessibility disabled | Not created | Not executed | Not measured | BLOCKED |
| Temporary conversation / Chat handoff / cancellation | Accessibility disabled | Not created | Unit/static paths pass; physical workflow not executed | Not measured | BLOCKED |
| Telegram workflow | Accessibility disabled | Not created | Not executed; no message was sent | Not measured | BLOCKED |

Lock screen and power dialog remain optional manual terminal checks and were not attempted.

## PERFORMANCE

The runtime now records request/session creation, observation, model request/first response/model
completion, action dispatch/completion, verification, next model request, step, and total session
timestamps. Invocation-to-first-action is derived from request receipt to first dispatch.

No honest live first-action/model/action/verification/workflow distributions or model-call and
recovery counts are available because Accessibility was disabled. Cold application launch was
419 ms; this is not action-runtime latency and must not be used as such.

## FOUND / NOT FIXED

- `NabuVoiceInteractionSession` remains a shallow launch/control surface rather than a rich live
  action console. It still converges on shared app entry, but was not expanded in this change.
- `AutomationSessionManager` remains as the process-lifecycle/checkpoint component beneath the
  canonical ActionSession. Identity is unified, but the two snapshot types were not mechanically
  collapsed into one giant state class.
- R8 emits existing Kotlin metadata compatibility warnings during release minification. The build,
  packaging, signing verification, install, and launch all succeed.
- The full physical action matrix and real latency capture remain unexecuted while Accessibility is
  disabled.

## RELEASE BLOCKERS

- Enable **Nabu Accessibility** manually on the preserved physical device, then execute and record
  the safe live acceptance matrix above. This is the only blocker to claiming real-device action
  acceptance or performance numbers.
- Do not ship based solely on unit tests: stale-state rejection, drawer transitions, semantic versus
  gesture fallback, temporary conversation, Chat handoff, and cancellation require live evidence.

## OVERLAP WITH LATER CLEANUP

- Runtime work touches `AccessibilityToolHandler`, `NabuAccessibilityService`, `UiSnapshot`, the
  `uiagent` planner/orchestrator/schema/verifier stack, `SettingsManager`, manifests,
  `app/build.gradle.kts`, `ActionSessionOverlay`, and `ChatViewModel`.
- The pre-existing user edit in `app-chat/src/main/java/com/mewmix/nabu/ChatScreen.kt`, untracked
  `.zcode/`, and modified `vendor/llama.cpp` were preserved and not committed by this work.
