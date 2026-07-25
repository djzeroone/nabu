package com.mewmix.nabu.goal

import com.mewmix.nabu.tools.CapabilityId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class GoalStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED
}

/**
 * Defines a discrete objective to be resolved by a specific capability domain.
 */
data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val objective: String,
    val targetCapability: CapabilityId,
    val parentId: String? = null
)

/**
 * A mutable node tracking the execution state of a goal within the GoalGraph.
 */
class GoalNode(val goal: Goal) {
    private val _status = MutableStateFlow(GoalStatus.PENDING)
    val status: StateFlow<GoalStatus> = _status.asStateFlow()

    private val _children = MutableStateFlow<List<GoalNode>>(emptyList())
    val children: StateFlow<List<GoalNode>> = _children.asStateFlow()

    private val _outcome = MutableStateFlow<String?>(null)
    val outcome: StateFlow<String?> = _outcome.asStateFlow()

    fun addChild(child: GoalNode) {
        _children.value = _children.value + child
    }

    fun start() {
        require(_status.value == GoalStatus.PENDING) { "Cannot start a goal that is not PENDING" }
        _status.value = GoalStatus.ACTIVE
    }

    fun complete(outcomeMessage: String) {
        _status.value = GoalStatus.COMPLETED
        _outcome.value = outcomeMessage
    }

    fun fail(outcomeMessage: String) {
        _status.value = GoalStatus.FAILED
        _outcome.value = outcomeMessage
    }
}
