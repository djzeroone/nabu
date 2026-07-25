package com.mewmix.nabu.uiagent

import com.mewmix.nabu.chat.LlmStructuredToolCall
import com.mewmix.nabu.tools.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstrainedDecisionDecoderTest {

    @Test
    fun decodeActTap() {
        val json = """{"v":3,"kind":"act","op":"tap","target":"p4","expect":"surface_change"}"""
        val decision = ConstrainedDecisionDecoder.decode(json)
        assertTrue(decision is AgentDecision.Act)
        val act = decision as AgentDecision.Act
        assertEquals(Operation.TAP, act.operation)
        assertEquals("p4", act.target?.id)
        assertEquals(ExpectedEffect.SURFACE_CHANGE, act.expectedEffect)
    }

    @Test
    fun decodeQuery() {
        val json = """{"v":3,"kind":"query","query":"find settings","expected_type":"boolean"}"""
        val decision = ConstrainedDecisionDecoder.decode(json)
        assertTrue(decision is AgentDecision.Query)
        val query = decision as AgentDecision.Query
        assertEquals("find settings", query.query.query)
        assertEquals("boolean", query.query.expectedType)
    }

    @Test
    fun decodeDelegate() {
        val json = """{"v":3,"kind":"delegate","capability":"SYSTEM_TOGGLE","objective":"turn off wifi"}"""
        val decision = ConstrainedDecisionDecoder.decode(json)
        assertTrue(decision is AgentDecision.Delegate)
        val delegate = decision as AgentDecision.Delegate
        assertEquals(CapabilityId.SYSTEM_TOGGLE, delegate.capability)
        assertEquals("turn off wifi", delegate.objective)
    }

    @Test
    fun rejectsTargetActionWithoutTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            ConstrainedDecisionDecoder.decode(
                """{"v":3,"kind":"act","op":"tap","expect":"surface_change"}"""
            )
        }
    }

    @Test
    fun rejectsUnknownFields() {
        assertThrows(IllegalArgumentException::class.java) {
            ConstrainedDecisionDecoder.decode(
                """{"v":3,"kind":"finish","outcome":"Done","summary":"alias"}"""
            )
        }
    }

    @Test
    fun decodeNativeStructuredAct() {
        val decision = ConstrainedDecisionDecoder.decode(
            LlmStructuredToolCall(
                name = "ui_act",
                arguments = mapOf(
                    "op" to "type_text",
                    "target" to "p2",
                    "text" to "High Council",
                    "expect" to "mutation"
                )
            )
        ) as AgentDecision.Act

        assertEquals(Operation.TYPE_TEXT, decision.operation)
        assertEquals("p2", decision.target?.id)
        assertEquals("High Council", decision.arguments["text"])
        assertEquals(ExpectedEffect.MUTATION, decision.expectedEffect)
    }
}
