package com.mewmix.nabu.uiagent

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTraceTest {
    @Test
    fun traceRedactsGoalsMessagesDestinationsAndPlannerOutput() {
        val secretGoal = "Send hello to Saved Messages"
        val secretOutput = "{\"action\":\"type_text\",\"text\":\"hello\"}"
        val json = AutomationTraceEvent(
            sessionId = "session-1",
            sequence = 3,
            elapsedMs = 42,
            name = "planner_output_received",
            fields = mapOf(
                "goal" to secretGoal,
                "destination" to "Saved Messages",
                "planner_output" to secretOutput,
                "action_count" to 3
            )
        ).toJson()

        assertFalse(json.contains(secretGoal))
        assertFalse(json.contains("Saved Messages"))
        assertFalse(json.contains(secretOutput))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(1, root.get("schema_version").asInt)
        assertEquals("planner_output_received", root.get("event").asString)
        assertEquals(3, root.getAsJsonObject("fields").get("action_count").asInt)
        assertTrue(root.getAsJsonObject("fields").getAsJsonObject("goal").get("redacted").asBoolean)
        assertEquals(64, root.getAsJsonObject("fields").getAsJsonObject("planner_output").get("sha256").asString.length)
    }

    @Test
    fun recorderEmitsOrderedStructuredEvents() {
        val lines = mutableListOf<String>()
        var now = 100L
        val recorder = AutomationTraceRecorder("session-1", lines::add) { now }

        recorder.emit("session_started")
        now = 125L
        recorder.emit("action_selected", mapOf("action" to "tap"))

        assertEquals(2, lines.size)
        val first = JsonParser.parseString(lines[0].removePrefix("UiAutomationTrace ")).asJsonObject
        val second = JsonParser.parseString(lines[1].removePrefix("UiAutomationTrace ")).asJsonObject
        assertEquals(0, first.get("sequence").asInt)
        assertEquals(1, second.get("sequence").asInt)
        assertEquals(25L, second.get("elapsed_ms").asLong)
    }
}
