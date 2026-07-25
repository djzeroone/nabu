package com.mewmix.nabu.accessibility

import com.mewmix.nabu.uiagent.UiElement
import com.mewmix.nabu.uiagent.UiScreenState
import com.mewmix.nabu.uiagent.UiTreeIndexer

/**
 * Produces a useful, bounded screen description without another inference call.
 * The complete snapshot remains available to trusted code; callers receive a
 * compact projection suitable for speech and agent context.
 */
object ScreenSemanticDescriber {
    private const val MAX_VISIBLE_TEXTS = 24
    private const val MAX_CONTROLS = 16

    fun describe(snapshot: UiSnapshot): String =
        describe(UiTreeIndexer.build(snapshot), snapshot.windowTitle)

    fun describe(screen: UiScreenState, windowTitle: String? = screen.activityName): String {
        val lines = mutableListOf<String>()
        val surface = windowTitle?.trim().takeUnless { it.isNullOrEmpty() }
            ?: screen.packageName?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "Current screen"
        lines += surface

        val focused = screen.elements.firstOrNull { it.visible && it.focused }
        focused?.let { element ->
            elementLabel(screen, element)?.let { lines += "Focused: $it." }
        }

        val controlIds = screen.plannerElements(MAX_CONTROLS).mapTo(hashSetOf()) { it.id }
        val visibleText = screen.elements.asSequence()
            .filter { it.visible && !it.password && it.id !in controlIds }
            .flatMap { sequenceOf(it.text, it.contentDescription, it.hintText) }
            .filterNotNull()
            .map(String::trim)
            .filter { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }
            .distinct()
            .take(MAX_VISIBLE_TEXTS)
            .toList()
        if (visibleText.isNotEmpty()) {
            lines += "Visible text: ${visibleText.joinToString("; ")}."
        }

        val controls = screen.plannerElements(MAX_CONTROLS).mapIndexedNotNull { index, element ->
            val label = elementLabel(screen, element) ?: return@mapIndexedNotNull null
            "p$index ${role(element)}: $label${stateSuffix(element)}"
        }
        if (controls.isNotEmpty()) {
            lines += "Controls: ${controls.joinToString("; ")}."
        } else {
            lines += "No interactive controls are exposed by accessibility."
        }
        return lines.joinToString("\n")
    }

    private fun elementLabel(screen: UiScreenState, element: UiElement): String? =
        screen.plannerLabel(element)
            ?: element.resourceId?.substringAfterLast('/')?.replace('_', ' ')?.takeIf(String::isNotBlank)

    private fun role(element: UiElement): String = when {
        element.editable -> "text field"
        element.checkable -> "toggle"
        element.scrollable -> "scroll area"
        element.longClickable && !element.clickable -> "long-press control"
        else -> "button"
    }

    private fun stateSuffix(element: UiElement): String = buildString {
        if (element.checkable) append(if (element.checked) " (on)" else " (off)")
        if (element.selected) append(" (selected)")
        if (element.focused) append(" (focused)")
        if (!element.enabled) append(" (disabled)")
    }
}
