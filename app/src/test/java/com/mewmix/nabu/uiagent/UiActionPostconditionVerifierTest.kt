package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Test

class UiActionPostconditionVerifierTest {
    @Test
    fun verifiesTextAfterElementIdChangesWithItsContent() {
        val before = screenWithInput("")
        val after = screenWithInput("hello from Nabu")
        val source = before.elements.single()

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.TypeText(
                text = "hello from Nabu",
                target = UiTarget(source.id, source.bounds)
            ),
            before,
            after
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun failsWhenSetTextReportsSuccessButComposerDidNotChange() {
        val before = screenWithInput("")
        val after = screenWithInput("")
        val source = before.elements.single()

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.TypeText("expected", UiTarget(source.id, source.bounds)),
            before,
            after
        )

        assertEquals(PostconditionStatus.FAILED, result.status)
    }

    @Test
    fun normalizesWhitespaceWhenVerifyingComposerText() {
        val before = screenWithInput("")
        val after = screenWithInput("hello\u00a0  from\nNabu")

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.TypeText("hello from Nabu", UiTarget(before.elements.single().id, null)),
            before,
            after
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun verifiesSemanticFocusState() {
        val before = screenWithInput("")
        val source = before.elements.single().copy(focused = false)
        val afterElement = source.copy(focused = true)

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.NodeAction("focus", UiTarget(source.id, source.bounds)),
            before.copy(elements = listOf(source), screenId = "before"),
            before.copy(elements = listOf(afterElement), screenId = "after")
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    @Test
    fun verifiesSetProgressAgainstRangeInfo() {
        val base = screenWithInput("")
        val source = base.elements.single().copy(range = UiRange(0, 0f, 100f, 10f))
        val destination = source.copy(range = source.range?.copy(current = 42f))

        val result = UiActionPostconditionVerifier.verify(
            UiActionStep.NodeAction(
                "set_progress",
                UiTarget(source.id, source.bounds),
                mapOf("value" to "42")
            ),
            base.copy(elements = listOf(source), screenId = "before"),
            base.copy(elements = listOf(destination), screenId = "after")
        )

        assertEquals(PostconditionStatus.VERIFIED, result.status)
    }

    private fun screenWithInput(text: String): UiScreenState = UiTreeIndexer.parse(
        """
        <hierarchy>
          <node package="org.telegram.messenger.web"
                resource-id="org.telegram.messenger.web:id/chat_message_edit_text"
                class="android.widget.EditText"
                text="$text"
                hint="Message"
                editable="true"
                focusable="true"
                focused="true"
                visible="true"
                bounds="[20,2000][900,2150]" />
        </hierarchy>
        """.trimIndent()
    )
}
