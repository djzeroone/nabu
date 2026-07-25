package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideDecisionDecoderTest {
    @Test
    fun decodesFocusDirection() {
        val decision = GuideDecisionDecoder.decode(
            """{"v":1,"kind":"direct","target":"p2","instruction":"Double-tap Continue."}"""
        )

        assertTrue(decision is GuideDecision.Direct)
        decision as GuideDecision.Direct
        assertEquals("p2", decision.target.id)
        assertEquals("Double-tap Continue.", decision.instruction)
    }

    @Test
    fun rejectsControlAction() {
        assertThrows(IllegalArgumentException::class.java) {
            GuideDecisionDecoder.decode(
                """{"v":1,"kind":"act","op":"tap","target":"p2","instruction":"Tap it."}"""
            )
        }
    }

    @Test
    fun rejectsUnknownFields() {
        assertThrows(IllegalArgumentException::class.java) {
            GuideDecisionDecoder.decode(
                """{"v":1,"kind":"direct","target":"p2","instruction":"Continue.","tap":true}"""
            )
        }
    }
}
