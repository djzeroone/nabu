package com.mewmix.nabu.uiagent

import java.util.concurrent.ConcurrentHashMap

data class ConfirmationGrant(
    val sessionId: String,
    val screenId: String,
    val actionFingerprint: String,
    val destination: String?,
    val contentHash: String?,
    val expiresAtMs: Long
)

object ConfirmationManager {
    private val grants = ConcurrentHashMap<String, ConfirmationGrant>()

    fun requestConfirmation(
        sessionId: String,
        screenId: String,
        actionFingerprint: String,
        destination: String?,
        contentHash: String?,
        timeoutMs: Long = 30_000
    ): String {
        val confirmationId = java.util.UUID.randomUUID().toString()
        grants[confirmationId] = ConfirmationGrant(
            sessionId = sessionId,
            screenId = screenId,
            actionFingerprint = actionFingerprint,
            destination = destination,
            contentHash = contentHash,
            expiresAtMs = System.currentTimeMillis() + timeoutMs
        )
        return confirmationId
    }

    fun consumeConfirmation(
        confirmationId: String,
        sessionId: String,
        screenId: String,
        actionFingerprint: String,
        destination: String?,
        contentHash: String?
    ): Boolean {
        val grant = grants[confirmationId] ?: return false
        if (System.currentTimeMillis() > grant.expiresAtMs) {
            grants.remove(confirmationId)
            return false
        }
        
        val isValid = grant.sessionId == sessionId &&
               grant.screenId == screenId &&
               grant.actionFingerprint == actionFingerprint &&
               grant.destination == destination &&
               grant.contentHash == contentHash
               
        if (isValid) {
            grants.remove(confirmationId)
        }
        return isValid
    }
    
    fun hasValidGrant(
        sessionId: String,
        screenId: String,
        actionFingerprint: String,
        destination: String?,
        contentHash: String?
    ): Boolean {
        cleanupExpired()
        return grants.values.any { grant ->
            grant.sessionId == sessionId &&
            grant.screenId == screenId &&
            grant.actionFingerprint == actionFingerprint &&
            grant.destination == destination &&
            grant.contentHash == contentHash &&
            System.currentTimeMillis() <= grant.expiresAtMs
        }
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        grants.entries.removeIf { it.value.expiresAtMs < now }
    }
    
    internal fun resetForTesting() {
        grants.clear()
    }
}
