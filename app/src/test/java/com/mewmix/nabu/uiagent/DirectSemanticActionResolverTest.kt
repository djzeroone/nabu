package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSemanticActionResolverTest {
    @Test
    fun `global shortcut requires runtime advertised action`() {
        assertNull(DirectSemanticActionResolver.resolve("open notifications", screen()))
        assertTrue(
            DirectSemanticActionResolver.resolve(
                "open notifications",
                screen(systemActions = setOf("notifications"))
            ) is UiActionStep.OpenNotifications
        )
    }

    @Test
    fun `exact unique semantic label resolves to click`() {
        val search = element("search", "Search", setOf("click"))

        val action = DirectSemanticActionResolver.resolve("tap Search", screen(search))

        assertEquals(UiActionStep.NodeAction("click", UiTarget("search", null)), action)
    }

    @Test
    fun `ambiguous semantic label falls through to model`() {
        val first = element("one", "Search", setOf("click"))
        val second = element("two", "Search", setOf("click"))

        assertNull(DirectSemanticActionResolver.resolve("tap Search", screen(first, second)))
    }

    @Test
    fun `deterministic consequential click retains confirmation boundary`() {
        val send = element("send", "Send", setOf("click"))
        val current = screen(send)
        val action = DirectSemanticActionResolver.resolve("tap Send", current)!!

        assertTrue(
            UiActionValidator.validate(
                UiActionPlan("tap Send", current.screenId, listOf(action)),
                current
            ) is UiPlanDecision.RequireConfirmation
        )
    }

    @Test
    fun `deterministic semantic action remains bound to exact screen identity`() {
        val search = element("search", "Search", setOf("click"))
        val current = screen(search)
        val action = DirectSemanticActionResolver.resolve("tap Search", current)!!

        assertTrue(
            UiActionValidator.validate(
                UiActionPlan("tap Search", "stale-screen", listOf(action)),
                current
            ) is UiPlanDecision.Invalid
        )
    }

    @Test
    fun `unique capability backed scroll resolves semantically`() {
        val list = element("list", null, setOf("scroll_down"), scrollable = true)

        assertEquals(
            UiActionStep.NodeAction("scroll_down", UiTarget("list", null)),
            DirectSemanticActionResolver.resolve("scroll down", screen(list))
        )
    }

    @Test
    fun `scroll with multiple candidates falls through to model`() {
        val first = element("one", null, setOf("scroll_forward"), scrollable = true)
        val second = element("two", null, setOf("scroll_forward"), scrollable = true)

        assertNull(DirectSemanticActionResolver.resolve("scroll down", screen(first, second)))
    }

    @Test
    fun `focus and expand require exact unique advertised capabilities`() {
        val search = element("search", "Search", setOf("focus"))
        val details = element("details", "Details", setOf("expand"))

        assertEquals(
            UiActionStep.NodeAction("focus", UiTarget("search", null)),
            DirectSemanticActionResolver.resolve("focus Search", screen(search, details))
        )
        assertEquals(
            UiActionStep.NodeAction("expand", UiTarget("details", null)),
            DirectSemanticActionResolver.resolve("expand Details", screen(search, details))
        )
    }

    @Test
    fun `explicit percentage resolves against one exact range`() {
        val volume = element(
            "volume",
            "Volume",
            setOf("set_progress"),
            range = UiRange(1, 0f, 10f, 2f)
        )

        assertEquals(
            UiActionStep.NodeAction(
                "set_progress",
                UiTarget("volume", null),
                mapOf("value" to "5.0")
            ),
            DirectSemanticActionResolver.resolve("set Volume to 50 percent", screen(volume))
        )
    }

    private fun screen(
        vararg elements: UiElement,
        systemActions: Set<String> = emptySet()
    ) = UiScreenState("screen", "p", null, elements.toList(), systemActions)

    private fun element(
        id: String,
        label: String?,
        actions: Set<String>,
        scrollable: Boolean = false,
        range: UiRange? = null
    ) = UiElement(
        id = id,
        text = label,
        contentDescription = null,
        resourceId = null,
        className = "android.view.View",
        packageName = "p",
        bounds = UiBounds(0, 0, 100, 100),
        clickable = "click" in actions,
        enabled = true,
        visible = true,
        editable = false,
        scrollable = scrollable,
        longClickable = false,
        checkable = false,
        checked = false,
        password = false,
        parentId = null,
        treePath = "0",
        standardActions = actions,
        range = range
    )
}
