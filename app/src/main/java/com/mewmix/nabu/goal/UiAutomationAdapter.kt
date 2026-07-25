package com.mewmix.nabu.goal

import android.content.Context
import android.graphics.BitmapFactory
import com.mewmix.nabu.accessibility.NabuAccessibilityService
import com.mewmix.nabu.accessibility.UiSnapshotStore
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmImageInput
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision
import com.mewmix.nabu.uiagent.ConstrainedDecisionDecoder
import com.mewmix.nabu.uiagent.UiActionHistoryEntry
import com.mewmix.nabu.uiagent.UiTreeIndexer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class UiAutomationAdapter(
    private val context: Context,
    private val backend: LlmBackend,
    private val decoder: ConstrainedDecisionDecoder
) : SurfaceAdapter {

    override val capabilityId: CapabilityId = CapabilityId.UI_ACT

    // Track history per goal. In a real system, this might be bounded or pruned.
    private val goalHistory = mutableMapOf<String, MutableList<UiActionHistoryEntry>>()

    override suspend fun executeStep(objective: String): AgentDecision {
        val service = NabuAccessibilityService.instance
            ?: return AgentDecision.Finish("Error: NabuAccessibilityService is not connected.")

        // 1. Capture State
        service.forceCaptureSnapshot()
        val snapshot = UiSnapshotStore.currentSnapshot.value
            ?: return AgentDecision.Finish("Error: Could not capture UI snapshot.")

        val screenState = UiTreeIndexer.build(snapshot)

        // 2. Build Prompt (Simplified for now, in practice we migrate the full Orchestrator logic)
        val userContent = buildPrompt(objective, screenState)
        val conversation = listOf(
            LlmMessage(
                role = "system",
                content = """
                    Choose one next Android UI action. Return exactly one V3 JSON object and no Markdown.
                    Example: {"v":3,"kind":"act","op":"tap","target":"p0","expect":"surface_change"}
                    Finish only when verified: {"v":3,"kind":"finish","outcome":"Done."}
                """.trimIndent()
            ),
            LlmMessage(role = "user", content = userContent)
        )

        // 3. Inference
        val completion = CompletableDeferred<String?>()
        val completed = AtomicBoolean(false)
        val output = StringBuilder()
        
        backend.sendMessage(conversation) { partial, done ->
            if (partial.isNotEmpty()) {
                output.append(partial)
            }
            if (done && completed.compareAndSet(false, true)) {
                completion.complete(output.toString())
            }
        }

        val rawOutput = withTimeoutOrNull(30_000) { completion.await() }
            ?: return AgentDecision.Finish("Error: Inference timed out.")

        // 4. Decode
        return runCatching { decoder.decode(rawOutput) }
            .getOrElse { AgentDecision.Finish("Error: Invalid UI decision: ${it.message}") }
    }

    private fun buildPrompt(objective: String, screen: com.mewmix.nabu.uiagent.UiScreenState): String {
        val sb = StringBuilder()
        sb.append("Objective: $objective\n\n")
        sb.append("Current Screen:\n")
        screen.plannerElements(100).forEachIndexed { index, element ->
            val label = screen.plannerLabel(element) ?: element.resourceId ?: "unknown"
            sb.append("p$index | $label\n")
        }
        return sb.toString()
    }
}
