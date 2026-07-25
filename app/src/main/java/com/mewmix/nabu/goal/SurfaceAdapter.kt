package com.mewmix.nabu.goal

import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision

/**
 * Defines how a specific capability domain (e.g. UI_ACT, WEB_SEARCH) prompts the LLM
 * and interprets its single-step decisions.
 */
interface SurfaceAdapter {
    val capabilityId: CapabilityId

    /**
     * Executes a single step of the given objective.
     * The adapter is responsible for building the domain-specific prompt, 
     * calling the LLM backend, and parsing the output into an AgentDecision.
     */
    suspend fun executeStep(objective: String): AgentDecision
}
