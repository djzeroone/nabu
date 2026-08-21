package com.mewmix.nabu.uiagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRequestOwnershipTest {
    @Test
    fun `new request immediately revokes queued and running predecessor`() {
        val ownership = ActionRequestOwnership()
        val first = ownership.begin("first")
        val second = ownership.begin("second")

        assertFalse(ownership.owns("first", first))
        assertTrue(ownership.owns("second", second))
    }

    @Test
    fun `cancelled session cannot publish terminal callback`() {
        val ownership = ActionRequestOwnership()
        val epoch = ownership.begin("session")

        ownership.invalidate("session")

        assertFalse(ownership.owns("session", epoch))
    }

    @Test
    fun `old follow up cannot overwrite newer turn in same session`() {
        val ownership = ActionRequestOwnership()
        val oldTurn = ownership.begin("session")
        val newTurn = ownership.begin("session")

        assertFalse(ownership.owns("session", oldTurn))
        assertTrue(ownership.owns("session", newTurn))
    }
}
