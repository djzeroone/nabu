package com.mewmix.nabu.uiagent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConfirmationManagerTest {
    @Before
    fun setup() {
        ConfirmationManager.resetForTesting()
    }

    @Test
    fun `test confirmation lifecycle and expiration`() {
        val id = ConfirmationManager.requestConfirmation(
            sessionId = "s1",
            screenId = "screen1",
            actionFingerprint = "fingerprint1",
            destination = "dest",
            contentHash = "hash",
            timeoutMs = 10_000
        )
        
        assertTrue(ConfirmationManager.hasValidGrant("s1", "screen1", "fingerprint1", "dest", "hash"))
        assertFalse(ConfirmationManager.hasValidGrant("s2", "screen1", "fingerprint1", "dest", "hash"))

        val consumed = ConfirmationManager.consumeConfirmation(id, "s1", "screen1", "fingerprint1", "dest", "hash")
        assertTrue(consumed)
        
        // Cannot consume twice
        assertFalse(ConfirmationManager.consumeConfirmation(id, "s1", "screen1", "fingerprint1", "dest", "hash"))
        assertFalse(ConfirmationManager.hasValidGrant("s1", "screen1", "fingerprint1", "dest", "hash"))
    }
}
