package com.mewmix.nabu.uiagent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

internal data class AutomationTraceEvent(
    val sessionId: String,
    val sequence: Int,
    val elapsedMs: Long,
    val name: String,
    val fields: Map<String, Any?> = emptyMap()
) {
    fun toJson(): String = JsonObject().apply {
        addProperty("schema_version", 1)
        addProperty("session_id", sessionId)
        addProperty("sequence", sequence)
        addProperty("elapsed_ms", elapsedMs)
        addProperty("event", name)
        add("fields", sanitizeMap(fields))
    }.toString()

    private fun sanitizeMap(values: Map<String, Any?>): JsonObject = JsonObject().apply {
        values.forEach { (key, value) -> add(key, sanitize(key, value)) }
    }

    private fun sanitize(key: String, value: Any?): JsonElement {
        if (value == null) return JsonNull.INSTANCE
        if (isSensitiveKey(key)) {
            val raw = value.toString()
            return JsonObject().apply {
                addProperty("redacted", true)
                addProperty("length", raw.length)
                addProperty("sha256", sha256(raw))
            }
        }
        return when (value) {
            is Map<*, *> -> JsonObject().apply {
                value.forEach { (nestedKey, nestedValue) ->
                    val name = nestedKey.toString()
                    add(name, sanitize(name, nestedValue))
                }
            }
            is Iterable<*> -> JsonArray().apply { value.forEach { add(sanitize(key, it)) } }
            is Array<*> -> JsonArray().apply { value.forEach { add(sanitize(key, it)) } }
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is JsonElement -> value
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized in SENSITIVE_KEYS || SENSITIVE_FRAGMENTS.any(normalized::contains)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val SENSITIVE_KEYS = setOf(
            "goal", "text", "message", "raw_output", "planner_output", "authorization",
            "token", "recipient", "destination", "expected_destination", "content"
        )
        val SENSITIVE_FRAGMENTS = listOf("password", "passcode", "secret", "bearer", "auth_token")
    }
}

internal class AutomationTraceRecorder(
    private val sessionId: String,
    private val logger: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val sequence = AtomicInteger(0)
    private val startedAtMs = nowMs()

    fun emit(name: String, fields: Map<String, Any?> = emptyMap()) {
        val event = AutomationTraceEvent(
            sessionId = sessionId,
            sequence = sequence.getAndIncrement(),
            elapsedMs = (nowMs() - startedAtMs).coerceAtLeast(0),
            name = name,
            fields = fields
        )
        logger("UiAutomationTrace ${event.toJson()}")
    }
}
