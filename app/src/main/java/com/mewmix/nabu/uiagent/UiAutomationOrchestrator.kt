package com.mewmix.nabu.uiagent

import android.content.Context
import android.graphics.BitmapFactory
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.chat.LiteRtLmBackend
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
import com.mewmix.nabu.actions.DeviceAction
import java.util.UUID

class UiAutomationOrchestrator(
    private val context: Context,
    private val backend: LlmBackend,
    private val requestConfirmation: suspend (String) -> Boolean,
    private val budget: AutomationBudget = AutomationBudget(),
    private val isScheduled: Boolean = false,
    private val onProgress: (phase: String, detail: String) -> Unit = { _, _ -> },
    private val onModelOutput: (String) -> Unit = {},
    private val logger: (String) -> Unit = {}
) {
    private data class Observation(
        val bridgeObservationId: String,
        val screen: UiScreenState,
        val screenshotPath: String?
    )

    private val pendingArtifactPaths = linkedSetOf<String>()

    suspend fun run(goal: String): ToolResult {
        if (!sessionMutex.tryLock()) {
            return failure("Automation session is already running. Please wait or cancel the existing session.")
        }
        val sessionId = UUID.randomUUID().toString()
        return try {
            runWithCleanup(goal, sessionId)
        } finally {
            withContext(NonCancellable) {
                cleanupArtifacts(pendingArtifactPaths.toList())
                sessionMutex.unlock()
            }
        }
    }

    private suspend fun runWithCleanup(goal: String, sessionId: String): ToolResult {
        if (goal.isBlank()) return failure("UI automation goal is blank.")
        onProgress("Observe", "Capturing the active window and accessibility tree")
        delay(INITIAL_OBSERVATION_DELAY_MS)
        var observation = observe() ?: return failure("Unable to observe the current UI through the Accessibility Service.")
        
        val startTimeMs = System.currentTimeMillis()
        var unchangedCount = 0
        var cumulativeWaitMs = 0L
        val actionHistory = mutableListOf<UiActionHistoryEntry>()
        val successfulFingerprints = mutableSetOf<String>()

        repeat(budget.maxExecutedActions) { actionIndex ->
            if (System.currentTimeMillis() - startTimeMs > budget.maxWallClockDurationMs) {
                return failure("UI automation reached wall-clock time limit.")
            }
            onProgress("Plan ${actionIndex + 1}", "Choosing the next UI action")
            
            var rawPlan = plan(goal, observation, actionHistory)
                ?: return failure("The UI planner returned no usable plan.")
            var parsedPlan = parsePlan(rawPlan, goal, observation)
            
            var retries = 0
            while (parsedPlan.isFailure && retries < budget.maxPlannerRetriesPerObservation) {
                val errorMsg = parsedPlan.exceptionOrNull()?.message
                logger("UiAutomation planner parse failed: $errorMsg; output=${rawPlan.take(500)}")
                onProgress("Plan ${actionIndex + 1}", "Retrying with a strict JSON-only prompt")
                rawPlan = plan(goal, observation, actionHistory, jsonRetry = true)
                    ?: return failure("The UI planner returned no usable JSON after retry.")
                parsedPlan = parsePlan(rawPlan, goal, observation)
                retries++
            }
            
            val actionPlan = parsedPlan.getOrElse { error ->
                logger("UiAutomation planner retry parse failed: ${error.message}; output=${rawPlan.take(500)}")
                return failure("The UI planner returned invalid action JSON after retry: ${error.message}")
            }
            
            val action = actionPlan.steps.first { it !is UiActionStep.Assert }
            val fingerprint = "${actionLabel(action)}|${observation.screen.screenId}"
            val contentHash = action.hashCode().toString()
            
            when (val decision = UiActionValidator.validate(actionPlan, observation.screen)) {
                UiPlanDecision.Allow -> Unit
                is UiPlanDecision.Invalid -> {
                    logger("UiAutomation plan rejected: ${decision.reason}")
                    return failure(decision.reason)
                }
                is UiPlanDecision.Block -> {
                    logger("UiAutomation plan blocked: ${decision.reason}")
                    return failure(decision.reason)
                }
                is UiPlanDecision.RequireConfirmation -> {
                    val grantId = ConfirmationManager.requestConfirmation(
                        sessionId = sessionId,
                        screenId = observation.screen.screenId,
                        actionFingerprint = fingerprint,
                        destination = null,
                        contentHash = contentHash
                    )
                    
                    if (!requestConfirmation(describeConfirmation(action, decision.reason))) {
                        return failure("User denied UI action confirmation.")
                    }
                    
                    if (!ConfirmationManager.consumeConfirmation(grantId, sessionId, observation.screen.screenId, fingerprint, null, contentHash)) {
                        return failure("Confirmation expired or invalid.")
                    }
                }
            }
            
            if (successfulFingerprints.contains(fingerprint) && unchangedCount > 0) {
                logger("UiAutomation repetion detected: fingerprint=$fingerprint")
                return failure("Planner proposed a repetitive action that already succeeded on this exact screen without changing it.")
            }

            onProgress("Decision ${actionIndex + 1}", "Planner selected ${actionLabel(action)}")
            when (action) {
                is UiActionStep.Done -> return success(action.summary)
                is UiActionStep.AskUser -> return success("User input required: ${action.reason}")
                is UiActionStep.Wait -> {
                    val waitTime = action.milliseconds.coerceAtMost(budget.maxSingleWaitMs)
                    if (cumulativeWaitMs + waitTime > budget.maxCumulativeWaitMs) {
                        return failure("Wait budget exceeded.")
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
                            detail = "waited ${waitTime}ms"
                        )
                    )
                }
                else -> {
                    onProgress("Execute ${actionIndex + 1}", "Running ${actionLabel(action)}")
                    val result = execute(action, observation, sessionId)
                    if (result.isError) {
                        logger("UiAutomation execution failed action=${actionLabel(action)}: ${result.output}")
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
                                detail = result.output
                            )
                        )
                        return result
                    }
                }
            }

            onProgress("Verify ${actionIndex + 1}", "Observing the resulting screen")
            val next = observe() ?: return failure("UI action ran, but the resulting screen could not be observed.")
            val assertion = actionPlan.steps.filterIsInstance<UiActionStep.Assert>().lastOrNull()?.condition
            if (assertion != null && assertionMatches(assertion, next.screen)) {
                return success("Completed UI goal: $goal")
            }

            val changed = next.screen.screenId != observation.screen.screenId
            unchangedCount = if (changed) 0 else unchangedCount + 1
            
            if (action !is UiActionStep.Wait && action !is UiActionStep.Done && action !is UiActionStep.AskUser) {
                successfulFingerprints.add(fingerprint)
                actionHistory.add(
                    UiActionHistoryEntry(
                        index = actionIndex,
                        action = actionLabel(action),
                        targetElementId = getTargetElementId(action),
                        targetLabel = getTargetLabel(action, observation.screen),
                        sourceScreenId = observation.screen.screenId,
                        resultScreenId = next.screen.screenId,
                        outcome = Outcome.SUCCEEDED,
                        changedScreen = changed,
                        detail = null
                    )
                )
            }
            
            logger(
                "UiAutomation step=${actionIndex + 1} action=${action::class.simpleName} " +
                    "screen=${observation.screen.screenId}->${next.screen.screenId} unchanged=$unchangedCount"
            )
            if (unchangedCount >= budget.maxUnchangedObservations) {
                return failure("UI did not change after repeated actions; stopping automation.")
            }
            observation = next
        }
        return failure("UI action limit reached before the goal was verified.")
    }

    private suspend fun observe(): Observation? {
        val requestScreenshot = shouldAttachScreenshot(jsonRetry = false)
        val args = mapOf("request_screenshot" to requestScreenshot)
        val result = AccessibilityToolHandler.execute(context, ToolCall("observe_ui", args))
        if (result == null || result.isError) {
            logger("UiAutomation observe_ui failed: ${result?.output}")
            return null
        }
        return runCatching {
            val envelope = JSONObject(result.output)
            require(envelope.optInt("schema_version") == 2) { "Unsupported observation schema." }
            val returnedXmlPath = envelope.getString("xml_path")
            val returnedScreenshotPath = envelope.optString("screenshot_path").takeIf(String::isNotBlank)
            val artifactPaths = listOfNotNull(returnedXmlPath, returnedScreenshotPath)
            require(artifactPaths.all(::isGeneratedObservationPath)) {
                "Accessibility Service returned unexpected observation artifact paths."
            }
            pendingArtifactPaths.addAll(artifactPaths)
            val xmlResult = AccessibilityToolHandler.execute(
                context,
                ToolCall("read_ui_xml", mapOf("path" to returnedXmlPath))
            )
            require(xmlResult != null && !xmlResult.isError) { xmlResult?.output ?: "Unknown error" }
            val packageName = envelope.optString("package").takeIf(String::isNotBlank)
            val windowTitle = envelope.optString("window_title").takeIf(String::isNotBlank)
            Observation(
                bridgeObservationId = envelope.getString("observation_id"),
                screen = UiTreeIndexer.parse(xmlResult.output, packageName, windowTitle),
                screenshotPath = returnedScreenshotPath
            )
        }.onFailure {
            logger("UiAutomation observation parse failed: ${it.message}")
        }.getOrNull()
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
        UiActionPlanParser.parsePlannerOutput(
            rawJson = extractJson(rawPlan),
            knownGoal = goal,
            knownScreenId = observation.screen.screenId
        ).resolveElementReferences(observation.screen)
    }

    private suspend fun plan(
        goal: String,
        observation: Observation,
        history: List<UiActionHistoryEntry>,
        jsonRetry: Boolean = false
    ): String? {
        val userContent = buildPlannerInput(goal, observation.screen, history)
        val attachScreenshot = shouldAttachScreenshot(jsonRetry)
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
        val completion = CompletableDeferred<String?>()
        val completed = AtomicBoolean(false)
        val output = StringBuilder()
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
                    "elements=${observation.screen.plannerElements(MAX_PROMPT_ELEMENTS).size}: ${raw.take(2_000)}"
            )
        }
    }

    private fun shouldAttachScreenshot(jsonRetry: Boolean): Boolean {
        if (jsonRetry || !backend.supportsImageInput()) return false
        return backend !is LiteRtLmBackend && !backend.runtimeDescription().startsWith("LITERT-LM")
    }

    private fun isDeviceLocked(): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return keyguardManager?.isDeviceLocked == true
    }

    private suspend fun execute(action: UiActionStep, observation: Observation, sessionId: String): ToolResult {
        val policyContext = PolicyContext(
            isScheduled = isScheduled,
            isDeviceLocked = isDeviceLocked(),
            destinationProvenance = "planner",
            context = context
        )
        val policyDecision = AutomationIntentPolicy.evaluate(action, policyContext)
        when (policyDecision) {
            is IntentPolicyDecision.Block -> return failure("Action blocked by policy: ${policyDecision.reason}")
            is IntentPolicyDecision.RequireConfirmation -> {
                val fingerprint = "${actionLabel(action)}|${observation.screen.screenId}"
                val contentHash = action.hashCode().toString()
                val grantId = ConfirmationManager.requestConfirmation(
                    sessionId = sessionId,
                    screenId = observation.screen.screenId,
                    actionFingerprint = fingerprint,
                    destination = policyDecision.preview,
                    contentHash = contentHash
                )
                
                if (!requestConfirmation(describeConfirmation(action, policyDecision.reason))) {
                    return failure("User denied UI action confirmation.")
                }
                
                if (!ConfirmationManager.consumeConfirmation(grantId, sessionId, observation.screen.screenId, fingerprint, policyDecision.preview, contentHash)) {
                    return failure("Confirmation expired or invalid.")
                }
            }
            is IntentPolicyDecision.Allow -> Unit
        }

        val arguments = linkedMapOf<String, Any>("observation_id" to observation.bridgeObservationId)
        val toolName = when (action) {
            is UiActionStep.Tap -> {
                addTarget(arguments, action.target, observation.screen)
                "ui_tap"
            }
            is UiActionStep.LongPress -> {
                addTarget(arguments, action.target, observation.screen)
                "ui_long_press"
            }
            is UiActionStep.TypeText -> {
                action.target?.let { addTarget(arguments, it, observation.screen) }
                arguments["text"] = action.text
                "ui_set_text"
            }
            is UiActionStep.Scroll -> {
                action.target?.let { addTarget(arguments, it, observation.screen) }
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
            is UiActionStep.OpenApp -> {
                val result = DeviceAction.openApp(context, action.packageName, "")
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.OpenSettingsPage -> {
                val result = DeviceAction.openSettingsPage(context, action.page.name, null)
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.OpenUrl -> {
                val result = DeviceAction.openUrl(context, action.url)
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.ShareText -> {
                val result = DeviceAction.shareText(context, action.text, "")
                return if (result.isError) failure(result.message) else success(result.message)
            }
            is UiActionStep.OpenCamera -> {
                val result = if (action.mode == CameraMode.VIDEO) {
                    DeviceAction.recordVideo(context)
                } else {
                    DeviceAction.takePhoto(context)
                }
                return if (result.isError) failure(result.message) else success(result.message)
            }
            else -> return failure("Unsupported executable UI action.")
        }
        return AccessibilityToolHandler.execute(context, ToolCall(toolName, arguments))
            ?: failure("Unknown UI tool: $toolName")
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

    private fun buildPlannerInput(goal: String, screen: UiScreenState, history: List<UiActionHistoryEntry>): String {
        val elements = JsonArray()
        screen.plannerElements(MAX_PROMPT_ELEMENTS)
            .forEachIndexed { index, element ->
                elements.add(JsonObject().apply {
                    addProperty("id", "p$index")
                    screen.plannerLabel(element)?.let { addProperty("label", it) }
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
                })
            }
        return JsonObject().apply {
            addProperty("goal", goal)
            addProperty("screen_id", screen.screenId)
            addProperty("package", screen.packageName)
            addProperty("activity", screen.activityName)
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
                        addProperty("outcome", entry.outcome.name.lowercase())
                        addProperty("screen_changed", entry.changedScreen)
                    })
                }
                add("history", historyArray)
            }
            add("elements", elements)
        }.toString()
    }

    private fun describeConfirmation(action: UiActionStep, reason: String): String =
        "$reason\n\nAction: ${action::class.simpleName}"

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        require(start >= 0 && end > start) { "No JSON object found." }
        return trimmed.substring(start, end + 1)
    }

    private fun success(output: String) = ToolResult(CONTROL_UI_TOOL, output)
    private fun failure(output: String) = ToolResult(CONTROL_UI_TOOL, output, true)

    private fun actionLabel(action: UiActionStep): String = when (action) {
        is UiActionStep.Tap -> "tap"
        is UiActionStep.LongPress -> "long press"
        is UiActionStep.TypeText -> "type text"
        UiActionStep.PressBack -> "press back"
        UiActionStep.PressHome -> "press home"
        is UiActionStep.Scroll -> "scroll ${action.direction.name.lowercase()}"
        is UiActionStep.Wait -> "wait"
        is UiActionStep.Assert -> "assert"
        is UiActionStep.AskUser -> "ask user"
        is UiActionStep.Done -> "done"
        is UiActionStep.OpenApp -> "open app ${action.packageName}"
        is UiActionStep.OpenSettingsPage -> "open settings ${action.page.name.lowercase()}"
        is UiActionStep.OpenUrl -> "open url"
        is UiActionStep.ShareText -> "share text"
        is UiActionStep.OpenCamera -> "open camera"
    }

    companion object {
        const val CONTROL_UI_TOOL = "control_ui"
        val sessionMutex = Mutex()
        private const val MAX_PROMPT_ELEMENTS = 32
        private const val PLANNER_TIMEOUT_MS = 45_000L
        private const val INITIAL_OBSERVATION_DELAY_MS = 500L

        private val JSON_RETRY_SYSTEM_PROMPT = """
            Return exactly one JSON object and no other text:
            {
              "goal": "the user's goal",
              "screen_id": "the exact screen_id provided",
              "steps": [
                {
                  "action": "tap",
                  "target": {"element_id": "p0"}
                }
              ]
            }
            Allowed action values: tap, long_press, type_text, scroll, press_back, press_home, wait, ask_user, done.
            Copy goal and screen_id exactly. Use only a supplied element id. Never use Markdown.
        """.trimIndent()

        private val PLANNER_SYSTEM_PROMPT = """
            Plan one safe Android UI action. Return one JSON object only, with no Markdown.
            Required: exact supplied goal, exact screen_id, and steps containing exactly one action.
            Actions: tap, long_press, type_text, scroll, press_back, press_home, wait, ask_user, done.
            If the history of previous actions and the current screen indicate the goal is achieved, use the 'done' action.
            Targets use {"element_id":"p0"}; copy only an id supplied in elements.
            type_text requires exact text from the goal. scroll requires direction UP, DOWN, LEFT, or RIGHT.
            wait uses ms. ask_user uses reason. done uses summary.
            You may append one assert step with condition containing element_id, text_contains, or checked.

            Never plan payments, purchases, passwords, 2FA, account deletion, factory reset, permission escalation, or unknown APK installation.
        """.trimIndent()
    }

    private fun getTargetElementId(action: UiActionStep): String? = when(action) {
        is UiActionStep.Tap -> action.target.elementId
        is UiActionStep.LongPress -> action.target.elementId
        is UiActionStep.TypeText -> action.target?.elementId
        is UiActionStep.Scroll -> action.target?.elementId
        is UiActionStep.OpenApp,
        is UiActionStep.OpenSettingsPage,
        is UiActionStep.OpenUrl,
        is UiActionStep.ShareText,
        is UiActionStep.OpenCamera,
        is UiActionStep.PressBack,
        is UiActionStep.PressHome,
        is UiActionStep.Wait,
        is UiActionStep.AskUser,
        is UiActionStep.Done,
        is UiActionStep.Assert -> null
    }

    private fun getTargetLabel(action: UiActionStep, screen: UiScreenState): String? {
        val id = getTargetElementId(action) ?: return null
        return screen.element(id)?.let { screen.plannerLabel(it) }
    }
}
