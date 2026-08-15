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
    var requestReceivedMs: Long = 0L,
    var sessionCreatedMs: Long = 0L,
    var observationStartedMs: Long = 0L,
    var observationReadyMs: Long = 0L,
    var modelRequestStartedMs: Long = 0L,
    var modelFirstResponseMs: Long = 0L,
    var modelResponseCompleteMs: Long = 0L,
    var actionDispatchMs: Long = 0L,
    var actionCompleteMs: Long = 0L,
    var verificationStartedMs: Long = 0L,
    var verificationCompleteMs: Long = 0L,
    var nextModelRequestMs: Long = 0L,
    var totalStepLatencyMs: Long = 0L,
    var totalSessionLatencyMs: Long = 0L
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
    val resultPackage: String? = null
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
    var status: ActionSessionStatus = ActionSessionStatus.IDLE,
    val originalGoal: String,
    var currentGoal: String = originalGoal,
    var currentStep: Int = 0,
    val turns: MutableList<ActionConversationTurn> = mutableListOf(),
    val stepHistory: MutableList<ActionStepRecord> = mutableListOf(),
    var pendingObjective: String? = null,
    var lastObservedPackage: String? = null,
    var lastObservedWindow: String? = null,
    var lastVerificationResult: String? = null,
    val metrics: ActionSessionMetrics = ActionSessionMetrics(),
    val createdAtMs: Long = System.currentTimeMillis(),
    var updatedAtMs: Long = System.currentTimeMillis()
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
