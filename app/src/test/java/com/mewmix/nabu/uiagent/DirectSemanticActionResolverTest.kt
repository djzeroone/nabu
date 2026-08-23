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

    private fun screen(
        vararg elements: UiElement,
        systemActions: Set<String> = emptySet()
    ) = UiScreenState("screen", "p", null, elements.toList(), systemActions)

    private fun element(
        id: String,
        label: String?,
        actions: Set<String>,
        scrollable: Boolean = false
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
        standardActions = actions
    )
}
