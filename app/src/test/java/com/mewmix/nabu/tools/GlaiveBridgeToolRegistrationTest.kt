package com.mewmix.nabu.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class GlaiveBridgeToolRegistrationTest {

    @Test
    fun registerDefaultTools_includesAllCurrentGlaiveFileToolsOnly() {
        val expected = listOf(
            "list_files",
            "read_file",
            "write_file",
            "create_dir",
            "delete_file",
            "search_files"
        )
        expected.forEach { ToolRegistry.unregister(it) }
        listOf("read_screen", "take_screenshot").forEach { ToolRegistry.unregister(it) }

        GlaiveBridge.registerDefaultTools()

        val names = ToolRegistry.tools.value.map { it.name }.toSet()
        expected.forEach { name ->
            assertTrue("Expected tool registration for $name", name in names)
        }
        assertTrue("Accessibility tools must not be registered by Glaive", "read_screen" !in names)
        assertTrue("Accessibility tools must not be registered by Glaive", "take_screenshot" !in names)
    }
}
