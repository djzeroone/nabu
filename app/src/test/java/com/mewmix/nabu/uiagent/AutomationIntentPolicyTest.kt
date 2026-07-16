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

    @Test
    fun `test URL schemes`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(isScheduled = false, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val block1 = AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("file:///system/etc/hosts"), policyContext)
        assertTrue(block1 is IntentPolicyDecision.Block)

        val block2 = AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("http://user:pass@example.com"), policyContext)
        assertTrue(block2 is IntentPolicyDecision.Block)

        val requireConf = AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("http://example.com"), policyContext)
        assertTrue(requireConf is IntentPolicyDecision.RequireConfirmation)

        val allow = AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("https://example.com"), policyContext)
        assertTrue(allow is IntentPolicyDecision.Allow)
    }
    
    @Test
    fun `test package installation blocks uninstalled apps`() {
        val mockContext = mock(Context::class.java)
        val mockPm = mock(PackageManager::class.java)
        `when`(mockContext.packageManager).thenReturn(mockPm)
        `when`(mockPm.getPackageInfo("com.missing", PackageManager.GET_META_DATA)).thenThrow(PackageManager.NameNotFoundException())

        val policyContext = PolicyContext(isScheduled = false, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val decision = AutomationIntentPolicy.evaluate(UiActionStep.OpenApp("com.missing"), policyContext)
        assertTrue(decision is IntentPolicyDecision.Block)
    }
    
    @Test
    fun `test sensitive settings require confirmation`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(isScheduled = false, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val requireConf = AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.WIRELESS_DEBUGGING, null), policyContext)
        assertTrue(requireConf is IntentPolicyDecision.RequireConfirmation)

        val devOpts = AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.DEVELOPER_OPTIONS, null), policyContext)
        assertTrue(devOpts is IntentPolicyDecision.RequireConfirmation)

        val accessibility = AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.ACCESSIBILITY, null), policyContext)
        assertTrue(accessibility is IntentPolicyDecision.RequireConfirmation)
        
        val allow = AutomationIntentPolicy.evaluate(UiActionStep.OpenSettingsPage(SettingsPage.WIFI, null), policyContext)
        assertTrue(allow is IntentPolicyDecision.Allow)
    }

    @Test
    fun `test javascript and intent URI schemes are blocked`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(isScheduled = false, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val js = AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("javascript:alert(1)"), policyContext)
        assertTrue("javascript: should be blocked", js is IntentPolicyDecision.Block)

        val intent = AutomationIntentPolicy.evaluate(UiActionStep.OpenUrl("intent://evil#Intent;end"), policyContext)
        assertTrue("intent: should be blocked", intent is IntentPolicyDecision.Block)
    }

    @Test
    fun `test scheduled share text is blocked`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(isScheduled = true, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val decision = AutomationIntentPolicy.evaluate(UiActionStep.ShareText("hello", null), policyContext)
        assertTrue(decision is IntentPolicyDecision.Block)
    }

    @Test
    fun `test scheduled camera is blocked`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(isScheduled = true, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val decision = AutomationIntentPolicy.evaluate(UiActionStep.OpenCamera(CameraMode.PHOTO, CameraFacing.FRONT), policyContext)
        assertTrue(decision is IntentPolicyDecision.Block)
    }

    @Test
    fun `test credential package is blocked by planner`() {
        val mockContext = mock(Context::class.java)
        val mockPm = mock(PackageManager::class.java)
        `when`(mockContext.packageManager).thenReturn(mockPm)
        `when`(mockPm.getPackageInfo(eq("com.bank.app"), anyInt())).thenReturn(null)

        val policyContext = PolicyContext(isScheduled = false, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val decision = AutomationIntentPolicy.evaluate(UiActionStep.OpenApp("com.bank.app"), policyContext)
        assertTrue(decision is IntentPolicyDecision.Block)
    }

    @Test
    fun `test share text requires confirmation`() {
        val mockContext = mock(Context::class.java)
        val policyContext = PolicyContext(isScheduled = false, isDeviceLocked = false, destinationProvenance = "planner", context = mockContext)

        val decision = AutomationIntentPolicy.evaluate(UiActionStep.ShareText("hello world", null), policyContext)
        assertTrue(decision is IntentPolicyDecision.RequireConfirmation)
    }

    @Test
    fun `captured media share is blocked when scheduled`() {
        val context = mock(Context::class.java)
        val policyContext = PolicyContext(true, false, "planner", context)
        val decision = AutomationIntentPolicy.evaluate(
            UiActionStep.ShareCapturedMedia("com.example.messages", "Saved Messages"),
            policyContext
        )
        assertTrue(decision is IntentPolicyDecision.Block)
    }

    @Test
    fun `captured media share requires installed target and confirmation`() {
        val context = mock(Context::class.java)
        val packageManager = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packageManager)
        `when`(packageManager.getPackageInfo("com.example.messages", PackageManager.GET_META_DATA))
            .thenReturn(android.content.pm.PackageInfo())
        val policyContext = PolicyContext(false, false, "planner", context)

        val decision = AutomationIntentPolicy.evaluate(
            UiActionStep.ShareCapturedMedia("com.example.messages", "Saved Messages"),
            policyContext
        )

        assertTrue(decision is IntentPolicyDecision.RequireConfirmation)
    }
}
