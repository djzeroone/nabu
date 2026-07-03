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
            timeoutMs = -100 // Immediately expires
        )
        assertFalse(ConfirmationManager.hasValidGrant("s1", "screen1", "fingerprint1", "dest", "hash"))
        
        val id2 = ConfirmationManager.requestConfirmation(
            sessionId = "s1",
            screenId = "screen1",
            actionFingerprint = "fingerprint1",
            destination = "dest",
            contentHash = "hash",
            timeoutMs = 10_000
        )
        assertTrue(ConfirmationManager.hasValidGrant("s1", "screen1", "fingerprint1", "dest", "hash"))
        
        val consumed = ConfirmationManager.consumeConfirmation(id2, "s1", "screen1", "fingerprint1", "dest", "hash")
        assertTrue(consumed)
        assertFalse(ConfirmationManager.consumeConfirmation(id2, "s1", "screen1", "fingerprint1", "dest", "hash"))
    }
    
    @Test
    fun `test field mismatch prevents consumption`() {
        val id = ConfirmationManager.requestConfirmation(
            sessionId = "s1",
            screenId = "screen1",
            actionFingerprint = "fingerprint1",
            destination = "dest",
            contentHash = "hash"
        )
        assertFalse(ConfirmationManager.consumeConfirmation(id, "s2", "screen1", "fingerprint1", "dest", "hash"))
        assertFalse(ConfirmationManager.consumeConfirmation(id, "s1", "screen2", "fingerprint1", "dest", "hash"))
        assertFalse(ConfirmationManager.consumeConfirmation(id, "s1", "screen1", "fingerprint2", "dest", "hash"))
        assertFalse(ConfirmationManager.consumeConfirmation(id, "s1", "screen1", "fingerprint1", "dest2", "hash"))
        assertFalse(ConfirmationManager.consumeConfirmation(id, "s1", "screen1", "fingerprint1", "dest", "hash2"))
        
        // Ensure the grant wasn't destroyed
        assertTrue(ConfirmationManager.hasValidGrant("s1", "screen1", "fingerprint1", "dest", "hash"))
    }
}
