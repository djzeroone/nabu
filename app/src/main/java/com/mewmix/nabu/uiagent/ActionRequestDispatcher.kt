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
import kotlinx.coroutines.withContext
import java.util.UUID

object ActionRequestDispatcher {
    private const val TAG = "ActionRequestDispatcher"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
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
                currentGoal = normalized
            ).apply {
                metrics.requestReceivedMs = requestReceivedMs
                metrics.sessionCreatedMs = System.currentTimeMillis()
            }
            _activeSession.value = newSession
            newSession
        }

        activeJob = scope.launch {
            val result = executeSessionTurn(context.applicationContext, session, normalized, onStep)
            synchronized(lock) {
                session.status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED
                session.updatedAtMs = System.currentTimeMillis()
                session.metrics.totalSessionLatencyMs = session.updatedAtMs - session.createdAtMs
                _activeSession.value = session
            }
            onComplete?.invoke(result)
        }

        return session
    }

    fun submitFollowUp(
        context: Context,
        sessionId: String,
        followUpRequest: String,
        onStep: ((ActionStepRecord) -> Unit)? = null,
        onComplete: ((ToolResult) -> Unit)? = null
    ): ActionSession? {
        val normalized = followUpRequest.trim()
        val session = synchronized(lock) {
            val current = _activeSession.value ?: return null
            if (current.id != sessionId) return null
            current.currentGoal = normalized
            current.status = ActionSessionStatus.PLANNING
            current.updatedAtMs = System.currentTimeMillis()
            current
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            val result = executeSessionTurn(context.applicationContext, session, normalized, onStep)
            synchronized(lock) {
                session.status = if (result.isError) ActionSessionStatus.FAILED else ActionSessionStatus.COMPLETED
                session.updatedAtMs = System.currentTimeMillis()
                _activeSession.value = session
            }
            onComplete?.invoke(result)
        }

        return session
    }

    fun cancelSession(sessionId: String) {
        synchronized(lock) {
            val current = _activeSession.value ?: return
            if (current.id == sessionId) {
                activeJob?.cancel()
                activeJob = null
                current.status = ActionSessionStatus.CANCELLED
                current.updatedAtMs = System.currentTimeMillis()
                _activeSession.value = current
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
            current.status = ActionSessionStatus.IDLE
            current
        }

        val intent = Intent(context, ChatActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("extra_handoff_session_id", session.id)
            putExtra("extra_initial_prompt", session.currentGoal)
        }
        context.startActivity(intent)
    }

    private suspend fun executeSessionTurn(
        context: Context,
        session: ActionSession,
        goal: String,
        onStep: ((ActionStepRecord) -> Unit)?
    ): ToolResult {
        val backend = resolveOrInitBackend(context)
            ?: return ToolResult(
                toolName = UiAutomationOrchestrator.CONTROL_UI_TOOL,
                output = "No LLM model is downloaded or available for action execution.",
                isError = true
            )

        val turn = ActionConversationTurn(
            userRequest = goal,
            timestampMs = System.currentTimeMillis()
        )

        var lastRecordedStep: ActionStepRecord? = null
        val orchestrator = UiAutomationOrchestrator(
            context = context,
            backend = backend,
            requestConfirmation = { true },
            budget = AutomationBudget(maxExecutedActions = 14),
            isScheduled = false,
            onProgress = { phase, detail ->
                synchronized(lock) {
                    session.status = when (phase.lowercase()) {
                        "observe" -> ActionSessionStatus.OBSERVING
                        "plan", "planning" -> ActionSessionStatus.PLANNING
                        "execute", "executing", "navigate" -> ActionSessionStatus.EXECUTING
                        "verify", "transition" -> ActionSessionStatus.VERIFYING
                        else -> session.status
                    }
                    session.pendingObjective = detail
                    session.updatedAtMs = System.currentTimeMillis()
                    _activeSession.value = session
                }
            },
            logger = { line ->
                DebugLogger.log("ActionSession[${session.id.take(8)}]: $line")
            }
        )

        val result = orchestrator.run(goal)
        val finalTurn = turn.copy(
            assistantResponse = result.output,
            isComplete = true,
            isError = result.isError
        )
        synchronized(lock) {
            session.turns.add(finalTurn)
            session.lastVerificationResult = result.output
            session.updatedAtMs = System.currentTimeMillis()
        }
        return result
    }

    private suspend fun resolveOrInitBackend(context: Context): LlmBackend? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            cachedBackend?.let { (id, backend) ->
                return@withContext backend
            }
        }

        val modelManager = ModelManager(context)
        val downloaded = modelManager.models.filter { it.isDownloaded && it.type == ModelType.LLM }
        val remote = OAuthRemoteModels.connectedModels(context)
        val available = (downloaded + remote).distinctBy { it.id }
        if (available.isEmpty()) return@withContext null

        val preferred = preferredRecentModel(context, available) ?: available.first()
        DebugLogger.log("ActionRequestDispatcher: initializing model ${preferred.id} for action session")
        val created = LlmBackendFactory.create(
            context = context,
            modelId = preferred.id,
            initializeSynchronously = true
        ) ?: return@withContext null

        synchronized(lock) {
            cachedBackend = preferred.id to created.backend
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
