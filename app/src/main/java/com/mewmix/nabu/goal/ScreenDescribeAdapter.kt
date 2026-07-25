package com.mewmix.nabu.goal

import android.content.Context
import com.mewmix.nabu.accessibility.NabuAccessibilityService
import com.mewmix.nabu.accessibility.ScreenSemanticDescriber
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision

class ScreenDescribeAdapter(
    private val context: Context,
    private val backend: LlmBackend
) : SurfaceAdapter {

    override val capabilityId: CapabilityId = CapabilityId.SCREEN_DESCRIBE

    override suspend fun executeStep(objective: String): AgentDecision {
        val service = NabuAccessibilityService.instance
            ?: return AgentDecision.Finish("Error: NabuAccessibilityService is not connected.")

        val snapshot = service.forceCaptureSnapshot()
            ?: return AgentDecision.Finish("Error: Could not capture UI snapshot.")
        return AgentDecision.Finish(ScreenSemanticDescriber.describe(snapshot))
    }
}
