package com.mewmix.nabu.uiagent

import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AutomationIntentPolicyTest {
    @Test
    fun `test scheduled execution blocks app launches`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(
            isScheduled = true,
            isDeviceLocked = false,
            destinationProvenance = "planner",
            context = mockContext
        )
        
        val decision = AutomationIntentPolicy.evaluate(UiActionStep.OpenApp("com.example"), policyContext)
        assertTrue(decision is IntentPolicyDecision.Block)
    }

    @Test
    fun `test device lock blocks execution`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(
            isScheduled = false,
            isDeviceLocked = true,
            destinationProvenance = "planner",
            context = mockContext
        )
        
        val decision = AutomationIntentPolicy.evaluate(UiActionStep.Tap(UiTarget(null, null, null)), policyContext)
        assertTrue(decision is IntentPolicyDecision.Block)
    }
}
