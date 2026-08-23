package com.mewmix.nabu.uiagent

enum class ActionModelLifecyclePhase {
    UNLOADED,
    SELECTED,
    WARMING,
    READY,
    FAILED
}

data class ActionModelLifecycleState(
    val phase: ActionModelLifecyclePhase = ActionModelLifecyclePhase.UNLOADED,
    val modelId: String? = null,
    val reason: String? = null,
    val initializationMs: Long? = null
)
