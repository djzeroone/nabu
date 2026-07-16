package com.mewmix.nabu.uiagent

import org.junit.Assert.*
import org.junit.Test

class DestinationResolverTest {

    private fun makeScreen(
        packageName: String?,
        vararg elements: UiElement
    ): UiScreenState = UiScreenState(
        screenId = "test_screen",
        packageName = packageName,
        activityName = null,
        elements = elements.toList()
    )

    private fun makeElement(
        id: String,
        text: String? = null,
        resourceId: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false
    ): UiElement = UiElement(
        id = id,
        text = text,
        contentDescription = null,
        resourceId = resourceId,
        className = "android.widget.TextView",
        packageName = null,
        bounds = UiBounds(0, 0, 100, 50),
        clickable = clickable,
        enabled = true,
        visible = true,
        editable = editable,
        scrollable = false,
        longClickable = false,
        checkable = false,
        checked = false,
        password = false,
        parentId = null,
        treePath = "0"
    )

    // --- Telegram fixtures ---

    @Test
    fun `telegram saved messages with matching expected destination`() {
        val screen = makeScreen(
            "org.telegram.messenger",
            makeElement("e_0", text = "Saved Messages", resourceId = "org.telegram.messenger:id/title"),
            makeElement("e_1", text = "Type a message", editable = true)
        )
        val result = DestinationResolver.resolve(screen, "Saved Messages")
        assertTrue("Expected Verified, got $result", result is DestinationResolver.DestinationResult.Verified)
        assertEquals("Saved Messages", (result as DestinationResolver.DestinationResult.Verified).observed)
    }

    @Test
    fun `telegram wrong recipient returns mismatch`() {
        val screen = makeScreen(
            "org.telegram.messenger",
            makeElement("e_0", text = "Mom", resourceId = "org.telegram.messenger:id/title"),
            makeElement("e_1", text = "Type a message", editable = true)
        )
        val result = DestinationResolver.resolve(screen, "Saved Messages")
        assertTrue("Expected Mismatch, got $result", result is DestinationResolver.DestinationResult.Mismatch)
        val mismatch = result as DestinationResolver.DestinationResult.Mismatch
        assertEquals("Mom", mismatch.observed)
        assertEquals("Saved Messages", mismatch.expected)
    }

    @Test
    fun `telegram no title element returns unresolvable`() {
        val screen = makeScreen(
            "org.telegram.messenger",
            makeElement("e_0", text = "Some other text", resourceId = "org.telegram.messenger:id/message"),
            makeElement("e_1", text = "Type a message", editable = true)
        )
        val result = DestinationResolver.resolve(screen, "Saved Messages")
        assertTrue("Expected Unresolvable, got $result", result is DestinationResolver.DestinationResult.Unresolvable)
    }

    @Test
    fun `telegram title element with blank text returns unresolvable`() {
        val screen = makeScreen(
            "org.telegram.messenger",
            makeElement("e_0", text = "  ", resourceId = "org.telegram.messenger:id/title"),
            makeElement("e_1", text = "Type a message", editable = true)
        )
        val result = DestinationResolver.resolve(screen, "Saved Messages")
        assertTrue("Expected Unresolvable, got $result", result is DestinationResolver.DestinationResult.Unresolvable)
    }

    // --- Google Messages fixtures ---

    @Test
    fun `google messages matching destination`() {
        val screen = makeScreen(
            "com.google.android.apps.messaging",
            makeElement("e_0", text = "John Doe", resourceId = "com.google.android.apps.messaging:id/conversation_title"),
            makeElement("e_1", text = "Text message", editable = true)
        )
        val result = DestinationResolver.resolve(screen, "John Doe")
        assertTrue("Expected Verified, got $result", result is DestinationResolver.DestinationResult.Verified)
        assertEquals("John Doe", (result as DestinationResolver.DestinationResult.Verified).observed)
    }

    @Test
    fun `google messages case insensitive match`() {
        val screen = makeScreen(
            "com.google.android.apps.messaging",
            makeElement("e_0", text = "john doe", resourceId = "com.google.android.apps.messaging:id/conversation_title"),
            makeElement("e_1", text = "Text message", editable = true)
        )
        val result = DestinationResolver.resolve(screen, "John Doe")
        assertTrue("Expected Verified, got $result", result is DestinationResolver.DestinationResult.Verified)
    }

    @Test
    fun `google messages matches formatted phone number`() {
        val screen = makeScreen(
            "com.google.android.apps.messaging",
            makeElement(
                "e_0",
                text = "(949) 771-4923",
                resourceId = "com.google.android.apps.messaging:id/conversation_title"
            )
        )

        val result = DestinationResolver.resolve(screen, "+1 949 771 4923")

        assertTrue(result is DestinationResolver.DestinationResult.Verified)
    }

    // --- Unknown package ---

    @Test
    fun `unknown package returns unresolvable`() {
        val screen = makeScreen(
            "com.example.random",
            makeElement("e_0", text = "Some title", resourceId = "com.example.random:id/title")
        )
        val result = DestinationResolver.resolve(screen, "Some title")
        assertTrue("Expected Unresolvable, got $result", result is DestinationResolver.DestinationResult.Unresolvable)
    }

    @Test
    fun `null package returns unresolvable`() {
        val screen = makeScreen(
            null,
            makeElement("e_0", text = "Some title")
        )
        val result = DestinationResolver.resolve(screen, "Some title")
        assertTrue("Expected Unresolvable, got $result", result is DestinationResolver.DestinationResult.Unresolvable)
    }

    // --- Required expected destination ---

    @Test
    fun `blank expected destination returns unresolvable`() {
        val screen = makeScreen(
            "org.telegram.messenger",
            makeElement("e_0", text = "Saved Messages", resourceId = "org.telegram.messenger:id/title")
        )
        val result = DestinationResolver.resolve(screen, "")
        assertTrue(result is DestinationResolver.DestinationResult.Unresolvable)
    }

    @Test
    fun `arbitrary observed chat does not match expected destination`() {
        val screen = makeScreen(
            "org.telegram.messenger",
            makeElement("e_0", text = "Random Chat Group", resourceId = "org.telegram.messenger:id/title")
        )
        val result = DestinationResolver.resolve(screen, "Saved Messages")
        assertTrue(result is DestinationResolver.DestinationResult.Mismatch)
    }

    // --- isSupported ---

    @Test
    fun `isSupported returns true for telegram`() {
        assertTrue(DestinationResolver.isSupported("org.telegram.messenger"))
    }

    @Test
    fun `isSupported returns false for unknown package`() {
        assertFalse(DestinationResolver.isSupported("com.example.random"))
    }

    @Test
    fun `isSupported returns false for null`() {
        assertFalse(DestinationResolver.isSupported(null))
    }
}
