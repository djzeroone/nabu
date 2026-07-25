package com.mewmix.nabu.uiagent

import com.mewmix.nabu.utils.DebugLogger
import com.mewmix.nabu.tools.CapabilityId

object LegacyPlannerAdapter {

    fun parseAndAdapt(rawJson: String, goal: String, screenId: String, logger: (String) -> Unit = {}): UiActionPlan {
        logger("LegacyPlannerAdapter: Falling back to legacy parser for goal: $goal on screen: $screenId")
        
        // Use the existing parser
        val plan = UiActionPlanParser.parsePlannerOutput(rawJson, goal, screenId)
        
        // Log the repairs (this relies on the side effects or structure of the parser output, 
        // we'll infer based on the output if it was malformed. A proper implementation would 
        // modify UiActionPlanParser to return a repair count, but for now we'll just log we used it).
        logger("LegacyPlannerAdapter: Successfully parsed V2 plan with ${plan.steps.size} steps.")
        
        return plan
    }
}
