package com.mewmix.nabu.goal

import android.content.Context
import com.mewmix.nabu.accessibility.NabuAccessibilityService
import com.mewmix.nabu.accessibility.UiSnapshotStore
import com.mewmix.nabu.chat.LlmBackend
import com.mewmix.nabu.chat.LlmMessage
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision
import com.mewmix.nabu.uiagent.ConstrainedDecisionDecoder
import com.mewmix.nabu.uiagent.GuideDecision
import com.mewmix.nabu.uiagent.GuideDecisionDecoder
import com.mewmix.nabu.uiagent.Operation
import com.mewmix.nabu.uiagent.UiTreeIndexer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class UiGuideAdapter(
    private val context: Context,
    private val backend: LlmBackend,
    private val decoder: ConstrainedDecisionDecoder
) : SurfaceAdapter {

    override val capabilityId: CapabilityId = CapabilityId.UI_GUIDE

    override suspend fun executeStep(objective: String): AgentDecision {
        val service = NabuAccessibilityService.instance
            ?: return AgentDecision.Finish("Error: NabuAccessibilityService is not connected.")

        service.forceCaptureSnapshot()
        val snapshot = UiSnapshotStore.currentSnapshot.value
            ?: return AgentDecision.Finish("Error: Could not capture UI snapshot.")

        val screenState = UiTreeIndexer.build(snapshot)

        val userContent = buildPrompt(objective, screenState)
        val conversation = listOf(
            LlmMessage(
                role = "system",
                content = """
                    Guide the user without operating the UI. Return exactly one JSON object.
                    Next control: {"v":1,"kind":"direct","target":"p0","instruction":"Double-tap Settings."}
                    Complete: {"v":1,"kind":"finish","summary":"You are done."}
                    Need input: {"v":1,"kind":"ask","question":"Which item should you choose?"}
                """.trimIndent()
            ),
            LlmMessage(role = "user", content = userContent)
        )

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

        return when (val decision = GuideDecisionDecoder.decode(rawOutput.trim())) {
            is GuideDecision.Direct -> AgentDecision.Act(
                operation = Operation.FOCUS,
                target = decision.target,
                arguments = mapOf("instruction" to decision.instruction)
            )
            is GuideDecision.Finish -> AgentDecision.Finish(decision.summary)
            is GuideDecision.Ask -> AgentDecision.Ask(decision.question)
        }
    }

    private fun buildPrompt(objective: String, screen: com.mewmix.nabu.uiagent.UiScreenState): String {
        val sb = StringBuilder()
        sb.append("Objective: $objective\n\n")
        sb.append("Current Screen Interactive Elements:\n")
        screen.plannerElements(100).forEachIndexed { index, element ->
            val label = screen.plannerLabel(element) ?: element.resourceId ?: "unknown element"
            sb.append("[$index] $label (id: ${element.id})\n")
        }
        return sb.toString()
    }
}
