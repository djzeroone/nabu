package com.mewmix.nabu.uiagent

internal enum class PostconditionStatus {
    VERIFIED,
    FAILED,
    NOT_APPLICABLE
}

internal data class PostconditionResult(
    val status: PostconditionStatus,
    val detail: String
)

internal object UiActionPostconditionVerifier {
    fun verify(
        action: UiActionStep,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult = when (action) {
        is UiActionStep.TypeText -> verifyTypedText(action, before, after)
        is UiActionStep.Focus -> verifyBooleanTarget(action.target, before, after, "focused", true) { it.focused }
        is UiActionStep.NodeAction -> verifyNodeAction(action, before, after)
        is UiActionStep.CustomAction -> verifyMutation(action.target, before, after, "custom action")
        is UiActionStep.Gesture -> verifyMutation(action.target, before, after, action.gesture)
        is UiActionStep.Tap -> verifyMutation(action.target, before, after, "activation")
        is UiActionStep.LongPress -> verifyMutation(action.target, before, after, "long press")
        is UiActionStep.Scroll -> verifyScreenMutation(before, after, "Visible scroll state")
        UiActionStep.PressBack,
        UiActionStep.PressHome,
        UiActionStep.PressRecents -> verifyScreenMutation(before, after, "Global navigation")
        UiActionStep.OpenNotifications -> verifySystemSurface(after, "notification")
        UiActionStep.OpenQuickSettings -> verifySystemSurface(after, "quick settings")
        is UiActionStep.GlobalAction -> when (action.action) {
            "notifications" -> verifySystemSurface(after, "notification")
            "quick_settings" -> verifySystemSurface(after, "quick settings")
            "dismiss_notification_shade" -> verifyScreenMutation(before, after, "Notification shade")
            else -> verifyScreenMutation(before, after, "Global action ${action.action}")
        }
        else -> PostconditionResult(
            PostconditionStatus.NOT_APPLICABLE,
            "No built-in postcondition is defined for this action."
        )
    }

    private fun verifyNodeAction(
        action: UiActionStep.NodeAction,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult = when (action.action) {
        "focus" -> verifyBooleanTarget(action.target, before, after, "focused", true) { it.focused }
        "clear_focus" -> verifyBooleanTarget(action.target, before, after, "focused", false) { it.focused }
        "accessibility_focus" -> verifyBooleanTarget(
            action.target,
            before,
            after,
            "accessibilityFocused",
            true
        ) { it.accessibilityFocused }
        "clear_accessibility_focus" -> verifyBooleanTarget(
            action.target,
            before,
            after,
            "accessibilityFocused",
            false
        ) { it.accessibilityFocused }
        "select" -> verifyBooleanTarget(action.target, before, after, "selected", true) { it.selected }
        "clear_selection" -> verifyBooleanTarget(action.target, before, after, "selected", false) { it.selected }
        "set_text", "cut", "paste" -> verifyTargetTextChanged(action.target, before, after)
        "set_progress" -> verifyProgress(action, before, after)
        "scroll_forward", "scroll_backward", "scroll_up", "scroll_down", "scroll_left", "scroll_right",
        "page_up", "page_down", "page_left", "page_right", "scroll_to_position", "scroll_in_direction" ->
            verifyScreenMutation(before, after, "Visible scroll state")
        "click", "long_click", "context_click", "press_and_hold", "ime_enter", "expand", "collapse",
        "show_on_screen", "show_tooltip", "hide_tooltip", "dismiss", "drag_start", "drag_drop",
        "drag_cancel", "move_window", "show_text_suggestions",
        "next_at_movement_granularity", "previous_at_movement_granularity", "next_html_element",
        "previous_html_element" -> verifyMutation(action.target, before, after, action.action)
        "set_selection" -> verifyTextSelection(action, before, after)
        "copy" -> PostconditionResult(
            PostconditionStatus.NOT_APPLICABLE,
            "Copy was executed, but clipboard contents remain opaque and were not verified."
        )
        else -> PostconditionResult(PostconditionStatus.FAILED, "No verifier exists for '${action.action}'.")
    }

    private fun verifyTextSelection(
        action: UiActionStep.NodeAction,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val start = action.arguments["selection_start"]?.toIntOrNull()
        val end = action.arguments["selection_end"]?.toIntOrNull()
        val source = action.target.elementId?.let(before::element)
        val destination = source?.let { findSameControl(it, after) }
        return if (start != null && end != null &&
            destination?.selectionStart == start && destination.selectionEnd == end
        ) {
            PostconditionResult(PostconditionStatus.VERIFIED, "The requested text selection is active.")
        } else {
            PostconditionResult(PostconditionStatus.FAILED, "The requested text selection was not observed.")
        }
    }

    private fun verifyProgress(
        action: UiActionStep.NodeAction,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val expected = action.arguments["value"]?.toFloatOrNull()
            ?: return PostconditionResult(PostconditionStatus.FAILED, "Requested progress value is missing.")
        val source = action.target.elementId?.let(before::element)
            ?: return PostconditionResult(PostconditionStatus.FAILED, "Progress target is missing.")
        val observed = findSameControl(source, after)?.range?.current
            ?: return PostconditionResult(PostconditionStatus.FAILED, "Progress target RangeInfo is unavailable after execution.")
        val range = source.range
        val tolerance = ((range?.max ?: expected) - (range?.min ?: expected)).let { kotlin.math.abs(it) * 0.01f }.coerceAtLeast(0.001f)
        return if (kotlin.math.abs(observed - expected) <= tolerance) {
            PostconditionResult(PostconditionStatus.VERIFIED, "The range control reached the requested value.")
        } else {
            PostconditionResult(PostconditionStatus.FAILED, "The range control did not reach the requested value.")
        }
    }

    private fun verifyTargetTextChanged(
        target: UiTarget,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val source = target.elementId?.let(before::element)
            ?: return PostconditionResult(PostconditionStatus.FAILED, "Text target is missing.")
        val destination = findSameControl(source, after)
            ?: return PostconditionResult(PostconditionStatus.FAILED, "Text target disappeared after execution.")
        return if (destination.text != source.text) {
            PostconditionResult(PostconditionStatus.VERIFIED, "The target text changed.")
        } else {
            PostconditionResult(PostconditionStatus.FAILED, "The target text did not change.")
        }
    }

    private fun verifyBooleanTarget(
        target: UiTarget,
        before: UiScreenState,
        after: UiScreenState,
        property: String,
        expected: Boolean,
        read: (UiElement) -> Boolean
    ): PostconditionResult {
        val source = target.elementId?.let(before::element)
            ?: return PostconditionResult(PostconditionStatus.FAILED, "Target is missing before execution.")
        val destination = findSameControl(source, after)
            ?: return PostconditionResult(PostconditionStatus.FAILED, "Target disappeared before $property could be verified.")
        return if (read(destination) == expected) {
            PostconditionResult(PostconditionStatus.VERIFIED, "Target $property is $expected.")
        } else {
            PostconditionResult(PostconditionStatus.FAILED, "Target $property did not become $expected.")
        }
    }

    private fun verifyMutation(
        target: UiTarget,
        before: UiScreenState,
        after: UiScreenState,
        label: String
    ): PostconditionResult {
        if (before.screenId != after.screenId || before.packageName != after.packageName) {
            return PostconditionResult(PostconditionStatus.VERIFIED, "$label produced an observable UI mutation.")
        }
        val source = target.elementId?.let(before::element)
        val destination = source?.let { findSameControl(it, after) }
        val stateChanged = source != null && destination != null &&
            (source.checked != destination.checked || source.selected != destination.selected ||
                source.focused != destination.focused || source.text != destination.text || source.range != destination.range)
        return if (stateChanged) {
            PostconditionResult(PostconditionStatus.VERIFIED, "$label changed the target state.")
        } else {
            PostconditionResult(PostconditionStatus.FAILED, "$label produced no observable target or screen change.")
        }
    }

    private fun verifyScreenMutation(
        before: UiScreenState,
        after: UiScreenState,
        label: String
    ): PostconditionResult = if (before.screenId != after.screenId || before.packageName != after.packageName) {
        PostconditionResult(PostconditionStatus.VERIFIED, "$label changed.")
    } else {
        PostconditionResult(PostconditionStatus.FAILED, "$label did not observably change.")
    }

    private fun verifySystemSurface(after: UiScreenState, expected: String): PostconditionResult {
        val evidence = buildString {
            append(after.packageName.orEmpty())
            append(' ')
            after.elements.take(40).forEach { append(it.text).append(' ').append(it.contentDescription).append(' ') }
        }.lowercase()
        val matches = evidence.contains("systemui") || when (expected) {
            "notification" -> evidence.contains("notification")
            else -> evidence.contains("quick settings") || evidence.contains("internet") && evidence.contains("brightness")
        }
        return if (matches) {
            PostconditionResult(PostconditionStatus.VERIFIED, "The $expected system surface is visible.")
        } else {
            PostconditionResult(PostconditionStatus.FAILED, "The $expected system surface was not observed.")
        }
    }

    private fun verifyTypedText(
        action: UiActionStep.TypeText,
        before: UiScreenState,
        after: UiScreenState
    ): PostconditionResult {
        val source = action.target?.elementId?.let(before::element)
            ?: return PostconditionResult(
                PostconditionStatus.FAILED,
                "The editable source element could not be identified before typing."
            )
        if (source.password) {
            return PostconditionResult(PostconditionStatus.FAILED, "Password text cannot be verified.")
        }
        val destination = findSameControl(source, after)
            ?: return PostconditionResult(
                PostconditionStatus.FAILED,
                "The editable control could not be re-identified after typing."
            )
        val expected = normalize(action.text)
        val observed = normalize(destination.text.orEmpty())
        return if (observed == expected) {
            PostconditionResult(PostconditionStatus.VERIFIED, "The editable control contains the requested text.")
        } else {
            PostconditionResult(
                PostconditionStatus.FAILED,
                "The editable control text did not match the requested text."
            )
        }
    }

    private fun findSameControl(source: UiElement, screen: UiScreenState): UiElement? {
        val candidates = screen.elements.filter { it.visible && !it.password }
        source.resourceId?.takeIf(String::isNotBlank)?.let { resourceId ->
            candidates.singleOrNull { it.resourceId == resourceId }?.let { return it }
        }
        candidates.singleOrNull {
            it.treePath == source.treePath && it.className == source.className
        }?.let { return it }
        return candidates.singleOrNull { candidate ->
            candidate.className == source.className && candidate.bounds == source.bounds
        }
    }

    private fun normalize(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}
