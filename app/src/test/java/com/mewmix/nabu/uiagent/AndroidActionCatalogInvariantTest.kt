package com.mewmix.nabu.uiagent

import com.mewmix.nabu.accessibility.AndroidActionCatalog
import com.mewmix.nabu.accessibility.StandardNodeAction
import com.mewmix.nabu.accessibility.GlobalSystemAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidActionCatalogInvariantTest {
    @Test
    fun `canonical standard action tokens are unique and executable`() {
        assertEquals(
            StandardNodeAction.entries.size,
            StandardNodeAction.entries.map { it.token }.distinct().size
        )
        StandardNodeAction.entries
            .filter { it.minimumApi <= 35 }
            .forEach { action ->
                assertNotNull("No Android action ID mapping for ${action.token}", AndroidActionCatalog.actionIdForToken(action.token))
            }
    }

    @Test
    fun `generic node action parses through planner decision schema`() {
        val decision = ConstrainedDecisionDecoder.decode(
            """{"v":3,"kind":"act","op":"node_action","target":"p0","args":{"action":"set_progress","value":"42"}}"""
        )

        assertTrue(decision is AgentDecision.Act)
        val act = decision as AgentDecision.Act
        assertEquals(Operation.NODE_ACTION, act.operation)
        assertEquals("set_progress", act.arguments["action"])
    }

    @Test
    fun `raw arbitrary accessibility action id is rejected`() {
        val result = runCatching {
            ConstrainedDecisionDecoder.decode(
                """{"v":3,"kind":"act","op":"node_action","target":"p0","args":{"action":"click","action_id":"16908342"}}"""
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `invented node action token is rejected`() {
        val result = runCatching {
            ConstrainedDecisionDecoder.decode(
                """{"v":3,"kind":"act","op":"node_action","target":"p0","args":{"action":"action_1234"}}"""
            )
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `global system action ids and tokens are unique`() {
        assertEquals(GlobalSystemAction.entries.size, GlobalSystemAction.entries.map { it.actionId }.distinct().size)
        assertEquals(GlobalSystemAction.entries.size, GlobalSystemAction.entries.map { it.token }.distinct().size)
        GlobalSystemAction.entries.filter { it.plannerAllowed }.forEach { action ->
            assertEquals(action, GlobalSystemAction.fromToken(action.token))
            assertEquals(action, GlobalSystemAction.fromId(action.actionId))
        }
    }
}
