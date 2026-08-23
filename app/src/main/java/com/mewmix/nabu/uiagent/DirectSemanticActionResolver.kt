package com.mewmix.nabu.uiagent

/** Resolves only exact, unique, capability-backed commands that do not need model reasoning. */
object DirectSemanticActionResolver {
    fun resolve(request: String, screen: UiScreenState): UiActionStep? {
        val normalized = normalize(request).removePrefix("please ")
        globalAction(normalized, screen)?.let { return it }
        scrollAction(normalized, screen)?.let { return it }
        focusAction(normalized, screen)?.let { return it }
        expandCollapseAction(normalized, screen)?.let { return it }
        progressAction(normalized, screen)?.let { return it }
        return clickAction(normalized, screen)
    }

    private fun globalAction(normalized: String, screen: UiScreenState): UiActionStep? {
        val (token, action) = when (normalized) {
            "press home", "go home", "go to home", "open home screen", "show home screen" ->
                "home" to UiActionStep.PressHome
            "press back", "go back" -> "back" to UiActionStep.PressBack
            "open recents", "show recents", "press recents" -> "recents" to UiActionStep.PressRecents
            "open notifications", "show notifications", "open notification shade" ->
                "notifications" to UiActionStep.OpenNotifications
            "open quick settings", "show quick settings" ->
                "quick_settings" to UiActionStep.OpenQuickSettings
            else -> return null
        }
        return action.takeIf { token in screen.systemActions }
    }

    private fun scrollAction(normalized: String, screen: UiScreenState): UiActionStep? {
        val direction = when (normalized) {
            "scroll down" -> ScrollDirection.DOWN
            "scroll up" -> ScrollDirection.UP
            "scroll left" -> ScrollDirection.LEFT
            "scroll right" -> ScrollDirection.RIGHT
            else -> return null
        }
        val preferredTokens = when (direction) {
            ScrollDirection.DOWN -> listOf("scroll_down", "scroll_forward")
            ScrollDirection.UP -> listOf("scroll_up", "scroll_backward")
            ScrollDirection.LEFT -> listOf("scroll_left", "scroll_backward")
            ScrollDirection.RIGHT -> listOf("scroll_right", "scroll_forward")
        }
        val candidates = screen.elements.filter { element ->
            element.visible && element.enabled && !element.password &&
                preferredTokens.any(element.standardActions::contains)
        }
        if (candidates.size != 1) return null
        val target = candidates.single()
        val token = preferredTokens.first(target.standardActions::contains)
        return UiActionStep.NodeAction(token, UiTarget(target.id, null))
    }

    private fun clickAction(normalized: String, screen: UiScreenState): UiActionStep? {
        val match = CLICK_COMMAND.matchEntire(normalized) ?: return null
        val requestedLabel = match.groupValues[1].removePrefix("the ").trim()
        if (requestedLabel.isEmpty()) return null
        val matches = exactElements(requestedLabel, screen, "click")
        if (matches.size != 1) return null
        return UiActionStep.NodeAction("click", UiTarget(matches.single().id, null))
    }

    private fun focusAction(normalized: String, screen: UiScreenState): UiActionStep? {
        val match = FOCUS_COMMAND.matchEntire(normalized) ?: return null
        val label = match.groupValues[1].removePrefix("the ").trim()
        val matches = exactElements(label, screen, "focus")
        return matches.singleOrNull()?.let {
            UiActionStep.NodeAction("focus", UiTarget(it.id, null))
        }
    }

    private fun expandCollapseAction(normalized: String, screen: UiScreenState): UiActionStep? {
        val match = EXPAND_COLLAPSE_COMMAND.matchEntire(normalized) ?: return null
        val action = match.groupValues[1]
        val label = match.groupValues[2].removePrefix("the ").trim()
        val matches = exactElements(label, screen, action)
        return matches.singleOrNull()?.let {
            UiActionStep.NodeAction(action, UiTarget(it.id, null))
        }
    }

    private fun progressAction(normalized: String, screen: UiScreenState): UiActionStep? {
        val match = PROGRESS_COMMAND.matchEntire(normalized) ?: return null
        val label = match.groupValues[1].removePrefix("the ").trim()
        val requested = match.groupValues[2].toFloatOrNull() ?: return null
        val isPercent = match.groupValues[3].isNotBlank()
        val target = exactElements(label, screen, "set_progress").singleOrNull() ?: return null
        val range = target.range ?: return null
        val value = if (isPercent) {
            if (requested !in 0f..100f) return null
            range.min + ((range.max - range.min) * requested / 100f)
        } else {
            requested
        }
        if (value !in range.min..range.max) return null
        return UiActionStep.NodeAction(
            "set_progress",
            UiTarget(target.id, null),
            mapOf("value" to value.toString())
        )
    }

    private fun exactElements(label: String, screen: UiScreenState, action: String): List<UiElement> =
        screen.elements.filter { element ->
            element.visible && element.enabled && !element.password &&
                action in element.standardActions &&
                listOfNotNull(element.text, element.contentDescription, element.hintText)
                    .any { normalize(it) == label }
        }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9%]+"), " ")
        .trim()

    private val CLICK_COMMAND = Regex("(?:tap|click|activate|press) (.+)")
    private val FOCUS_COMMAND = Regex("focus (?:on )?(.+)")
    private val EXPAND_COLLAPSE_COMMAND = Regex("(expand|collapse) (.+)")
    private val PROGRESS_COMMAND = Regex("set (.+) to (-?[0-9]+(?:[.][0-9]+)?) ?(%|percent)?")
}
