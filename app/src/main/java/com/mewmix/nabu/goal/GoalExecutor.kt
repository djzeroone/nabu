package com.mewmix.nabu.goal

import android.content.Context
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision
import com.mewmix.nabu.uiagent.UiActionStep
import com.mewmix.nabu.tools.ToolCall
import com.mewmix.nabu.accessibility.AccessibilityToolHandler
import com.mewmix.nabu.tools.ToolResult
import kotlinx.coroutines.delay

class GoalExecutor(
    private val graph: GoalGraph,
    private val adapters: Map<CapabilityId, SurfaceAdapter>,
    private val context: Context
) {

    /**
     * Runs the goal graph until the root goal completes, fails, or we hit a limit.
     */
    suspend fun runLoop(maxSteps: Int = 50) {
        var steps = 0
        while (steps < maxSteps) {
            val activeNode = graph.getActiveNode()
            if (activeNode == null) {
                // No active node means the graph is fully resolved (or empty)
                break
            }

            // Route to the appropriate adapter
            val adapter = adapters[activeNode.goal.targetCapability]
            if (adapter == null) {
                graph.failActiveGoal("No adapter registered for capability ${activeNode.goal.targetCapability}")
                continue
            }

            val decision = adapter.executeStep(activeNode.goal.objective)

            when (decision) {
                is AgentDecision.Finish -> {
                    if (decision.outcome.startsWith("Error:", ignoreCase = true) ||
                        decision.outcome.startsWith("Failed:", ignoreCase = true)
                    ) {
                        graph.failActiveGoal(decision.outcome)
                    } else {
                        graph.completeActiveGoal(decision.outcome)
                    }
                }
                is AgentDecision.Ask -> {
                    // For now, fail out when requiring user interaction in a headless run
                    graph.failActiveGoal("Agent asked user: ${decision.question}")
                }
                is AgentDecision.Act -> {
                    // Execute the action natively via legacy ToolCall
                    val step = decision.toUiActionPlan(activeNode.goal.objective, "snapshot").steps.first()
                    val result = executeUiAction(step)
                    if (result == null || result.isError) {
                        graph.failActiveGoal(result?.output ?: "UI action is unsupported.")
                    }
                    // The goal remains active; it will be evaluated again in the next loop
                }
                is AgentDecision.Query -> {
                    graph.failActiveGoal("Query not yet supported in native executor.")
                }
                is AgentDecision.Delegate -> {
                    // Native Delegation! We push a new goal to the graph.
                    val subGoal = Goal(
                        objective = decision.objective,
                        targetCapability = decision.capability,
                        parentId = activeNode.goal.id
                    )
                    graph.pushSubGoal(subGoal)
                }
            }

            steps++
            delay(500) // Small settle delay between steps
        }

        // If we exit the loop and the root is still active, we timed out
        val root = graph.getRootNode()
        if (root?.status?.value == GoalStatus.ACTIVE) {
            graph.failActiveGoal("Goal execution exceeded max steps ($maxSteps)")
        }
    }

    private suspend fun executeUiAction(action: UiActionStep): ToolResult? {
        val toolCall = action.toLegacyToolCall()
        return AccessibilityToolHandler.execute(context, toolCall)
    }

    private fun UiActionStep.toLegacyToolCall(): ToolCall {
        val snapshot = com.mewmix.nabu.accessibility.UiSnapshotStore.currentSnapshot.value
        val observationId = snapshot?.id ?: ""
        val screen = snapshot?.let(com.mewmix.nabu.uiagent.UiTreeIndexer::build)
        
        val target = when (this) {
            is UiActionStep.Tap -> this.target
            is UiActionStep.LongPress -> this.target
            is UiActionStep.TypeText -> this.target
            is UiActionStep.Scroll -> this.target
            is UiActionStep.Focus -> this.target
            is UiActionStep.NodeAction -> this.target
            is UiActionStep.CustomAction -> this.target
            is UiActionStep.Gesture -> this.target
            else -> null
        }
        
        val element = target?.elementId?.let { screen?.element(it) }
        val selector = element?.let {
            mapOf(
                "tree_path" to it.treePath,
                "resource_id" to it.resourceId.orEmpty(),
                "text" to it.text.orEmpty(),
                "content_desc" to it.contentDescription.orEmpty(),
                "class" to it.className.orEmpty()
            )
        } ?: emptyMap<String, String>()
        
        fun args(vararg pairs: Pair<String, Any>): Map<String, Any> {
            val map = mutableMapOf<String, Any>("observation_id" to observationId, "selector" to selector)
            element?.bounds?.let { map["fallback_bounds"] = it.toList() }
            map.putAll(pairs)
            return map
        }
        
        return when (this) {
            is UiActionStep.Focus -> ToolCall("ui_focus", args())
            is UiActionStep.NodeAction -> ToolCall(
                "ui_node_action",
                args(*((arguments + ("node_action" to action)).map { it.key to it.value }.toTypedArray()))
            )
            is UiActionStep.CustomAction -> {
                val custom = element?.customActions?.singleOrNull { it.ref == actionRef }
                ToolCall(
                    "ui_custom_action",
                    args(
                        "trusted_action_id" to (custom?.trustedActionId ?: Int.MIN_VALUE),
                        "custom_action_label" to (custom?.label ?: "")
                    )
                )
            }
            is UiActionStep.Gesture -> {
                val gestureArgs = arguments.toMutableMap<String, Any>()
                gestureArgs["gesture"] = gesture
                destination?.elementId?.let { destinationId ->
                    screen?.element(destinationId)?.bounds?.let { gestureArgs["destination_bounds"] = it.toList() }
                }
                ToolCall("ui_gesture", args(*gestureArgs.map { it.key to it.value }.toTypedArray()))
            }
            is UiActionStep.Tap -> ToolCall("ui_tap", args())
            is UiActionStep.LongPress -> ToolCall("ui_long_press", args())
            is UiActionStep.TypeText -> ToolCall("ui_set_text", args("text" to text))
            is UiActionStep.Scroll -> ToolCall("ui_scroll", args("direction" to direction.name.lowercase()))
            is UiActionStep.Wait -> ToolCall("wait", mapOf("duration_ms" to milliseconds.toString()))
            is UiActionStep.PressHome -> ToolCall("ui_global_action", mapOf("observation_id" to observationId, "global_action" to "home"))
            is UiActionStep.PressBack -> ToolCall("ui_global_action", mapOf("observation_id" to observationId, "global_action" to "back"))
            is UiActionStep.GlobalAction -> ToolCall(
                "ui_global_action",
                mapOf("observation_id" to observationId, "global_action" to action)
            )
            else -> ToolCall("unknown", emptyMap())
        }
    }
}
