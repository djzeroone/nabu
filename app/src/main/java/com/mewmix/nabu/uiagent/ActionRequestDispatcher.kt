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
import com.mewmix.nabu.utils.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
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
    private val ownership = ActionRequestOwnership()
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
        val (session, epoch) = synchronized(lock) {
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
            val epoch = ownership.begin(newSession.id)
            _activeSession.value = newSession
            newSession to epoch
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            dispatchMutex.withLock {
                if (!isOwner(session.id, epoch)) return@withLock
                val result = executeSessionTurn(
                    context = context.applicationContext,
                    session = session,
                    goal = normalized,
                    preferredModelId = preferredModelId,
                    requestConfirmation = requestConfirmation,
                    onStep = onStep,
                    epoch = epoch
                )
                val published = updateOwned(session.id, epoch) { current ->
                    val endMs = System.currentTimeMillis()
                    val updatedMetrics = current.metrics.copy(
                        totalSessionLatencyMs = endMs - current.createdAtMs
                    )
                    current.copy(
                        status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED,
                        lastVerificationResult = result.output,
                        metrics = updatedMetrics,
                        updatedAtMs = endMs
                    )
                }
                synchronized(lock) { if (isOwnerLocked(session.id, epoch)) activeJob = null }
                if (published) onComplete?.invoke(result)
            }
        }
        synchronized(lock) {
            if (isOwnerLocked(session.id, epoch)) activeJob = job else job.cancel()
        }
        job.start()

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
        val (session, epoch) = synchronized(lock) {
            val current = _activeSession.value ?: return null
            if (current.id != sessionId) return null
            activeJob?.cancel()
            val updated = current.copy(
                currentGoal = normalized,
                status = ActionSessionStatus.PLANNING,
                updatedAtMs = System.currentTimeMillis()
            )
            val epoch = ownership.begin(updated.id)
            _activeSession.value = updated
            updated to epoch
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            dispatchMutex.withLock {
                if (!isOwner(session.id, epoch)) return@withLock
                val result = executeSessionTurn(
                    context = context.applicationContext,
                    session = session,
                    goal = normalized,
                    preferredModelId = preferredModelId,
                    requestConfirmation = requestConfirmation,
                    onStep = onStep,
                    epoch = epoch
                )
                val published = updateOwned(sessionId, epoch) { current ->
                    val endMs = System.currentTimeMillis()
                    current.copy(
                        status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED,
                        lastVerificationResult = result.output,
                        updatedAtMs = endMs
                    )
                }
                synchronized(lock) { if (isOwnerLocked(session.id, epoch)) activeJob = null }
                if (published) onComplete?.invoke(result)
            }
        }
        synchronized(lock) {
            if (isOwnerLocked(session.id, epoch)) activeJob = job else job.cancel()
        }
        job.start()

        return session
    }

    fun cancelSession(sessionId: String) {
        synchronized(lock) {
            val current = _activeSession.value ?: return
            if (current.id == sessionId) {
                ownership.invalidate(sessionId)
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
                ownership.invalidate(sessionId)
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
            ownership.invalidate(sessionId)
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
        onStep: ((ActionStepRecord) -> Unit)?,
        epoch: Long
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
                updateOwned(session.id, epoch) { current ->
                    val newStatus = when (phase.lowercase()) {
                        "observe" -> ActionSessionStatus.OBSERVING
                        "plan", "planning" -> ActionSessionStatus.PLANNING
                        "execute", "executing", "navigate" -> ActionSessionStatus.EXECUTING
                        "verify", "transition" -> ActionSessionStatus.VERIFYING
                        else -> current.status
                    }
                    current.copy(
                        status = newStatus,
                        pendingObjective = detail,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
            },
            onStepRecord = { stepRecord ->
                val published = updateOwned(session.id, epoch) { current ->
                    val updatedSteps = current.stepHistory + stepRecord
                    current.copy(
                        stepHistory = updatedSteps,
                        lastObservedPackage = stepRecord.resultPackage ?: stepRecord.sourcePackage,
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
                if (published) onStep?.invoke(stepRecord)
            },
            externalSessionId = session.id,
            logger = { line ->
                DebugLogger.log("ActionSession[${session.id.take(8)}]: $line")
            }
        )

        val result = orchestrator.run(goal)
        updateOwned(session.id, epoch) { current ->
            val finalTurn = turn.copy(
                assistantResponse = result.output,
                stepRecords = current.stepHistory,
                isComplete = true,
                isError = result.isError
            )
            val updatedTurns = current.turns + finalTurn
            current.copy(
                turns = updatedTurns,
                lastVerificationResult = result.output,
                updatedAtMs = System.currentTimeMillis()
            )
        }
        return result
    }

    private fun isOwner(sessionId: String, epoch: Long): Boolean = synchronized(lock) {
        isOwnerLocked(sessionId, epoch)
    }

    private fun isOwnerLocked(sessionId: String, epoch: Long): Boolean =
        ownership.owns(sessionId, epoch) && _activeSession.value?.id == sessionId

    private fun updateOwned(
        sessionId: String,
        epoch: Long,
        transform: (ActionSession) -> ActionSession
    ): Boolean = synchronized(lock) {
        if (!isOwnerLocked(sessionId, epoch)) return@synchronized false
        val current = _activeSession.value ?: return@synchronized false
        _activeSession.value = transform(current)
        true
    }

    private suspend fun resolveOrInitBackend(context: Context, preferredModelId: String?): LlmBackend? = withContext(Dispatchers.IO) {
        val modelManager = ModelManager(context)
        val downloaded = modelManager.models.filter { it.isDownloaded && it.type == ModelType.LLM }
        val remote = OAuthRemoteModels.connectedModels(context)
        val available = (downloaded + remote).distinctBy { it.id }
        if (available.isEmpty()) return@withContext null

        val configuredModelId = preferredModelId?.takeIf(String::isNotBlank)
            ?: SettingsManager.getActionModelId(context)
        val selectedModel = if (!configuredModelId.isNullOrBlank()) {
            available.firstOrNull {
                it.id == configuredModelId || it.id.contains(configuredModelId, ignoreCase = true)
            }
        } else {
            // Prioritize fast on-device action models first
            available.firstOrNull { it.isDownloaded && it.type == ModelType.LLM && (
                it.id.contains("gemma-4-E2B", ignoreCase = true) ||
                it.id.contains("qwen", ignoreCase = true) ||
                it.id.contains("2b", ignoreCase = true) ||
                it.id.contains("3b", ignoreCase = true)
            ) } ?: preferredRecentModel(context, available) ?: available.first()
        } ?: run {
            DebugLogger.log("ActionRequestDispatcher: configured action model '$configuredModelId' is unavailable")
            return@withContext null
        }

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
