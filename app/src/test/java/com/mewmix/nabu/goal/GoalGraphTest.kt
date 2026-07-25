package com.mewmix.nabu.goal

import com.mewmix.nabu.tools.CapabilityId
import org.junit.Assert.*
import org.junit.Test

class GoalGraphTest {

    @Test
    fun testGoalGraphInitialization() {
        val graph = GoalGraph()
        val root = Goal(objective = "Test Objective", targetCapability = CapabilityId.UI_ACT)
        
        graph.initialize(root)
        
        assertEquals(root.id, graph.activeGoalId.value)
        assertEquals(GoalStatus.ACTIVE, graph.getRootNode()?.status?.value)
        assertEquals(root, graph.getActiveNode()?.goal)
    }

    @Test
    fun testGoalGraphSubGoalPushAndComplete() {
        val graph = GoalGraph()
        val root = Goal(objective = "Test Objective", targetCapability = CapabilityId.UI_ACT)
        graph.initialize(root)
        
        val subGoal = Goal(
            objective = "Sub Objective",
            targetCapability = CapabilityId.WEB_SEARCH,
            parentId = root.id
        )
        graph.pushSubGoal(subGoal)
        
        // Active goal should now be the sub-goal
        assertEquals(subGoal.id, graph.activeGoalId.value)
        assertEquals(GoalStatus.ACTIVE, graph.getActiveNode()?.status?.value)
        
        // Root node should have the sub-goal as a child
        val rootNode = graph.getRootNode()!!
        assertEquals(1, rootNode.children.value.size)
        assertEquals(subGoal.id, rootNode.children.value.first().goal.id)

        // Complete the sub-goal
        graph.completeActiveGoal("Sub-goal finished")
        
        // Active goal should bubble up to root
        assertEquals(root.id, graph.activeGoalId.value)
        
        // Sub-goal should be completed
        val subNode = graph.getNode(subGoal.id)!!
        assertEquals(GoalStatus.COMPLETED, subNode.status.value)
        assertEquals("Sub-goal finished", subNode.outcome.value)
    }

    @Test
    fun testGoalGraphSubGoalFail() {
        val graph = GoalGraph()
        val root = Goal(objective = "Test Objective", targetCapability = CapabilityId.UI_ACT)
        graph.initialize(root)
        
        val subGoal = Goal(
            objective = "Sub Objective",
            targetCapability = CapabilityId.WEB_SEARCH,
            parentId = root.id
        )
        graph.pushSubGoal(subGoal)
        
        // Fail the sub-goal
        graph.failActiveGoal("Sub-goal failed")
        
        // Active goal should bubble up to root
        assertEquals(root.id, graph.activeGoalId.value)
        
        // Sub-goal should be failed
        val subNode = graph.getNode(subGoal.id)!!
        assertEquals(GoalStatus.FAILED, subNode.status.value)
        assertEquals("Sub-goal failed", subNode.outcome.value)
    }
}
