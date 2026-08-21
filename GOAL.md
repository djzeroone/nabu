# NABU OVERNIGHT CODEX MISSION
## One Coherent Android Action Runtime
## Full Runtime-Discoverable Android Control Surface
## Strict Observe → Act → Verify Custody
## Release-Build / State-Preserving Physical-Device Validation

You are taking ownership of the difficult architectural portion of the next Nabu release.

Repository:

    mewmix/nabu

Primary branch:

    latest

Do NOT treat this as a feature-spike, demo hack, or isolated control_UI patch.

The objective is to transform the existing Nabu automation implementation into a
coherent Android action runtime that can:

1. receive an action request from any legitimate Nabu control surface;
2. maintain one canonical ActionSession;
3. observe the actual Android state;
4. discover what actions Android actually permits on that state;
5. select one semantically appropriate action;
6. validate that action against the exact observation that authorized it;
7. execute it using the strongest deterministic Android mechanism available;
8. observe the resulting state;
9. verify the action really produced the expected effect;
10. recover intelligently when it did not;
11. preserve context while moving between arbitrary applications;
12. pause safely for confirmation;
13. continue after Activities are backgrounded;
14. report a structured verified action receipt;
15. remain fast enough for a small dedicated action model.

This is the core product.

The target product statement is:

    Nabu is a local Android action agent/runtime that can operate
    across applications, verify the result, preserve context, and
    show the user what it did.

The product must not merely claim arbitrary Android control.

The implementation must make that claim technically defensible.

======================================================================
0. READ THE EXISTING SYSTEM BEFORE MODIFYING IT
======================================================================

Before writing code, inspect at minimum:

    app/src/main/java/com/mewmix/nabu/accessibility/
        NabuAccessibilityService.kt
        AccessibilityToolHandler.kt
        UiSnapshot.kt
        ActionSessionOverlay.kt
        ScreenSemanticDescriber.kt

    app/src/main/java/com/mewmix/nabu/uiagent/
        UiAutomationOrchestrator.kt
        UiActionPlan.kt
        UiActionSerializer.kt
        UiActionValidator.kt
        UiActionPostconditionVerifier.kt
        UiScreenState.kt
        UiTreeIndexer.kt
        AgentDecisionV3.kt
        ConstrainedDecisionDecoder.kt
        AutomationModels.kt
        AutomationSessionManager.kt
        ActionSession.kt
        ActionRequestDispatcher.kt
        AutomationTrace.kt
        ConfirmationManager.kt
        AutomationIntentPolicy.kt
        DestinationResolver.kt
        TelegramUiAdapter.kt

    app/src/main/java/com/mewmix/nabu/actions/
        ActionTools.kt
        DeviceAction.kt
        ScheduledAgentStepExecutor.kt

    app/src/main/java/com/mewmix/nabu/tools/
        ToolCapability.kt
        CapabilityRegistry.kt / related registry code

    app/src/main/java/com/mewmix/nabu/assistant/
        NabuVoiceInteractionService.kt
        NabuVoiceInteractionSession.kt
        NabuTileService.kt

    app/src/main/AndroidManifest.xml
    app/build.gradle.kts

    docs/DEEP_AUTOMATION_SPEC.md

    existing uiagent/accessibility/action tests
    existing Android instrumentation tests

Do not blindly follow old comments or old specifications when the implementation has
already evolved beyond them.

Establish the current behavior from actual code.

Produce a short FOUND section in the engineering handoff describing:

    current control surfaces
    current session owners
    current observation mechanism
    current action schema
    current model schema
    current execution mechanisms
    current confirmation mechanism
    current verifier coverage
    current tracing
    current backend/model ownership
    current known inconsistencies

Example inconsistency already worth verifying:

    Operation.FOCUS exists
    ui_focus execution exists

but the currently structured ui_act tool schema and planner instructions do not
advertise focus consistently.

Search for similar schema drift.

There must not be six independently maintained lists of what "Android actions"
exist.

======================================================================
1. PHILOSOPHY: DO NOT BUILD A GIANT FLAT ACTION ENUM FOR THE MODEL
======================================================================

The long-term Android action surface is larger than the current:

    tap
    long_press
    type_text
    scroll
    back
    home
    recents
    notifications
    quick_settings
    ...

Do NOT solve this by putting dozens of operation strings in every local-model prompt.

The runtime should distinguish four layers:

    1. SEMANTIC NODE ACTIONS
       Android AccessibilityNodeInfo actions exposed by the current node.

    2. GESTURE ACTIONS
       Physical touch-like gestures when semantic node actions are insufficient.

    3. GLOBAL SYSTEM ACTIONS
       AccessibilityService system/global actions available at runtime.

    4. TRUSTED TYPED ANDROID ACTIONS
       Nabu-owned intents/device APIs such as open app, camera, settings,
       flashlight, volume, calendar, navigation, sharing, etc.

The preferred action hierarchy is:

    native semantic node action
        ↓
    stable trusted typed Android action
        ↓
    semantic gesture against validated element/bounds
        ↓
    validated coordinate/path gesture

Coordinate interaction is a fallback, not the default abstraction.

The model should normally say:

    "activate this target"

not:

    "tap pixel 842, 1913"

unless no semantic representation exists.

======================================================================
2. BUILD A SINGLE ANDROID ACTION CATALOG / CAPABILITY SOURCE OF TRUTH
======================================================================

Create or refactor toward one canonical representation of available UI actions.

Do not create another giant framework merely for abstraction.

The goal is to eliminate drift among:

    UiActionStep
    AgentDecisionV3.Operation
    ConstrainedDecisionDecoder
    UI_DECISION_TOOLS
    planner system prompt
    AccessibilityToolHandler
    NabuAccessibilityService
    UiActionValidator
    UiTransitionPolicy
    UiActionPostconditionVerifier
    action labels
    action history
    AutomationTrace

A reasonable design might resemble:

    StandardNodeAction
    GestureAction
    GlobalSystemAction
    TypedAndroidAction

with metadata such as:

    canonical token
    minimum API
    required arguments
    target required?
    effect class
    risk class
    requires confirmation?
    expected postcondition type
    scheduled allowed?
    reversible?
    applicable only when node reports support?

Do not overbuild this if a smaller structure cleanly centralizes the same information.

The important invariant is:

    ONE DEFINITION
        ↓
    schema / prompt / validation / execution / trace remain synchronized

Add tests that fail if an executable action can no longer be represented by the
planner schema or if a planner-visible action has no executor.

======================================================================
3. ENRICH THE ACCESSIBILITY SNAPSHOT WITH REAL ACTION CAPABILITIES
======================================================================

The current UiNode representation captures boolean summaries:

    clickable
    editable
    scrollable
    longClickable
    focusable
    checkable
    selected
    ...

That is insufficient for full Android accessibility control.

Capture the node's actual AccessibilityNodeInfo.actionList.

Do not persist raw live AccessibilityNodeInfo references.

Continue converting Android objects into lightweight immutable snapshot data.

For each node capture enough data to represent:

    standard supported action IDs
    standard canonical action names
    custom accessibility actions
    custom/overridden action labels
    movement granularities if useful
    RangeInfo where present
        min
        max
        current
        type
    CollectionInfo where useful
    CollectionItemInfo where useful
    selected state
    focus state
    accessibility-focus state if needed
    expandable/collapsible capability
    dismiss capability
    context-click capability
    text-selection metadata where available
    action-specific data needed for validation

Do NOT send all of this to the model every turn.

The rich snapshot belongs in Kotlin.

The model gets a compact projection.

Update:

    UiNode
    UiSnapshotFingerprint
    UiElement
    UiTreeIndexer

so capability changes that are relevant to action safety can invalidate the screen
fingerprint.

For example:

    same text
    same package
    same bounds
    but Send action disappeared

must be capable of producing a new trusted UI state.

======================================================================
4. EXPOSE STANDARD ACCESSIBILITY NODE ACTIONS
======================================================================

Support the Android node actions that are useful and supported on Nabu's compile/runtime
SDK, but expose each one only when Android reports it on the target node.

At minimum audit and support appropriate members of the platform action surface:

FOCUS

    accessibility_focus
    clear_accessibility_focus
    focus
    clear_focus

SELECTION / TEXT

    select
    clear_selection
    set_selection
    set_text
    copy
    cut
    paste

Do not expose clipboard contents to the LLM automatically.

Do not allow clipboard operations on password/authentication fields.

MOVEMENT / TEXT NAVIGATION

    next_at_movement_granularity
    previous_at_movement_granularity

Supported granularities may include:

    character
    word
    line
    paragraph
    page

Arguments can include:

    granularity
    extend_selection

HTML NAVIGATION

    next_html_element
    previous_html_element

where the target widget actually supports it.

ACTIVATION / CONTEXT

    click
    long_click
    context_click
    press_and_hold
    ime_enter

EXPANSION / VISIBILITY

    expand
    collapse
    show_on_screen
    show_tooltip
    hide_tooltip
    dismiss

SCROLL / PAGING

    scroll_forward
    scroll_backward
    scroll_up
    scroll_down
    scroll_left
    scroll_right
    page_up
    page_down
    page_left
    page_right
    scroll_to_position
    scroll_in_direction

Where supported by Android/node metadata, accept typed arguments such as:

    row
    column
    direction
    scroll amount

Do not expose unsupported arguments merely because Android has a constant.

RANGE CONTROLS

    set_progress

This is important.

For sliders, seek bars, brightness-like controls and volume-like widgets:

    SET_PROGRESS is preferable to estimating a drag coordinate

when the node actually exposes it.

Use RangeInfo to validate:

    requested value is in range
    requested type is compatible

After action:

    re-observe
    verify actual progress/current value changed as expected

WINDOW MOVEMENT

    move_window

Only when Android reports the action and a window is legitimately movable.

Validate x/y bounds.

Do not use this as a general task/window-management bypass.

TEXT SUGGESTIONS

    show_text_suggestions

where exposed.

DRAG/DROP SEMANTIC ACTIONS

    drag_start
    drag_drop
    drag_cancel

These require special handling.

Do NOT expose:

    model turn 1 → drag_start
    model turn 2 → maybe eventually drag_drop

as unrelated operations.

Represent a user-level:

    drag_drop(source, destination)

operation.

Internally use Android's semantic drag actions where possible.

The transaction must:

    validate source against fresh observation
    validate destination
    start drag
    maintain drag transaction ownership
    resolve destination safely
    drop
    cancel if anything invalidates state
    re-observe
    verify resulting state

If Android's semantic drag actions are unavailable, use an atomic gesture drag.

======================================================================
5. SUPPORT CUSTOM ACCESSIBILITY ACTIONS WITHOUT GIVING THE MODEL RAW IDS
======================================================================

Android applications can expose custom AccessibilityAction objects.

Nabu should be able to use them.

However:

    NEVER allow the LLM to invent or submit arbitrary integer action IDs.

Instead, when building one observation:

    Android node
        custom action id = 0x....
        label = "Archive"
            ↓
    trusted Kotlin snapshot
            ↓
    ephemeral observation-scoped action reference
        ca0
            ↓
    planner receives:
        target = p7
        available_action = ca0
        label = "Archive"

Execution maps:

    observation_id + target_id + ca0

back to the exact Android action ID captured in trusted code.

The mapping expires with the observation.

A custom action from observation A must never be executable against observation B.

Custom action labels are safety evidence.

Examples:

    Delete
    Remove
    Send
    Post
    Purchase
    Approve
    Grant
    Install

must still pass the policy/confirmation classifier.

A custom action is NOT a way to bypass Nabu safety merely because its integer action ID
came from Android.

======================================================================
6. BUILD A REAL GESTURE PRIMITIVE LAYER
======================================================================

Nabu currently supports:

    point tap
    point long press
    directional swipe/scroll

through GestureDescription fallback.

Expand this into a bounded typed gesture executor.

Gesture support should include:

A. TAP

    tap(target)
    tap_point(normalizedX, normalizedY)

Element/bounds preferred.

Normalized coordinates are preferable to model-generated absolute pixels.

B. DOUBLE TAP

    double_tap(target)

Use deterministic timing.

Do not ask the LLM to independently schedule two taps.

C. LONG PRESS

    long_press(target)
    long_press_point(...)

D. PRESS AND HOLD

    press_and_hold(target, duration)

Use semantic node action when available.

Gesture fallback where appropriate.

Bound allowed duration.

E. SWIPE

    swipe(
        direction,
        target/region?,
        distance?,
        duration?
    )

Support:

    up
    down
    left
    right

Use semantic scroll first where possible.

F. DIRECT PATH SWIPE

For canvas-like or non-accessible surfaces:

    swipe_path(start, end, duration)

Coordinates must be normalized or generated from observed bounds.

G. DRAG AND DROP

    drag_drop(
        source,
        destination,
        duration?
    )

Implementation preference:

    semantic Accessibility drag transaction
        ↓ fallback
    GestureDescription path:
        touch source
        hold sufficiently
        move source → destination
        release

This is a single owned operation.

The model should not micromanage DOWN/MOVE/UP independently.

H. POLYLINE / ARBITRARY BOUNDED DRAG

For controls such as:

    canvas
    maps
    drawing surfaces
    sliders where SET_PROGRESS is unavailable

support a bounded path containing a small maximum number of normalized points.

Do not expose unbounded point arrays.

I. PINCH IN

Two simultaneous strokes moving toward a common center.

J. PINCH OUT / SPREAD

Two simultaneous strokes moving away from a center.

K. TWO-FINGER / MULTI-STROKE SWIPE

Support through the same generic multi-stroke execution machinery where genuinely needed.

Do not expose arbitrary 10-finger gestures to the small model by default.

Use runtime GestureDescription maximum stroke count/duration.

L. EDGE GESTURES

Examples:

    swipe down from top
    swipe up from bottom
    horizontal edge swipe

BUT:

when Android exposes a corresponding semantic/global action, use it instead.

Example:

    open notification shade

should normally be:

    GLOBAL_ACTION_NOTIFICATIONS

not a guessed top-edge swipe.

Opening Quick Settings should normally be:

    GLOBAL_ACTION_QUICK_SETTINGS

Coordinate edge gestures are fallback for surfaces Android does not expose semantically.

======================================================================
7. DO NOT EXPOSE RAW TOUCH_DOWN / TOUCH_MOVE / TOUCH_UP ACROSS MODEL TURNS
======================================================================

This is important.

GestureDescription can implement continued strokes, but a model must not produce:

    turn 1: finger down
    wait for inference
    turn 2: move
    wait for inference
    turn 3: release

That is a reliability disaster.

Pointer lifetime must be owned by Kotlin inside one bounded gesture transaction.

If continued strokes are necessary internally:

    executor owns transaction ID
    executor owns pointer state
    cancellation guarantees pointer cleanup
    screen/state validation happens at safe transaction boundaries

The LLM supplies semantic intent.

Kotlin owns mechanics.

======================================================================
8. GLOBAL ANDROID / SYSTEM ACTIONS
======================================================================

Nabu currently exposes:

    back
    home
    recents
    notifications
    quick_settings

Expand the runtime to understand the broader global action surface available on the
running Android version.

Do NOT blindly assume every global action exists.

Query runtime availability using the AccessibilityService system-action API when
available.

The action catalog should be able to represent, as supported by current SDK/device:

NAVIGATION

    back
    home
    recents

SYSTEM SURFACES

    notifications
    quick_settings
    dismiss_notification_shade

NAVIGATION / DPAD

    dpad_up
    dpad_down
    dpad_left
    dpad_right
    dpad_center

DEVICE / SYSTEM

    power_dialog
    lock_screen

WINDOWING

    toggle_split_screen

Only if runtime getSystemActions indicates it is actually supported.

ACCESSIBILITY SURFACES

    accessibility_button
    accessibility_button_chooser
    accessibility_shortcut
    accessibility_all_apps

These should NOT automatically become normal planner actions just because they exist.

Some could recurse into Nabu or produce confusing accessibility-state transitions.

Treat them as explicit/special-purpose actions.

SCREENSHOT

Android has a global screenshot action, but Nabu already has a controlled screenshot
capture path that returns a Nabu-owned file.

Prefer the controlled Nabu screenshot path for model observation.

Do not invoke user-facing screenshot UI unless explicitly required.

HEADSET HOOK

The platform may expose a headset-hook global action.

Be extremely careful:

it can interact with calls as well as media.

Nabu already has trusted media controls.

Do not make headset-hook a routine planner primitive.

CALL-STATE operations must remain high risk.

POWER DIALOG

Opening the power dialog may be allowed if the user explicitly requests it.

Do NOT automatically choose shutdown/reboot.

LOCK SCREEN

Locking the device is potentially session-ending.

Never schedule it automatically.

Never use it during unattended overnight validation until all other tests are done.

Treat a real live lock-screen test as manual/terminal validation.

SDK FUTURE ACTIONS

The app currently compileSdk/targetSdk should be respected.

Do not increase compileSdk simply to gain newer action constants during this mission.

Design the action catalog so future SDK additions can be incorporated cleanly.

======================================================================
9. "DROP DOWN THE DRAWER" MUST BE A FIRST-CLASS TEST
======================================================================

The user should be able to say:

    "pull down my notifications"

or:

    "open the notification drawer"

or:

    "show quick settings"

or:

    "close the notification shade"

and Nabu should act reliably.

Preferred implementation:

    open_notifications
        → GLOBAL_ACTION_NOTIFICATIONS
        → observe SystemUI
        → verify shade open

    open_quick_settings
        → GLOBAL_ACTION_QUICK_SETTINGS
        → observe SystemUI
        → verify Quick Settings open

    dismiss_notification_shade
        → runtime-supported dismiss action
        → observe
        → verify original/underlying application surface restored

Fallback:

    validated top-edge swipe

only if the global action is unavailable and the runtime/device permits it.

Do not mark success merely because performGlobalAction returned true.

Verify the resulting window/surface.

======================================================================
10. MODEL-FACING ACTION SURFACE MUST REMAIN SMALL
======================================================================

The action model is supposed to be small and fast.

Do not dump:

    40 standard node actions
    15 global actions
    all custom actions
    all typed Android actions

into every prompt.

Instead supply current-state capabilities.

Example:

    {
      "id":"p4",
      "label":"Brightness",
      "role":"slider",
      "actions":[
        "set_progress",
        "scroll_forward",
        "scroll_backward"
      ],
      "range":{
        "min":0,
        "max":100,
        "current":42
      }
    }

Another:

    {
      "id":"p8",
      "label":"Search",
      "actions":[
        "tap",
        "focus",
        "set_text",
        "paste",
        "ime_enter"
      ]
    }

Another:

    {
      "id":"p12",
      "label":"Archive",
      "actions":[
        {
          "ref":"ca0",
          "label":"Archive"
        }
      ]
    }

At top level:

    "system_actions":[
        "back",
        "home",
        "recents",
        "notifications",
        "quick_settings"
    ]

Only include actions actually valid now.

This turns action discovery into:

    Android tells Kotlin what is possible
        ↓
    Kotlin filters and sanitizes
        ↓
    model chooses among a small valid set

rather than:

    model memorizes the Android API and guesses

That is the architecture we want.

======================================================================
11. FIX THE ACTION SCHEMA DRIFT
======================================================================

Audit every currently supported operation.

At present there are already mismatches between:

    AgentDecisionV3.Operation
    UI_DECISION_TOOLS schema
    JSON retry prompt
    main planner prompt
    UiActionPlan
    UiActionSerializer
    executor

Fix this permanently.

Do not merely append more strings to every list manually.

Add invariant tests such as:

    every planner-visible canonical action:
        parses
        serializes
        validates
        has executor mapping
        has action label
        has transition policy
        has history representation
        has verifier strategy

    every executor action:
        is either planner-visible
        or deliberately internal-only with documented reason

Backward compatibility with current V3 output is desirable.

If introducing a V4 decision representation significantly improves the design:

    preserve V3 decoder compatibility
    normalize V3 into the canonical internal action type

Do not force migration of every old test in one destructive rewrite unless necessary.

======================================================================
12. RESTORE STRICT OBSERVATION LEASE SAFETY BEFORE ADDING POWER
======================================================================

This is the most important correctness requirement.

Do not expand Nabu's action power until stale-action execution is fixed.

The current low-level Accessibility action implementation must be audited because the
action request currently includes an observation ID but execution must be strictly bound
to the exact observation that granted the action.

Required invariant:

    observation O
        ↓
    planner sees O
        ↓
    validator validates against O
        ↓
    executor may perform one mutation only if current state still matches O

Validate at minimum:

    observation ID
    package
    applicable window identity
    screen/state fingerprint
    display/rotation where coordinate gestures are involved

A same-package mutation matters.

Example:

    Telegram chat list
        package org.telegram...
        ↓
    screen transitions to conversation
        SAME PACKAGE

An action planned against the old list must be invalid.

Do not treat:

    lastObservedPackage == null

as meaning package validation is no longer necessary.

A missing/expired lease must mean:

    NO ACTION AUTHORIZED

not:

    VALIDATION DISABLED

Once an action begins:

    consume lease exactly once

After action:

    fresh observation required

For gestures:

    source/destination coordinates belong to that exact observation.

No reusing coordinates after state changes.

Add explicit tests:

    correct observation → allowed
    random non-empty observation ID → rejected
    stale observation ID → rejected
    same package / different fingerprint → rejected
    changed package → rejected
    changed rotation → coordinate gesture rejected/replanned
    cleared lease → rejected
    second action using consumed lease → rejected
    fresh observation → allowed

======================================================================
13. EVERY NEW ACTION MUST HAVE A VERIFICATION STRATEGY
======================================================================

The current built-in postcondition verifier primarily gives robust specific verification
to text input.

That is not sufficient for an expanded action runtime.

Do not add 30 actions that merely return:

    performAction() == true

and call that success.

For each action family define an expected verification policy.

Examples:

TAP / CLICK

    expected screen mutation
    target checked/selected change
    window/package change
    appearance/disappearance of expected content
    or explicit NO_CHANGE if appropriate

LONG PRESS / CONTEXT CLICK

    context menu appears
    selection mode begins
    screen/window fingerprint changes

TYPE / SET_TEXT

    editable control contains normalized expected text

FOCUS

    target focused == true

CLEAR FOCUS

    focused == false

ACCESSIBILITY FOCUS

    verify accessibility focus where snapshot supports it

SELECT

    selected == true

CLEAR SELECTION

    selected == false

SET_SELECTION

    selectionStart/selectionEnd match where available

COPY

    do not log copied text
    verify selection/state where feasible
    success does not imply clipboard contents should enter planner context

CUT

    source text changes as expected
    clipboard remains opaque to model

PASTE

    destination text/state changes
    do not paste into password/authentication/payment fields

EXPAND

    expanded state or child visibility changes

COLLAPSE

    inverse

DISMISS

    target disappears or containing surface closes

SHOW_ON_SCREEN

    target becomes visible / viewport changes

SCROLL

    visible item signature or scroll state changes

SCROLL_TO_POSITION

    requested row/column or expected item becomes visible

SCROLL_IN_DIRECTION

    targeted scroll event/result visible

PAGE_*

    page or visible signature changes

SET_PROGRESS

    RangeInfo current matches requested value within reasonable tolerance

IME_ENTER

    input action effect / submitted surface transition

SHOW/HIDE TOOLTIP

    tooltip state/window changes if observable

DRAG_DROP

    source/target state mutation
    target content/location changed
    application-specific observable effect

GESTURE DRAG

    never assume gesture completion means goal completion
    re-observe and verify downstream state

PINCH

    screen fingerprint/zoom-state change where observable
    otherwise report gesture executed but not verified, not "goal complete"

GLOBAL BACK/HOME/RECENTS

    verify resulting window/package/system surface

NOTIFICATION SHADE

    verify SystemUI/notification surface

QUICK SETTINGS

    verify Quick Settings surface

DISMISS SHADE

    verify underlying app restored

OPEN APP

    expected package is foreground

OPEN SETTINGS

    expected Settings surface visible

OPEN URL

    intended browser/app surface appears

SHARE

    composer/destination state verified
    final external send still requires confirmation

CAMERA

    camera surface appears
    shutter/capture remains commit-boundary confirmation

CUSTOM ACTION

    use expected-effect metadata
    require observable mutation when one is expected

If Nabu cannot prove an outcome:

    it may report "action executed; result not yet verified"

but it must not fabricate completion.

======================================================================
14. SAFETY MUST APPLY TO THE TARGET EFFECT, NOT JUST THE TOOL NAME
======================================================================

This becomes more important after generic gestures are introduced.

The system must not allow:

    "tap Send"

to require confirmation,

while:

    "gesture at Send's coordinates"

bypasses confirmation.

Safety classification must bind to:

    observed target
    semantic target label
    resource ID
    custom action label
    destination
    pending external effect
    content hash
    screen
    action family
    expected effect

The executor should know that:

    semantic click of Send
    coordinate tap of Send
    custom action labelled Send

represent the same consequential boundary.

Likewise:

    Delete
    Post
    Publish
    Capture
    Record
    Call
    Approve
    Permission Allow

cannot bypass policy by changing execution mechanism.

Never automate:

    passwords
    passcodes
    OTP/2FA
    authentication approvals
    payment
    account deletion
    factory reset
    unknown APK installation
    security permission escalation

without changing the existing explicit product policy.

Do not weaken safety tests to get the action expansion passing.

======================================================================
15. ONE CANONICAL ACTIONSESSION
======================================================================

The current implementation contains overlapping session abstractions:

    ActionRequestDispatcher / ActionSession

and:

    AutomationSessionManager / AutomationSessionSnapshot

while UiAutomationOrchestrator also generates its own session identifier.

This needs consolidation.

Desired external identity:

    ActionSession.id
        │
        ├── RequestDispatcher
        ├── Action Runtime
        ├── Observer
        ├── Planner
        ├── Executor
        ├── Verifier
        ├── Recovery
        ├── Confirmation
        ├── AutomationTrace
        ├── Overlay
        └── Chat handoff

One logical user action session should have one ID.

Internal components may have internal attempt/step identifiers.

Do not require all runtime state to be jammed into one giant mutable data class.

The canonical external lifecycle is what must be singular.

Use immutable StateFlow-visible session snapshots.

Do not mutate the same ActionSession reference and reassign it to StateFlow expecting a
state emission.

Use:

    immutable state transitions

or equivalent explicit publication.

Preserve the best existing AutomationSessionManager behavior:

    process-level ownership
    lifecycle independence
    suspend
    resume
    steer
    waiting for user
    cancellation
    persisted goal after process restart

Do not regress that functionality while removing duplicate ownership.

======================================================================
16. TEMPORARY CONVERSATION MUST ACTUALLY BE CONTEXTUAL
======================================================================

The accessibility overlay's Conversation mode should be real.

Example:

    user:
        "Open Telegram"

    Nabu:
        opens Telegram
        verifies

    user:
        "search Agent Junkies"

    Nabu:
        same ActionSession
        knows previous target/app/session context

    user:
        "open it"

    Nabu:
        resolves "it" from previous request + verified current UI

Do not rely on the current visible screen being sufficient to accidentally make this work.

Create compact action-session working memory:

    original objective
    recent user turns
    verified checkpoints
    recent successful actions
    latest failure/recovery
    current package/window
    current subgoal
    pending objective
    pending destination/external effect

Bound the history.

Do not feed the small model the entire chat transcript.

======================================================================
17. REAL CHAT HANDOFF
======================================================================

"Open in Nabu" must not mean:

    copy currentGoal into Chat text field

It must mean:

    transfer/observe the same ActionSession

Preserve:

    same session ID
    original goal
    recent action conversation
    verified checkpoints
    action receipt so far
    current package/window
    pending objective
    pending question
    pending confirmation state where safe
    failure/recovery context

Chat should become another control surface for the same session.

Define behavior when handoff is requested during execution.

Choose one coherent model:

A.

    action continues
    Chat observes same live session

or:

B.

    runtime pauses at next safe boundary
    Chat attaches
    user resumes

Do not leave two concurrent owners controlling Android.

======================================================================
18. DEDICATED FAST ACTION MODEL
======================================================================

The ActionRequestDispatcher currently has an action-model preference parameter, but the
actual backend selection path must be audited to ensure the configured action model is
really used.

Build an explicit:

    Chat Model

and:

    Action Model

separation.

The Action Model should be optimized for:

    structured short decisions
    low latency
    small context
    deterministic schema

The action model does NOT need to reason about:

    Android node traversal algorithms
    bounds calculation
    retry loops
    stale-state checks
    accessibility API mechanics
    action-ID mapping
    gesture timing
    verification implementation

Kotlin owns those.

The small model decides:

    what semantic operation should happen next?
    which currently advertised target/action is intended?
    is the goal complete?
    is user clarification necessary?

Keep the backend warm while useful.

Honor configured action model ID.

Handle changing the configured model cleanly.

Dispose obsolete backends.

Do not silently select "last chat model" when a dedicated action model is configured.

======================================================================
19. ACTIONREQUESTDISPATCHER CONCURRENCY
======================================================================

Audit and fix:

    submitRequest
    submitFollowUp
    cancelSession
    handoff
    activeJob
    activeSession publication
    backend lifecycle

Rapid requests must not produce:

    two physical action runners
    old session overwriting new session
    cancelled session publishing COMPLETED later
    stale callback changing UI state
    ghost actions
    cross-session confirmation
    stale onComplete callback

Serialize/actorize command ownership as appropriate.

Every asynchronous completion must verify:

    "am I still the owner of this session?"

before publishing terminal state.

======================================================================
20. OBSERVATION / WINDOW STABILIZATION
======================================================================

Deep Android workflows routinely pass through:

    target app
    launcher
    SystemUI
    IME
    chooser
    permission controller
    dialog
    transient loading window
    old app window
    empty/no-root state

Do not treat one missing root as terminal.

Create bounded classification/recovery.

Classify observed surfaces:

    target application
    Nabu
    SystemUI
    keyboard/IME
    chooser
    permission dialog
    transient/loading
    unrelated foreground interference
    no-readable-root

Use this to decide:

    wait
    re-observe
    continue
    recover
    ask
    fail safely

Never use an old actionable snapshot while waiting for the new surface.

======================================================================
21. FAILURE RECOVERY POLICY
======================================================================

Classify failures.

At minimum:

    stale observation
    target disappeared
    target ambiguous
    node action unavailable
    node action returned false
    gesture cancelled
    no screen change
    unexpected screen change
    package changed
    IME covered target
    loading
    no accessibility root
    destination mismatch
    verification failed
    permission/security surface encountered
    user interference

Each class gets bounded recovery.

Example:

    semantic click failed
        ↓
    fresh target still uniquely resolves?
        ↓ yes
    validated gesture fallback
        ↓
    re-observe
        ↓
    verify

Another:

    target stale
        ↓
    DO NOT use old coordinate
        ↓
    capture fresh observation
        ↓
    replan

Another:

    keyboard obscures target
        ↓
    identify IME
        ↓
    use deterministic back/IME resolution if appropriate
        ↓
    fresh observation
        ↓
    continue

Never loop identical failed actions.

======================================================================
22. OVERLAY MUST GET OUT OF THE WAY
======================================================================

The current Accessibility overlay remains a full-width bottom action surface during the
run.

That can obstruct:

    bottom navigation
    composer fields
    Send buttons
    sliders
    gesture destinations

New behavior:

    invoke
        ↓
    full lightweight request surface
        ↓
    submit
        ↓
    hide keyboard
        ↓
    collapse overlay to small non-obstructive status chip
        ↓
    execute
        ↓
    expand only when:
        user input required
        confirmation required
        temporary conversation turn requested
        completion/result requested

The progress chip should support:

    phase
    Stop
    maybe Open in Nabu

but should not block large regions of the target app.

Overlay geometry must not contaminate Nabu's target window selection.

======================================================================
23. NABU ANYWHERE
======================================================================

Unify legitimate user entry points behind one internal request dispatcher:

    Accessibility button
    Android assistant / VoiceInteractionSession
    Quick Settings tile
    Nabu Chat
    internal action overlay
    future widget/shortcut
    safe debug harness

The runtime flow should be:

    entry point
        ↓
    trusted request dispatcher
        ↓
    ActionSession
        ↓
    Action Runtime

The current VoiceInteractionSession is largely just a placeholder UI.

Turn it into an actual lightweight Nabu action entry point if priorities 0-21 are stable.

Ideal behavior:

    Telegram foreground
        ↓
    invoke Android assistant gesture
        ↓
    small Nabu action surface
        ↓
    user types/speaks:
        "search Agent Junkies"
        ↓
    request enters shared dispatcher
        ↓
    Nabu action surface collapses
        ↓
    Telegram remains/returns foreground
        ↓
    action continues
        ↓
    verified completion

No full ChatActivity flash is necessary.

Quick Settings tile should eventually enter the same lightweight surface rather than
being architecturally separate.

======================================================================
24. TRUST BOUNDARY: DO NOT KEEP AN INSECURE RELEASE RECEIVER FOR ADB
======================================================================

The current ACTION_EXECUTE receiver must not remain an unrestricted production control
surface just because it makes ADB testing convenient.

An externally callable command path combined with Accessibility privilege is a major
trust boundary.

The rule is:

    arbitrary app broadcast
        ≠
    trusted Nabu user request

If the receiver remains available in production, it must have a genuine trusted
authorization mechanism.

Prefer:

    debug-only test receiver

or an equivalent secure test surface.

Do not preserve:

    exported release receiver
    no permission
    arbitrary request text

merely for test automation.

IMPORTANT:

Once the release receiver is secured, ADB shell may no longer be able to inject arbitrary
requests directly.

THAT IS ACCEPTABLE.

Do not weaken production security for the test harness.

For release-device acceptance testing:

    invoke the real user-facing entry surface

while ADB:

    launches known Activities if appropriate
    observes process/activity/window state
    captures logcat
    checks packages
    validates lifecycle

The actual device workflow must still run through Nabu.

======================================================================
25. TRUSTED TYPED ANDROID ACTIONS MUST REMAIN AVAILABLE
======================================================================

Preserve and integrate the existing trusted DeviceAction/ActionTools capabilities.

These include, as implemented/applicable:

APPLICATION

    open app
    resolve installed apps by name/package

SETTINGS

    Wi-Fi settings
    Bluetooth settings
    Display
    Sound
    Accessibility
    app notification settings
    app details
    Developer Options
    Wireless Debugging

URL / INTENT NAVIGATION

    open URL through trusted policy
    navigation geo intent

COMMUNICATION PREPARATION

    SMS composer
    dialer
    share text
    share captured media

The final consequential action remains subject to confirmation.

DEVICE STATE

    brightness where WRITE_SETTINGS already granted
    flashlight
    media/ring/alarm/notification volume
    mute/unmute

MEDIA CONTROL

    play
    pause
    next track

PIM

    create calendar event
    alarms
    timers

CAMERA

    photo
    video
    trusted capture output URI
    front/rear preference where supported

CONNECTIVITY PANELS

    Wi-Fi panel
    Bluetooth panel

Do not replace trusted typed operations with generic UI tapping when a deterministic API
already exists.

Do not add arbitrary launch_intent.

Do not permit model-provided:

    component names
    raw Intent flags
    arbitrary extras
    arbitrary MIME types
    unrestricted URI schemes

======================================================================
26. VERIFIED ACTION RECEIPTS
======================================================================

The existing AutomationTrace is a product feature, not merely debugging infrastructure.

Build the structured state needed to render:

    ✓ Opened Telegram                     184 ms
    ✓ Search selected                     103 ms
    ✓ Entered search query                 71 ms
    ✓ Search results verified             246 ms
    ✓ Opened Agent Junkies                112 ms
    ! Send requires confirmation
    ✓ Confirmation approved
    ✓ Send verified                       193 ms

    Completed · 2.4 s
    7 actions · 7 verified

Do NOT expose model chain-of-thought.

Receipt data should contain operational facts:

    sequence
    action family
    semantic action
    target summary
    source package/window
    result package/window
    execution mechanism
        semantic_node
        trusted_intent
        gesture
        custom_accessibility_action
        global_action
    execution result
    verification result
    retry/recovery count
    elapsed duration

Sensitive content continues to be redacted/hashed.

Do not claim cryptographic attestation.

This is a verified execution ledger.

======================================================================
27. REAL LATENCY INSTRUMENTATION
======================================================================

Populate production metrics.

Measure with a monotonic clock where measuring durations.

Track:

    request received
    session created
    observation start
    observation ready
    planner/model request start
    first usable structured decision
    model completion
    validation
    action dispatch
    action completion
    transition observation
    verification start
    verification complete
    next planning iteration
    total step duration
    total session duration

Also record:

    number of planner/model calls
    semantic-node actions
    gesture fallbacks
    recovery count
    stale-action rejections
    verification failures

Important user-perceived metric:

    INVOCATION → FIRST PHYSICAL ACTION

Do not invent numbers.

Measure them.

======================================================================
28. RELEASE BUILD IS THE PRIMARY PHYSICAL-DEVICE VALIDATION BUILD
======================================================================

THIS IS A HARD REQUIREMENT.

We deliberately use the RELEASE build on the primary physical device because we need to
PRESERVE THE EXISTING NABU DEVICE STATE.

That includes:

    installed package identity
    application data
    settings
    downloaded models
    model preferences
    OAuth/auth state
    conversation state where applicable
    automation/session preferences
    Accessibility enablement
    device-specific configuration
    any existing user setup needed for realistic testing

DO NOT:

    adb uninstall com.mewmix.nabu

DO NOT:

    adb shell pm clear com.mewmix.nabu

DO NOT:

    wipe app data

DO NOT:

    replace the installed release package with a differently signed debug APK

DO NOT:

    use uninstall/reinstall as a shortcut for signing errors

DO NOT:

    reset application state merely to make a test pass

The validation flow must preserve state.

======================================================================
29. VERIFY THE RELEASE SIGNER BEFORE INSTALLING
======================================================================

The current Gradle configuration must be inspected carefully.

A "release" artifact is not sufficient proof that it is signed with the same release
certificate as the installed package.

Before updating the primary physical device:

1. Inspect installed package:

    adb shell dumpsys package com.mewmix.nabu
    adb shell pm path com.mewmix.nabu

2. Identify/pull the currently installed base APK when practical.

3. Inspect its signer using Android build tools:

    apksigner verify --print-certs <installed-apk>

4. Build Nabu release with the actual configured release credentials:

    ./gradlew app:assembleRelease --no-daemon

or the correct project equivalent.

5. Inspect the new release artifact:

    apksigner verify --print-certs <new-release-apk>

6. Verify signing identity matches.

IF IT DOES NOT MATCH:

    STOP.

Do not uninstall the existing application.

Do not clear it.

Do not install the mismatched artifact.

Report:

    installed signer
    candidate signer
    build configuration state
    exact blocker

If the current release build configuration would fall back to debug signing because
release credentials are unavailable, DO NOT install that fallback artifact onto the
preserved primary device.

Another cleanup pass may change build/signing policy.

For this overnight mission, the minimum requirement is:

    never destroy preserved device state because signing is inconvenient.

======================================================================
30. INSTALL RELEASE IN PLACE
======================================================================

Once signing compatibility has been proven, update in place.

Use the equivalent of:

    adb install -r <release-apk>

Do not downgrade version state casually.

If Android rejects the install due to:

    signer mismatch
    version downgrade
    package incompatibility

investigate the root cause.

Do not use destructive workarounds on the primary device.

After install verify:

    package still present
    app data still present
    configured model still available
    Accessibility service remains configured as expected
    settings/preferences preserved
    app launches
    ActionSession control path works

Release minification/shrinking makes this test more valuable than debug-only validation.

======================================================================
31. DEBUG AND INSTRUMENTATION POLICY
======================================================================

Debug builds are still useful for:

    compilation
    JVM tests
    Robolectric tests
    emulator tests
    isolated instrumentation
    security-unit tests

But:

    PRIMARY REAL-DEVICE BEHAVIORAL ACCEPTANCE
        =
    PRESERVED RELEASE INSTALLATION

Do not install a debug-signed package over the primary preserved release state.

If a destructive test requires:

    app clear
    reinstall
    alternate signature
    intentionally corrupted state

run it on:

    emulator
    secondary test device
    isolated test profile

not the preserved primary Nabu installation.

======================================================================
32. ADB IS THE HARNESS, NOT THE ACTOR
======================================================================

Use ADB aggressively for evidence:

    adb devices -l

    adb shell dumpsys package com.mewmix.nabu

    adb shell dumpsys activity activities

    adb shell dumpsys activity top

    adb shell dumpsys window

    adb shell settings get secure enabled_accessibility_services

    adb logcat

    adb shell pm path com.mewmix.nabu

Use screenshots/UI dumps where needed.

ADB may:

    prepare starting state
    foreground an app
    launch Nabu control surface
    inspect lifecycle
    read logs
    verify processes/windows
    collect evidence

ADB must NOT replace Nabu's runtime.

Invalid acceptance test:

    adb shell input tap ...
    adb shell input swipe ...
    adb shell input text ...

and then claim Nabu controlled Android.

Valid:

    use real Nabu entry point
        ↓
    Nabu model/planner chooses action
        ↓
    Nabu AccessibilityService/DeviceAction performs action
        ↓
    ADB observes evidence

======================================================================
33. LIVE SAFE ACTION ACCEPTANCE MATRIX
======================================================================

Build a real release-device validation matrix.

A. TAP

Safe target in Calculator/Nabu-owned fixture/app.

Verify node click mechanism.

B. COORDINATE TAP FALLBACK

Use controlled fixture where node click intentionally fails but valid bounds exist.

Verify:

    semantic attempt failed
    gesture fallback used
    result verified

C. LONG PRESS

Use harmless context menu.

Verify resulting menu.

D. DOUBLE TAP

Use reversible harmless fixture/surface.

E. SET TEXT

Use non-secret field.

Verify text after re-observation.

F. FOCUS / CLEAR FOCUS

Verify focus flags.

G. SELECTION

On Nabu-owned text fixture:

    select
    set selection
    clear selection

No sensitive clipboard content.

H. COPY / PASTE

Use synthetic harmless string.

Verify destination.

Never log clipboard payload into the model trace.

I. SCROLL SEMANTIC

Scrollable Android Settings list.

Verify visible item signature changes.

J. SCROLL GESTURE FALLBACK

Controlled fixture without semantic scroll.

K. SHOW ON SCREEN

Use off-screen accessible target when possible.

L. SET PROGRESS

Use harmless slider/seekbar.

Record original value.

Set new value.

Verify.

Restore original value.

M. EXPAND / COLLAPSE

Use expandable surface.

Verify child state.

N. DISMISS

Dismiss harmless transient UI.

O. CONTEXT CLICK

Use fixture if device/app exposes it.

P. DRAG AND DROP

Prefer controlled Nabu fixture/emulator first.

Validate:

    source
    destination
    transaction
    drop
    resulting state

Q. GESTURE DRAG

Test fallback independently.

R. PINCH IN / OUT

Use reversible zoomable surface if practical.

Record state before/after.

S. NOTIFICATION SHADE

    open_notifications
    verify
    dismiss_notification_shade
    verify return

T. QUICK SETTINGS

    open_quick_settings
    verify

Do not toggle a destructive setting just to prove the surface opened.

U. HOME / RECENTS / BACK

Verify task/window transitions.

V. DPAD

Only where getSystemActions says supported and a safe surface can be exercised.

W. APP LAUNCH

Launch an installed safe app.

Verify foreground package.

X. APP SWITCH CONTINUITY

Start Nabu action.

Move across:

    Nabu
    target app
    SystemUI
    target app

Same session survives.

Y. LOCK SCREEN

DO NOT perform as an unattended early test.

Treat live lock action as optional/manual terminal validation because it intentionally
makes the device unavailable to the current automation.

Z. POWER DIALOG

If tested:

    open dialog
    verify
    Back

Never select shutdown/restart during automated acceptance.

======================================================================
34. TELEGRAM ACCEPTANCE
======================================================================

Retain Telegram as one high-value deep-control example, but do not make Telegram-specific
logic the fundamental executor.

The general machinery should handle it.

Safe workflow:

    Telegram authenticated
        ↓
    invoke Nabu
        ↓
    search runtime target
        ↓
    verify exact destination
        ↓
    open destination
        ↓
    type harmless runtime-unique message
        ↓
    verify composer contents
        ↓
    just-in-time confirmation
        ↓
    send
        ↓
    verify outgoing message

Prefer Saved Messages or another explicitly approved test target.

Never send to a random contact merely to validate automation.

Failure:

    ambiguous destination
    wrong destination
    login screen
    2FA
    passcode
    state changed during confirmation

must stop safely.

======================================================================
35. TEMPORARY CONVERSATION ACCEPTANCE
======================================================================

From arbitrary app:

    invoke Accessibility action surface

Set:

    Conversation

Then:

    "Open Telegram"

Verify same ActionSession ID.

Then:

    "search Agent Junkies"

Verify same ID/context.

Then:

    "open it"

Verify "it" resolves through session context.

Add another test where the reference cannot be inferred solely from current visible UI.

This proves conversational continuity is real.

======================================================================
36. HANDOFF ACCEPTANCE
======================================================================

Run:

    temporary action session
    → several verified actions
    → Open in Nabu

Verify:

    same ActionSession ID
    same turns
    same receipt history
    same current objective
    same package/window context
    no duplicate runner
    no second independent conversation created

Continue from Chat.

======================================================================
37. LIFECYCLE ACCEPTANCE
======================================================================

Reproduce the original architectural failure explicitly:

    launch Nabu Chat
        ↓
    start device action
        ↓
    action brings another app foreground
        ↓
    ChatActivity receives onPause/onStop
        ↓
    action must continue
        ↓
    verify target result

Also test:

    Chat Activity destroyed/recreated
    configuration change
    Nabu returns foreground
    overlay closed
    overlay reopened

The session is not Activity-owned.

======================================================================
38. CANCELLATION ACCEPTANCE
======================================================================

Cancel during:

    model inference
    observation wait
    transition wait
    gesture execution if possible
    temporary conversation
    user-input wait
    confirmation wait
    between actions

After cancellation:

    no new physical actions
    no ghost callbacks
    no stale terminal COMPLETED
    pending confirmation invalid
    pending gesture transaction cleaned
    generated screenshots removed
    capture URI grants cleaned
    session has one terminal result

======================================================================
39. INTERFERENCE ACCEPTANCE
======================================================================

During an action session deliberately create benign interference:

    user changes foreground app
    notification appears
    IME appears
    target screen changes before execution

Nabu must:

    detect drift
    invalidate stale target
    re-observe
    recover or pause

It must NOT:

    execute old coordinates
    blindly continue queued taps
    interpret user interference as verified progress

======================================================================
40. PERFORMANCE ACCEPTANCE
======================================================================

Record across repeated safe workflows:

    invocation → first action
    observation duration
    planner duration
    native structured generation duration
    execution duration
    transition wait
    verification duration
    total step latency
    total session latency
    model call count
    fallback count
    recovery count

Run multiple repetitions.

Report:

    min
    median/p50
    p95 if sample count permits
    max

Do not produce fake precision with tiny samples.

The primary optimization target is:

    REMOVE NABU OVERHEAD

not:

    DELETE SAFETY CHECKS

======================================================================
41. TEST COVERAGE FOR THE EXPANDED ACTION SURFACE
======================================================================

Add unit tests for:

ACTION CATALOG

    standard action mapping
    SDK gating
    custom action reference mapping
    unknown raw action rejected

SNAPSHOT

    action list captured
    custom labels captured
    range metadata captured
    action capability affects fingerprint

PLANNER PROJECTION

    only current valid actions exposed
    unavailable action absent
    compact planner remains compact

DECODER

    every canonical action parses
    wrong args rejected
    raw arbitrary action IDs rejected

VALIDATOR

    target must advertise semantic action
    stale target rejected
    password target blocked
    range bounds validated
    gesture points validated
    custom dangerous labels classified

GESTURE EXECUTOR

    tap
    long press
    double tap
    swipe
    drag
    polyline bounds
    pinch stroke construction
    cancellation

GLOBAL ACTIONS

    runtime unavailable action rejected
    runtime available action mapped correctly

DRAG TRANSACTION

    source invalid
    destination invalid
    drag start failed
    target stale
    cancel
    successful drop

POSTCONDITIONS

    focus
    selection
    set text
    set progress
    expand/collapse
    dismiss
    scroll
    show on screen
    global surface
    drag/drop

SESSION

    one session ID
    no stale publication
    concurrency
    cancellation
    follow-up context
    handoff

RELEASE-SAFETY HELPERS

where testable:

    signer mismatch produces abort path, never destructive install guidance

======================================================================
42. REPLAYABLE ORCHESTRATOR TESTING
======================================================================

The deep automation spec already identifies this need.

Continue moving observation/execution/planner timing behind narrow injectable interfaces
where doing so materially improves deterministic testing.

We should be able to replay:

    observation 1
    action
    transient SystemUI
    observation 2
    failed verifier
    recovery
    observation 3
    second action
    confirmation
    interference
    recovery
    success

without requiring a physical phone for every regression.

Do not rewrite the entire orchestrator merely to accomplish dependency injection.

Use seams.

======================================================================
43. ACTION RECEIPT / TRACE MUST IDENTIFY EXECUTION MECHANISM
======================================================================

For every executed action record:

    semantic action requested
    target
    target capability
    mechanism chosen

Examples:

    ACTION_CLICK
    AccessibilityNodeInfo semantic action

or:

    tap
    GestureDescription fallback

or:

    set_progress
    AccessibilityNodeInfo ACTION_SET_PROGRESS

or:

    drag_drop
    semantic drag transaction

or:

    drag_drop
    GestureDescription path fallback

or:

    open_app
    trusted DeviceAction Intent

or:

    notifications
    AccessibilityService global action

This information is useful both to users and engineering.

======================================================================
44. DO NOT OVER-SPECIALIZE FOR TELEGRAM
======================================================================

App-specific state adapters are acceptable for:

    recognizing states
    supplying strong semantic labels
    verifying destinations

They are NOT acceptable as the fundamental action mechanism.

Telegram should work because:

    generic action runtime
    +
    generic semantic node actions
    +
    generic gesture fallback
    +
    generic verification
    +
    optional Telegram state recognition

work together.

Do not hardcode:

    pixel coordinates
    screen dimensions
    brittle list indexes

for production Telegram control.

======================================================================
45. SCHEDULED ACTION BOUNDARIES
======================================================================

The expanded action surface does NOT imply every action is schedulable.

Unattended scheduled control must remain more restricted.

Scheduled execution must not perform operations requiring interactive confirmation.

Generally disallow unattended:

    external sends/posts
    calls
    camera/mic capture
    security settings
    permission grants
    destructive actions
    lock/power operations
    arbitrary custom actions with meaningful external effect

A capability should carry scheduling eligibility rather than maintaining another
disconnected hardcoded list if practical.

======================================================================
46. CODING DISCIPLINE
======================================================================

Do not produce a single giant overnight commit.

Preferred sequence:

COMMIT 1
    Restore strict observation lease/fingerprint enforcement.

COMMIT 2
    Add immutable action capability snapshot + canonical action catalog.

COMMIT 3
    Add semantic node actions and dynamic planner exposure.

COMMIT 4
    Add typed gesture executor / drag-drop / multi-stroke support.

COMMIT 5
    Expand global actions/runtime discovery.

COMMIT 6
    Add verification policies for new actions.

COMMIT 7
    Consolidate ActionSession identity/state publication/concurrency.

COMMIT 8
    Real temporary conversation and chat handoff.

COMMIT 9
    Dedicated action-model ownership and latency instrumentation.

COMMIT 10
    Overlay / Nabu Anywhere integration if core priorities are stable.

COMMIT 11
    Acceptance/replay/device fixes discovered during validation.

Before each commit:

    inspect diff
    remove churn
    run focused tests
    ensure no unrelated Gemini/easy-cleanup scope was modified

======================================================================
47. OUT OF SCOPE FOR THIS OVERNIGHT RUN
======================================================================

Do not spend the overnight run doing the bounded cleanup work reserved for later unless
a compile/runtime blocker absolutely requires it:

    README cleanup
    project naming cleanup
    Glaive README
    Glaive archive cleanup
    Glaive bridge cleanup
    OAuth logging cleanup
    OAuth encrypted-storage cleanup
    backup-rule cleanup
    general CI polish
    DST scheduling cleanup
    generic dependency updates
    broad UI redesign

Also do not spend time on:

    new TTS engines
    new chat features
    unrelated API server enhancements
    marketplace/plugin architecture
    arbitrary new LLM backends

Stay on the Android action runtime.

======================================================================
48. DO NOT CHEAT
======================================================================

Do NOT solve "all Android actions" by:

    shell commands
    root
    Shizuku
    adb input
    hidden APIs
    arbitrary intents
    package-specific hardcoded pixels

The product claim is:

    robust control through public Android mechanisms

using:

    Accessibility
    trusted intents
    permitted device APIs
    explicit user permissions

Do not bypass Android security boundaries.

======================================================================
49. DEFINITION OF DONE
======================================================================

The overnight run is successful when all of the following are true:

CORE CUSTODY

    ✓ stale observation cannot authorize action
    ✓ same-package screen change invalidates stale action
    ✓ consumed lease cannot be reused
    ✓ coordinate gesture is observation-bound

ACTION SURFACE

    ✓ snapshot captures actual node action capabilities
    ✓ planner sees only currently valid actions
    ✓ standard semantic node actions have canonical mappings
    ✓ custom actions are observation-scoped refs
    ✓ raw arbitrary action IDs cannot be model-supplied
    ✓ full gesture layer includes drag/drop
    ✓ notification shade / Quick Settings / dismiss are supported
    ✓ runtime system actions are discovered rather than blindly assumed

MODEL

    ✓ small model prompt remains compact
    ✓ action schema cannot silently drift from executor
    ✓ dedicated Action Model preference actually works

VERIFICATION

    ✓ new meaningful actions have postcondition strategies
    ✓ executor return true != automatic goal success
    ✓ consequential coordinate gestures cannot bypass confirmation

SESSION

    ✓ one canonical ActionSession ID spans runtime/trace/UI
    ✓ Activity lifetime does not own session lifetime
    ✓ temporary conversation has real context
    ✓ handoff preserves same session
    ✓ cancellation causes no ghost actions
    ✓ concurrent requests do not overwrite each other

UX

    ✓ overlay collapses during physical interaction
    ✓ legitimate entry surfaces converge on shared dispatcher
    ✓ action progress remains visible without blocking target app

PERFORMANCE

    ✓ real latency measurements recorded
    ✓ invocation → first action measured
    ✓ model/runtime overhead separated

RELEASE DEVICE

    ✓ release artifact built
    ✓ release signer verified before install
    ✓ release installed in place
    ✓ primary device data preserved
    ✓ no uninstall
    ✓ no pm clear
    ✓ no debug-signature replacement
    ✓ Accessibility/config/model state preserved

REAL DEVICE

    ✓ arbitrary-app action survives Chat backgrounding
    ✓ notifications drawer open/close verified
    ✓ Quick Settings open verified
    ✓ semantic scroll verified
    ✓ gesture scroll fallback verified
    ✓ safe slider/progress action verified if fixture available
    ✓ safe drag/drop verified on fixture
    ✓ temporary conversation verified
    ✓ handoff verified
    ✓ cancellation verified
    ✓ stale-state rejection verified

======================================================================
50. FINAL OVERNIGHT HANDOFF
======================================================================

Leave a precise engineering handoff.

Use:

# SHIPPED

For each commit:

    SHA
    title
    files
    invariant established

# ARCHITECTURE

Explain the final:

    ActionSession
    action catalog
    snapshot capability model
    semantic executor
    gesture executor
    global actions
    verifier
    recovery path

# ACTION SURFACE

Report:

    standard node actions supported
    gesture primitives supported
    global actions supported
    typed Android actions preserved
    intentionally excluded actions and why

# RELEASE STATE PRESERVATION

Report:

    installed package version
    installed signer fingerprint
    candidate release signer fingerprint
    update command
    whether in-place update succeeded
    evidence that state remained present

DO NOT include secrets.

# TESTS

Report actual test numbers/results.

Do not say:

    "58 tasks passed"

when what matters is:

    actual tests completed
    failures
    skipped tests

# DEVICE VALIDATION

For every scenario:

    starting state
    entry point
    user request
    ActionSession ID
    actions
    execution mechanisms
    result
    verification evidence
    latency
    PASS / FAIL

# PERFORMANCE

    first-action latency
    model latency
    action latency
    verification latency
    workflow latency
    model calls
    recoveries

# FOUND / NOT FIXED

Only concrete issues.

# RELEASE BLOCKERS

Anything preventing safe next release.

# OVERLAP WITH LATER CLEANUP

Identify any files touched that collide with the postponed easy-cleanup work.

======================================================================
51. FINAL PRINCIPLE
======================================================================

Nabu is not successful because it can emit more action JSON.

Nabu is successful when a user can point it at an unfamiliar Android state and say:

    "do this"

and the runtime can:

    understand where it is
        ↓
    discover what Android actually allows
        ↓
    choose the strongest semantic action
        ↓
    fall back to physical gestures when necessary
        ↓
    move through applications and SystemUI
        ↓
    preserve one continuous session
        ↓
    avoid stale actions
        ↓
    pause for consequential confirmation
        ↓
    recover from interference
        ↓
    verify the requested end state
        ↓
    show a concise receipt proving what happened

without bespoke integration for every application.

The goal is not:

    more automation code

The goal is:

    a coherent Android action runtime.
