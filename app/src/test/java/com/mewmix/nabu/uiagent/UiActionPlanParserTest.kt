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
    fun `normalizes common open app package aliases`() {
        val targetPackagePlan = UiActionPlanParser.parsePlannerOutput(
            """{"action":"open_app","target_package":"com.google.android.youtube"}""",
            "Open YouTube",
            "screen1"
        )
        val nestedPackagePlan = UiActionPlanParser.parsePlannerOutput(
            """{"action":"open_app","target":{"package_name":"com.oneplus.calculator"}}""",
            "Open Calculator",
            "screen1"
        )

        assertEquals(
            "com.google.android.youtube",
            (targetPackagePlan.steps.single() as UiActionStep.OpenApp).packageName
        )
        assertEquals(
            "com.oneplus.calculator",
            (nestedPackagePlan.steps.single() as UiActionStep.OpenApp).packageName
        )
    }

    @Test
    fun `repairs malformed local planner package pair`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """{"action":"open_app","package","com.oneplus.calculator"}""",
            "Open Calculator",
            "screen1"
        )

        assertEquals(
            "com.oneplus.calculator",
            (plan.steps.single() as UiActionStep.OpenApp).packageName
        )
    }

    @Test
    fun `ignores harmless ui target fields on open app`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """{"action":"open_app","element_id":"p0","package_name":"com.google.android.youtube"}""",
            "Open YouTube",
            "screen1"
        )

        assertEquals(
            "com.google.android.youtube",
            (plan.steps.single() as UiActionStep.OpenApp).packageName
        )
    }

    @Test
    fun `ignores harmless planner metadata`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """
            {
              "action":"tap",
              "target":{"element_id":"p0"},
              "action_description":"Tap the selected item",
              "detail":{"screen_id":"screen1"}
            }
            """.trimIndent(),
            "Tap the item",
            "screen1"
        )

        assertTrue(plan.steps.single() is UiActionStep.Tap)
    }

    @Test
    fun `accepts plain done shorthand`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            "done",
            "Open YouTube then Calculator",
            "screen1"
        )

        assertTrue(plan.steps.single() is UiActionStep.Done)
    }

    @Test
    fun `preserves a top level receding horizon array`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """
            [
              {"action":"open_app","package_name":"com.google.android.youtube"},
              {"action":"wait","ms":30},
              {"action":"open_app","package":"com.oneplus.calculator"}
            ]
            """.trimIndent(),
            "Open YouTube then Calculator",
            "screen1"
        )

        assertEquals(3, plan.executableSteps.size)
        assertEquals("com.google.android.youtube", (plan.executableSteps[0] as UiActionStep.OpenApp).packageName)
        assertEquals(UiActionStep.Wait(30), plan.executableSteps[1])
        assertEquals("com.oneplus.calculator", (plan.executableSteps[2] as UiActionStep.OpenApp).packageName)
    }

    @Test
    fun `accepts multiple executable actions but slices only the first for execution`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """
            {
              "steps": [
                {"action":"tap","target":{"element_id":"p0"}},
                {"action":"assert","condition":{"text_contains":"Opened"}},
                {"action":"open_app","package_name":"com.oneplus.calculator"}
              ]
            }
            """.trimIndent(),
            "Open Calculator",
            "screen1"
        )

        assertEquals(2, plan.executableSteps.size)
        assertEquals(3, plan.steps.size)
        val executionSlice = plan.firstExecutionSlice()
        assertEquals(1, executionSlice.executableSteps.size)
        assertEquals(2, executionSlice.steps.size)
        assertTrue(executionSlice.steps[0] is UiActionStep.Tap)
        assertTrue(executionSlice.steps[1] is UiActionStep.Assert)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unbounded planner horizon`() {
        val actions = (0..UiActionPlanParser.MAX_HORIZON_ACTIONS)
            .joinToString(",") { "{\"action\":\"press_back\"}" }
        UiActionPlanParser.parsePlannerOutput(
            """{"steps":[$actions]}""",
            "Go back",
            "screen1"
        )
    }

    @Test
    fun `keeps a valid execution prefix when speculative future intent is malformed`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """
            {
              "steps": [
                {"action":"press_back"},
                {"action":"tap","unknown_future_field":"discard me"},
                {"action":"press_home"}
              ]
            }
            """.trimIndent(),
            "Navigate safely",
            "screen1"
        )

        assertEquals(listOf(UiActionStep.PressBack), plan.steps)
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

    @Test
    fun `parses captured media share with explicit destination`() {
        val plan = UiActionPlanParser.parsePlannerOutput(
            """{"action":"share_captured_media","target_package":"com.google.android.apps.messaging","expected_destination":"+19497714923"}""",
            "Send the captured image to +19497714923",
            "screen1"
        )

        assertEquals(
            UiActionStep.ShareCapturedMedia(
                targetPackage = "com.google.android.apps.messaging",
                expectedDestination = "+19497714923"
            ),
            plan.steps.single()
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `captured media share rejects missing destination`() {
        UiActionPlanParser.parsePlannerOutput(
            """{"action":"share_captured_media","target_package":"com.google.android.apps.messaging"}""",
            "Share captured media",
            "screen1"
        )
    }
}
