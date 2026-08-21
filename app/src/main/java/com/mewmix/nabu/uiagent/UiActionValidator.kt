package com.mewmix.nabu.uiagent

import com.mewmix.nabu.accessibility.BoundedGestureCatalog
import com.mewmix.nabu.accessibility.StandardNodeAction

sealed interface UiPlanDecision {
    data object Allow : UiPlanDecision
    data class RequireConfirmation(val reason: String) : UiPlanDecision
    data class Block(val reason: String) : UiPlanDecision
    data class Invalid(val reason: String) : UiPlanDecision
}

object UiActionValidator {
    private val blockedTerms = listOf(
        "payment", "purchase", "buy now", "checkout", "bank transfer", "wire transfer",
        "password", "passcode", "two factor", "2fa", "authentication approval",
        "delete account", "factory reset", "install unknown", "unknown apk"
    )
    private val confirmationTerms = listOf(
        "send", "post", "publish", "call", "delete", "remove file", "allow",
        "shutter", "take photo", "capture", "record"
    )

    fun validate(plan: UiActionPlan, screen: UiScreenState): UiPlanDecision {
        if (plan.screenId != screen.screenId) {
            return UiPlanDecision.Invalid("Plan screen_id does not match the current observation.")
        }
        val context = buildSafetyContext(plan, screen).lowercase()
        blockedTerms.firstOrNull(context::contains)?.let {
            return UiPlanDecision.Block("Blocked sensitive UI action involving '$it'.")
        }

        for (step in plan.steps) {
            validateStep(step, screen)?.let { return it }
        }

        val confirmationContext = buildCommitBoundaryContext(plan, screen).lowercase()
        confirmationTerms.firstOrNull(confirmationContext::contains)?.let {
            return UiPlanDecision.RequireConfirmation("Confirmation required for action involving '$it'.")
        }
        return UiPlanDecision.Allow
    }

    private fun validateStep(step: UiActionStep, screen: UiScreenState): UiPlanDecision? = when (step) {
        is UiActionStep.Tap -> validateTarget(step.target, screen, requireEnabled = true)?.let { it }
            ?: validateCapability(step.target, screen, "clickable") { it.clickable || it.checkable }
        is UiActionStep.Focus -> validateTarget(step.target, screen, requireEnabled = true)?.let { it }
            ?: validateCapability(step.target, screen, "focusable") { it.focusable || it.clickable || it.checkable }
        is UiActionStep.NodeAction -> validateNodeAction(step, screen)
        is UiActionStep.CustomAction -> validateCustomAction(step, screen)
        is UiActionStep.Gesture -> validateGesture(step, screen)
        is UiActionStep.LongPress -> validateTarget(step.target, screen, requireEnabled = true)?.let { it }
            ?: validateCapability(step.target, screen, "long-clickable") { it.longClickable }
        is UiActionStep.TypeText -> {
            if (step.text.isBlank()) {
                UiPlanDecision.Invalid("type_text requires non-blank text.")
            } else if (step.target == null) {
                UiPlanDecision.Invalid("type_text requires an editable target element.")
            } else {
                val targetDecision = step.target?.let { validateTarget(it, screen, requireEnabled = true) }
                if (targetDecision != null) {
                    targetDecision
                } else {
                    val targetElement = step.target?.elementId?.let(screen::element)
                    if (targetElement?.password == true) {
                        UiPlanDecision.Block("Typing into password fields is blocked.")
                    } else {
                        null
                    }
                }
            }
        }
        is UiActionStep.Scroll -> step.target?.let { target ->
            validateTarget(target, screen, requireEnabled = true)
        }
        is UiActionStep.Wait -> if (step.milliseconds !in 0..5_000) {
            UiPlanDecision.Invalid("wait must be between 0 and 5000 ms.")
        } else null
        is UiActionStep.Assert -> validateAssertion(step.condition, screen)
        is UiActionStep.AskUser -> if (step.reason.isBlank()) UiPlanDecision.Invalid("ask_user requires a reason.") else null
        is UiActionStep.Done -> if (step.summary.isBlank()) UiPlanDecision.Invalid("done requires a summary.") else null
        UiActionStep.PressBack, UiActionStep.PressHome, UiActionStep.PressRecents, UiActionStep.OpenNotifications, UiActionStep.OpenQuickSettings -> null
        is UiActionStep.GlobalAction -> if (step.action !in screen.systemActions) {
            UiPlanDecision.Invalid("Global action '${step.action}' is unavailable in the current observation.")
        } else null
        is UiActionStep.OpenApp,
        is UiActionStep.OpenSettingsPage,
        is UiActionStep.OpenUrl,
        is UiActionStep.ShareText,
        is UiActionStep.OpenCamera,
        is UiActionStep.ShareCapturedMedia -> null
    }

    private fun validateGesture(step: UiActionStep.Gesture, screen: UiScreenState): UiPlanDecision? {
        validateTarget(step.target, screen, requireEnabled = true)?.let { return it }
        step.destination?.let { validateTarget(it, screen, requireEnabled = true)?.let { decision -> return decision } }
        if (step.gesture !in BoundedGestureCatalog.plannerTokens) {
            return UiPlanDecision.Invalid("Unknown or unbounded gesture '${step.gesture}'.")
        }
        if (step.gesture == "drag_drop" && step.destination == null) {
            return UiPlanDecision.Invalid("drag_drop requires an observed destination target.")
        }
        val coordinateKeys = listOf("start_x", "start_y", "end_x", "end_y", "center_x", "center_y")
        coordinateKeys.forEach { key ->
            step.arguments[key]?.toFloatOrNull()?.let { value ->
                if (!value.isFinite() || value !in 0f..1f) {
                    return UiPlanDecision.Invalid("Gesture coordinate '$key' must be normalized.")
                }
            }
        }
        val points = runCatching { BoundedGestureCatalog.parsePoints(step.arguments["points"].orEmpty()) }
        if (points.isFailure) return UiPlanDecision.Invalid(points.exceptionOrNull()?.message ?: "Invalid gesture points.")
        return null
    }

    private fun validateNodeAction(
        step: UiActionStep.NodeAction,
        screen: UiScreenState
    ): UiPlanDecision? {
        validateTarget(step.target, screen, requireEnabled = true)?.let { return it }
        val element = step.target.elementId?.let(screen::element)
            ?: return UiPlanDecision.Invalid("node_action requires a current target element.")
        val token = step.action.trim().lowercase()
        if (token !in element.standardActions) {
            return UiPlanDecision.Invalid("Target element '${element.id}' does not advertise '$token'.")
        }
        if (element.password && token in setOf("copy", "cut", "paste", "set_text", "set_selection")) {
            return UiPlanDecision.Block("Text and clipboard actions on password fields are blocked.")
        }
        val metadata = StandardNodeAction.entries.firstOrNull { it.token == token }
            ?: return UiPlanDecision.Invalid("Unknown standard node action '$token'.")
        metadata.requiredArguments.firstOrNull { step.arguments[it].isNullOrBlank() }?.let { missing ->
            return UiPlanDecision.Invalid("$token requires '$missing'.")
        }
        if (token == "set_progress") {
            val value = step.arguments["value"]?.toFloatOrNull()
                ?: return UiPlanDecision.Invalid("set_progress requires a numeric value.")
            val range = element.range
                ?: return UiPlanDecision.Invalid("set_progress requires target RangeInfo.")
            if (value !in range.min..range.max) {
                return UiPlanDecision.Invalid("set_progress value is outside the target range.")
            }
        }
        return null
    }

    private fun validateCustomAction(
        step: UiActionStep.CustomAction,
        screen: UiScreenState
    ): UiPlanDecision? {
        validateTarget(step.target, screen, requireEnabled = true)?.let { return it }
        val element = step.target.elementId?.let(screen::element)
            ?: return UiPlanDecision.Invalid("custom_action requires a current target element.")
        if (element.customActions.none { it.ref == step.actionRef }) {
            return UiPlanDecision.Invalid("Custom action ref is not advertised for this target observation.")
        }
        return null
    }

    private fun validateTarget(
        target: UiTarget,
        screen: UiScreenState,
        requireEnabled: Boolean
    ): UiPlanDecision? {
        val elementId = target.elementId ?: return if (target.fallbackBounds?.isValid == true) null else {
            UiPlanDecision.Invalid("Target has no valid element or bounds.")
        }
        val element = screen.element(elementId) ?: return if (isOnScreen(target.fallbackBounds, screen)) {
            null
        } else {
            UiPlanDecision.Invalid("Target element '$elementId' is not present and has no on-screen fallback bounds.")
        }
        if (!element.visible) return UiPlanDecision.Invalid("Target element '$elementId' is not visible.")
        if (requireEnabled && !element.enabled) return UiPlanDecision.Invalid("Target element '$elementId' is disabled.")
        if (element.password) return UiPlanDecision.Block("Password fields cannot be targeted.")
        return null
    }

    private fun validateCapability(
        target: UiTarget,
        screen: UiScreenState,
        capability: String,
        predicate: (UiElement) -> Boolean
    ): UiPlanDecision? {
        val element = target.elementId?.let(screen::element) ?: return null
        return if (predicate(element)) null else {
            UiPlanDecision.Invalid("Target element '${element.id}' is not $capability.")
        }
    }

    private fun validateAssertion(assertion: UiAssertion, screen: UiScreenState): UiPlanDecision? {
        // A stale optional assertion should fail post-action verification and trigger
        // replanning, not block an otherwise valid primary action.
        return null
    }

    private fun isOnScreen(bounds: UiBounds?, screen: UiScreenState): Boolean {
        if (bounds?.isValid != true) return false
        val knownBounds = screen.elements.mapNotNull { it.bounds }
        if (knownBounds.isEmpty()) return false
        val left = knownBounds.minOf { it.left }
        val top = knownBounds.minOf { it.top }
        val right = knownBounds.maxOf { it.right }
        val bottom = knownBounds.maxOf { it.bottom }
        return bounds.left >= left && bounds.top >= top && bounds.right <= right && bounds.bottom <= bottom
    }

    private fun buildSafetyContext(plan: UiActionPlan, screen: UiScreenState): String {
        val targetText = plan.steps.mapNotNull { targetSafetyEvidence(it, screen) }
        return (listOf(plan.goal) + targetText).joinToString(" ")
    }

    private fun buildCommitBoundaryContext(plan: UiActionPlan, screen: UiScreenState): String =
        plan.steps.mapNotNull { step ->
            if (step is UiActionStep.TypeText || step is UiActionStep.Scroll || step is UiActionStep.Assert) {
                null
            } else {
                targetSafetyEvidence(step, screen)
            }
        }.joinToString(" ")

    private fun targetSafetyEvidence(step: UiActionStep, screen: UiScreenState): String? {
        val id = when (step) {
            is UiActionStep.Tap -> step.target.elementId
            is UiActionStep.LongPress -> step.target.elementId
            is UiActionStep.TypeText -> step.target?.elementId
            is UiActionStep.Scroll -> step.target?.elementId
            is UiActionStep.NodeAction -> step.target.elementId
            is UiActionStep.CustomAction -> step.target.elementId
            is UiActionStep.Gesture -> step.target.elementId
            is UiActionStep.Assert -> step.condition.elementId
            else -> null
        }
        val element = id?.let(screen::element) ?: return null
        val actionEvidence = when (step) {
            is UiActionStep.CustomAction -> element.customActions.singleOrNull { it.ref == step.actionRef }?.label
            is UiActionStep.NodeAction -> step.action
            is UiActionStep.Gesture -> step.gesture
            else -> null
        }
        return listOfNotNull(
            element.text,
            element.contentDescription,
            element.resourceId,
            actionEvidence
        ).joinToString(" ")
    }
}

internal fun UiActionPlan.resolveElementReferences(screen: UiScreenState): UiActionPlan = copy(
    steps = steps.map { step ->
        when (step) {
            is UiActionStep.Tap -> step.copy(target = step.target.resolve(screen))
            is UiActionStep.LongPress -> step.copy(target = step.target.resolve(screen))
            is UiActionStep.TypeText -> step.copy(target = step.target?.resolve(screen))
            is UiActionStep.Scroll -> step.copy(target = step.target?.resolve(screen))
            is UiActionStep.NodeAction -> step.copy(target = step.target.resolve(screen))
            is UiActionStep.CustomAction -> step.copy(target = step.target.resolve(screen))
            is UiActionStep.Gesture -> step.copy(
                target = step.target.resolve(screen),
                destination = step.destination?.resolve(screen)
            )
            is UiActionStep.Assert -> step.copy(
                condition = step.condition.copy(
                    elementId = step.condition.elementId?.let { id -> screen.element(id)?.id ?: id }
                )
            )
            else -> step
        }
    }
)

private fun UiTarget.resolve(screen: UiScreenState): UiTarget {
    val resolvedById = elementId?.let(screen::element)
    val resolvedByLabel = if (resolvedById == null && elementId == null && textContains != null) {
        val query = textContains.trim()
        val candidates = screen.plannerElements().filter { element ->
            screen.plannerLabel(element)?.contains(query, ignoreCase = true) == true
        }
        candidates.singleOrNull()
            ?: candidates.singleOrNull { screen.plannerLabel(it).equals(query, ignoreCase = true) }
    } else {
        null
    }
    val resolved = resolvedById ?: resolvedByLabel
    return copy(
        elementId = resolved?.id ?: elementId,
        fallbackBounds = fallbackBounds ?: resolved?.bounds
    )
}
