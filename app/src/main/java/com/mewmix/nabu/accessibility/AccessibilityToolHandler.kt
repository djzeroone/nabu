package com.mewmix.nabu.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
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
        "ui_global_action",
        "ui_focus"
    )

    fun isEnabled(): Boolean {
        return NabuAccessibilityService.instance != null
    }

    /**
     * Verifies the control plane while Nabu is still visible.
     *
     * Callers must run this before parking Chat or opening another task. A successful probe proves
     * both that Android has bound the service and that it can lease a real accessibility snapshot.
     */
    fun controlPlaneFailure(context: Context): String? {
        val service = NabuAccessibilityService.instance
        if (service == null) {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val enabledByAndroid = manager
                ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    info.resolveInfo.serviceInfo.packageName == context.packageName &&
                        info.resolveInfo.serviceInfo.name == NabuAccessibilityService::class.java.name
                } == true
            return if (enabledByAndroid) {
                "Nabu Accessibility Service is enabled but has not connected yet. " +
                    "Keep Nabu open for a moment or toggle the service off and on, then invoke device control again. " +
                    "The current screen was not changed."
            } else {
                "Nabu Accessibility Service is disabled. Enable Nabu in Android Settings > " +
                    "Accessibility, then invoke device control again. The current screen was not changed."
            }
        }
        if (service.forceCaptureSnapshot() == null) {
            return "Nabu Accessibility Service is connected, but Android did not expose a readable active window. " +
                "Keep the device awake and unlocked, then invoke device control again. " +
                "The current screen was not changed."
        }
        return null
    }

    val TOOLS = listOf(
        com.mewmix.nabu.tools.Tool(
            name = "read_screen",
            description = "Instantly describe the current Android screen, including visible text, focus, controls, and control states. This is read-only.",
            parameters = emptyMap()
        ),
        com.mewmix.nabu.tools.Tool(
            name = "guide_ui",
            description = "Tell the user the next step and move accessibility focus to that control without clicking, typing, or otherwise operating it.",
            parameters = mapOf("goal" to "The user's goal")
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
                val snapshot = service.forceCaptureSnapshot()
                    ?: return ToolResult(call.toolName, "Failed to capture the current screen.", true)
                ToolResult(call.toolName, ScreenSemanticDescriber.describe(snapshot), false)
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
            "ui_tap", "ui_long_press", "ui_set_text", "ui_scroll", "ui_global_action", "ui_focus" -> {
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
