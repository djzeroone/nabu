package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActionPlanParserTest {

    @Test
    fun `parses typed actions correctly`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """
            {
                "steps": [
                    { "action": "open_app", "package_name": "com.example.app" }
                ]
            }
            """.trimIndent(),
            "goal",
            "screen1"
        )
        
        val step = plan.steps.first()
        assertTrue(step is UiActionStep.OpenApp)
        assertEquals("com.example.app", (step as UiActionStep.OpenApp).packageName)
    }

    @Test
    fun `parses enums correctly`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """
            {
                "steps": [
                    { "action": "open_settings_page", "page": "developer_options" }
                ]
            }
            """.trimIndent(),
            "goal",
            "screen1"
        )
        
        val step = plan.steps.first() as UiActionStep.OpenSettingsPage
        assertEquals(SettingsPage.DEVELOPER_OPTIONS, step.page)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid enums`() {
        UiActionPlanParser.parsePlannerOutput(
            """
            {
                "steps": [
                    { "action": "open_settings_page", "page": "unknown_page" }
                ]
            }
            """.trimIndent(),
            "goal",
            "screen1"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown json fields`() {
        UiActionPlanParser.parsePlannerOutput(
            """
            {
                "steps": [
                    { "action": "open_app", "package_name": "com.example", "unknown_field": 123 }
                ]
            }
            """.trimIndent(),
            "goal",
            "screen1"
        )
    }
}
