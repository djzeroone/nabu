package com.mewmix.nabu.tools

import com.google.gson.JsonObject

enum class CapabilityId {
    SCREEN_QUERY,
    SCREEN_DESCRIBE,
    SCREEN_DUMP,
    SCREEN_CAPTURE,
    SCREEN_WATCH,
    SCREEN_COMPARE,
    UI_ACT,
    WEB_SEARCH,
    LOCAL_MEMORY_SAVE,
    LOCAL_MEMORY_RETRIEVE,
    SCHEDULE_ACTION,
    SYSTEM_TOGGLE,
    MEDIA_CONTROL,
    DEVICE_DIAGNOSTICS,
    APP_LAUNCH,
    UI_GUIDE
}

enum class ToolOwner {
    SYSTEM,
    UI_AUTOMATION,
    USER_DELEGATE
}

enum class ToolDomain {
    DEVICE_CONTROL,
    INFORMATION_RETRIEVAL,
    WORKFLOW_SCHEDULING,
    COMMUNICATION,
    VISION_UNDERSTANDING
}

enum class EffectClass {
    READ_ONLY,
    LOCAL_STATE_CHANGE,
    EXTERNAL_COMMUNICATION
}

enum class RiskClass {
    NONE,
    LOW,
    MODERATE,
    HIGH
}

enum class LatencyClass {
    INSTANT, // < 50ms
    FAST,    // < 500ms
    SLOW     // > 500ms
}

data class JsonSchema(val schema: JsonObject)

data class ToolCapability(
    val id: CapabilityId,
    val owner: ToolOwner,
    val domain: ToolDomain,
    val effect: EffectClass,
    val risk: RiskClass,
    val schedulable: Boolean,
    val requiresUnlockedDevice: Boolean,
    val requiresForeground: Boolean,
    val requiresConfirmation: Boolean,
    val reversible: Boolean,
    val estimatedLatency: LatencyClass,
    val argumentSchema: JsonSchema,
    val resultSchema: JsonSchema,
    val aliases: Set<String>
)
