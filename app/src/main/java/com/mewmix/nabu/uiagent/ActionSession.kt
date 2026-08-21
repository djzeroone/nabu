package com.mewmix.nabu.uiagent

import java.util.UUID

enum class ActionSessionMode {
    SINGLE_TURN,
    TEMPORARY_CONVERSATION,
    HANDOFF_TO_CHAT
}

enum class ActionSessionStatus {
    IDLE,
    OBSERVING,
    PLANNING,
    EXECUTING,
    VERIFYING,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class ActionSessionMetrics(
    val requestReceivedMs: Long = 0L,
    val sessionCreatedMs: Long = 0L,
    val observationStartedMs: Long = 0L,
    val observationReadyMs: Long = 0L,
    val modelRequestStartedMs: Long = 0L,
    val modelFirstResponseMs: Long = 0L,
    val modelResponseCompleteMs: Long = 0L,
    val actionDispatchMs: Long = 0L,
    val actionCompleteMs: Long = 0L,
    val verificationStartedMs: Long = 0L,
    val verificationCompleteMs: Long = 0L,
    val nextModelRequestMs: Long = 0L,
    val totalStepLatencyMs: Long = 0L,
    val totalSessionLatencyMs: Long = 0L
) {
    fun toMap(): Map<String, Long> = mapOf(
        "request_received_ms" to requestReceivedMs,
        "session_created_ms" to sessionCreatedMs,
        "observation_started_ms" to observationStartedMs,
        "observation_ready_ms" to observationReadyMs,
        "model_request_started_ms" to modelRequestStartedMs,
        "model_first_response_ms" to modelFirstResponseMs,
        "model_response_complete_ms" to modelResponseCompleteMs,
        "action_dispatch_ms" to actionDispatchMs,
        "action_complete_ms" to actionCompleteMs,
        "verification_started_ms" to verificationStartedMs,
        "verification_complete_ms" to verificationCompleteMs,
        "next_model_request_ms" to nextModelRequestMs,
        "total_step_latency_ms" to totalStepLatencyMs,
        "total_session_latency_ms" to totalSessionLatencyMs
    )
}

data class ActionStepRecord(
    val sequence: Int,
    val observation: String,
    val reasoningSummary: String,
    val action: String,
    val target: String?,
    val result: String,
    val postActionObservation: String?,
    val verification: String?,
    val latencyMs: Long,
    val retryCount: Int = 0,
    val sourcePackage: String? = null,
    val resultPackage: String? = null,
    val actionFamily: String? = null,
    val semanticAction: String? = null,
    val executionMechanism: String? = null,
    val verificationStatus: String? = null,
    val sourceWindow: String? = null,
    val resultWindow: String? = null
)

data class ActionConversationTurn(
    val id: String = UUID.randomUUID().toString(),
    val userRequest: String,
    val assistantResponse: String? = null,
    val stepRecords: List<ActionStepRecord> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val isComplete: Boolean = false,
    val isError: Boolean = false
)

data class ActionSession(
    val id: String = UUID.randomUUID().toString(),
    val mode: ActionSessionMode = ActionSessionMode.SINGLE_TURN,
    val status: ActionSessionStatus = ActionSessionStatus.IDLE,
    val originalGoal: String,
    val currentGoal: String = originalGoal,
    val currentStep: Int = 0,
    val turns: List<ActionConversationTurn> = emptyList(),
    val stepHistory: List<ActionStepRecord> = emptyList(),
    val pendingObjective: String? = null,
    val lastObservedPackage: String? = null,
    val lastObservedWindow: String? = null,
    val lastVerificationResult: String? = null,
    val metrics: ActionSessionMetrics = ActionSessionMetrics(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toHandoffMap(): Map<String, String> = mapOf(
        "session_id" to id,
        "mode" to mode.name,
        "original_goal" to originalGoal,
        "current_goal" to currentGoal,
        "pending_objective" to (pendingObjective ?: ""),
        "last_package" to (lastObservedPackage ?: ""),
        "last_window" to (lastObservedWindow ?: ""),
        "last_verification" to (lastVerificationResult ?: ""),
        "step_count" to stepHistory.size.toString(),
        "turn_count" to turns.size.toString()
    )

    fun toHandoffBundle(): android.os.Bundle = android.os.Bundle().apply {
        putString("session_id", id)
        putString("mode", mode.name)
        putString("original_goal", originalGoal)
        putString("current_goal", currentGoal)
        putString("pending_objective", pendingObjective)
        putString("last_package", lastObservedPackage)
        putString("last_window", lastObservedWindow)
        putString("last_verification", lastVerificationResult)
        putInt("step_count", stepHistory.size)
        putInt("turn_count", turns.size)
    }
}

/** Keeps process-lifetime action memory useful without allowing a long conversation to grow forever. */
object ActionSessionWorkingMemory {
    const val MAX_RECENT_TURNS = 8
    const val MAX_RECENT_STEPS = 64

    fun appendStep(
        history: List<ActionStepRecord>,
        step: ActionStepRecord
    ): List<ActionStepRecord> = (history + step).takeLast(MAX_RECENT_STEPS)

    fun appendTurn(
        history: List<ActionConversationTurn>,
        turn: ActionConversationTurn
    ): List<ActionConversationTurn> = (history + turn).takeLast(MAX_RECENT_TURNS)
}
