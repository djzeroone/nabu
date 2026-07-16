package com.mewmix.nabu.uiagent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class UiActionPlan(
    val goal: String,
    val screenId: String,
    val steps: List<UiActionStep>
)

sealed interface UiActionStep {
    data class Tap(val target: UiTarget) : UiActionStep
    data class LongPress(val target: UiTarget) : UiActionStep
    data class TypeText(val text: String, val target: UiTarget?) : UiActionStep
    data object PressBack : UiActionStep
    data object PressHome : UiActionStep
    data object PressRecents : UiActionStep
    data object OpenNotifications : UiActionStep
    data object OpenQuickSettings : UiActionStep
    data class Scroll(val direction: ScrollDirection, val target: UiTarget?) : UiActionStep
    data class Wait(val milliseconds: Long) : UiActionStep
    data class Assert(val condition: UiAssertion) : UiActionStep
    data class AskUser(val reason: String) : UiActionStep
    data class Done(val summary: String) : UiActionStep
    
    // Typed Android Actions
    data class OpenApp(val packageName: String) : UiActionStep
    data class OpenSettingsPage(val page: SettingsPage, val packageName: String?) : UiActionStep
    data class OpenUrl(val url: String) : UiActionStep
    data class ShareText(
        val text: String,
        val targetPackage: String?,
        val expectedDestination: String? = null
    ) : UiActionStep
    data class OpenCamera(val mode: CameraMode, val facing: CameraFacing) : UiActionStep
    data class ShareCapturedMedia(
        val targetPackage: String,
        val expectedDestination: String
    ) : UiActionStep
}

data class UiTarget(
    val elementId: String?,
    val fallbackBounds: UiBounds?,
    val textContains: String? = null
)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

enum class SettingsPage {
    WIFI, BLUETOOTH, DISPLAY, SOUND, ACCESSIBILITY, 
    NOTIFICATION_SETTINGS, APP_DETAILS, DEVELOPER_OPTIONS, WIRELESS_DEBUGGING
}

enum class CameraMode { PHOTO, VIDEO }

enum class CameraFacing { FRONT, REAR, UNSPECIFIED }

data class UiAssertion(
    val elementId: String?,
    val textContains: String?,
    val checked: Boolean?
)

object UiActionPlanParser {
    fun parsePlannerOutput(rawJson: String, knownGoal: String, knownScreenId: String): UiActionPlan {
        val root = JsonParser.parseString(rawJson).asJsonObject
        root.addProperty("goal", knownGoal)
        root.addProperty("screen_id", knownScreenId)
        if (!root.has("steps") && root.has("action")) {
            val step = root.deepCopy().apply {
                remove("goal")
                remove("screen_id")
            }
            root.add("steps", JsonArray().apply { add(normalizeStepEnvelope(step)) })
        }
        return parse(root.toString())
    }

    fun parse(rawJson: String): UiActionPlan {
        val root = JsonParser.parseString(rawJson).asJsonObject
        val goal = root.requiredString("goal")
        val screenId = root.requiredString("screen_id")
        val rawSteps = root.optJsonArray("steps")
            ?: root.optJsonObject("steps")?.let { JsonArray().apply { add(it) } }
            ?: error("Missing steps array.")
        require(rawSteps.size() > 0) { "A plan must contain at least one step." }
        val normalizedSteps = rawSteps.map { normalizeStepEnvelope(it.asJsonObject.deepCopy()) }
        val actionJson = normalizedSteps.firstOrNull {
            normalizeAction(it.requiredString("action"), it) != "assert"
        }
            ?: error("A plan must contain at least one non-assert action.")
        val action = parseStep(actionJson)
        val assertion = normalizedSteps.lastOrNull {
            normalizeAction(it.requiredString("action"), it) == "assert"
        }?.let(::parseStep) as? UiActionStep.Assert
        return UiActionPlan(goal, screenId, listOfNotNull(action, assertion))
    }

    private fun checkKeys(json: JsonObject, vararg allowedKeys: String) {
        val extraKeys = json.keySet() - allowedKeys.toSet()
        require(extraKeys.isEmpty()) { "Unknown JSON fields in action '${json.get("action")?.asString}': $extraKeys" }
    }

    private fun parseStep(json: JsonObject): UiActionStep {
        val actionName = normalizeAction(json.requiredString("action"), json)
        return when (actionName) {
            "tap" -> UiActionStep.Tap(parseTarget(json.optJsonObject("target")) ?: error("Missing or invalid target."))
                .also { checkKeys(json, "action", "target") }
            "long_press" -> UiActionStep.LongPress(parseTarget(json.optJsonObject("target")) ?: error("Missing or invalid target."))
                .also { checkKeys(json, "action", "target") }
            "type_text" -> UiActionStep.TypeText(
                text = json.requiredString("text"),
                target = parseTarget(json.optJsonObject("target"))
            ).also { checkKeys(json, "action", "text", "target") }
            "press_back" -> UiActionStep.PressBack.also { checkKeys(json, "action", "target", "element_id", "selector_id", "target_id", "fallback_bounds", "text_contains", "label") }
            "press_home" -> UiActionStep.PressHome.also { checkKeys(json, "action", "target", "element_id", "selector_id", "target_id", "fallback_bounds", "text_contains", "label") }
            "press_recents" -> UiActionStep.PressRecents.also { checkKeys(json, "action") }
            "open_notifications" -> UiActionStep.OpenNotifications.also { checkKeys(json, "action") }
            "open_quick_settings" -> UiActionStep.OpenQuickSettings.also { checkKeys(json, "action") }
            "scroll" -> UiActionStep.Scroll(
                direction = ScrollDirection.valueOf(json.requiredString("direction").uppercase()),
                target = parseTarget(json.optJsonObject("target"))
            ).also { checkKeys(json, "action", "direction", "target") }
            "wait" -> UiActionStep.Wait(json.optLong("ms") ?: error("Missing ms."))
                .also { checkKeys(json, "action", "ms") }
            "assert" -> UiActionStep.Assert(parseAssertion(json.optJsonObject("condition")) ?: error("Missing or invalid condition."))
                .also { checkKeys(json, "action", "condition") }
            "ask_user" -> UiActionStep.AskUser(json.requiredString("reason"))
                .also { checkKeys(json, "action", "reason") }
            "done" -> UiActionStep.Done(json.requiredString("summary"))
                .also { checkKeys(json, "action", "summary") }
            "open_app" -> UiActionStep.OpenApp(json.requiredString("package_name"))
                .also { checkKeys(json, "action", "package_name") }
            "open_settings_page" -> UiActionStep.OpenSettingsPage(
                page = SettingsPage.valueOf(json.requiredString("page").uppercase()),
                packageName = json.optionalString("package_name")
            ).also { checkKeys(json, "action", "page", "package_name") }
            "open_url" -> UiActionStep.OpenUrl(json.requiredString("url"))
                .also { checkKeys(json, "action", "url") }
            "share_text" -> UiActionStep.ShareText(
                text = json.requiredString("text"),
                targetPackage = json.optionalString("target_package"),
                expectedDestination = json.optionalString("expected_destination")
            ).also { checkKeys(json, "action", "text", "target_package", "expected_destination") }
            "open_camera" -> UiActionStep.OpenCamera(
                mode = CameraMode.valueOf(json.requiredString("mode").uppercase()),
                facing = CameraFacing.valueOf(json.requiredString("facing").uppercase())
            ).also { checkKeys(json, "action", "mode", "facing") }
            "share_captured_media" -> UiActionStep.ShareCapturedMedia(
                targetPackage = json.requiredString("target_package"),
                expectedDestination = json.requiredString("expected_destination")
            ).also { checkKeys(json, "action", "target_package", "expected_destination") }
            else -> error("Unsupported UI action '$actionName'.")
        }
    }

    private fun normalizeStepEnvelope(step: JsonObject): JsonObject {
        val action = normalizeAction(step.get("action")?.asString.orEmpty(), step)
        step.addProperty("action", action)
        if (action in TARGET_ACTIONS && !step.has("target")) {
            val target = JsonObject()
            step.remove("element_id")?.let { target.add("element_id", it) }
            if (!target.has("element_id")) {
                step.remove("selector_id")?.let { target.add("element_id", it) }
            }
            if (!target.has("element_id")) {
                step.remove("target_id")?.let { target.add("element_id", it) }
            }
            step.remove("fallback_bounds")?.let { target.add("fallback_bounds", it) }
            step.remove("text_contains")?.let { target.add("text_contains", it) }
            step.remove("label")?.let { target.add("text_contains", it) }
            if (target.size() > 0) step.add("target", target)
        }
        if (action == "assert" && !step.has("condition")) {
            val condition = JsonObject()
            listOf("element_id", "text_contains", "checked").forEach { name ->
                step.remove(name)?.let { condition.add(name, it) }
            }
            if (condition.size() > 0) step.add("condition", condition)
        }
        return step
    }

    private fun normalizeAction(action: String, step: JsonObject? = null): String {
        val normalized = normalizeActionToken(action)
        if (normalized in SUPPORTED_ACTIONS) return normalized

        val candidates = action.lowercase()
            .split('|', '/')
            .map(::normalizeActionToken)
            .filter { it in SUPPORTED_ACTIONS }
            .distinct()
        if (candidates.size == 1) return candidates.single()
        if (candidates.size > 1) {
            val hasTarget = step?.has("target") == true ||
                step?.has("element_id") == true ||
                step?.has("selector_id") == true ||
                step?.has("target_id") == true ||
                step?.has("text_contains") == true ||
                step?.has("label") == true ||
                step?.has("fallback_bounds") == true
            val shapeMatches = candidates.filter { candidate ->
                if (hasTarget) candidate in TARGET_ACTIONS else candidate !in TARGET_ACTIONS
            }
            if (shapeMatches.size == 1) return shapeMatches.single()
        }
        return normalized
    }

    private fun normalizeActionToken(action: String): String = when (
        val normalized = action.trim().lowercase().replace('-', '_').replace(' ', '_')
    ) {
        "tap_text", "click", "click_text" -> "tap"
        "longpress", "long_press_text" -> "long_press"
        "input_text", "set_text", "enter_text" -> "type_text"
        "back", "go_back" -> "press_back"
        "home", "go_home" -> "press_home"
        "recents", "recent_apps" -> "press_recents"
        "notifications", "open_notification" -> "open_notifications"
        "quick_settings" -> "open_quick_settings"
        else -> normalized
    }

    private val TARGET_ACTIONS = setOf("tap", "long_press", "type_text", "scroll")
    private val SUPPORTED_ACTIONS = TARGET_ACTIONS +
        setOf(
            "press_back", "press_home", "press_recents", "open_notifications", "open_quick_settings",
            "wait", "assert", "ask_user", "done",
            "open_app", "open_settings_page", "open_url", "share_text", "open_camera", "share_captured_media"
        )

    private fun parseTarget(json: JsonObject?): UiTarget? {
        if (json == null) return null
        val elementId = json.optionalString("element_id")
            ?: json.optionalString("selector_id")
            ?: json.optionalString("target_id")
        val bounds = json.optJsonArray("fallback_bounds")?.toBounds()
        val textContains = json.optionalString("text_contains") ?: json.optionalString("label")
        if (elementId == null && bounds == null && textContains == null) return null
        
        val allowedTargetKeys = setOf("element_id", "selector_id", "target_id", "fallback_bounds", "text_contains", "label")
        val extraTargetKeys = json.keySet() - allowedTargetKeys
        require(extraTargetKeys.isEmpty()) { "Unknown JSON fields in target: $extraTargetKeys" }

        return UiTarget(elementId, bounds, textContains)
    }

    private fun parseAssertion(json: JsonObject?): UiAssertion? {
        if (json == null) return null
        val elementId = json.optionalString("element_id")
        val textContains = json.optionalString("text_contains")
        val checked = json.optBoolean("checked")
        if (elementId == null && textContains == null && checked == null) return null

        val allowedAssertionKeys = setOf("element_id", "text_contains", "checked")
        val extraAssertionKeys = json.keySet() - allowedAssertionKeys
        require(extraAssertionKeys.isEmpty()) { "Unknown JSON fields in assertion condition: $extraAssertionKeys" }

        return UiAssertion(elementId, textContains, checked)
    }

    private fun JsonArray.toBounds(): UiBounds? {
        val ints = mapNotNull {
            if (it.isJsonPrimitive && it.asJsonPrimitive.isNumber) it.asInt
            else if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString.toIntOrNull()
            else null
        }
        if (ints.size == 4) {
            val bounds = UiBounds(ints[0], ints[1], ints[2], ints[3])
            if (bounds.isValid) return bounds
        }
        return null
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: error("Missing $name.")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.optJsonObject(name: String): JsonObject? {
        val element = get(name) ?: return null
        if (element.isJsonObject) return element.asJsonObject
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val str = element.asString.trim()
            if (str.startsWith("{")) {
                return runCatching { JsonParser.parseString(str).asJsonObject }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.optJsonArray(name: String): JsonArray? {
        val element = get(name) ?: return null
        if (element.isJsonArray) return element.asJsonArray
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val str = element.asString.trim()
            if (str.startsWith("[")) {
                return runCatching { JsonParser.parseString(str).asJsonArray }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.optBoolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isBoolean) return element.asBoolean
        return element.asString.equals("true", ignoreCase = true)
    }

    private fun JsonObject.optLong(name: String): Long? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) return element.asLong
        return element.asString.toLongOrNull()
    }
}
