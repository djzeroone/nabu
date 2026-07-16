# Nabu Deep Automation Specification

Status: implementation handoff  
Baseline: `latest` after `8be55e1` (`Restore Glaive file tool bridge`)  
Primary implementer: Gemini  
Review and cleanup owner: Codex

## 1. Objective

Extend `control_ui` into a bounded, local-LLM automation engine capable of reliable multi-screen and multi-app workflows while preserving explicit safety boundaries.

Target workflows include:

1. Navigating deeply into Android settings.
2. Opening Telegram and sending a test message to Saved Messages.
3. Opening the camera, switching to the front camera, capturing a selfie, and preparing an image message to a user-provided test number.
4. Combining registered Nabu, native accessibility, and Glaive file tools in one traceable workflow.

“Complete device control” means broad automation within Android’s public intent, accessibility, permission, and app-sandbox boundaries. It does not mean bypassing the lock screen, Android permissions, app authentication, security confirmations, or Nabu policy.

## 2. Non-negotiable constraints

- Do not add an arbitrary `launch_intent` action.
- Do not accept model-provided component names, intent flags, MIME types, arbitrary extras, or unrestricted URIs.
- Do not execute actions against a locked device.
- Do not automate passwords, passcodes, two-factor authentication, payment, account deletion, permission escalation, unknown APK installation, or security confirmations.
- Do not send a message, publish content, place a call, or capture a photo without a just-in-time confirmation.
- Do not hardcode or commit test phone numbers, contact details, message bodies, or captured media.
- Do not move file tools out of Glaive. Glaive remains the owner of its six current file tools.
- Do not route normal Android intents through `AccessibilityToolHandler`.
- Do not expose unsupported tools merely because an older fallback schema mentioned them.
- Do not raise the action limit to 50.

## 3. Capability ownership

### 3.1 Nabu native accessibility

Owned by `NabuAccessibilityService` and `AccessibilityToolHandler`:

- `observe_ui`
- `read_screen`
- `take_screenshot`
- `read_ui_xml`
- `ui_tap`
- `ui_long_press`
- `ui_set_text`
- `ui_scroll`
- `ui_global_action`

### 3.2 Nabu typed Android actions

Owned by `ActionTools` and `DeviceAction`:

- `open_app`
- `open_url`
- `share_text`
- `take_photo`
- `record_video`
- alarms, timers, calendar, navigation, media, flashlight, volume, and other existing device actions
- new typed settings and camera actions defined below

### 3.3 Glaive

Glaive remains responsible for its currently implemented bridge tools:

- `list_files`
- `read_file`
- `write_file`
- `create_dir`
- `delete_file`
- `search_files`

Nabu must route these through `GlaiveBridgeRelayActivity` to Glaive’s `BridgeActivity`. Accessibility availability must not affect Glaive routing.

## 4. Foundation work required before deeper automation

### 4.1 Fix click fallback

Current behavior only dispatches a coordinate gesture when no node is found. If a node is found but `ACTION_CLICK` or `ACTION_LONG_CLICK` returns false, the action fails without trying validated fallback bounds.

Required behavior:

1. Resolve the node from the observed selector.
2. Attempt the node action and walk actionable parents.
3. If the node action fails and validated `fallback_bounds` are present, dispatch a gesture at the bounds center.
4. Return failure only if both mechanisms fail.
5. Record which mechanism succeeded in the execution trace.

### 4.2 Make scheduled `read_screen` executable

Adding `read_screen` to `schedulableToolNames` is insufficient. `ScheduledAgentStepExecutor` must explicitly route it to `AccessibilityToolHandler`.

Scheduled screen inspection requirements:

- Device must be interactive and unlocked.
- Nabu accessibility service must be connected.
- The result returned to the scheduled agent should be XML content or a compact indexed summary, not only a cache path.
- The generated cache file must be deleted in a `NonCancellable` cleanup block.
- Scheduled screen reading must never silently enable the screen, dismiss the lock screen, or expose password nodes.

### 4.3 Correct global trigger routing

The Quick Settings tile and accessibility button currently open `MainActivity`. If the product claim is “open Nabu Chat,” both entry points must use one shared helper that launches `ChatActivity` or launches `MainActivity` with an explicit Chat destination.

Requirements:

- Preserve an already-running Chat session when possible.
- Avoid duplicate Chat activities.
- Use `FLAG_ACTIVITY_NEW_TASK` where required by service context.
- Use `CLEAR_TOP`/`SINGLE_TOP` only with verified task-stack behavior.
- Add an instrumentation test for both entry-point intents.

### 4.4 Observation behavior and API compatibility

- Android 10/API 29 must not call API 30 screenshot methods.
- XML observation must succeed even when screenshots are unavailable or disabled.
- Screenshot capture should be requested only when the selected planner backend consumes it or when an explicit screenshot tool is invoked.
- All generated XML and PNG files must be deleted after success, failure, timeout, or cancellation.

## 5. Bounded automation loop

### 5.0 Cross-application transitions

For multi-app goals, Nabu resolves goal-relevant launcher applications in trusted code and
includes their labels and package names in planner state. `open_app` may only use a package
from that resolved set, unless the user explicitly supplied the package name.

After an app launch, settings intent, share intent, camera intent, global navigation action,
or ordinary UI action, the orchestrator must not assume the next accessibility snapshot is
already current. It polls bounded observations until the screen or foreground package changes,
accepts intermediate Android system windows for subsequent planning, and then continues the
same automation session in the new application.

### 5.1 Budgets

Use the following defaults:

- Maximum executed actions: 12.
- Maximum wall-clock duration: 180 seconds.
- Maximum planner retries per observation: 1.
- Maximum identical action on an unchanged screen: 1.
- Maximum unchanged observations: 3, but only when the last action could plausibly be asynchronous.
- Maximum single wait: 5 seconds.
- Maximum cumulative wait: 20 seconds.

Budgets must be centralized in an `AutomationBudget` value object and included in the trace. Tests must be able to inject smaller budgets.

### 5.2 Structured action history

Replace `List<String>` history entries such as `"tap"` with structured entries:

```kotlin
data class UiActionHistoryEntry(
    val index: Int,
    val action: String,
    val targetElementId: String?,
    val targetLabel: String?,
    val sourceScreenId: String,
    val resultScreenId: String?,
    val outcome: Outcome,
    val changedScreen: Boolean,
    val detail: String?
)
```

`Outcome` is `SUCCEEDED`, `FAILED`, `BLOCKED`, `DENIED`, or `TIMED_OUT`.

Planner input should include at most the most recent eight entries plus a compact summary of older successful state transitions. Never include typed secrets or captured screen text from password nodes.

Example planner context:

```json
{
  "goal": "open Settings and select Display",
  "history": [
    {
      "action": "tap",
      "target_label": "Settings",
      "outcome": "succeeded",
      "screen_changed": true
    }
  ]
}
```

### 5.3 Repetition detection

Before execution, derive an action fingerprint from:

- canonical action type;
- canonical target element ID or typed action arguments;
- current screen ID;
- destination package where applicable.

If the same fingerprint already succeeded on the same screen and no asynchronous transition is pending, reject it and replan once with an explicit repetition error. A second repeated proposal terminates the run.

### 5.4 Progress, cancellation, and lifecycle

- Every phase must be visible: observe, plan, validate, confirm, execute, verify, cleanup.
- A persistent Cancel action must cancel the current run.
- Chat activity transitions must not destroy the automation coroutine or LLM backend.
- The run must terminate and clean artifacts when the initiating process is killed.
- Only one active `control_ui` session may own the global observation/action channel. Concurrent scheduled and interactive sessions must queue or fail clearly.

## 6. Typed planner actions

Add explicit `UiActionStep` variants. The parser accepts only the exact schemas below and must reject unknown fields where practical.

### 6.1 Open app

```json
{
  "action": "open_app",
  "package_name": "org.telegram.messenger"
}
```

Rules:

- Package must be installed and launchable.
- Package must come from the installed-app resolver or the user’s explicit request.
- No component/class name is accepted.
- Execution delegates to `ActionTools`/`DeviceAction.openApp`.

### 6.2 Open settings page

```json
{
  "action": "open_settings_page",
  "page": "wireless_debugging"
}
```

Use a closed enum mapped in trusted code:

- `wifi`
- `bluetooth`
- `display`
- `sound`
- `accessibility`
- `notification_settings`
- `app_details`
- `developer_options`
- `wireless_debugging`

Rules:

- The model supplies only the enum and, for `app_details`, an installed package name.
- Trusted code owns every Android action string and URI.
- `developer_options`, `wireless_debugging`, accessibility settings, and app permission pages are high-risk navigation.
- Opening a high-risk page requires confirmation.
- Changing a security-sensitive toggle requires a second just-in-time confirmation.
- These actions are never allowed from scheduled/background execution.

### 6.3 Open URL

```json
{
  "action": "open_url",
  "url": "https://example.com/path"
}
```

Rules:

- HTTPS only by default.
- Reject embedded credentials, `javascript:`, `intent:`, `file:`, and unknown schemes.
- HTTP may be supported only after an explicit user confirmation and a visible warning.
- Execution delegates to `DeviceAction.openUrl` after policy validation.

### 6.4 Share text

```json
{
  "action": "share_text",
  "text": "Hello from Nabu",
  "target_package": "org.telegram.messenger"
}
```

Rules:

- Trusted code uses `ACTION_SEND` and `text/plain`.
- Only `EXTRA_TEXT` and optional trusted subject are supported.
- Target package must be installed and user-selected or explicitly named.
- This action may open a chooser or destination app, but it must not press the final Send button without confirmation.
- Message text must appear verbatim in the confirmation preview.

### 6.5 Open camera

```json
{
  "action": "open_camera",
  "mode": "photo",
  "facing": "front",
  "capture_output": true
}
```

Allowed modes are `photo` and `video`. Allowed facing values are `front`, `rear`, and `unspecified`.

Rules:

- Camera launch uses trusted `MediaStore` actions.
- Do not claim that a standard intent can reliably force front-camera selection across OEM camera apps.
- When front-facing selection is not honored, the accessibility planner may locate and press the camera-switch control.
- Capturing a photo requires just-in-time confirmation.
- A capture intended for sharing must use a Nabu-owned `MediaStore` or `FileProvider` content URI with temporary read permission.
- Never use a `file:` URI.
- Delete failed or abandoned temporary captures.

### 6.6 Global UI actions

Extend `ui_global_action` with typed values:

- `back`
- `home`
- `recents`
- `notifications`
- `quick_settings`

These map only to trusted `AccessibilityService.GLOBAL_ACTION_*` constants supported by the current Android version.

Screenshot remains a separate `take_screenshot` capability and is not a global action.

## 7. Intent policy engine

Create a centralized `AutomationIntentPolicy` used before any typed action launches an intent.

It must return one of:

- `ALLOW`
- `REQUIRE_CONFIRMATION(reason, preview)`
- `BLOCK(reason)`

Policy inputs include action type, destination package, URI scheme/host, content preview, foreground/background state, lock state, and whether the user explicitly named the destination.

Minimum blocked cases:

- explicit component/class names from model output;
- model-provided intent flags;
- arbitrary extras;
- `file:` URIs;
- unapproved `content:` authorities;
- `intent:`, `javascript:`, shell, package-installer, or unknown schemes;
- actions targeting package installers, device-admin enrollment, credential UI, payment UI, or authentication approval;
- external-effect actions from an unattended scheduled run.

## 8. Confirmation model

Confirmation must occur at the commit boundary, not merely at the beginning of the workflow.

Examples:

- Opening Telegram: no confirmation if explicitly requested.
- Selecting Saved Messages: no confirmation.
- Typing the test message: preview may be shown, but no external effect yet.
- Pressing Telegram Send: confirmation required immediately before the tap.
- Opening Camera: no confirmation if explicitly requested.
- Pressing shutter: confirmation required immediately before capture.
- Opening the messaging composer with an image attached: confirmation required.
- Pressing the final MMS Send button: a second confirmation is required unless the immediately preceding confirmation explicitly covered that exact destination, attachment, and send action.

Confirmation state must bind to:

- action fingerprint;
- observed screen ID;
- destination package/contact;
- message text hash or attachment URI;
- short expiry time.

A screen change, target change, or expiry invalidates approval.

## 9. Tool discovery and local-LLM prompting

All registered tools should remain discoverable through `list_tools`, but not all tool descriptions should be inserted into every 2K-token LiteRT prompt.

Implement capability metadata:

```kotlin
data class ToolCapability(
    val owner: Owner,
    val risk: Risk,
    val schedulable: Boolean,
    val requiresUnlockedDevice: Boolean,
    val requiresConfirmation: Boolean,
    val aliases: Set<String>
)
```

The prompt builder should select only relevant tools plus `list_tools`. The registry remains complete; prompt exposure is relevance-filtered and deterministic.

Tool results and failures must be fed back to the planner in compact form. A failed tool must never be represented as a successful history entry.

## 10. Live workflow specifications

Live tests are opt-in and must never run in normal CI. Each external effect requires a human confirmation on the device.

### 10.1 Telegram Saved Messages

Preconditions:

- Telegram installed and already authenticated.
- Device awake and unlocked.
- Nabu accessibility enabled.
- A unique test message supplied at runtime, for example `NABU_TEST_<timestamp>`.

Workflow:

1. Launch Telegram through typed `open_app`.
2. Observe the current Telegram UI.
3. Navigate to Saved Messages using visible labels or search.
4. Verify the destination label is exactly Saved Messages.
5. Type the runtime-provided test string.
6. Present confirmation containing destination and exact message.
7. Tap Send only after approval.
8. Re-observe and verify a visible outgoing message matching the unique string.
9. Record success without deleting the sent message unless the user explicitly requests cleanup.

Failure conditions:

- Planner selects any human/group contact.
- Destination cannot be uniquely verified.
- Telegram requests login, passcode, or 2FA.
- Confirmation is denied or expires.

### 10.2 Selfie capture and image message

An image attachment makes this an MMS/RCS-style workflow, not plain SMS.

Preconditions:

- Runtime recipient supplied through instrumentation arguments or local environment; never committed.
- Camera and messaging apps installed.
- Device awake and unlocked.
- Camera permission already granted by the user.

Workflow:

1. Create a temporary output content URI owned by Nabu.
2. Launch the trusted camera photo action.
3. Observe camera UI.
4. If front camera is not active, locate and press the camera-switch control.
5. Present confirmation immediately before shutter activation.
6. Capture photo.
7. Verify the output URI contains a non-empty image.
8. Open a trusted image-share/MMS composer with temporary URI read permission and runtime recipient.
9. Verify recipient and attachment in the visible composer.
10. Present final confirmation showing recipient and attachment thumbnail/metadata.
11. Press Send only after approval.
12. Verify a sent-state indicator when accessible.
13. Revoke URI grants and remove temporary data according to retention policy.

Failure conditions:

- Camera permission prompt appears during unattended execution.
- Front/rear state cannot be determined.
- Captured output is empty.
- Recipient cannot be verified exactly.
- Messaging app drops the attachment.
- Any confirmation is denied or expires.

## 11. Scheduling policy

Allowed scheduled automation:

- read-only screen inspection while awake and unlocked;
- existing background-safe tools;
- bounded `control_ui` navigation that requires no confirmation and has no external effect.

Disallowed scheduled automation:

- sending or posting content;
- camera or microphone capture;
- changing security/developer settings;
- permission grants;
- destructive file actions;
- any action that would normally require interactive confirmation.

Scheduled runs must fail clearly rather than waiting indefinitely for confirmation.

## 12. Automated test requirements

### 12.1 Unit tests

- Parser accepts each typed action’s canonical schema.
- Parser rejects unknown action values, fields, URI schemes, extras, flags, and component names.
- Intent policy allow/confirm/block matrix.
- Settings enum maps only to trusted constants.
- Structured history contains target and outcome.
- History truncation preserves recent actions and bounded size.
- Repeated action fingerprint detection.
- Action-count, wait, retry, and wall-clock budgets.
- Confirmation binding and expiry.
- Failed execution is recorded as failed and never as successful.
- Glaive tools remain routed to Glaive when accessibility is disabled.
- Native accessibility tools never fall through to Glaive.
- Scheduled `read_screen` routes correctly and deletes artifacts.
- Screenshot behavior is API-level and backend aware.

### 12.2 Instrumentation tests

- Accessibility service connection/disconnection.
- XML observation without screenshot.
- Node click success.
- Node click failure followed by coordinate fallback.
- Stale observation rejection.
- Concurrent automation session rejection/serialization.
- Quick Settings and accessibility-button Chat intents.
- Typed open-app/settings/url/camera intents.
- FileProvider/MediaStore URI grants and revocation.
- Cancellation cleanup for XML, screenshots, and captured media.
- Glaive provider discovery and file-tool relay.

### 12.3 Fixture-driven planner tests

Use recorded/synthetic XML fixtures for:

- Nabu Chat and Main screens;
- Android Settings lists and toggles;
- Telegram chat list, search, Saved Messages, composer, and sent message;
- camera controls for representative OEM layouts;
- messaging composer with image attachment.

Tests must not depend on generated screenshots when XML is sufficient.

### 12.4 Opt-in live tests

Live tests require all of:

- explicit build/test flag;
- unlocked physical device;
- runtime test data;
- visible human confirmation;
- persistent logs and artifact cleanup verification.

The test recipient must be passed at runtime, for example through an instrumentation argument named `test_recipient`. Do not place the user-provided number in source control.

## 13. Acceptance criteria

The feature is ready for live external-effect testing only when:

1. Full unit suite passes.
2. Debug and release builds pass.
3. Instrumentation sources compile.
4. Native accessibility instrumentation tests pass on the target device.
5. Glaive file-tool relay test passes.
6. Scheduled `read_screen` returns useful content and leaves no artifact.
7. Identical successful taps cannot repeat indefinitely.
8. Chat/global trigger routing opens the intended destination.
9. Every external effect is blocked without a valid just-in-time confirmation.
10. No XML, screenshots, temporary photos, or URI grants remain after cancellation/failure.
11. Telegram Saved Messages test succeeds without selecting another recipient.
12. Selfie-to-MMS test succeeds only after capture and send confirmations.

## 14. Implementation sequence

### Phase 0: stabilize existing automation

- Fix node-action gesture fallback.
- Fix scheduled `read_screen` routing and cleanup.
- Fix global trigger destination.
- Make observation screenshot optional and API-safe.
- Add missing tests.

### Phase 1: history and budgets

- Add structured history.
- Add action fingerprints and repetition detection.
- Add injectable action/time/wait budgets.
- Add one-session ownership and cancellation handling.

### Phase 2: typed actions and policy

- Add typed planner steps.
- Add `AutomationIntentPolicy`.
- Execute typed intents through `ActionTools`/`DeviceAction`.
- Add typed global actions.
- Add commit-boundary confirmations.

### Phase 3: media sharing

- Add trusted capture output URI management.
- Add image-share/MMS composer action.
- Add URI grant lifecycle and cleanup.
- Add camera and messaging fixtures/tests.

### Phase 4: opt-in live verification

- Telegram Saved Messages.
- Selfie capture.
- Image message to runtime recipient.
- Final log, artifact, and permission audit.

## 15. Gemini implementation rules

- Work phase by phase; do not implement all phases in one unreviewable patch.
- Preserve unrelated user changes and `vendor/llama.cpp` state.
- Do not introduce arbitrary intents as a shortcut.
- Do not weaken `UiActionValidator` to make tests pass.
- Do not add unsupported Glaive tool names.
- Add tests in the same commit as behavior.
- Run focused tests, full unit tests, debug assembly, and instrumentation compilation for every phase.
- Do not run live send/capture tests until Phase 0–3 acceptance criteria pass.
- Do not commit live recipient data, photos, chat text, XML dumps, screenshots, logs, or generated APKs.

## 16. Codex cleanup checklist

After Gemini finishes each phase, Codex should:

1. Review the diff for scope creep and arbitrary intent paths.
2. Verify routing ownership between native accessibility, `ActionTools`, and Glaive.
3. Audit confirmation timing at the real external-effect boundary.
4. Inspect lifecycle, concurrency, timeout, and cancellation behavior.
5. Run focused and full tests.
6. Inspect device logs during opt-in tests.
7. Verify generated artifacts and URI grants are cleaned.
8. Commit only the reviewed phase.
