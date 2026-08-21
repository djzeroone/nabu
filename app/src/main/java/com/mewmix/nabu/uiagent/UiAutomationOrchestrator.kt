package com.mewmix.nabu.uiagent

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.chat.LiteRtLmBackend
import com.mewmix.nabu.chat.LlmToolDefinition
import com.mewmix.nabu.accessibility.AccessibilityToolHandler
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import com.mewmix.nabu.actions.DeviceAction
import java.util.UUID

class UiAutomationOrchestrator(
    private val context: Context,
    private val backend: LlmBackend,
    private val requestConfirmation: suspend (String) -> Boolean,
    private val budget: AutomationBudget = AutomationBudget(),
    private val isScheduled: Boolean = false,
    private val priorTurns: List<ActionConversationTurn> = emptyList(),
    private val onProgress: (phase: String, detail: String) -> Unit = { _, _ -> },
    private val onModelOutput: (String) -> Unit = {},
    private val onStepRecord: ((ActionStepRecord) -> Unit)? = null,
    private val onUserQuestion: suspend (String) -> Unit = {},
    private val onUserQuestionCleared: suspend () -> Unit = {},
    private val logger: (String) -> Unit = {}
) {
    private data class Observation(
        val bridgeObservationId: String,
        val snapshotSequence: Long,
        val screen: UiScreenState,
        val screenshotPath: String?,
        val artifactPaths: List<String>
    )

    private data class PendingCapture(val uri: Uri, val mimeType: String)
    private data class PendingExternalEffect(
        val targetPackage: String,
        val expectedDestination: String,
        val contentHash: String
    )

    private val pendingArtifactPaths = linkedSetOf<String>()
    private val pendingMediaUris = linkedSetOf<Uri>()
    private var pendingCapture: PendingCapture? = null
    private var pendingExternalEffect: PendingExternalEffect? = null
    private var activeTrace: AutomationTraceRecorder? = null
    private var activeSessionId: String? = null

    suspend fun run(goal: String): ToolResult {
        val sessionId = UUID.randomUUID().toString()
        val trace = AutomationTraceRecorder(sessionId, logger)
        trace.emit("session_requested", mapOf("goal" to goal, "scheduled" to isScheduled))
        if (!sessionMutex.tryLock()) {
            onProgress("Queue", "Waiting for the active device-control session to finish")
            trace.emit("session_queued", mapOf("reason_code" to "device_control_owned"))
            sessionMutex.lock()
            trace.emit("session_dequeued")
        }
        activeTrace = trace
        activeSessionId = sessionId
        var terminalResult: ToolResult? = null
        return try {
            if (goal.isBlank()) {
                trace.emit("session_preflight_failed", mapOf("reason_code" to "blank_goal"))
                return failure("UI automation goal is blank.").also { terminalResult = it }
            }
            if (isDeviceLocked()) {
                trace.emit("session_preflight_failed", mapOf("reason_code" to "device_locked"))
                return failure("UI automation requires the device to be awake and unlocked.").also {
                    terminalResult = it
                }
            }
            AccessibilityToolHandler.controlPlaneFailure(context)?.let { detail ->
                logger("UiAutomation preflight failed before takeover: $detail")
                trace.emit(
                    "session_preflight_failed",
                    mapOf("reason_code" to "accessibility_control_plane_unavailable")
                )
                return failure(detail).also { terminalResult = it }
            }

            AutomationSessionManager.begin(context, sessionId, goal)
            trace.emit("session_started")
            if (!isScheduled && ControlSurfaceCoordinator.isChatForegrounded) {
                progress("Take over", "Parking Chat after acquiring the device-control channel")
                val acknowledged = ControlSurfaceCoordinator.parkAndAwait(sessionId, timeoutMs = 800L)
                trace.emit(
                    "control_surface_parked",
                    mapOf("acknowledged" to acknowledged)
                )
            }
            runWithCleanup(goal, sessionId).also { result ->
                terminalResult = result
                trace.emit(
                    "session_finished",
                    mapOf(
                        "status" to if (result.isError) "failed" else "succeeded",
                        "result_length" to result.output.length,
                        "result_hash" to hashContent(result.output)
                    )
                )
            }
        } finally {
            withContext(NonCancellable) {
                cleanupArtifacts(pendingArtifactPaths.toList())
                AutomationMediaManager.cleanupAll(context, pendingMediaUris.toList())
                pendingMediaUris.clear()
                trace.emit(
                    "session_cleaned_up",
                    mapOf(
                        "remaining_artifacts" to pendingArtifactPaths.size,
                        "remaining_media" to pendingMediaUris.size
                    )
                )
                activeTrace = null
                activeSessionId = null
                ControlSurfaceCoordinator.clear(sessionId)
                AutomationSessionManager.finish(
                    sessionId = sessionId,
                    succeeded = terminalResult?.isError == false,
                    detail = terminalResult?.output ?: "Device-control runner was cancelled."
                )
                sessionMutex.unlock()
            }
        }
    }

    private suspend fun runWithCleanup(initialGoal: String, sessionId: String): ToolResult {
        var goal = initialGoal
        if (goal.isBlank()) return failure("UI automation goal is blank.")
        when (val directive = AutomationSessionManager.awaitDirective(sessionId)) {
            AutomationSessionDirective.Cancel ->
                return failure("Device control was stopped by the user.")
            is AutomationSessionDirective.Run -> goal = directive.goal
        }
        if (isDeviceLocked()) {
            trace("session_blocked", mapOf("reason_code" to "device_locked"))
            return failure("UI automation requires the device to be awake and unlocked.")
        }
        progress("Observe", "Capturing the active window and accessibility tree")
        var observation = observeInitial()
            ?: return failure(
                "Nabu lost its accessibility control plane while Android was switching windows. " +
                    "Return to Nabu and invoke device control again."
            )

        deterministicNavigationAction(goal)?.let { action ->
            progress("Execute", "Running ${actionLabel(action)} without model inference")
            val actionPlan = UiActionPlan(goal, observation.screen.screenId, listOf(action))
            when (val decision = UiActionValidator.validate(actionPlan, observation.screen)) {
                UiPlanDecision.Allow -> Unit
                is UiPlanDecision.Invalid -> return failure(decision.reason)
                is UiPlanDecision.Block -> return failure(decision.reason)
                is UiPlanDecision.RequireConfirmation -> {
                    return failure("Unexpected confirmation requirement for ${actionLabel(action)}.")
                }
            }
            val result = execute(action, observation, sessionId, goal)
            if (result.isError) return result
            val next = observeAfterAction(observation, action)
                ?: return failure("${actionLabel(action)} ran, but the resulting screen could not be observed.")
            trace(
                "deterministic_action_verified",
                mapOf(
                    "action" to actionLabel(action),
                    "source_screen_id" to observation.screen.screenId,
                    "result_screen_id" to next.screen.screenId,
                    "source_package" to observation.screen.packageName,
                    "result_package" to next.screen.packageName
                )
            )
            return success("${actionLabel(action).replaceFirstChar(Char::uppercase)} completed.")
        }
        
        var activeDeadlineMs = monotonicNowMs() + budget.maxWallClockDurationMs
        var unchangedCount = 0
        var cumulativeWaitMs = 0L
        val actionHistory = mutableListOf<UiActionHistoryEntry>()
        val successfulFingerprints = mutableSetOf<String>()
        val actionRepetitions = mutableMapOf<String, Int>()
        var goalAppCandidates = DeviceAction.findGoalAppCandidates(context, goal)

        DeviceAction.findExplicitGoalAppCandidate(context, goal)?.let { app ->
            val launchAction = UiActionStep.OpenApp(app.packageName)
            if (!observation.screen.packageName.equals(app.packageName, ignoreCase = true)) {
                progress("Navigate", "Opening ${app.label} through the installed-app registry")
                val launchPlan = UiActionPlan(goal, observation.screen.screenId, listOf(launchAction))
                when (val decision = UiActionValidator.validate(launchPlan, observation.screen)) {
                    UiPlanDecision.Allow -> Unit
                    is UiPlanDecision.Invalid -> return failure(decision.reason)
                    is UiPlanDecision.Block -> return failure(decision.reason)
                    is UiPlanDecision.RequireConfirmation ->
                        return failure("Unexpected confirmation requirement while opening ${app.label}.")
                }
                val result = execute(launchAction, observation, sessionId, goal)
                if (result.isError) return result
                val next = observeAfterAction(observation, launchAction)
                    ?: return failure("${app.label} opened, but its screen could not be observed.")
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = -1,
                        action = actionLabel(launchAction),
                        targetElementId = null,
                        targetLabel = app.label,
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = next.screen.screenId,
                        outcome = Outcome.SUCCEEDED,
                        changedScreen = next.screen.screenId != observation.screen.screenId,
                        detail = "Resolved from an exact installed-app label in the goal.",
                        sourcePackage = observation.screen.packageName,
                        resultPackage = next.screen.packageName
                    )
                )
                trace(
                    "installed_app_navigation",
                    mapOf(
                        "label" to app.label,
                        "package" to app.packageName,
                        "result_package" to next.screen.packageName
                    )
                )
                observation = next
            } else {
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = -1,
                        action = actionLabel(launchAction),
                        targetElementId = null,
                        targetLabel = app.label,
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = observation.screen.screenId,
                        outcome = Outcome.SUCCEEDED,
                        changedScreen = false,
                        detail = "${app.label} was already foreground.",
                        sourcePackage = observation.screen.packageName,
                        resultPackage = observation.screen.packageName
                    )
                )
            }
        }

        val executionResult = run execution@ {
            repeat(budget.maxExecutedActions) { actionIndex ->
            val stepStartMs = monotonicNowMs()
            val directiveWaitStartedMs = monotonicNowMs()
            when (val directive = AutomationSessionManager.awaitDirective(sessionId)) {
                AutomationSessionDirective.Cancel ->
                    return@execution failure("Device control was stopped by the user.")
                is AutomationSessionDirective.Run -> {
                    if (directive.goal != goal) {
                        trace(
                            "session_steered",
                            mapOf(
                                "old_goal_hash" to hashContent(goal),
                                "new_goal_hash" to hashContent(directive.goal)
                            )
                        )
                        goal = directive.goal
                        goalAppCandidates = DeviceAction.findGoalAppCandidates(context, goal)
                        actionHistory.add(
                            UiActionHistoryEntry(
                                index = actionIndex,
                                action = "steer",
                                targetElementId = null,
                                targetLabel = null,
                                sourceScreenId = observation.screen.screenId,
                                resultScreenId = observation.screen.screenId,
                                outcome = Outcome.SUCCEEDED,
                                changedScreen = false,
                                detail = "Goal updated; all speculative downstream actions were discarded.",
                                sourcePackage = observation.screen.packageName,
                                resultPackage = observation.screen.packageName
                            )
                        )
                    }
                }
            }
            // A suspended session keeps its goal and checkpoint without consuming its active
            // execution budget. Planner/action time remains bounded by the ordinary deadline.
            activeDeadlineMs += monotonicNowMs() - directiveWaitStartedMs
            if (monotonicNowMs() >= activeDeadlineMs) {
                return@execution failure("UI automation reached its active wall-clock time limit.")
            }
            UiGoalCompletionEvaluator.evaluate(goal, observation.screen, goalAppCandidates)?.let { completion ->
                trace(
                    "goal_condition_satisfied",
                    mapOf(
                        "summary" to completion.summary,
                        "screen_id" to observation.screen.screenId,
                        "package" to observation.screen.packageName
                    )
                )
                return@execution success(completion.summary)
            }
            progress("Plan ${actionIndex + 1}", "Choosing the next UI action")
            
            var rawPlan = plan(goal, observation, actionHistory, goalAppCandidates)
                ?: return@execution failure("The UI planner returned no usable plan.")
            var parsedPlan = parsePlan(rawPlan, goal, observation)
            
            var retries = 0
            while (parsedPlan.isFailure && retries < budget.maxPlannerRetriesPerObservation) {
                val errorMsg = parsedPlan.exceptionOrNull()?.message
                logger(
                    "UiAutomation planner parse failed: $errorMsg; " +
                        "outputLength=${rawPlan.length} outputHash=${hashContent(rawPlan)}"
                )
                progress("Plan ${actionIndex + 1}", "Retrying with a strict JSON-only prompt")
                rawPlan = plan(goal, observation, actionHistory, goalAppCandidates, jsonRetry = true)
                    ?: return@execution failure("The UI planner returned no usable JSON after retry.")
                parsedPlan = parsePlan(rawPlan, goal, observation)
                retries++
            }
            
            val planHorizon = parsedPlan.getOrElse { error ->
                logger(
                    "UiAutomation planner retry parse failed: ${error.message}; " +
                        "outputLength=${rawPlan.length} outputHash=${hashContent(rawPlan)}"
                )
                trace(
                    "plan_parse_failed",
                    mapOf("error_type" to error::class.java.simpleName, "error_message" to error.message)
                )
                return@execution failure("The UI planner returned invalid action JSON after retry: ${error.message}")
            }
            val actionPlan = planHorizon.firstExecutionSlice()
            trace(
                "plan_parsed",
                mapOf(
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName,
                    "action_count" to planHorizon.executableSteps.size,
                    "assertion_count" to planHorizon.steps.count { it is UiActionStep.Assert },
                    "selected_action" to actionLabel(actionPlan.executableSteps.single()),
                    "deferred_actions" to planHorizon.executableSteps.drop(1).map(::actionLabel)
                )
            )
            
            val action = actionPlan.executableSteps.single()
            val remainingAppCandidates = AutomationAppScope.remainingCandidates(
                goal = goal,
                candidates = goalAppCandidates,
                history = actionHistory
            )
            if (action is UiActionStep.OpenApp) {
                val wasResolvedForGoal = goalAppCandidates.any {
                    it.packageName.equals(action.packageName, ignoreCase = true)
                }
                val isStillRequired = remainingAppCandidates.any {
                    it.packageName.equals(action.packageName, ignoreCase = true)
                }
                val isExplicitUnresolvedPackage = !wasResolvedForGoal &&
                    goal.lowercase().contains(action.packageName.lowercase())
                if (!isStillRequired && !isExplicitUnresolvedPackage) {
                    return@execution failure(
                        "Planner requested app package '${action.packageName}' outside the remaining goal-relevant app set."
                    )
                }
            }
            val fingerprint = "${actionLabel(action)}|${hashContent(action.toJson().toString(), goal)}|${observation.screen.screenId}"
            val contentHash = confirmationContentHash(action, observation.screen, goal)
            
            when (val decision = UiActionValidator.validate(actionPlan, observation.screen)) {
                UiPlanDecision.Allow -> Unit
                is UiPlanDecision.Invalid -> {
                    logger("UiAutomation plan rejected: ${decision.reason}")
                    return@execution failure(decision.reason)
                }
                is UiPlanDecision.Block -> {
                    logger("UiAutomation plan blocked: ${decision.reason}")
                    return@execution failure(decision.reason)
                }
                is UiPlanDecision.RequireConfirmation -> {
                    progress("Confirm", "Evaluating confirmation requirements")
                    val expectedDestination = expectedDestinationForCommit(action, observation.screen)
                    if (isMessagingCommitBoundary(action, observation.screen) && expectedDestination == null) {
                        return@execution failure("Action blocked: no verified pending message is bound to this send action.")
                    }
                    val needsDestination = expectedDestination != null
                    var verifiedDestination: String? = null
                    if (needsDestination) {
                        when (val result = DestinationResolver.resolve(observation.screen, expectedDestination)) {
                            is DestinationResolver.DestinationResult.Verified -> verifiedDestination = result.observed
                            is DestinationResolver.DestinationResult.Mismatch -> return@execution failure("Destination mismatch: observed '${result.observed}' but expected '${result.expected}'.")
                            is DestinationResolver.DestinationResult.Unresolvable -> return@execution failure("Action blocked: ${result.reason}")
                        }
                    }
                    val userApproved = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                        requestConfirmation(describeConfirmation(action, decision.reason, observation.screen, goal, verifiedDestination))
                    } ?: false
                    
                    if (!userApproved) {
                        return@execution failure("User denied UI action confirmation or timed out.")
                    }
                    
                    val latestObservation = observe(requestScreenshot = false)
                        ?: return@execution failure("Failed to re-observe screen for confirmation.")
                    if (latestObservation.screen.screenId != observation.screen.screenId) {
                        return@execution failure("Screen changed while awaiting confirmation. Action aborted.")
                    }

                    // Re-verify destination after user confirmation
                    if (needsDestination && verifiedDestination != null) {
                        when (val recheck = DestinationResolver.resolve(latestObservation.screen, verifiedDestination)) {
                            is DestinationResolver.DestinationResult.Verified -> Unit
                            is DestinationResolver.DestinationResult.Mismatch -> return@execution failure("Destination changed after confirmation: observed '${recheck.observed}' but expected '${recheck.expected}'.")
                            is DestinationResolver.DestinationResult.Unresolvable -> return@execution failure("Destination unresolvable after confirmation: ${recheck.reason}")
                        }
                    }

                    val updatedFingerprint = "${actionLabel(action)}|${hashContent(action.toJson().toString(), goal)}|${latestObservation.screen.screenId}"
                    
                    // Assign latest observation before proceeding
                    observation = latestObservation
                    
                    val grantId = ConfirmationManager.requestConfirmation(
                        sessionId = sessionId,
                        screenId = latestObservation.screen.screenId,
                        actionFingerprint = updatedFingerprint,
                        destination = verifiedDestination,
                        contentHash = contentHash,
                        timeoutMs = 120_000
                    )
                    
                    if (!ConfirmationManager.consumeConfirmation(grantId, sessionId, latestObservation.screen.screenId, updatedFingerprint, verifiedDestination, contentHash)) {
                        return@execution failure("Confirmation expired or invalid.")
                    }
                }
            }
            
            val repCount = actionRepetitions.getOrDefault(fingerprint, 0)
            if (successfulFingerprints.contains(fingerprint) && unchangedCount > 0) {
                if (repCount >= budget.maxIdenticalActionUnchangedScreen) {
                    logger("UiAutomation repetion detected: fingerprint=$fingerprint")
                    return@execution failure("Planner proposed a repetitive action that already succeeded on this exact screen without changing it.")
                }
                
                logger("UiAutomation repetion warned: fingerprint=$fingerprint")
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = actionIndex,
                        action = actionLabel(action),
                        targetElementId = getTargetElementId(action),
                        targetLabel = getTargetLabel(action, observation.screen),
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = observation.screen.screenId,
                        outcome = Outcome.FAILED,
                        changedScreen = false,
                        detail = "Action proposed again after succeeding without changing the screen.",
                        sourcePackage = observation.screen.packageName,
                        resultPackage = observation.screen.packageName
                    )
                )
                actionRepetitions[fingerprint] = repCount + 1
                observation = observe() ?: return@execution failure("Failed to re-observe after repetition.")
                return@repeat
            }

            progress("Decision ${actionIndex + 1}", "Planner selected ${actionLabel(action)}")
            trace(
                "action_selected",
                mapOf(
                    "index" to actionIndex,
                    "action" to actionLabel(action),
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName
                )
            )
            when (action) {
                is UiActionStep.Done -> {
                    val explicitConditions = UiGoalCompletionEvaluator.compile(goal, goalAppCandidates)
                    if (explicitConditions.isEmpty()) {
                        return@execution success(action.summary)
                    }
                    actionHistory.add(
                        UiActionHistoryEntry(
                            index = actionIndex,
                            action = actionLabel(action),
                            targetElementId = null,
                            targetLabel = null,
                            sourceScreenId = observation.screen.screenId,
                            resultScreenId = observation.screen.screenId,
                            outcome = Outcome.FAILED,
                            changedScreen = false,
                            detail = "Completion rejected: the compiled success condition is not visible yet.",
                            sourcePackage = observation.screen.packageName,
                            resultPackage = observation.screen.packageName
                        )
                    )
                    return@repeat
                }
                is UiActionStep.AskUser -> {
                    if (isScheduled) {
                        return@execution failure(
                            "Scheduled device control needs user input but has no interactive control surface."
                        )
                    }
                    if (!AutomationSessionManager.requestUserInput(sessionId, action.reason)) {
                        return@execution failure("Device control could not preserve its pending user question.")
                    }
                    onUserQuestion(action.reason)
                    progress("Needs input", action.reason)
                    val foregrounded = ControlSurfaceCoordinator.showAndAwait(sessionId)
                    trace(
                        "user_question_presented",
                        mapOf("foregrounded" to foregrounded, "message" to action.reason)
                    )
                    if (!foregrounded) {
                        return@execution failure(
                            "Nabu could not bring Chat forward for the required clarification."
                        )
                    }
                    val inputWaitStartedMs = monotonicNowMs()
                    val answer = AutomationSessionManager.awaitUserInput(sessionId)
                    activeDeadlineMs += monotonicNowMs() - inputWaitStartedMs
                    onUserQuestionCleared()
                    if (answer.isNullOrBlank()) {
                        return@execution failure("Device control was stopped while waiting for user input.")
                    }
                    val resumedObservation = observe()
                        ?: return@execution failure(
                            "Device control resumed, but the target screen could not be observed."
                        )
                    actionHistory.add(
                        UiActionHistoryEntry(
                            index = actionIndex,
                            action = "user input",
                            targetElementId = null,
                            targetLabel = null,
                            sourceScreenId = observation.screen.screenId,
                            resultScreenId = resumedObservation.screen.screenId,
                            outcome = Outcome.SUCCEEDED,
                            changedScreen = resumedObservation.screen.screenId != observation.screen.screenId,
                            detail = "Question: ${action.reason}\nAnswer: $answer",
                            sourcePackage = observation.screen.packageName,
                            resultPackage = resumedObservation.screen.packageName
                        )
                    )
                    trace(
                        "user_question_answered",
                        mapOf(
                            "message" to answer,
                            "result_screen_id" to resumedObservation.screen.screenId,
                            "result_package" to resumedObservation.screen.packageName
                        )
                    )
                    observation = resumedObservation
                    return@repeat
                }
                is UiActionStep.Wait -> {
                    val waitTime = action.milliseconds.coerceAtMost(budget.maxSingleWaitMs)
                    if (cumulativeWaitMs + waitTime > budget.maxCumulativeWaitMs) {
                        return@execution failure("Wait budget exceeded.")
                    }
                    cumulativeWaitMs += waitTime
                    delay(waitTime)
                    actionHistory.add(
                        UiActionHistoryEntry(
                            index = actionIndex,
                            action = actionLabel(action),
                            targetElementId = null,
                            targetLabel = null,
                            sourceScreenId = observation.screen.screenId,
                            resultScreenId = observation.screen.screenId,
                            outcome = Outcome.SUCCEEDED,
                            changedScreen = false,
                            detail = "waited ${waitTime}ms",
                            sourcePackage = observation.screen.packageName,
                            resultPackage = observation.screen.packageName
                        )
                    )
                }
                else -> {
                    progress("Execute ${actionIndex + 1}", "Running ${actionLabel(action)}")
                    val result = execute(action, observation, sessionId, goal)
                    trace(
                        "action_executed",
                        mapOf(
                            "index" to actionIndex,
                            "action" to actionLabel(action),
                            "status" to if (result.isError) "failed" else "succeeded",
                            "result_length" to result.output.length,
                            "result_hash" to hashContent(result.output)
                        )
                    )
                    if (result.isError) {
                        logger(
                            "UiAutomation execution failed action=${actionLabel(action)} " +
                                "resultLength=${result.output.length} resultHash=${hashContent(result.output)}"
                        )
                        actionHistory.add(
                            UiActionHistoryEntry(
                                index = actionIndex,
                                action = actionLabel(action),
                                targetElementId = getTargetElementId(action),
                                targetLabel = getTargetLabel(action, observation.screen),
                                sourceScreenId = observation.screen.screenId,
                                resultScreenId = observation.screen.screenId,
                                outcome = Outcome.FAILED,
                                changedScreen = false,
                                detail = result.output,
                                sourcePackage = observation.screen.packageName,
                                resultPackage = observation.screen.packageName
                            )
                        )
                        observation = observe() ?: return@execution failure("UI action failed and screen could not be observed.")
                        return@repeat
                    }
                }
            }

            progress("Verify ${actionIndex + 1}", "Observing the resulting screen")
            val next = observeAfterAction(observation, action)
                ?: return@execution failure("UI action ran, but the resulting screen could not be observed.")
            val assertion = actionPlan.steps.filterIsInstance<UiActionStep.Assert>().lastOrNull()?.condition
            val assertionMatched = assertion?.let { assertionMatches(it, next.screen) }
            val builtInPostcondition = UiActionPostconditionVerifier.verify(action, observation.screen, next.screen)
            if (assertion != null) {
                trace(
                    "postcondition_checked",
                    mapOf(
                        "index" to actionIndex,
                        "matched" to assertionMatched,
                        "screen_id" to next.screen.screenId,
                        "package" to next.screen.packageName
                    )
                )
            }
            if (builtInPostcondition.status != PostconditionStatus.NOT_APPLICABLE) {
                trace(
                    "built_in_postcondition_checked",
                    mapOf(
                        "index" to actionIndex,
                        "status" to builtInPostcondition.status.name.lowercase(),
                        "detail" to builtInPostcondition.detail,
                        "screen_id" to next.screen.screenId,
                        "package" to next.screen.packageName
                    )
                )
            }

            val changed = next.screen.screenId != observation.screen.screenId
            unchangedCount = if (changed) 0 else unchangedCount + 1
            val postconditionFailed = assertionMatched == false ||
                builtInPostcondition.status == PostconditionStatus.FAILED
            val postconditionDetail = buildList {
                when (assertionMatched) {
                    true -> add("Planner postcondition matched; continue until an explicit done action.")
                    false -> add("Planner postcondition did not match; replan from the observed state.")
                    null -> Unit
                }
                if (builtInPostcondition.status != PostconditionStatus.NOT_APPLICABLE) {
                    add(builtInPostcondition.detail)
                }
            }.takeIf { it.isNotEmpty() }?.joinToString(" ")
            
            if (action !is UiActionStep.Wait && action !is UiActionStep.Done && action !is UiActionStep.AskUser) {
                if (!postconditionFailed) successfulFingerprints.add(fingerprint)
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = actionIndex,
                        action = actionLabel(action),
                        targetElementId = getTargetElementId(action),
                        targetLabel = getTargetLabel(action, observation.screen),
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = next.screen.screenId,
                        outcome = if (postconditionFailed) Outcome.FAILED else Outcome.SUCCEEDED,
                        changedScreen = changed,
                        detail = postconditionDetail,
                        sourcePackage = observation.screen.packageName,
                        resultPackage = next.screen.packageName
                    )
                )
            }
            
            logger(
                "UiAutomation step=${actionIndex + 1} action=${action::class.simpleName} " +
                    "screen=${observation.screen.screenId}->${next.screen.screenId} unchanged=$unchangedCount"
            )
            trace(
                "transition_observed",
                mapOf(
                    "index" to actionIndex,
                    "source_screen_id" to observation.screen.screenId,
                    "result_screen_id" to next.screen.screenId,
                    "source_package" to observation.screen.packageName,
                    "result_package" to next.screen.packageName,
                    "changed" to changed,
                    "unchanged_count" to unchangedCount
                )
            )
            AutomationSessionManager.actionCompleted(
                sessionId,
                "${actionLabel(action)}: ${observation.screen.packageName} → ${next.screen.packageName}"
            )
            val stepRecord = ActionStepRecord(
                sequence = actionIndex + 1,
                observation = observation.screen.screenId,
                reasoningSummary = actionLabel(action),
                action = actionLabel(action),
                target = getTargetLabel(action, observation.screen),
                result = if (postconditionFailed) "failed" else "succeeded",
                postActionObservation = next.screen.screenId,
                verification = postconditionDetail,
                latencyMs = monotonicNowMs() - stepStartMs,
                sourcePackage = observation.screen.packageName,
                resultPackage = next.screen.packageName
            )
            onStepRecord?.invoke(stepRecord)
            UiGoalCompletionEvaluator.evaluate(goal, next.screen, goalAppCandidates)?.let { completion ->
                trace(
                    "goal_condition_satisfied",
                    mapOf(
                        "summary" to completion.summary,
                        "screen_id" to next.screen.screenId,
                        "package" to next.screen.packageName
                    )
                )
                return@execution success(completion.summary)
            }
            if (unchangedCount >= budget.maxUnchangedObservations) {
                return@execution failure("UI did not change after repeated actions; stopping automation.")
            }
            observation = next
            }
            failure("UI action limit reached before the goal was verified.")
        }
        return executionResult
    }

    private suspend fun observe(
        requestScreenshot: Boolean = false
    ): Observation? {
        val service = com.mewmix.nabu.accessibility.NabuAccessibilityService.instance
        if (service == null) {
            logger("UiAutomation observe failed: AccessibilityService not connected")
            return null
        }

        val captureStartedMs = monotonicNowMs()
        val snapshot = service.forceCaptureSnapshot()
        if (snapshot == null) {
            logger("UiAutomation observe failed: No snapshot available")
            return null
        }

        val capturedMs = monotonicNowMs()
        val screenState = UiTreeIndexer.build(snapshot)
        val indexedMs = monotonicNowMs()
        var screenshotPath: String? = null
        val artifactPaths = mutableListOf<String>()
        val needsVisionFallback = screenState.plannerElements().isEmpty() && shouldAttachScreenshot(jsonRetry = false)
        if ((requestScreenshot || needsVisionFallback) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        ) {
            val path = context.cacheDir.absolutePath + "/nabu_ui_${snapshot.id}.png"
            if (service.takeScreenshotToPath(path)) {
                screenshotPath = path
                artifactPaths.add(path)
                pendingArtifactPaths.add(path)
            }
        }

        return Observation(
            bridgeObservationId = snapshot.id,
            snapshotSequence = snapshot.sequence,
            screen = screenState,
            screenshotPath = screenshotPath,
            artifactPaths = artifactPaths
        ).also { observation ->
            trace(
                "observation_captured",
                mapOf(
                    "observation_id" to observation.bridgeObservationId,
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName,
                    "window" to observation.screen.activityName,
                    "element_count" to observation.screen.elements.size,
                    "actionable_count" to observation.screen.plannerElements().size,
                    "screenshot" to (observation.screenshotPath != null),
                    "capture_ms" to (capturedMs - captureStartedMs),
                    "index_ms" to (indexedMs - capturedMs)
                )
            )
        }
    }

    private suspend fun observeInitial(): Observation? {
        val deadline = monotonicNowMs() + INITIAL_OBSERVATION_TIMEOUT_MS
        var sequence = com.mewmix.nabu.accessibility.UiSnapshotStore.currentSnapshot.value?.sequence ?: 0L
        do {
            observe(requestScreenshot = false)?.let { return it }
            val remainingMs = deadline - monotonicNowMs()
            if (remainingMs <= 0L) break
            val eventSnapshot = com.mewmix.nabu.accessibility.UiSnapshotStore.awaitAfter(
                sequence = sequence,
                timeoutMs = remainingMs.coerceAtMost(INITIAL_EVENT_WAIT_SLICE_MS)
            )
            if (eventSnapshot != null) {
                sequence = eventSnapshot.sequence
            } else {
                delay(INITIAL_RETRY_DELAY_MS)
            }
        } while (monotonicNowMs() < deadline)
        return observe(requestScreenshot = false)
    }

    private suspend fun observeAfterAction(
        previous: Observation,
        action: UiActionStep
    ): Observation? {
        val maxWaitMs = UiTransitionPolicy.maxWaitMs(action, budget)
        if (budget.postActionSettleDelayMs > 0) {
            delay(budget.postActionSettleDelayMs)
        }
        if (maxWaitMs <= 0) return observe()

        progress("Transition", "Waiting for Android to settle after ${actionLabel(action)}")
        val deadline = monotonicNowMs() + maxWaitMs
        var latest: Observation? = null
        var observedSequence = previous.snapshotSequence
        do {
            val remainingMs = deadline - monotonicNowMs()
            if (remainingMs <= 0) break
            com.mewmix.nabu.accessibility.UiSnapshotStore.awaitAfter(
                sequence = observedSequence,
                timeoutMs = remainingMs
            ) ?: break
            if (budget.postActionSettleDelayMs > 0) {
                delay(budget.postActionSettleDelayMs.coerceAtMost(75L))
            }
            val candidate = observe(requestScreenshot = false)
            if (candidate != null) {
                latest?.let { cleanupArtifacts(it.artifactPaths) }
                latest = candidate
                observedSequence = candidate.snapshotSequence
                if (UiTransitionPolicy.isSettled(previous.screen, candidate.screen, action)) break
            }
        } while (true)

        return latest ?: observe(requestScreenshot = false)
    }

    private suspend fun cleanupArtifacts(paths: Collection<String>) {
        paths.forEach { path ->
            if (!isGeneratedObservationPath(path)) {
                logger("UiAutomation refused to delete unexpected artifact path $path")
                return@forEach
            }
            val deletedLocally = runCatching { !File(path).exists() || File(path).delete() }.getOrDefault(false)
            val deleted = deletedLocally || (AccessibilityToolHandler.execute(
                context,
                ToolCall("delete_file", mapOf("path" to path))
            )?.isError == false)
            if (!deleted) logger("UiAutomation could not delete consumed artifact $path")
            if (deleted) pendingArtifactPaths.remove(path)
        }
    }

    private fun isGeneratedObservationPath(path: String): Boolean {
        val normalized = path.replace("/sdcard/", "/storage/emulated/0/")
        val file = File(normalized)
        return (file.parent == "/storage/emulated/0/Download" || file.parent == context.cacheDir.absolutePath) &&
            file.name.startsWith("nabu_ui_") &&
            (file.extension == "xml" || file.extension == "png")
    }

    private fun parsePlan(
        rawPlan: String,
        goal: String,
        observation: Observation
    ): Result<UiActionPlan> = runCatching {
        val rawJson = extractJson(rawPlan)
        try {
            val decision = ConstrainedDecisionDecoder.decode(rawJson)
            require(decision !is AgentDecision.Query && decision !is AgentDecision.Delegate) {
                "UI control does not accept query or delegation decisions."
            }
            logger("Successfully parsed V3 decision: $decision")
            decision.toUiActionPlan(goal, observation.screen.screenId)
        } catch (e: Exception) {
            val isV3Payload = runCatching {
                com.google.gson.JsonParser.parseString(rawJson)
                    .asJsonObject
                    .get("v")
                    ?.asInt == 3
            }.getOrDefault(false)
            logger("V3 decision rejected: ${e.message}")
            if (isV3Payload) throw e
            LegacyPlannerAdapter.parseAndAdapt(
                rawJson = rawJson,
                goal = goal,
                screenId = observation.screen.screenId,
                logger = { logger(it) }
            )
        }.resolveElementReferences(observation.screen)
    }

    private suspend fun plan(
        goal: String,
        observation: Observation,
        history: List<UiActionHistoryEntry>,
        goalAppCandidates: List<DeviceAction.AppCandidate>,
        jsonRetry: Boolean = false
    ): String? {
        val userContent = buildPlannerInput(goal, observation.screen, history, goalAppCandidates)
        val attachScreenshot = observation.screenshotPath != null && shouldAttachScreenshot(jsonRetry)
        logger(
            "UiAutomation planner input backend=${backend::class.java.simpleName} " +
                "runtime=${backend.runtimeDescription()} jsonRetry=$jsonRetry screenshot=$attachScreenshot"
        )
        val images = if (attachScreenshot) {
            observation.screenshotPath
                ?.let(BitmapFactory::decodeFile)
                ?.let(::LlmImageInput)
                ?.let(::listOf)
                .orEmpty()
        } else {
            emptyList()
        }
        val conversation = listOf(
            LlmMessage(
                role = "system",
                content = if (jsonRetry) JSON_RETRY_SYSTEM_PROMPT else PLANNER_SYSTEM_PROMPT
            ),
            LlmMessage(role = "user", content = userContent, images = images)
        )
        val structuredStartedMs = monotonicNowMs()
        val structuredResult = try {
            backend.generateStructured(conversation, UI_DECISION_TOOLS)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger(
                "UiAutomation native structured generation failed; falling back to JSON: " +
                    "${error::class.java.simpleName}: ${error.message}"
            )
            null
        }
        structuredResult?.let { result ->
            val raw = try {
                result.toolCall?.let(ConstrainedDecisionDecoder::toCanonicalJson)
                    ?: result.text
            } catch (error: Exception) {
                logger(
                    "UiAutomation native structured decision was unusable; falling back to JSON: " +
                        "${error::class.java.simpleName}: ${error.message}"
                )
                ""
            }
            if (raw.isBlank()) {
                logger("UiAutomation native structured generation returned no decision; falling back to JSON")
            } else {
                onModelOutput(raw)
                logger(
                    "UiAutomation native structured output screen=${observation.screen.screenId} " +
                        "tool=${result.toolCall?.name ?: "text_fallback"} outputLength=${raw.length} " +
                        "outputHash=${hashContent(raw)}"
                )
                trace(
                    "planner_output_received",
                    mapOf(
                        "screen_id" to observation.screen.screenId,
                        "package" to observation.screen.packageName,
                        "planner_output" to raw,
                        "output_length" to raw.length,
                        "json_retry" to jsonRetry,
                        "native_structured" to (result.toolCall != null),
                        "latency_ms" to (monotonicNowMs() - structuredStartedMs)
                    )
                )
                return raw
            }
        }
        val completion = CompletableDeferred<String?>()
        val completed = AtomicBoolean(false)
        val output = StringBuilder()
        val plannerStartedMs = monotonicNowMs()
        backend.sendMessage(conversation) { partial, done ->
            if (partial.isNotEmpty()) {
                output.append(partial)
                onModelOutput(partial)
            }
            if (done && completed.compareAndSet(false, true)) completion.complete(output.toString())
        }
        return withTimeoutOrNull(PLANNER_TIMEOUT_MS) { completion.await() }?.also { raw ->
            logger(
                "UiAutomation planner output screen=${observation.screen.screenId} " +
                    "elements=${observation.screen.plannerElements(plannerElementLimit()).size} " +
                    "outputLength=${raw.length} outputHash=${hashContent(raw)}"
            )
            trace(
                "planner_output_received",
                mapOf(
                    "screen_id" to observation.screen.screenId,
                    "package" to observation.screen.packageName,
                    "planner_output" to raw,
                    "output_length" to raw.length,
                    "json_retry" to jsonRetry,
                    "latency_ms" to (monotonicNowMs() - plannerStartedMs)
                )
            )
        }
    }

    private fun shouldAttachScreenshot(jsonRetry: Boolean): Boolean {
        if (jsonRetry || !backend.supportsImageInput()) return false
        return backend !is LiteRtLmBackend && !backend.runtimeDescription().startsWith("LITERT-LM")
    }

    private fun deterministicNavigationAction(goal: String): UiActionStep? {
        val normalized = goal.lowercase()
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .removePrefix("please ")
        return when (normalized) {
            "press home", "go home", "go to home", "open home screen", "show home screen" ->
                UiActionStep.PressHome
            "press back", "go back" -> UiActionStep.PressBack
            "open recents", "show recents", "press recents" -> UiActionStep.PressRecents
            "open notifications", "show notifications" -> UiActionStep.OpenNotifications
            "open quick settings", "show quick settings" -> UiActionStep.OpenQuickSettings
            else -> null
        }
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return keyguardManager?.isDeviceLocked == true
    }

    private suspend fun execute(action: UiActionStep, observation: Observation, sessionId: String, goal: String): ToolResult {
        if (action is UiActionStep.ShareCapturedMedia) {
            val capture = pendingCapture
                ?: return failure("No captured media is available to share.")
            if (!AutomationMediaManager.validateCaptureOutput(context, capture.uri)) {
                return failure("Captured media is empty or unavailable.")
            }
            if (!goalExplicitlyNamesDestination(goal, action.expectedDestination)) {
                return failure("Expected destination must be explicitly present in the user's goal.")
            }
        }
        val policyContext = PolicyContext(
            isScheduled = isScheduled,
            isDeviceLocked = isDeviceLocked(),
            destinationProvenance = "planner",
            context = context
        )
        val policyDecision = AutomationIntentPolicy.evaluate(action, policyContext)
        var currentObservation = observation
        when (policyDecision) {
            is IntentPolicyDecision.Block -> return failure("Action blocked by policy: ${policyDecision.reason}")
            is IntentPolicyDecision.RequireConfirmation -> {
                progress("Confirm", "Evaluating confirmation requirements")
                val expectedDestination = expectedDestinationForCommit(action, currentObservation.screen)
                if (isMessagingCommitBoundary(action, currentObservation.screen) && expectedDestination == null) {
                    return failure("Action blocked: no verified pending message is bound to this send action.")
                }
                val needsDestination = expectedDestination != null
                var verifiedDestination: String? = null
                if (needsDestination) {
                    when (val result = DestinationResolver.resolve(currentObservation.screen, expectedDestination)) {
                        is DestinationResolver.DestinationResult.Verified -> verifiedDestination = result.observed
                        is DestinationResolver.DestinationResult.Mismatch -> return failure("Destination mismatch: observed '${result.observed}' but expected '${result.expected}'.")
                        is DestinationResolver.DestinationResult.Unresolvable -> return failure("Action blocked: ${result.reason}")
                    }
                }
                val userApproved = kotlinx.coroutines.withTimeoutOrNull(60_000) {
                    val description = describeConfirmation(action, policyDecision.reason, currentObservation.screen, goal, verifiedDestination)
                    requestConfirmation(description)
                } ?: false
                
                if (!userApproved) {
                    return failure("User denied UI action confirmation or timed out.")
                }
                
                val latestObservation = observe(requestScreenshot = false)
                    ?: return failure("Failed to re-observe screen for confirmation.")
                if (latestObservation.screen.screenId != currentObservation.screen.screenId) {
                    return failure("Screen changed while awaiting confirmation. Action aborted.")
                }
                
                currentObservation = latestObservation

                // Re-verify destination after user confirmation
                if (needsDestination && verifiedDestination != null) {
                    when (val recheck = DestinationResolver.resolve(currentObservation.screen, verifiedDestination)) {
                        is DestinationResolver.DestinationResult.Verified -> Unit
                        is DestinationResolver.DestinationResult.Mismatch -> return failure("Destination changed after confirmation: observed '${recheck.observed}' but expected '${recheck.expected}'.")
                        is DestinationResolver.DestinationResult.Unresolvable -> return failure("Destination unresolvable after confirmation: ${recheck.reason}")
                    }
                }
                
                val fingerprint = "${actionLabel(action)}|${hashContent(action.toJson().toString(), goal)}|${currentObservation.screen.screenId}"
                val contentHash = confirmationContentHash(action, currentObservation.screen, goal)
                val grantId = ConfirmationManager.requestConfirmation(
                    sessionId = sessionId,
                    screenId = currentObservation.screen.screenId,
                    actionFingerprint = fingerprint,
                    destination = verifiedDestination,
                    contentHash = contentHash,
                    timeoutMs = 120_000
                )
                
                if (!ConfirmationManager.consumeConfirmation(grantId, sessionId, currentObservation.screen.screenId, fingerprint, verifiedDestination, contentHash)) {
                    return failure("Confirmation expired or invalid.")
                }
            }
            is IntentPolicyDecision.Allow -> Unit
        }

        val arguments = linkedMapOf<String, Any>("observation_id" to currentObservation.bridgeObservationId)
        val toolName = when (action) {
            is UiActionStep.Tap -> {
                addTarget(arguments, action.target, currentObservation.screen)
                "ui_tap"
            }
            is UiActionStep.Focus -> {
                addTarget(arguments, action.target, currentObservation.screen)
                "ui_focus"
            }
            is UiActionStep.LongPress -> {
                addTarget(arguments, action.target, currentObservation.screen)
                "ui_long_press"
            }
            is UiActionStep.TypeText -> {
                action.target?.let { addTarget(arguments, it, currentObservation.screen) }
                arguments["text"] = action.text
                "ui_set_text"
            }
            is UiActionStep.Scroll -> {
                action.target?.let { addTarget(arguments, it, currentObservation.screen) }
                arguments["direction"] = action.direction.name.lowercase()
                "ui_scroll"
            }
            UiActionStep.PressBack -> {
                arguments["global_action"] = "back"
                "ui_global_action"
            }
            UiActionStep.PressHome -> {
                arguments["global_action"] = "home"
                "ui_global_action"
            }
            UiActionStep.PressRecents -> {
                arguments["global_action"] = "recents"
                "ui_global_action"
            }
            UiActionStep.OpenNotifications -> {
                arguments["global_action"] = "notifications"
                "ui_global_action"
            }
            UiActionStep.OpenQuickSettings -> {
                arguments["global_action"] = "quick_settings"
                "ui_global_action"
            }
            is UiActionStep.OpenApp -> {
                val result = DeviceAction.openApp(context, action.packageName, "")
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.OpenSettingsPage -> {
                val result = DeviceAction.openSettingsPage(context, action.page.name, action.packageName)
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.OpenUrl -> {
                val result = DeviceAction.openUrl(context, action.url)
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.ShareText -> {
                if (action.expectedDestination != null &&
                    !goalExplicitlyNamesDestination(goal, action.expectedDestination)
                ) {
                    return failure("Expected destination must be explicitly present in the user's goal.")
                }
                val result = DeviceAction.shareText(context, action.text, "", action.targetPackage)
                if (!result.isError && action.targetPackage != null && action.expectedDestination != null) {
                    pendingExternalEffect = PendingExternalEffect(
                        targetPackage = action.targetPackage,
                        expectedDestination = action.expectedDestination,
                        contentHash = hashContent(action.text)
                    )
                }
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.OpenCamera -> {
                val mimeType = if (action.mode == CameraMode.VIDEO) {
                    AutomationMediaManager.VIDEO_MIME_TYPE
                } else {
                    AutomationMediaManager.IMAGE_MIME_TYPE
                }
                val outputUri = runCatching {
                    AutomationMediaManager.createCaptureOutputUri(context, mimeType)
                }.getOrElse { return failure("Unable to create trusted capture output: ${it.message}") }
                pendingMediaUris += outputUri
                val result = if (action.mode == CameraMode.VIDEO) {
                    DeviceAction.recordVideo(context, action.facing.name, outputUri)
                } else {
                    DeviceAction.takePhoto(context, action.facing.name, outputUri)
                }
                if (result.isError) {
                    AutomationMediaManager.cleanupAll(context, listOf(outputUri))
                    pendingMediaUris -= outputUri
                } else {
                    pendingCapture = PendingCapture(outputUri, mimeType)
                }
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.ShareCapturedMedia -> {
                val capture = pendingCapture ?: return failure("No captured media is available to share.")
                val mediaHash = AutomationMediaManager.contentSha256(context, capture.uri)
                    ?: return failure("Unable to hash captured media.")
                AutomationMediaManager.grantUriReadPermission(context, capture.uri, action.targetPackage)
                val result = DeviceAction.shareMedia(
                    context = context,
                    uri = capture.uri,
                    mimeType = capture.mimeType,
                    targetPackage = action.targetPackage
                )
                if (!result.isError) {
                    pendingExternalEffect = PendingExternalEffect(
                        targetPackage = action.targetPackage,
                        expectedDestination = action.expectedDestination,
                        contentHash = mediaHash
                    )
                }
                return if (result.isError) failure(result.message) else success(result.message)
            }
            else -> return failure("Unsupported executable UI action.")
        }
        val result = AccessibilityToolHandler.execute(context, ToolCall(toolName, arguments))
            ?: failure("Unknown UI tool: $toolName")
        if (!result.isError && isMessagingCommitBoundary(action, currentObservation.screen)) {
            pendingExternalEffect = null
        }
        return result
    }

    private fun addTarget(
        arguments: MutableMap<String, Any>,
        target: UiTarget,
        screen: UiScreenState
    ) {
        val element = target.elementId?.let(screen::element)
        if (element != null) {
            arguments["selector"] = mapOf(
                "tree_path" to element.treePath,
                "resource_id" to element.resourceId.orEmpty(),
                "text" to element.text.orEmpty(),
                "content_desc" to element.contentDescription.orEmpty(),
                "class" to element.className.orEmpty()
            )
        }
        val bounds = element?.bounds ?: target.fallbackBounds
        if (bounds != null) arguments["fallback_bounds"] = bounds.toList()
    }

    private fun assertionMatches(assertion: UiAssertion, screen: UiScreenState): Boolean {
        val element = assertion.elementId?.let(screen::element)
        if (assertion.elementId != null && element == null) return false
        assertion.checked?.let { expected -> if (element?.checked != expected) return false }
        assertion.textContains?.let { expected ->
            val matches = if (element != null) {
                listOfNotNull(element.text, element.contentDescription).any { it.contains(expected, true) }
            } else {
                screen.elements.any { candidate ->
                    listOfNotNull(candidate.text, candidate.contentDescription).any { it.contains(expected, true) }
                }
            }
            if (!matches) return false
        }
        return true
    }

    private fun buildPlannerInput(
        goal: String,
        screen: UiScreenState,
        history: List<UiActionHistoryEntry>,
        goalAppCandidates: List<DeviceAction.AppCandidate>
    ): String {
        val elements = JsonArray()
        val plannerIdByElementId = linkedMapOf<String, String>()
        val remainingAppCandidates = AutomationAppScope.remainingCandidates(
            goal = goal,
            candidates = goalAppCandidates,
            history = history
        )
        screen.plannerElements(plannerElementLimit())
            .forEachIndexed { index, element ->
                plannerIdByElementId[element.id] = "p$index"
                elements.add(JsonObject().apply {
                    addProperty("id", "p$index")
                    screen.plannerLabel(element)?.let { addProperty("label", it) }
                    if (isCompactLocalPlanner()) {
                        if (screen.plannerLabel(element) == null) {
                            element.resourceId?.let { addProperty("resource_id", it) }
                        }
                        add("capabilities", JsonArray().apply {
                            if (element.clickable || element.checkable) add("tap")
                            if (element.longClickable) add("long_press")
                            if (element.editable) add("type_text")
                            if (element.scrollable) add("scroll")
                        })
                        if (element.checkable) addProperty("checked", element.checked)
                        if (element.password) addProperty("password", true)
                    } else {
                        element.resourceId?.let { addProperty("resource_id", it) }
                        element.className?.let { addProperty("class", it) }
                        element.bounds?.let { bounds ->
                            add("bounds", JsonArray().apply { bounds.toList().forEach(::add) })
                        }
                        addProperty("clickable", element.clickable)
                        addProperty("enabled", element.enabled)
                        addProperty("editable", element.editable)
                        addProperty("scrollable", element.scrollable)
                        addProperty("checkable", element.checkable)
                        addProperty("checked", element.checked)
                        addProperty("password", element.password)
                    }
                })
            }
        return JsonObject().apply {
            addProperty("goal", goal)
            addProperty("screen_id", screen.screenId)
            addProperty("package", screen.packageName)
            addProperty("activity", screen.activityName)
            val successConditions = UiGoalCompletionEvaluator.plannerDescriptions(
                goal,
                goalAppCandidates
            )
            if (successConditions.isNotEmpty()) {
                add("success_conditions", JsonArray().apply {
                    successConditions.forEach(::add)
                })
            }
            if (priorTurns.isNotEmpty()) {
                val turnsArray = JsonArray()
                priorTurns.takeLast(4).forEach { turn ->
                    turnsArray.add(JsonObject().apply {
                        addProperty("user_goal", turn.userRequest)
                        turn.assistantResponse?.let { addProperty("assistant_summary", it) }
                    })
                }
                add("prior_turns", turnsArray)
            }
            if (history.isNotEmpty()) {
                val recentEntries = history.takeLast(8)
                val olderEntries = history.dropLast(8)
                val olderSuccesses = olderEntries.count { it.outcome == Outcome.SUCCEEDED }
                
                if (olderSuccesses > 0) {
                    addProperty("older_successful_actions", olderSuccesses)
                }

                val historyArray = JsonArray()
                recentEntries.forEach { entry -> 
                    historyArray.add(JsonObject().apply {
                        addProperty("action", entry.action)
                        entry.targetLabel?.let { addProperty("target_label", it) }
                        entry.sourcePackage?.let { addProperty("source_package", it) }
                        entry.resultPackage?.let { addProperty("result_package", it) }
                        addProperty("outcome", entry.outcome.name.lowercase())
                        addProperty("screen_changed", entry.changedScreen)
                        entry.detail?.let { addProperty("detail", it) }
                    })
                }
                add("history", historyArray)
            }
            val completedAppPackages = goalAppCandidates
                .map(DeviceAction.AppCandidate::packageName)
                .filter { packageName ->
                    history.any { entry ->
                        entry.outcome == Outcome.SUCCEEDED &&
                            entry.action.equals("open app $packageName", ignoreCase = true)
                    }
                }
            if (completedAppPackages.isNotEmpty()) {
                add("completed_app_packages", JsonArray().apply {
                    completedAppPackages.forEach(::add)
                })
            }
            if (remainingAppCandidates.isNotEmpty()) {
                add("launchable_apps", JsonArray().apply {
                    remainingAppCandidates.forEach { app ->
                        add(JsonObject().apply {
                            addProperty("label", app.label)
                            addProperty("package_name", app.packageName)
                        })
                    }
                })
            }
            TelegramUiAdapter.inspect(screen)?.let { telegram ->
                add("application_context", JsonObject().apply {
                    addProperty("adapter", "telegram")
                    addProperty("surface", telegram.surface.name.lowercase())
                    if (telegram.targets.isNotEmpty()) {
                        add("semantic_targets", JsonArray().apply {
                            telegram.targets.forEach { target ->
                                plannerIdByElementId[target.elementId]?.let { plannerId ->
                                    add(JsonObject().apply {
                                        addProperty("kind", target.kind)
                                        addProperty("label", target.label)
                                        addProperty("element_id", plannerId)
                                        addProperty("confidence", "exact_unique")
                                    })
                                }
                            }
                        })
                    }
                    if (telegram.ambiguousKinds.isNotEmpty()) {
                        add("ambiguous_targets", JsonArray().apply {
                            telegram.ambiguousKinds.forEach { (kind, count) ->
                                add(JsonObject().apply {
                                    addProperty("kind", kind)
                                    addProperty("count", count)
                                })
                            }
                        })
                    }
                })
            }
            add("elements", elements)
        }.toString()
    }

    private fun plannerElementLimit(): Int =
        if (isCompactLocalPlanner()) MAX_LOCAL_PROMPT_ELEMENTS else MAX_PROMPT_ELEMENTS

    private fun isCompactLocalPlanner(): Boolean =
        backend is LiteRtLmBackend || backend.runtimeDescription().startsWith("LITERT-LM")

    private fun describeConfirmation(action: UiActionStep, reason: String, screen: UiScreenState, goal: String, destination: String?): String {
        val contextLines = screen.elements
            .filter { !it.editable && it.text?.isNotBlank() == true }
            .sortedBy { it.bounds?.top ?: 0 }
            .take(3)
            .joinToString("\n") { "> ${it.text}" }

        val details = when(action) {
            is UiActionStep.Tap -> {
                val element = action.target.elementId?.let { screen.element(it) }
                val label = element?.text?.takeIf(String::isNotBlank) ?: element?.contentDescription?.takeIf(String::isNotBlank)
                val targetDesc = if (label != null) "Target: \"$label\"" else "Target: (unlabeled)"
                val inputs = screen.elements.filter { it.editable && !it.text.isNullOrBlank() }
                    .joinToString("\n") { "Input field contains: ${it.text}" }
                if (inputs.isNotEmpty()) "$targetDesc\n$inputs" else targetDesc
            }
            is UiActionStep.LongPress -> {
                val element = action.target.elementId?.let { screen.element(it) }
                val label = element?.text?.takeIf(String::isNotBlank) ?: element?.contentDescription?.takeIf(String::isNotBlank)
                val targetDesc = if (label != null) "Target: \"$label\"" else "Target: (unlabeled)"
                val inputs = screen.elements.filter { it.editable && !it.text.isNullOrBlank() }
                    .joinToString("\n") { "Input field contains: ${it.text}" }
                if (inputs.isNotEmpty()) "$targetDesc\n$inputs" else targetDesc
            }
            is UiActionStep.OpenApp -> "App: ${action.packageName}"
            is UiActionStep.OpenUrl -> "URL: ${action.url}"
            is UiActionStep.ShareText -> "Text: ${action.text}\nTarget Package: ${action.targetPackage ?: "Any"}"
            is UiActionStep.OpenSettingsPage -> "Page: ${action.page.name}\nTarget Package: ${action.packageName ?: "None"}"
            is UiActionStep.OpenCamera -> "Mode: ${action.mode.name}\nFacing: ${action.facing.name}"
            is UiActionStep.ShareCapturedMedia -> {
                val capture = pendingCapture
                val size = capture?.let { AutomationMediaManager.contentSize(context, it.uri) }
                val hash = capture?.let { AutomationMediaManager.contentSha256(context, it.uri) }
                "Captured media: ${capture?.mimeType ?: "unavailable"}\n" +
                    "Size: ${size?.let { "$it bytes" } ?: "unknown"}\n" +
                    "SHA-256: ${hash ?: "unavailable"}\n" +
                    "Target Package: ${action.targetPackage}\n" +
                    "Expected Destination: ${action.expectedDestination}"
            }
            else -> ""
        }
        val contextStr = if (contextLines.isNotEmpty()) "\n\nContext:\n$contextLines" else ""
        val destStr = if (!destination.isNullOrBlank() && destination != "UNKNOWN") "\n\nRecipient/Destination: $destination" else ""
        return if (details.isNotEmpty()) {
            "$reason\n\nGoal: $goal$destStr$contextStr\n\nAction: ${action::class.simpleName}\n$details"
        } else {
            "$reason\n\nGoal: $goal$destStr$contextStr\n\nAction: ${action::class.simpleName}"
        }
    }
    private fun expectedDestinationForCommit(action: UiActionStep, screen: UiScreenState): String? =
        pendingExternalEffect
            ?.takeIf { it.targetPackage == screen.packageName && isMessagingCommitBoundary(action, screen) }
            ?.expectedDestination

    private fun isMessagingCommitBoundary(action: UiActionStep, screen: UiScreenState): Boolean {
        if (!DestinationResolver.isSupported(screen.packageName)) return false
        val target = when (action) {
            is UiActionStep.Tap -> action.target
            is UiActionStep.LongPress -> action.target
            else -> return false
        }
        val element = target.elementId?.let(screen::element)
        val evidence = listOfNotNull(
            element?.text,
            element?.contentDescription,
            element?.resourceId,
            target.textContains
        ).joinToString(" ").lowercase()
        return SEND_TARGET_TERMS.any(evidence::contains)
    }

    private fun confirmationContentHash(action: UiActionStep, screen: UiScreenState, goal: String): String =
        pendingExternalEffect
            ?.takeIf { isMessagingCommitBoundary(action, screen) }
            ?.contentHash
            ?: if (action is UiActionStep.ShareCapturedMedia) {
                pendingCapture?.let { AutomationMediaManager.contentSha256(context, it.uri) }
                    ?: hashContent(action.toJson().toString(), goal)
            } else {
                hashContent(action.toJson().toString(), goal)
            }

    private fun goalExplicitlyNamesDestination(goal: String, destination: String): Boolean {
        val normalizedGoal = goal.lowercase().replace(Regex("\\s+"), " ").trim()
        val normalizedDestination = destination.lowercase().replace(Regex("\\s+"), " ").trim()
        if (normalizedDestination.isBlank()) return false
        if (normalizedGoal.contains(normalizedDestination)) return true
        val destinationDigits = destination.filter(Char::isDigit)
        return destinationDigits.length >= 7 && goal.filter(Char::isDigit).contains(destinationDigits)
    }

    private fun hashContent(vararg inputs: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (input in inputs) {
            val bytes = input.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (trimmed.equals("done", ignoreCase = true)) {
            return """{"action":"done","summary":"Completed the requested UI workflow."}"""
        }
        val arrayStart = trimmed.indexOf('[')
        val arrayEnd = trimmed.lastIndexOf(']')
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (arrayStart >= 0 && arrayEnd > arrayStart && (start < 0 || arrayStart < start)) {
            return trimmed.substring(arrayStart, arrayEnd + 1)
        }
        require(start >= 0 && end > start) { "No JSON object found." }
        return trimmed.substring(start, end + 1)
    }

    private fun success(output: String) = ToolResult(CONTROL_UI_TOOL, output)
    private fun failure(output: String) = ToolResult(CONTROL_UI_TOOL, output, true)
    private fun trace(name: String, fields: Map<String, Any?> = emptyMap()) {
        activeTrace?.emit(name, fields)
    }

    private fun progress(phase: String, detail: String) {
        onProgress(phase, detail)
        activeSessionId?.let { sessionId ->
            AutomationSessionManager.progress(sessionId, phase, detail)
        }
    }

    private fun actionLabel(action: UiActionStep): String = when (action) {
        is UiActionStep.Tap -> "tap"
        is UiActionStep.Focus -> "focus"
        is UiActionStep.LongPress -> "long press"
        is UiActionStep.TypeText -> "type text"
        UiActionStep.PressBack -> "press back"
        UiActionStep.PressHome -> "press home"
        is UiActionStep.Scroll -> "scroll ${action.direction.name.lowercase()}"
        UiActionStep.PressRecents -> "press recents"
        UiActionStep.OpenNotifications -> "open notifications"
        UiActionStep.OpenQuickSettings -> "open quick settings"
        is UiActionStep.Wait -> "wait"
        is UiActionStep.Assert -> "assert"
        is UiActionStep.AskUser -> "ask user"
        is UiActionStep.Done -> "done"
        is UiActionStep.OpenApp -> "open app ${action.packageName}"
        is UiActionStep.OpenSettingsPage -> "open settings ${action.page.name.lowercase()}"
        is UiActionStep.OpenUrl -> "open url"
        is UiActionStep.ShareText -> "share text"
        is UiActionStep.OpenCamera -> "open camera"
        is UiActionStep.ShareCapturedMedia -> "share captured media"
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    companion object {
        const val CONTROL_UI_TOOL = "control_ui"
        val sessionMutex = Mutex()
        private const val MAX_PROMPT_ELEMENTS = 32
        private const val MAX_LOCAL_PROMPT_ELEMENTS = 12
        private const val PLANNER_TIMEOUT_MS = 45_000L
        private const val INITIAL_OBSERVATION_DELAY_MS = 75L
        private const val INITIAL_OBSERVATION_TIMEOUT_MS = 1_500L
        private const val INITIAL_EVENT_WAIT_SLICE_MS = 250L
        private const val INITIAL_RETRY_DELAY_MS = 40L

        private val UI_DECISION_TOOLS = listOf(
            LlmToolDefinition(
                name = "ui_act",
                description = "Choose exactly one next Android accessibility action from the supplied screen state.",
                parametersJson = """
                    {
                      "type":"object",
                      "properties":{
                        "op":{"type":"string","enum":["tap","long_press","type_text","scroll","press_back","press_home","press_recents","open_notifications","open_quick_settings","open_app","open_settings_page","open_url","open_camera","wait","share_text","share_captured_media"]},
                        "target":{"type":"string","description":"A supplied pN target ID; required for tap, long_press, and type_text."},
                        "expect":{"type":"string","enum":["surface_change","mutation","content_appear","no_change"]},
                        "text":{"type":"string"},
                        "direction":{"type":"string","enum":["up","down","left","right"]},
                        "package_name":{"type":"string"},
                        "page":{"type":"string"},
                        "url":{"type":"string"},
                        "mode":{"type":"string"},
                        "facing":{"type":"string"},
                        "ms":{"type":"integer"},
                        "target_package":{"type":"string"},
                        "expected_destination":{"type":"string"}
                      },
                      "required":["op"],
                      "additionalProperties":false
                    }
                """.trimIndent()
            ),
            LlmToolDefinition(
                name = "ui_ask",
                description = "Ask the user one question only when the target or required information is ambiguous.",
                parametersJson = """{"type":"object","properties":{"question":{"type":"string"}},"required":["question"],"additionalProperties":false}"""
            ),
            LlmToolDefinition(
                name = "ui_finish",
                description = "Finish only when current screen state or successful action history proves the goal is complete.",
                parametersJson = """{"type":"object","properties":{"outcome":{"type":"string"}},"required":["outcome"],"additionalProperties":false}"""
            )
        )

        private val JSON_RETRY_SYSTEM_PROMPT = """
            Return exactly one V3 JSON object and no other text.
            Act: {"v":3,"kind":"act","op":"tap","target":"p0","expect":"surface_change"}
            Ask: {"v":3,"kind":"ask","question":"Which item should I choose?"}
            Finish: {"v":3,"kind":"finish","outcome":"The requested state is verified."}
            Put action inputs in "args", for example:
            {"v":3,"kind":"act","op":"type_text","target":"p1","args":{"text":"hello"},"expect":"mutation"}
            Allowed ops: tap, long_press, type_text, scroll, press_back, press_home, press_recents,
            open_notifications, open_quick_settings, open_app, open_settings_page, open_url,
            open_camera, wait, share_text, share_captured_media.
            Required args: scroll direction; open_app package_name; open_settings_page page and optional package_name;
            open_url url; open_camera mode and facing; wait ms; share_text text and optional target_package/expected_destination;
            share_captured_media target_package and expected_destination.
            Use only supplied pN target IDs and exact supplied package names. Never use Markdown.
        """.trimIndent()

        private val PLANNER_SYSTEM_PROMPT = """
            Choose exactly one next Android UI decision. Return one V3 JSON object only, without Markdown.
            Act: {"v":3,"kind":"act","op":"tap","target":"p0","expect":"surface_change"}
            Ask: {"v":3,"kind":"ask","question":"Which item should I choose?"}
            Finish: {"v":3,"kind":"finish","outcome":"The requested state is verified."}
            Put action inputs in "args":
            {"v":3,"kind":"act","op":"type_text","target":"p1","args":{"text":"hello"},"expect":"mutation"}
            {"v":3,"kind":"act","op":"scroll","target":"p2","args":{"direction":"down"},"expect":"mutation"}
            {"v":3,"kind":"act","op":"open_app","args":{"package_name":"com.example"},"expect":"surface_change"}
            Allowed ops: tap, long_press, type_text, scroll, press_back, press_home, press_recents,
            open_notifications, open_quick_settings, open_app, open_settings_page, open_url,
            open_camera, wait, share_text, share_captured_media.
            Required args: scroll direction; open_app package_name; open_settings_page page and optional package_name;
            open_url url; open_camera mode and facing; wait ms; share_text text and optional target_package/expected_destination;
            share_captured_media target_package and expected_destination.
            Use IDs and package names exactly as supplied. Never invent targets, destinations, or completion.
            Do not target passwords or enter secrets, OTPs, payment data, or authentication data.
            Ask when the target is ambiguous or information is missing. Finish only when current state or successful history proves completion.
            Never select a messaging destination listed in ambiguous_targets. Trusted code handles confirmation and verification.
            Never plan payments, purchases, account deletion, factory reset, permission escalation, or unknown APK installation.
        """.trimIndent()
    }

    private fun getTargetElementId(action: UiActionStep): String? = when(action) {
        is UiActionStep.Tap -> action.target.elementId
        is UiActionStep.Focus -> action.target.elementId
        is UiActionStep.LongPress -> action.target.elementId
        is UiActionStep.TypeText -> action.target?.elementId
        is UiActionStep.Scroll -> action.target?.elementId
        is UiActionStep.OpenApp,
        is UiActionStep.OpenSettingsPage,
        is UiActionStep.OpenUrl,
        is UiActionStep.ShareText,
        is UiActionStep.OpenCamera,
        is UiActionStep.ShareCapturedMedia,
        is UiActionStep.PressBack,
        is UiActionStep.PressHome,
        is UiActionStep.PressRecents,
        is UiActionStep.OpenNotifications,
        is UiActionStep.OpenQuickSettings,
        is UiActionStep.Wait,
        is UiActionStep.AskUser,
        is UiActionStep.Done,
        is UiActionStep.Assert -> null
    }

    private fun getTargetLabel(action: UiActionStep, screen: UiScreenState): String? {
        val id = getTargetElementId(action) ?: return null
        return screen.element(id)?.let { screen.plannerLabel(it) }
    }

    private val SEND_TARGET_TERMS = setOf("send", "send_message", "send button", "send_button")
}
