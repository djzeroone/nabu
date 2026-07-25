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
                    call.arguments["op"]?.toString()?.let { addProperty("op", it) }
                    call.arguments["target"]?.toString()?.let { addProperty("target", it) }
                    call.arguments["expect"]?.toString()?.let { addProperty("expect", it) }
                    val actionArguments = JsonObject()
                    ACTION_ARGUMENT_KEYS.forEach { key ->
                        call.arguments[key]?.let { value ->
                            actionArguments.add(key, JsonPrimitive(value.toString()))
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
                val opString = root.requiredString("op").uppercase()
                val operation = Operation.valueOf(opString)
                val target = root.get("target")?.takeUnless { it.isJsonNull }?.asString?.let { PlannerElementId(it) }
                val expectString = root.get("expect")?.takeUnless { it.isJsonNull }?.asString?.uppercase()
                val expect = expectString?.let { ExpectedEffect.valueOf(it) }
                
                val argsObj = root.getAsJsonObject("args")
                val arguments = mutableMapOf<String, String>()
                argsObj?.entrySet()?.forEach { (k, v) ->
                    require(v.isJsonPrimitive) { "Action argument '$k' must be a primitive value." }
                    arguments[k] = v.asString
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
}
