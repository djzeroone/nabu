package com.mewmix.nabu.uiagent

enum class Outcome {
    SUCCEEDED,
    FAILED,
    BLOCKED,
    DENIED,
    TIMED_OUT
}

data class UiActionHistoryEntry(
    val index: Int,
    val action: String,
    val targetElementId: String?,
    val targetLabel: String?,
    val sourceScreenId: String,
    val resultScreenId: String?,
    val outcome: Outcome,
    val changedScreen: Boolean,
    val detail: String?
)

data class AutomationBudget(
    val maxExecutedActions: Int = 12,
    val maxWallClockDurationMs: Long = 180_000,
    val maxPlannerRetriesPerObservation: Int = 1,
    val maxIdenticalActionUnchangedScreen: Int = 1,
    val maxUnchangedObservations: Int = 3,
    val maxSingleWaitMs: Long = 5_000,
    val maxCumulativeWaitMs: Long = 20_000
)
