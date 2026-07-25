package com.mewmix.nabu.goal

import android.content.Context
import com.mewmix.nabu.tools.CapabilityId
import com.mewmix.nabu.uiagent.AgentDecision
import com.mewmix.nabu.uiagent.Operation
import com.mewmix.nabu.uiagent.UiActionStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class GoalExecutorTest {

    private val mockContext = mock(Context::class.java)

    class MockAdapter(override val capabilityId: CapabilityId, private val decisions: List<AgentDecision>) : SurfaceAdapter {
        var callCount = 0
        override suspend fun executeStep(objective: String): AgentDecision {
            val decision = decisions.getOrNull(callCount) ?: AgentDecision.Finish("No more mock decisions")
            callCount++
            return decision
        }
    }

    @Test
    fun testGoalExecutorCompletesImmediately() = runBlocking {
        val graph = GoalGraph()
        val root = Goal(objective = "Test", targetCapability = CapabilityId.UI_ACT)
        graph.initialize(root)

        val mockUiAdapter = MockAdapter(
            CapabilityId.UI_ACT,
            listOf(AgentDecision.Finish("Done natively"))
        )
        val executor = GoalExecutor(graph, mapOf(CapabilityId.UI_ACT to mockUiAdapter), mockContext)
        
        executor.runLoop(maxSteps = 5)

        assertEquals(GoalStatus.COMPLETED, graph.getRootNode()?.status?.value)
        assertEquals("Done natively", graph.getRootNode()?.outcome?.value)
        assertNull(graph.getActiveNode())
    }

    @Test
    fun testGoalExecutorDelegatesThenCompletes() = runBlocking {
        val graph = GoalGraph()
        val root = Goal(objective = "Test Root", targetCapability = CapabilityId.UI_ACT)
        graph.initialize(root)

        val mockUiAdapter = MockAdapter(
            CapabilityId.UI_ACT,
            listOf(
                AgentDecision.Delegate(CapabilityId.WEB_SEARCH, "Search for x"),
                AgentDecision.Finish("Root done")
            )
        )
        val mockWebAdapter = MockAdapter(
            CapabilityId.WEB_SEARCH,
            listOf(AgentDecision.Finish("Search done"))
        )
        val executor = GoalExecutor(
            graph, 
            mapOf(CapabilityId.UI_ACT to mockUiAdapter, CapabilityId.WEB_SEARCH to mockWebAdapter), 
            mockContext
        )
        
        executor.runLoop(maxSteps = 5)

        assertEquals(GoalStatus.COMPLETED, graph.getRootNode()?.status?.value)
        assertEquals("Root done", graph.getRootNode()?.outcome?.value)
        
        val children = graph.getRootNode()?.children?.value
        assertNotNull(children)
        assertEquals(1, children?.size)
        assertEquals(GoalStatus.COMPLETED, children?.first()?.status?.value)
        assertEquals("Search done", children?.first()?.outcome?.value)
        assertEquals(CapabilityId.WEB_SEARCH, children?.first()?.goal?.targetCapability)
    }

    @Test
    fun testGoalExecutorPropagatesUiActionFailure() = runBlocking {
        val graph = GoalGraph()
        val root = Goal(objective = "Tap unavailable control", targetCapability = CapabilityId.UI_ACT)
        graph.initialize(root)
        val adapter = MockAdapter(
            CapabilityId.UI_ACT,
            listOf(AgentDecision.Act(Operation.PRESS_BACK))
        )

        GoalExecutor(graph, mapOf(CapabilityId.UI_ACT to adapter), mockContext).runLoop(maxSteps = 2)

        assertEquals(GoalStatus.FAILED, graph.getRootNode()?.status?.value)
        assertTrue(graph.getRootNode()?.outcome?.value?.contains("Accessibility Service") == true)
    }
}
