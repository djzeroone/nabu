package com.mewmix.nabu.accessibility

import android.content.Context
import com.mewmix.nabu.tools.ToolCall
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class AccessibilityToolHandlerTest {
    @Test
    fun handlesOnlyNativeAccessibilityTools() {
        listOf(
            "observe_ui",
            "read_screen",
            "take_screenshot",
            "read_ui_xml",
            "ui_tap",
            "ui_long_press",
            "ui_set_text",
            "ui_scroll",
            "ui_global_action"
        ).forEach { toolName ->
            assertTrue("Expected native accessibility tool: $toolName", AccessibilityToolHandler.handles(toolName))
        }

        listOf(
            "list_files",
            "read_file",
            "write_file",
            "create_dir",
            "delete_file",
            "search_files"
        ).forEach { toolName ->
            assertFalse("Expected Glaive tool: $toolName", AccessibilityToolHandler.handles(toolName))
        }
    }

    @Test
    fun executeLeavesGlaiveFileToolsUnhandledWhenAccessibilityIsDisabled() {
        val result = AccessibilityToolHandler.execute(
            mock(Context::class.java),
            ToolCall("list_files", mapOf("path" to "/sdcard"))
        )

        assertNull(result)
    }
}
