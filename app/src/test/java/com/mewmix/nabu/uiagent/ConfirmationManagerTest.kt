package com.mewmix.nabu.uiagent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConfirmationManagerTest {

    @Before
    fun setup() {
        ConfirmationManager.resetForTesting()
    }

    @Test
    fun `consumes valid grant`() {
        val grantId = ConfirmationManager.requestConfirmation(
            sessionId = "session1",
            screenId = "screen1",
            actionFingerprint = "tap|screen1",
            destination = null,
            contentHash = "hash1",
            timeoutMs = 5000
        )

        assertNotNull(grantId)
        assertTrue(ConfirmationManager.hasValidGrant("session1", "screen1", "tap|screen1", null, "hash1"))
        
        // Consume it
        val success = ConfirmationManager.consumeConfirmation(
            grantId, "session1", "screen1", "tap|screen1", null, "hash1"
        )
        assertTrue(success)

        // Cannot consume again
        assertFalse(ConfirmationManager.consumeConfirmation(grantId, "session1", "screen1", "tap|screen1", null, "hash1"))
        assertFalse(ConfirmationManager.hasValidGrant("session1", "screen1", "tap|screen1", null, "hash1"))
    }

    @Test
    fun `rejects mismatched fields`() {
        val grantId = ConfirmationManager.requestConfirmation(
            sessionId = "session1",
            screenId = "screen1",
            actionFingerprint = "tap|screen1",
            destination = "example.com",
            contentHash = "hash1",
            timeoutMs = 5000
        )

        assertFalse(ConfirmationManager.consumeConfirmation(grantId, "session2", "screen1", "tap|screen1", "example.com", "hash1"))
        assertFalse(ConfirmationManager.consumeConfirmation(grantId, "session1", "screen2", "tap|screen1", "example.com", "hash1"))
        assertFalse(ConfirmationManager.consumeConfirmation(grantId, "session1", "screen1", "long_press|screen1", "example.com", "hash1"))
        assertFalse(ConfirmationManager.consumeConfirmation(grantId, "session1", "screen1", "tap|screen1", "google.com", "hash1"))
        assertFalse(ConfirmationManager.consumeConfirmation(grantId, "session1", "screen1", "tap|screen1", "example.com", "hash2"))
    }

    @Test
    fun `expires grants after timeout`() {
        val grantId = ConfirmationManager.requestConfirmation(
            sessionId = "session1",
            screenId = "screen1",
            actionFingerprint = "tap|screen1",
            destination = null,
            contentHash = "hash1",
            timeoutMs = 0 // expires immediately
        )
        
        Thread.sleep(10)
        
        assertFalse(ConfirmationManager.hasValidGrant("session1", "screen1", "tap|screen1", null, "hash1"))
        assertFalse(ConfirmationManager.consumeConfirmation(grantId, "session1", "screen1", "tap|screen1", null, "hash1"))
    }
}
