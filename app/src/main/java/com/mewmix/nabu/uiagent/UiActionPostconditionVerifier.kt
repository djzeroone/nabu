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
        else -> PostconditionResult(
            PostconditionStatus.NOT_APPLICABLE,
            "No built-in postcondition is defined for this action."
        )
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
        val editable = screen.elements.filter { it.visible && it.editable && !it.password }
        source.resourceId?.takeIf(String::isNotBlank)?.let { resourceId ->
            editable.singleOrNull { it.resourceId == resourceId }?.let { return it }
        }
        editable.singleOrNull {
            it.treePath == source.treePath && it.className == source.className
        }?.let { return it }
        return editable.singleOrNull { candidate ->
            candidate.className == source.className && candidate.bounds == source.bounds
        }
    }

    private fun normalize(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}
