package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyPlannerAdapterTest {

    @Test
    fun parseLegacyJson() {
        val legacyJson = """
            {
                "steps": [
                    {
                        "action": "tap",
                        "target": { "element_id": "p4" }
                    }
                ]
            }
        """.trimIndent()

        val plan = LegacyPlannerAdapter.parseAndAdapt(legacyJson, "Test goal", "screen1") { _ -> }
        
        assertEquals(1, plan.steps.size)
        val step = plan.steps.first()
        assert(step is UiActionStep.Tap)
        val tap = step as UiActionStep.Tap
        assertEquals("p4", tap.target.elementId)
    }
}
