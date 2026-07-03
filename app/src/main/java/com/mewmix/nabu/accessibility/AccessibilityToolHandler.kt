package com.mewmix.nabu.accessibility

import android.content.Context
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import org.json.JSONObject
import java.io.File
import java.util.UUID

object AccessibilityToolHandler {
    private val ACCESSIBILITY_TOOLS = setOf(
        "observe_ui",
        "read_screen",
        "take_screenshot",
        "read_ui_xml",
        "ui_tap",
        "ui_long_press",
        "ui_set_text",
        "ui_scroll",
        "ui_global_action"
    )

    fun isEnabled(): Boolean {
        return NabuAccessibilityService.instance != null
    }

    val TOOLS = listOf(
        com.mewmix.nabu.tools.Tool(
            name = "read_screen",
            description = "Reads the visible UI structure (Accessibility node tree) of the device screen. Use this ONLY for inspecting the Android UI, NOT for reading files from storage.",
            parameters = emptyMap()
        ),
        com.mewmix.nabu.tools.Tool(
            name = "take_screenshot",
            description = "Takes a screenshot of the current Android device screen. Use this ONLY to capture the device screen, NOT to take a photo using the camera.",
            parameters = emptyMap()
        )
    )

    internal fun handles(toolName: String): Boolean = toolName in ACCESSIBILITY_TOOLS

    fun execute(context: Context, call: ToolCall): ToolResult? {
        if (!handles(call.toolName)) return null
        val service = NabuAccessibilityService.instance ?: return ToolResult(
            toolName = call.toolName,
            output = "Nabu Accessibility Service is not enabled.",
            isError = true
        )

        return when (call.toolName) {
            "observe_ui" -> {
                val id = UUID.randomUUID().toString()
                val xmlPath = File(context.cacheDir, "nabu_ui_$id.xml").absolutePath
                val requestScreenshot = call.arguments["request_screenshot"]?.toString()?.toBoolean() == true
                val screenshotPath = if (requestScreenshot) File(context.cacheDir, "nabu_ui_$id.png").absolutePath else null
                try {
                    val result = service.observeUi(xmlPath, screenshotPath)
                    ToolResult(call.toolName, result.toString(), false)
                } catch (e: Exception) {
                    ToolResult(call.toolName, "Error: ${e.message}", true)
                }
            }
            "read_screen" -> {
                val id = UUID.randomUUID().toString()
                val xmlPath = File(context.cacheDir, "nabu_ui_$id.xml").absolutePath
                if (service.dumpScreenToXml(xmlPath)) {
                    val result = JSONObject().put("path", xmlPath)
                    ToolResult(call.toolName, result.toString(), false)
                } else {
                    ToolResult(call.toolName, "Failed to capture screen XML.", true)
                }
            }
            "take_screenshot" -> {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    return ToolResult(call.toolName, "take_screenshot requires API 30+.", true)
                }
                val id = UUID.randomUUID().toString()
                val screenshotPath = File(context.cacheDir, "nabu_ui_$id.png").absolutePath
                if (service.takeScreenshotToPath(screenshotPath)) {
                    val result = JSONObject().put("path", screenshotPath)
                    ToolResult(call.toolName, result.toString(), false)
                } else {
                    ToolResult(call.toolName, "Failed to capture screenshot.", true)
                }
            }
            "read_ui_xml" -> {
                val path = call.arguments["path"]?.toString() ?: return ToolResult(call.toolName, "Missing path argument.", true)
                val file = File(path)
                if (file.exists()) {
                    ToolResult(call.toolName, file.readText(), false)
                } else {
                    ToolResult(call.toolName, "XML file not found at $path.", true)
                }
            }
            "ui_tap", "ui_long_press", "ui_set_text", "ui_scroll", "ui_global_action" -> {
                try {
                    val params = JSONObject(call.arguments)
                    val result = service.performUiAction(call.toolName, params)
                    ToolResult(call.toolName, result.toString(), false)
                } catch (e: Exception) {
                    ToolResult(call.toolName, "Error: ${e.message}", true)
                }
            }
            else -> null
        }
    }
}
