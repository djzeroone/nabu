package com.mewmix.nabu.accessibility

import com.mewmix.nabu.uiagent.UiBounds
import com.mewmix.nabu.uiagent.UiElement
import com.mewmix.nabu.uiagent.UiScreenState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenSemanticDescriberTest {
    @Test
    fun describeIncludesVisibleTextFocusAndControlState() {
        val title = element("title", text = "Display settings")
        val toggle = element(
            "toggle",
            text = "Dark theme",
            clickable = true,
            checkable = true,
            checked = true,
            focused = true
        )
        val password = element("password", text = "secret", password = true)
        val screen = UiScreenState("s1", "com.android.settings", null, listOf(title, toggle, password))

        val description = ScreenSemanticDescriber.describe(screen, "Settings")

        assertTrue(description.contains("Settings"))
        assertTrue(description.contains("Visible text: Display settings"))
        assertTrue(description.contains("Focused: Dark theme"))
        assertTrue(description.contains("toggle: Dark theme (on) (focused)"))
        assertFalse(description.contains("secret"))
    }

    private fun element(
        id: String,
        text: String,
        clickable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        focused: Boolean = false,
        password: Boolean = false
    ) = UiElement(
        id = id,
        text = text,
        contentDescription = null,
        resourceId = null,
        className = "android.view.View",
        packageName = "com.android.settings",
        bounds = UiBounds(0, 0, 100, 100),
        clickable = clickable,
        enabled = true,
        visible = true,
        editable = false,
        scrollable = false,
        longClickable = false,
        checkable = checkable,
        checked = checked,
        password = password,
        parentId = null,
        treePath = "0/$id",
        focused = focused
    )
}
