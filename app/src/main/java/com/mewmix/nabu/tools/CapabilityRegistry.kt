package com.mewmix.nabu.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.gson.JsonObject

object CapabilityRegistry {
    private val _capabilities = MutableStateFlow<Map<CapabilityId, ToolCapability>>(emptyMap())
    val capabilities = _capabilities.asStateFlow()

    fun register(capability: ToolCapability) {
        val current = _capabilities.value.toMutableMap()
        current[capability.id] = capability
        _capabilities.value = current
    }

    fun unregister(id: CapabilityId) {
        val current = _capabilities.value.toMutableMap()
        current.remove(id)
        _capabilities.value = current
    }

    fun getCapability(id: CapabilityId): ToolCapability? {
        return _capabilities.value[id]
    }

    fun getAllCapabilities(): List<ToolCapability> {
        return _capabilities.value.values.toList()
    }

    /**
     * Translates a ToolCapability into the generic Tool format used by models.
     */
    fun toTool(capability: ToolCapability): Tool {
        val params = mutableMapOf<String, String>()
        // A proper implementation would parse JsonSchema to extract parameters and descriptions
        // For now, this is a placeholder that assumes the schema provides a mapping.
        return Tool(
            name = capability.id.name.lowercase(),
            description = "Domain: ${capability.domain}, Effect: ${capability.effect}",
            parameters = params,
            isAvailable = true
        )
    }
}
