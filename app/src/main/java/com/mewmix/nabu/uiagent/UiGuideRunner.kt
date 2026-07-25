package com.mewmix.nabu.uiagent

import android.app.KeyguardManager
import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mewmix.nabu.accessibility.AccessibilityToolHandler
import com.mewmix.nabu.accessibility.NabuAccessibilityService
import com.mewmix.nabu.accessibility.ScreenSemanticDescriber
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

sealed interface GuideDecision {
    data class Direct(
        val target: PlannerElementId,
        val instruction: String
    ) : GuideDecision

    data class Finish(val summary: String) : GuideDecision
    data class Ask(val question: String) : GuideDecision
}

object GuideDecisionDecoder {
    fun decode(raw: String): GuideDecision {
        val root = JsonParser.parseString(raw).asJsonObject
        requireOnlyKeys(root, "v", "kind", "target", "instruction", "summary", "question")
        require(root.requiredInt("v") == 1) { "Unsupported guide decision version." }
        return when (root.requiredString("kind")) {
            "direct" -> {
                requireOnlyKeys(root, "v", "kind", "target", "instruction")
                val target = root.requiredString("target")
                require(target.matches(Regex("""(?:p\d+|e_[a-fA-F0-9]+)"""))) {
                    "Guide target must be a supplied planner element ID."
                }
                val instruction = root.requiredString("instruction")
                require(instruction.length <= 500) { "Guide instruction is too long." }
                GuideDecision.Direct(PlannerElementId(target), instruction)
            }
            "finish" -> {
                requireOnlyKeys(root, "v", "kind", "summary")
                GuideDecision.Finish(root.requiredString("summary"))
            }
            "ask" -> {
                requireOnlyKeys(root, "v", "kind", "question")
                GuideDecision.Ask(root.requiredString("question"))
            }
            else -> error("Unknown guide decision kind.")
        }
    }

    private fun requireOnlyKeys(json: JsonObject, vararg allowed: String) {
        val extras = json.keySet() - allowed.toSet()
        require(extras.isEmpty()) { "Unknown guide decision fields: ${extras.joinToString()}." }
    }

    private fun JsonObject.requiredString(name: String): String {
        val value = get(name)?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()
        require(value.isNotEmpty()) { "Missing or blank '$name'." }
        return value
    }

    private fun JsonObject.requiredInt(name: String): Int =
        get(name)?.takeUnless { it.isJsonNull }?.asInt
            ?: error("Missing '$name'.")
}

class UiGuideRunner(
    private val context: Context,
    private val backend: LlmBackend,
    private val onProgress: (phase: String, detail: String) -> Unit = { _, _ -> },
    private val logger: (String) -> Unit = {}
) {
    suspend fun run(goal: String): ToolResult {
        if (goal.isBlank()) return failure("Guide goal is blank.")
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isDeviceLocked == true) {
            return failure("Guide requires the device to be awake and unlocked.")
        }
        val service = NabuAccessibilityService.instance
            ?: return failure("Nabu Accessibility Service is not enabled.")

        onProgress("Observe", "Reading the current screen")
        val snapshot = service.forceCaptureSnapshot()
            ?: return failure("Unable to read the current screen.")
        val screen = UiTreeIndexer.build(snapshot)
        val targets = screen.plannerElements(MAX_TARGETS)
        if (targets.isEmpty()) {
            return success(
                "I cannot find an interactive accessibility target on this screen. " +
                    ScreenSemanticDescriber.describe(screen, snapshot.windowTitle)
            )
        }

        onProgress("Guide", "Choosing the next control")
        val raw = infer(buildPrompt(goal, screen, snapshot.windowTitle))
            ?: return failure("Guide planning timed out.")
        val decision = runCatching { GuideDecisionDecoder.decode(raw.trim()) }
            .onFailure { logger("Guide decision rejected: ${it.message}") }
            .getOrElse { return failure("Guide returned an invalid decision: ${it.message}") }

        return when (decision) {
            is GuideDecision.Finish -> success(decision.summary)
            is GuideDecision.Ask -> success(decision.question)
            is GuideDecision.Direct -> {
                val element = screen.element(decision.target.id)
                    ?: return failure("Guide selected a target that is no longer available.")
                if (!element.visible || !element.enabled) {
                    return failure("Guide selected a target that is not available.")
                }
                val selector = mapOf(
                    "tree_path" to element.treePath,
                    "resource_id" to element.resourceId.orEmpty(),
                    "text" to element.text.orEmpty(),
                    "content_desc" to element.contentDescription.orEmpty(),
                    "class" to element.className.orEmpty()
                )
                val arguments = linkedMapOf<String, Any>(
                    "observation_id" to snapshot.id,
                    "selector" to selector
                )
                element.bounds?.let { arguments["fallback_bounds"] = it.toList() }
                val result = AccessibilityToolHandler.execute(
                    context,
                    ToolCall("ui_focus", arguments)
                ) ?: return failure("Accessibility focus is unavailable.")
                if (result.isError) return result.copy(toolName = TOOL_NAME)

                val label = screen.plannerLabel(element)
                    ?: element.resourceId?.substringAfterLast('/')
                    ?: "the highlighted control"
                success("${decision.instruction}\nFocused: $label.")
            }
        }
    }

    private suspend fun infer(userPrompt: String): String? {
        val completion = CompletableDeferred<String>()
        val completed = AtomicBoolean(false)
        val output = StringBuilder()
        backend.sendMessage(
            listOf(
                LlmMessage("system", SYSTEM_PROMPT),
                LlmMessage("user", userPrompt)
            )
        ) { partial, done ->
            if (partial.isNotEmpty()) output.append(partial)
            if (done && completed.compareAndSet(false, true)) {
                completion.complete(output.toString())
            }
        }
        return withTimeoutOrNull(PLANNER_TIMEOUT_MS) { completion.await() }
    }

    private fun buildPrompt(goal: String, screen: UiScreenState, windowTitle: String): String = buildString {
        append("goal=").append(goal).append('\n')
        append("surface=").append(windowTitle.ifBlank { screen.packageName.orEmpty() }).append('\n')
        append("targets:\n")
        screen.plannerElements(MAX_TARGETS).forEachIndexed { index, element ->
            append("p").append(index).append('|')
            append(role(element)).append('|')
            append(screen.plannerLabel(element) ?: element.resourceId ?: "unlabeled")
            if (element.focused) append("|focused")
            if (element.checkable) append(if (element.checked) "|on" else "|off")
            append('\n')
        }
    }

    private fun role(element: UiElement): String = when {
        element.editable -> "text_field"
        element.checkable -> "toggle"
        element.scrollable -> "scroll_area"
        else -> "button"
    }

    private fun success(output: String) = ToolResult(TOOL_NAME, output)
    private fun failure(output: String) = ToolResult(TOOL_NAME, output, true)

    companion object {
        const val TOOL_NAME = "guide_ui"
        private const val MAX_TARGETS = 24
        private const val PLANNER_TIMEOUT_MS = 15_000L
        private val SYSTEM_PROMPT = """
            Choose the single next control the user should operate. You guide but never operate controls.
            Return exactly one JSON object and no Markdown.
            To direct the user:
            {"v":1,"kind":"direct","target":"p0","instruction":"Double-tap Settings."}
            If the goal is already complete:
            {"v":1,"kind":"finish","summary":"You are done."}
            If essential information is missing:
            {"v":1,"kind":"ask","question":"Which account should you choose?"}
            Use only a supplied target ID. Do not invent controls. Keep the instruction brief and actionable.
        """.trimIndent()
    }
}
