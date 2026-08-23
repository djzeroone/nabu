package com.mewmix.nabu.uiagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSessionTest {

    @Test
    fun routing_state_and_metrics_precede_planning() {
        assertEquals(
            ActionSessionStatus.ROUTING,
            actionStatusForProgress("Routing request", ActionSessionStatus.IDLE)
        )
        val routed = ActionSessionMetrics(requestReceivedMs = 900L)
            .recordRuntimeEvent("routing_started", 1_000L)
            .recordRuntimeEvent(
                "routing_completed",
                1_010L,
                mapOf(
                    "model_required" to false,
                    "model_initialization_on_critical_path" to false,
                    "app_resolution_ms" to 3L
                )
            )
        assertEquals(1_000L, routed.routingStartedMs)
        assertEquals(1_010L, routed.routingCompletedMs)
        assertEquals(0L, routed.plannerRequestCount)
        assertEquals(0L, routed.modelRequired)
        assertEquals(0L, routed.modelInitializationOnCriticalPath)
        assertEquals(3L, routed.appResolutionMs)

        val planned = routed
            .recordRuntimeEvent("planner_request_started", 1_020L)
            .recordRuntimeEvent("planner_request_started", 1_030L)
        assertEquals(2L, planned.plannerRequestCount)
    }

    @Test
    fun actionSession_initializesWithCorrectDefaults() {
        val session = ActionSession(
            originalGoal = "Open Telegram and send hello",
            mode = ActionSessionMode.SINGLE_TURN
        )

        assertEquals("Open Telegram and send hello", session.originalGoal)
        assertEquals("Open Telegram and send hello", session.currentGoal)
        assertEquals(ActionSessionMode.SINGLE_TURN, session.mode)
        assertEquals(ActionSessionStatus.IDLE, session.status)
        assertEquals(0, session.turns.size)
        assertEquals(0, session.stepHistory.size)
    }

    @Test
    fun actionSession_tracksConversationTurnsAndMetricsImmutably() {
        val initialSession = ActionSession(
            originalGoal = "Open Settings",
            mode = ActionSessionMode.TEMPORARY_CONVERSATION
        )

        val metrics = ActionSessionMetrics(
            requestReceivedMs = 1000L,
            observationReadyMs = 1050L,
            modelResponseCompleteMs = 1200L,
            actionCompleteMs = 1250L,
            verificationCompleteMs = 1300L,
            totalStepLatencyMs = 300L,
            totalSessionLatencyMs = 300L
        )

        val turn = ActionConversationTurn(
            userRequest = "Open Settings",
            assistantResponse = "Settings opened.",
            isComplete = true
        )

        val step = ActionStepRecord(
            sequence = 1,
            observation = "screen_1",
            reasoningSummary = "Opening settings app",
            action = "open_app com.android.settings",
            target = "Settings",
            result = "success",
            postActionObservation = "screen_2",
            verification = "verified",
            latencyMs = 250L,
            sourcePackage = "com.mewmix.nabu",
            resultPackage = "com.android.settings"
        )

        val session = initialSession.copy(
            metrics = metrics,
            turns = listOf(turn),
            stepHistory = listOf(step),
            status = ActionSessionStatus.COMPLETED
        )

        val metricsMap = session.metrics.toMap()
        assertEquals(1000L, metricsMap["request_received_ms"])
        assertEquals(1050L, metricsMap["observation_ready_ms"])
        assertEquals(1200L, metricsMap["model_response_complete_ms"])
        assertEquals(1250L, metricsMap["action_complete_ms"] ?: 0L)
        assertEquals(1300L, metricsMap["verification_complete_ms"])
        assertEquals(300L, metricsMap["total_session_latency_ms"])

        assertEquals(1, session.turns.size)
        assertEquals(1, session.stepHistory.size)
        assertEquals("Settings opened.", session.turns.first().assistantResponse)
        assertEquals("open_app com.android.settings", session.stepHistory.first().action)
        assertEquals(ActionSessionStatus.COMPLETED, session.status)
    }

    @Test
    fun actionSession_generatesHandoffMapAndBundle() {
        val session = ActionSession(
            originalGoal = "Search for Agent Junkies in Telegram",
            mode = ActionSessionMode.TEMPORARY_CONVERSATION,
            pendingObjective = "Open chat",
            lastObservedPackage = "org.telegram.messenger.web",
            lastVerificationResult = "Search completed"
        )

        val map = session.toHandoffMap()
        assertEquals(session.id, map["session_id"])
        assertEquals("TEMPORARY_CONVERSATION", map["mode"])
        assertEquals("Search for Agent Junkies in Telegram", map["original_goal"])
        assertEquals("Open chat", map["pending_objective"])
        assertEquals("org.telegram.messenger.web", map["last_package"])
        assertEquals("Search completed", map["last_verification"])
    }
}
