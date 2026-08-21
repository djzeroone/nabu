package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Test

class ActionSessionWorkingMemoryTest {
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
