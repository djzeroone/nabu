package com.mewmix.nabu.goal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GoalGraph {
    private val nodes = mutableMapOf<String, GoalNode>()
    private var rootNode: GoalNode? = null
    
    private val _activeGoalId = MutableStateFlow<String?>(null)
    val activeGoalId: StateFlow<String?> = _activeGoalId.asStateFlow()

    fun initialize(rootGoal: Goal) {
        require(nodes.isEmpty()) { "GoalGraph is already initialized" }
        val node = GoalNode(rootGoal)
        nodes[rootGoal.id] = node
        rootNode = node
        _activeGoalId.value = rootGoal.id
        node.start()
    }

    fun pushSubGoal(goal: Goal) {
        val parentId = _activeGoalId.value
        requireNotNull(parentId) { "Cannot push a sub-goal when there is no active goal" }
        require(goal.parentId == parentId) { "Sub-goal must specify the active goal as its parent" }
        
        val parentNode = nodes[parentId]
        requireNotNull(parentNode) { "Active goal not found in graph" }
        require(parentNode.status.value == GoalStatus.ACTIVE) { "Parent goal must be ACTIVE" }

        val node = GoalNode(goal)
        nodes[goal.id] = node
        parentNode.addChild(node)
        
        _activeGoalId.value = goal.id
        node.start()
    }

    fun completeActiveGoal(outcome: String) {
        val activeId = _activeGoalId.value
        requireNotNull(activeId) { "No active goal to complete" }
        
        val node = nodes[activeId]
        requireNotNull(node) { "Active goal not found in graph" }
        
        node.complete(outcome)
        _activeGoalId.value = node.goal.parentId
    }

    fun failActiveGoal(outcome: String) {
        val activeId = _activeGoalId.value
        requireNotNull(activeId) { "No active goal to fail" }
        
        val node = nodes[activeId]
        requireNotNull(node) { "Active goal not found in graph" }
        
        node.fail(outcome)
        _activeGoalId.value = node.goal.parentId
    }

    fun getActiveNode(): GoalNode? {
        val id = _activeGoalId.value ?: return null
        return nodes[id]
    }

    fun getNode(id: String): GoalNode? = nodes[id]

    fun getRootNode(): GoalNode? = rootNode
}
