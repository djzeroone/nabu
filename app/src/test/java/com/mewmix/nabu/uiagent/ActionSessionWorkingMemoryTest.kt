package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionSessionWorkingMemoryTest {
    @Test
    fun `numbered progress phases publish canonical lifecycle states`() {
        assertEquals(
            ActionSessionStatus.EXECUTING,
            actionStatusForProgress("Execute 2", ActionSessionStatus.PLANNING)
        )
        assertEquals(
            ActionSessionStatus.VERIFYING,
            actionStatusForProgress("Verify 2", ActionSessionStatus.EXECUTING)
        )
        assertEquals(
            ActionSessionStatus.WAITING_FOR_USER,
            actionStatusForProgress("Needs input", ActionSessionStatus.PLANNING)
        )
    }

    @Test
    fun `runtime events populate real timing boundaries`() {
        val metrics = ActionSessionMetrics(requestReceivedMs = 100L)
            .recordRuntimeEvent(
                "observation_captured",
                occurredAtMs = 200L,
                fields = mapOf("capture_ms" to 10L, "index_ms" to 5L)
            )
            .recordRuntimeEvent("planner_request_started", 220L)
            .recordRuntimeEvent("planner_first_response", 250L)
            .recordRuntimeEvent("planner_output_received", 300L)
            .recordRuntimeEvent("action_dispatch_started", 310L)
            .recordRuntimeEvent("action_dispatch_completed", 330L)
            .recordRuntimeEvent("verification_started", 340L)
            .recordRuntimeEvent("verification_completed", 370L)
            .recordRuntimeEvent("planner_request_started", 400L)

        assertEquals(185L, metrics.observationStartedMs)
        assertEquals(200L, metrics.observationReadyMs)
        assertEquals(220L, metrics.modelRequestStartedMs)
        assertEquals(250L, metrics.modelFirstResponseMs)
        assertEquals(300L, metrics.modelResponseCompleteMs)
        assertEquals(310L, metrics.actionDispatchMs)
        assertEquals(330L, metrics.actionCompleteMs)
        assertEquals(340L, metrics.verificationStartedMs)
        assertEquals(370L, metrics.verificationCompleteMs)
        assertEquals(400L, metrics.nextModelRequestMs)
        assertEquals(210L, metrics.toMap().getValue("invocation_to_first_action_ms"))
        assertEquals(30L, metrics.toMap().getValue("model_first_response_latency_ms"))
        assertEquals(80L, metrics.toMap().getValue("model_latency_ms"))
        assertEquals(20L, metrics.toMap().getValue("action_latency_ms"))
        assertEquals(30L, metrics.toMap().getValue("verification_latency_ms"))
    }

    @Test
    fun `step history retains only the newest bounded records`() {
        val records = (1..ActionSessionWorkingMemory.MAX_RECENT_STEPS + 5).fold(emptyList<ActionStepRecord>()) {
                history, sequence ->
            ActionSessionWorkingMemory.appendStep(history, step(sequence))
        }

        assertEquals(ActionSessionWorkingMemory.MAX_RECENT_STEPS, records.size)
        assertEquals(6, records.first().sequence)
        assertEquals(ActionSessionWorkingMemory.MAX_RECENT_STEPS + 5, records.last().sequence)
    }

    @Test
    fun `turn history retains recent context without cumulative step copies`() {
        val turns = (1..ActionSessionWorkingMemory.MAX_RECENT_TURNS + 3).fold(emptyList<ActionConversationTurn>()) {
                history, sequence ->
            ActionSessionWorkingMemory.appendTurn(
                history,
                ActionConversationTurn(
                    userRequest = "turn $sequence",
                    stepRecords = listOf(step(sequence))
                )
            )
        }

        assertEquals(ActionSessionWorkingMemory.MAX_RECENT_TURNS, turns.size)
        assertEquals("turn 4", turns.first().userRequest)
        assertEquals(listOf(11), turns.last().stepRecords.map(ActionStepRecord::sequence))
    }

    private fun step(sequence: Int) = ActionStepRecord(
        sequence = sequence,
        observation = "screen",
        reasoningSummary = "reason",
        action = "tap",
        target = "p1",
        result = "ok",
        postActionObservation = "screen2",
        verification = "changed",
        latencyMs = 1
    )
}
