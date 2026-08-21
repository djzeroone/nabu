package com.mewmix.nabu.uiagent

import android.content.Context
import android.content.Intent
import com.mewmix.nabu.ChatActivity
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmBackendFactory
import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelManager
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.OAuthRemoteModels
import com.mewmix.nabu.data.ConversationRepository
import com.mewmix.nabu.tools.ToolResult
import com.mewmix.nabu.utils.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

object ActionRequestDispatcher {
    private const val TAG = "ActionRequestDispatcher"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val dispatchMutex = Mutex()
    private val lock = Any()

    private val _activeSession = MutableStateFlow<ActionSession?>(null)
    val activeSession = _activeSession.asStateFlow()

    private var activeJob: Job? = null
    private var cachedBackend: Pair<String, LlmBackend>? = null

    fun submitRequest(
        context: Context,
        request: String,
        mode: ActionSessionMode = ActionSessionMode.SINGLE_TURN,
        preferredModelId: String? = null,
        requestConfirmation: (suspend (String) -> Boolean)? = null,
        onStep: ((ActionStepRecord) -> Unit)? = null,
        onComplete: ((ToolResult) -> Unit)? = null
    ): ActionSession {
        val requestReceivedMs = System.currentTimeMillis()
        val normalized = request.trim()
        val session = synchronized(lock) {
            activeJob?.cancel()
            val newSession = ActionSession(
                id = UUID.randomUUID().toString(),
                mode = mode,
                status = ActionSessionStatus.PLANNING,
                originalGoal = normalized,
                currentGoal = normalized,
                metrics = ActionSessionMetrics(
                    requestReceivedMs = requestReceivedMs,
                    sessionCreatedMs = System.currentTimeMillis()
                )
            )
            _activeSession.value = newSession
            newSession
        }

        scope.launch {
            dispatchMutex.withLock {
                synchronized(lock) {
                    activeJob = coroutineContext[Job]
                }
                val result = executeSessionTurn(
                    context = context.applicationContext,
                    session = session,
                    goal = normalized,
                    preferredModelId = preferredModelId,
                    requestConfirmation = requestConfirmation,
                    onStep = onStep
                )
                synchronized(lock) {
                    val current = _activeSession.value ?: session
                    if (current.id == session.id) {
                        val endMs = System.currentTimeMillis()
                        val updatedMetrics = current.metrics.copy(
                            totalSessionLatencyMs = endMs - current.createdAtMs
                        )
                        val updatedSession = current.copy(
                            status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED,
                            lastVerificationResult = result.output,
                            metrics = updatedMetrics,
                            updatedAtMs = endMs
                        )
                        _activeSession.value = updatedSession
                    }
                }
                onComplete?.invoke(result)
            }
        }

        return session
    }

    fun submitFollowUp(
        context: Context,
        sessionId: String,
        followUpRequest: String,
        preferredModelId: String? = null,
        requestConfirmation: (suspend (String) -> Boolean)? = null,
        onStep: ((ActionStepRecord) -> Unit)? = null,
        onComplete: ((ToolResult) -> Unit)? = null
    ): ActionSession? {
        val normalized = followUpRequest.trim()
        val session = synchronized(lock) {
            val current = _activeSession.value ?: return null
            if (current.id != sessionId) return null
            activeJob?.cancel()
            val updated = current.copy(
                currentGoal = normalized,
                status = ActionSessionStatus.PLANNING,
                updatedAtMs = System.currentTimeMillis()
            )
            _activeSession.value = updated
            updated
        }

        scope.launch {
            dispatchMutex.withLock {
                synchronized(lock) {
                    activeJob = coroutineContext[Job]
                }
                val result = executeSessionTurn(
                    context = context.applicationContext,
                    session = session,
                    goal = normalized,
                    preferredModelId = preferredModelId,
                    requestConfirmation = requestConfirmation,
                    onStep = onStep
                )
                synchronized(lock) {
                    val current = _activeSession.value ?: session
                    if (current.id == sessionId) {
                        val endMs = System.currentTimeMillis()
                        val updated = current.copy(
                            status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED,
                            lastVerificationResult = result.output,
                            updatedAtMs = endMs
                        )
                        _activeSession.value = updated
                    }
                }
                onComplete?.invoke(result)
            }
        }

        return session
    }

    fun cancelSession(sessionId: String) {
        synchronized(lock) {
            val current = _activeSession.value ?: return
            if (current.id == sessionId) {
                activeJob?.cancel()
                activeJob = null
                val updated = current.copy(
                    status = ActionSessionStatus.CANCELLED,
                    updatedAtMs = System.currentTimeMillis()
                )
                _activeSession.value = updated
                AutomationSessionManager.cancelActive()
            }
        }
    }

    fun clearSession(sessionId: String) {
        synchronized(lock) {
            if (_activeSession.value?.id == sessionId) {
                activeJob?.cancel()
                activeJob = null
                _activeSession.value = null
            }
        }
    }

    fun handoffToChat(context: Context, sessionId: String) {
        val session = synchronized(lock) {
            val current = _activeSession.value ?: return
            if (current.id != sessionId) return
            // If currently running, suspend execution safely
            if (current.status in listOf(ActionSessionStatus.PLANNING, ActionSessionStatus.OBSERVING, ActionSessionStatus.EXECUTING, ActionSessionStatus.VERIFYING)) {
                AutomationSessionManager.suspendActive("Handoff to Chat screen requested by user.")
            }
            activeJob?.cancel()
            activeJob = null
            val updated = current.copy(
                status = ActionSessionStatus.IDLE,
                mode = ActionSessionMode.HANDOFF_TO_CHAT,
                updatedAtMs = System.currentTimeMillis()
            )
            _activeSession.value = updated
            updated
        }

        val intent = Intent(context, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("extra_handoff_session_id", session.id)
            putExtra(ChatActivity.EXTRA_INITIAL_PROMPT, session.currentGoal)
        }
        context.startActivity(intent)
    }

    private suspend fun executeSessionTurn(
        context: Context,
        session: ActionSession,
        goal: String,
        preferredModelId: String?,
        requestConfirmation: (suspend (String) -> Boolean)?,
        onStep: ((ActionStepRecord) -> Unit)?
    ): ToolResult {
        val backend = resolveOrInitBackend(context, preferredModelId)
            ?: return ToolResult(
                toolName = UiAutomationOrchestrator.CONTROL_UI_TOOL,
                output = "No LLM model is downloaded or available for action execution.",
                isError = true
            )

        val turn = ActionConversationTurn(
            userRequest = goal,
            timestampMs = System.currentTimeMillis()
        )

        val orchestrator = UiAutomationOrchestrator(
            context = context,
            backend = backend,
            requestConfirmation = requestConfirmation ?: { prompt ->
                // If no confirmation requester is provided (e.g. non-interactive background),
                // reject potentially destructive unconfirmed actions safely.
                DebugLogger.log("ActionRequestDispatcher: confirmation requested without interactive handler: $prompt")
                false
            },
            budget = AutomationBudget(maxExecutedActions = 14),
            isScheduled = false,
            priorTurns = session.turns,
            onProgress = { phase, detail ->
                synchronized(lock) {
                    val current = _activeSession.value ?: session
                    val newStatus = when (phase.lowercase()) {
                        "observe" -> ActionSessionStatus.OBSERVING
                        "plan", "planning" -> ActionSessionStatus.PLANNING
                        "execute", "executing", "navigate" -> ActionSessionStatus.EXECUTING
                        "verify", "transition" -> ActionSessionStatus.VERIFYING
                        else -> current.status
                    }
                    val updated = current.copy(
                        status = newStatus,
                        pendingObjective = detail,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    _activeSession.value = updated
                }
            },
            onStepRecord = { stepRecord ->
                synchronized(lock) {
                    val current = _activeSession.value ?: session
                    val updatedSteps = current.stepHistory + stepRecord
                    val updated = current.copy(
                        stepHistory = updatedSteps,
                        lastObservedPackage = stepRecord.resultPackage ?: stepRecord.sourcePackage,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    _activeSession.value = updated
                }
                onStep?.invoke(stepRecord)
            },
            logger = { line ->
                DebugLogger.log("ActionSession[${session.id.take(8)}]: $line")
            }
        )

        val result = orchestrator.run(goal)
        val finalTurn = turn.copy(
            assistantResponse = result.output,
            stepRecords = session.stepHistory,
            isComplete = true,
            isError = result.isError
        )
        synchronized(lock) {
            val current = _activeSession.value ?: session
            val updatedTurns = current.turns + finalTurn
            val updated = current.copy(
                turns = updatedTurns,
                lastVerificationResult = result.output,
                updatedAtMs = System.currentTimeMillis()
            )
            _activeSession.value = updated
        }
        return result
    }

    private suspend fun resolveOrInitBackend(context: Context, preferredModelId: String?): LlmBackend? = withContext(Dispatchers.IO) {
        val modelManager = ModelManager(context)
        val downloaded = modelManager.models.filter { it.isDownloaded && it.type == ModelType.LLM }
        val remote = OAuthRemoteModels.connectedModels(context)
        val available = (downloaded + remote).distinctBy { it.id }
        if (available.isEmpty()) return@withContext null

        val selectedModel = if (!preferredModelId.isNullOrBlank()) {
            available.firstOrNull { it.id == preferredModelId || it.id.contains(preferredModelId, ignoreCase = true) }
        } else {
            // Prioritize fast on-device action models first
            available.firstOrNull { it.isDownloaded && it.type == ModelType.LLM && (
                it.id.contains("gemma-4-E2B", ignoreCase = true) ||
                it.id.contains("qwen", ignoreCase = true) ||
                it.id.contains("2b", ignoreCase = true) ||
                it.id.contains("3b", ignoreCase = true)
            ) } ?: preferredRecentModel(context, available) ?: available.first()
        } ?: return@withContext null

        synchronized(lock) {
            cachedBackend?.let { (id, backend) ->
                if (id == selectedModel.id) {
                    return@withContext backend
                } else {
                    runCatching { backend.close() }
                    cachedBackend = null
                }
            }
        }

        DebugLogger.log("ActionRequestDispatcher: initializing action model ${selectedModel.id}")
        val created = LlmBackendFactory.create(
            context = context,
            modelId = selectedModel.id,
            initializeSynchronously = true
        ) ?: return@withContext null

        synchronized(lock) {
            cachedBackend = selectedModel.id to created.backend
        }
        created.backend
    }

    private fun preferredRecentModel(context: Context, available: List<Model>): Model? {
        val modelsById = available.associateBy { it.id }
        val normalizedModelsById = available.associateBy { OAuthRemoteModels.normalizeModelId(it.id) }
        ConversationRepository.getConversationSummaries(context)
            .sortedByDescending { it.updatedAt }
            .forEach { summary ->
                val modelId = summary.modelId ?: return@forEach
                modelsById[modelId]?.let { return it }
                normalizedModelsById[OAuthRemoteModels.normalizeModelId(modelId)]?.let { return it }
            }
        return null
    }
}
