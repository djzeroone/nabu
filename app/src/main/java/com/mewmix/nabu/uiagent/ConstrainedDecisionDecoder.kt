package com.mewmix.nabu.uiagent

import com.google.gson.JsonParser
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mewmix.nabu.chat.LlmStructuredToolCall
import com.mewmix.nabu.tools.CapabilityId

object ConstrainedDecisionDecoder {

    fun decode(call: LlmStructuredToolCall): AgentDecision {
        return decode(toCanonicalJson(call))
    }

    fun toCanonicalJson(call: LlmStructuredToolCall): String {
        val root = JsonObject().apply {
            addProperty("v", 3)
            when (call.name) {
                "ui_act" -> {
                    addProperty("kind", "act")
                    val rawOperation = call.arguments["op"]?.toString().orEmpty()
                    rawOperation.takeIf(String::isNotBlank)?.let { addProperty("op", it) }
                    val target = listOf("target", "targetId", "target_id")
                        .firstNotNullOfOrNull { key ->
                            call.arguments[key]?.toString()?.let {
                                extractPlannerElementId(it, allowBareIdField = true)
                            }
                        }
                        ?: extractPlannerElementId(rawOperation)
                    target?.let { addProperty("target", it) }
                    val expectedEffect = call.arguments["expect"]?.toString()
                        ?.takeIf(String::isNotBlank)
                        ?: extractBundledField(rawOperation, "expect")
                    expectedEffect?.let { addProperty("expect", it) }
                    val actionArguments = JsonObject()
                    ACTION_ARGUMENT_KEYS.forEach { key ->
                        call.arguments[key]?.let { value ->
                            actionArguments.add(key, JsonPrimitive(value.toString()))
                        }
                    }
                    extractBundledField(rawOperation, "direction")
                        ?.takeUnless { it.equals("undefined", ignoreCase = true) }
                        ?.let { direction ->
                            if (!actionArguments.has("direction")) {
                                actionArguments.addProperty("direction", direction)
                            }
                        }
                    if (actionArguments.size() > 0) add("args", actionArguments)
                }
                "ui_ask" -> {
                    addProperty("kind", "ask")
                    call.arguments["question"]?.toString()?.let { addProperty("question", it) }
                }
                "ui_finish" -> {
                    addProperty("kind", "finish")
                    call.arguments["outcome"]?.toString()?.let { addProperty("outcome", it) }
                }
                else -> throw IllegalArgumentException("Unknown structured UI decision '${call.name}'.")
            }
        }
        return root.toString()
    }
    
    fun decode(jsonString: String): AgentDecision {
        val root = JsonParser.parseString(jsonString).asJsonObject
        require(root.has("v") && root.get("v").asInt == 3) { "Not a V3 decision payload." }
        val kind = root.requiredString("kind")
        
        return when (kind) {
            "act" -> {
                requireOnlyKeys(root, "v", "kind", "op", "target", "args", "expect")
                val rawOperation = root.requiredString("op")
                val operation = parseOperation(rawOperation)
                val target = root.get("target")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.let { extractPlannerElementId(it, allowBareIdField = true) }
                    ?.let(::PlannerElementId)
                    ?: extractPlannerElementId(rawOperation)?.let(::PlannerElementId)
                val expect = (root.get("expect")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString ?: extractBundledField(rawOperation, "expect"))
                    ?.let(::parseExpectedEffect)
                
                val argsObj = root.getAsJsonObject("args")
                val arguments = mutableMapOf<String, String>()
                argsObj?.entrySet()?.forEach { (k, v) ->
                    require(v.isJsonPrimitive) { "Action argument '$k' must be a primitive value." }
                    arguments[k] = v.asString
                }
                extractBundledField(rawOperation, "direction")
                    ?.takeUnless { it.equals("undefined", ignoreCase = true) }
                    ?.let { arguments.putIfAbsent("direction", it) }
                if (operation == Operation.SCROLL && !arguments.containsKey("direction")) {
                    arguments["direction"] = when {
                        rawOperation.contains("down", ignoreCase = true) -> "down"
                        rawOperation.contains("up", ignoreCase = true) -> "up"
                        rawOperation.contains("left", ignoreCase = true) -> "left"
                        rawOperation.contains("right", ignoreCase = true) -> "right"
                        else -> "down"
                    }
                }
                validateAct(operation, target, arguments)
                
                AgentDecision.Act(
                    operation = operation,
                    target = target,
                    arguments = arguments,
                    expectedEffect = expect
                )
            }
            "query" -> {
                requireOnlyKeys(root, "v", "kind", "query", "expected_type")
                val q = root.requiredString("query")
                val t = root.get("expected_type")?.takeUnless { it.isJsonNull }?.asString
                AgentDecision.Query(UiQuery(query = q, expectedType = t))
            }
            "delegate" -> {
                requireOnlyKeys(root, "v", "kind", "capability", "objective")
                val cap = root.requiredString("capability").uppercase()
                val obj = root.requiredString("objective")
                AgentDecision.Delegate(
                    capability = CapabilityId.valueOf(cap),
                    objective = obj
                )
            }
            "ask" -> {
                requireOnlyKeys(root, "v", "kind", "question")
                AgentDecision.Ask(question = root.requiredString("question"))
            }
            "finish" -> {
                requireOnlyKeys(root, "v", "kind", "outcome")
                AgentDecision.Finish(outcome = root.requiredString("outcome"))
            }
            else -> throw IllegalArgumentException("Unknown decision kind: $kind")
        }
    }

    /**
     * Small local models often produce a semantically exact operation with a UI-oriented alias.
     * Normalize only lossless aliases; schema and target validation still run afterward.
     */
    private fun parseOperation(raw: String): Operation {
        val trimmed = raw.trim()
        val stripped = when {
            Regex("""(?i)^([a-zA-Z_]+)_(p\d+|e_[a-f0-9]+)$""").matches(trimmed) ->
                trimmed.substringBeforeLast('_')
            Regex("""(?i)^([a-zA-Z_]+)_x.*""").matches(trimmed) ->
                trimmed.substringBefore("_x")
            Regex("""(?i)^([a-zA-Z_]+)\s+target.*""").matches(trimmed) ->
                trimmed.substringBefore(" target")
            else -> trimmed
        }
        val operationToken = OPERATION_PREFIX.find(stripped)?.groupValues?.get(1).orEmpty()
        val bundledSuffix = stripped.removePrefix(operationToken).trim()
        require(
            bundledSuffix.isBlank() || BUNDLED_FIELD_MARKER.containsMatchIn(bundledSuffix) || EXACT_PLANNER_ID.containsMatchIn(bundledSuffix)
        ) {
            "Unsupported bundled data in UI operation '$raw'."
        }
        val normalized = operationToken.lowercase()
            .replace('-', '_')
            .replace(' ', '_')
        val canonical = when {
            normalized.startsWith("tap") || normalized.startsWith("click") || normalized.startsWith("touch") || normalized.startsWith("select") -> "tap"
            normalized.startsWith("long_press") || normalized.startsWith("longpress") || normalized.startsWith("hold") -> "long_press"
            normalized.startsWith("type") || normalized.startsWith("enter") || normalized.startsWith("input") || normalized.startsWith("set_text") -> "type_text"
            normalized.startsWith("scroll") || normalized.startsWith("swipe") -> "scroll"
            normalized.startsWith("back") || normalized.startsWith("press_back") || normalized.startsWith("go_back") -> "press_back"
            normalized.startsWith("home") || normalized.startsWith("press_home") || normalized.startsWith("go_home") -> "press_home"
            normalized.startsWith("recents") || normalized.startsWith("recent_apps") || normalized.startsWith("press_recents") -> "press_recents"
            normalized.startsWith("notification") || normalized.startsWith("open_notification") -> "open_notifications"
            normalized.startsWith("quick_settings") || normalized.startsWith("open_quick_settings") -> "open_quick_settings"
            normalized.startsWith("open_settings") -> "open_settings_page"
            normalized.startsWith("open_app") -> "open_app"
            normalized.startsWith("open_url") || normalized.startsWith("open_link") -> "open_url"
            normalized.startsWith("open_cam") -> "open_camera"
            normalized.startsWith("wait") -> "wait"
            normalized.startsWith("share_text") -> "share_text"
            normalized.startsWith("share_cap") || normalized.startsWith("share_media") -> "share_captured_media"
            normalized.startsWith("focus") -> "focus"
            else -> normalized
        }
        return Operation.entries.firstOrNull { it.name.equals(canonical, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unsupported UI operation '$raw'. Allowed operations: " +
                    Operation.entries.joinToString { it.name.lowercase() } + "."
            )
    }

    private fun parseExpectedEffect(raw: String): ExpectedEffect {
        val normalized = raw.trim().lowercase()
            .replace('-', '_')
            .replace(' ', '_')
        val canonical = when (normalized) {
            "screen_change", "window_change" -> "surface_change"
            "content_change", "state_change" -> "mutation"
            "appears", "content_appears" -> "content_appear"
            "none", "unchanged" -> "no_change"
            else -> normalized
        }
        return ExpectedEffect.entries.firstOrNull { it.name.equals(canonical, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unsupported expected effect '$raw'.")
    }

    private fun validateAct(
        operation: Operation,
        target: PlannerElementId?,
        arguments: Map<String, String>
    ) {
        if (operation in TARGET_REQUIRED_OPERATIONS) {
            require(target != null && target.id.matches(Regex("""(?:p\d+|e_[a-fA-F0-9]+)"""))) {
                "$operation requires a supplied target ID."
            }
        }
        when (operation) {
            Operation.TYPE_TEXT -> require(!arguments["text"].isNullOrEmpty()) { "TYPE_TEXT requires non-empty text." }
            Operation.SCROLL -> require(
                arguments["direction"]?.uppercase() in setOf("UP", "DOWN", "LEFT", "RIGHT")
            ) { "SCROLL requires a supported direction." }
            Operation.OPEN_APP -> require(!arguments["package_name"].isNullOrBlank()) { "OPEN_APP requires package_name." }
            Operation.OPEN_SETTINGS_PAGE -> require(!arguments["page"].isNullOrBlank()) { "OPEN_SETTINGS_PAGE requires page." }
            Operation.OPEN_URL -> require(!arguments["url"].isNullOrBlank()) { "OPEN_URL requires url." }
            Operation.OPEN_CAMERA -> {
                require(!arguments["mode"].isNullOrBlank()) { "OPEN_CAMERA requires mode." }
                require(!arguments["facing"].isNullOrBlank()) { "OPEN_CAMERA requires facing." }
            }
            Operation.WAIT -> require(arguments["ms"]?.toLongOrNull()?.let { it > 0 } == true) { "WAIT requires positive ms." }
            Operation.SHARE_TEXT -> require(!arguments["text"].isNullOrBlank()) { "SHARE_TEXT requires text." }
            Operation.SHARE_CAPTURED_MEDIA -> {
                require(!arguments["target_package"].isNullOrBlank()) { "SHARE_CAPTURED_MEDIA requires target_package." }
                require(!arguments["expected_destination"].isNullOrBlank()) {
                    "SHARE_CAPTURED_MEDIA requires expected_destination."
                }
            }
            else -> Unit
        }
    }

    private fun requireOnlyKeys(json: JsonObject, vararg allowed: String) {
        val extras = json.keySet() - allowed.toSet()
        require(extras.isEmpty()) { "Unknown decision fields: ${extras.joinToString()}." }
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name)?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        require(value.isNotEmpty()) { "Missing or blank '$name'." }
        return value
    }

    private fun extractPlannerElementId(
        raw: String,
        allowBareIdField: Boolean = false
    ): String? {
        val trimmed = raw.trim()
        if (allowBareIdField) {
            EXACT_PLANNER_ID.matchEntire(trimmed)?.let { return it.value.lowercase() }
            BARE_ID_FIELD.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it.lowercase() }
        }
        TARGET_ID_FIELD.find(trimmed)?.groupValues?.getOrNull(1)?.lowercase()?.let { return it }
        return EXACT_PLANNER_ID.find(trimmed)?.value?.lowercase()
    }

    private fun extractBundledField(raw: String, field: String): String? =
        Regex("""(?i)\b${Regex.escape(field)}\b\s*[:=]\s*["']?([a-zA-Z_-]+)""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

    private val TARGET_REQUIRED_OPERATIONS = setOf(
        Operation.TAP,
        Operation.LONG_PRESS,
        Operation.TYPE_TEXT,
        Operation.FOCUS
    )

    private val ACTION_ARGUMENT_KEYS = setOf(
        "text",
        "direction",
        "package_name",
        "page",
        "url",
        "mode",
        "facing",
        "ms",
        "target_package",
        "expected_destination"
    )

    private val EXACT_PLANNER_ID = Regex("""(?i)(?:p\d+|e_[a-f0-9]+)""")
    private val BARE_ID_FIELD =
        Regex("""(?i)\b(?:id|target|target_?id)\b\s*[:=]\s*["']?((?:p\d+|e_[a-f0-9]+))\b""")
    private val TARGET_ID_FIELD = Regex(
        """(?i)\btarget(?:_?id)?\b\s*(?:[:=]\s*)?(?:\{\s*(?:"?id"?\s*[:=]\s*)?)?["']?((?:p\d+|e_[a-f0-9]+))\b"""
    )
    private val OPERATION_PREFIX = Regex("""^\s*([a-zA-Z][a-zA-Z_-]*)""")
    private val BUNDLED_FIELD_MARKER =
        Regex("""(?i)(?:\b(?:target|target_?id)\b\s*(?:[:=]|\{)|\b(?:expect|direction)\b\s*[:=])""")
}
